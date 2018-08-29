/*
 * Copyright (c) 2013 idealo internet GmbH -- all rights reserved.
 */
package de.idealo.mongodb.slowops.collector;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mongodb.ServerAddress;
import de.idealo.mongodb.slowops.dto.ApplicationStatusDto;
import de.idealo.mongodb.slowops.dto.CollectorServerDto;
import de.idealo.mongodb.slowops.dto.CollectorStatusDto;
import de.idealo.mongodb.slowops.dto.ProfiledServerDto;
import de.idealo.mongodb.slowops.jmx.CollectorManagerMBean;
import de.idealo.mongodb.slowops.monitor.MongoDbAccessor;
import de.idealo.mongodb.slowops.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import java.lang.management.ManagementFactory;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 * 
 * 
 * @author kay.agahd
 * @since 27.02.2013
 * @version $Id: $
 * @copyright idealo internet GmbH
 */
public class CollectorManager extends Thread implements CollectorManagerMBean {

    private static final Logger LOG = LoggerFactory.getLogger(CollectorManager.class);


    private final BlockingQueue<ProfilingEntry> jobQueue;
    private volatile List<ProfilingReader> readers;
    private ProfilingWriter writer;
    private boolean stop;
    private long doneJobsOfRemovedReaders;
    private long doneJobsOfRemovedWriters;
    private final ReadWriteLock globalLock = new ReentrantReadWriteLock();
    private final Lock readLock;
    private final Lock writeLock;
    private String logLine1 = null;
    private String logLine2 = null;


    CollectorManager() {

        readers = Lists.newLinkedList();
        jobQueue = new LinkedBlockingQueue<ProfilingEntry>();
        doneJobsOfRemovedReaders = 0;
        doneJobsOfRemovedWriters = 0;
        readLock = globalLock.readLock();
        writeLock = globalLock.writeLock();

        registerMBean();
        addShutdownHook();
    }

    public void reloadConfig(String cfg){
        if(ConfigReader.reloadConfig(cfg)){
            LOG.info("new config has been applied");

            terminateReadersAndWriters(false);
            startWriter();
            startReaders();
        }else{
            LOG.warn("new config was not applied");
        }
    }
    
    private void startWriter() {
        LOG.info(">>> start writer");
        boolean isSameWriter = writer != null && ConfigReader.getCollectorServer().equals(writer.getCollectorServerDto());
        if(!isSameWriter) {
            LOG.info("old and new writer are different, so start a new one");
            writer = new ProfilingWriter(jobQueue);
            writer.start();

        }
        LOG.info("<<< writer");
    }

    private void startReaders() {
        LOG.info(">>> start readers");

        final List<ProfiledServerDto> profiledServers = ConfigReader.getProfiledServers();
        resolveMongodAdresses(profiledServers);
        createProfilingReaders(profiledServers);

        LOG.info("<<< start readers");
    }

    /**
     * resolve in parallel all mongod addresses (and database names if ns:*) for all systems to be profiled
     * @param profiledServers
     */
    private void resolveMongodAdresses(List<ProfiledServerDto> profiledServers){

        final int poolSize = 1 + Math.min(2*profiledServers.size(), Util.MAX_THREADS);
        LOG.info("MongoResolver poolSize:{} ", poolSize );

        final ThreadFactory threadFactoryMongoResolver = new ThreadFactoryBuilder()
                .setNameFormat("MongoResolver-%d")
                .setDaemon(true)
                .build();
        final ExecutorService hostExecutor = Executors.newFixedThreadPool(poolSize, threadFactoryMongoResolver);
        int maxResponseTimeout = 0;

        for (ProfiledServerDto dto : profiledServers) {
            //use all hosts of the dto to let mongo-driver figure out which one is working to get all mongod addresses
            final MongoResolver mongoResolver = new MongoResolver(-1, dto.getResponseTimeout(), dto.getAdminUser(), dto.getAdminPw(), dto.getHosts());
            final Future<MongoResolver> futureMongoResolver = hostExecutor.submit(mongoResolver);

            //get the result of the future by a separate thread within responseTimeout
            hostExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try{
                        final MongoResolver mongoResolver = futureMongoResolver.get(dto.getResponseTimeout(), TimeUnit.MILLISECONDS);
                        dto.setResolvedResults(mongoResolver);
                    }
                    catch (InterruptedException | ExecutionException | TimeoutException e){
                        futureMongoResolver.cancel(true);
                        final String errMsg = e.getClass().getSimpleName() + " while resolving mongod server addresses and database names within "+dto.getResponseTimeout()+" ms for '"+dto.getLabel()+"'";
                        LOG.error("{}", errMsg, e);
                        ApplicationStatusDto.addWebLog(errMsg);
                    }
                }
            });

            if(maxResponseTimeout < dto.getResponseTimeout()) maxResponseTimeout = dto.getResponseTimeout();
        }

        hostExecutor.shutdown();
        long start = System.currentTimeMillis();
        try {
            hostExecutor.awaitTermination(maxResponseTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            LOG.error("Error while awaiting termination of hostExecutor for resolving mongod addresses", e);
        }finally {
            LOG.error("Waited termination of hostExecutor for {} ms, isTerminated: {}", System.currentTimeMillis()-start, hostExecutor.isTerminated());
            hostExecutor.shutdownNow();
        }
    }

    /**
     * create for each resolved host and database one profiling reader
     * @param profiledServers
     */
    private void createProfilingReaders(List<ProfiledServerDto> profiledServers){

        final List<ProfilingReaderCreator> profilingReaderCreatorlist = new LinkedList<ProfilingReaderCreator>();
        int maxResponseTimeout = 0;

        MongoDbAccessor writerMongo = null;
        if(writer!=null){
            writerMongo = writer.getMongoDbAccessor();
        }else{
            LOG.error("profilingWriter ist null but should not");
        }

        for (ProfiledServerDto dto : profiledServers) {
            HashMap<String, List<String>> collectionsPerDb = dto.getCollectionsPerDatabase();
            for (ServerAddress mongodAddress : dto.getResolvedHosts()) {
                for (String db : collectionsPerDb.keySet()) {
                    if(!isReaderExists(mongodAddress, db)){//dont't create readers twice
                        profilingReaderCreatorlist.add(new ProfilingReaderCreator(0, mongodAddress, dto, db, collectionsPerDb.get(db), this, writerMongo));
                    }else{
                        LOG.info("Reader already exists for: {}/{}", mongodAddress, db );
                    }
                }
            }
            if(maxResponseTimeout < dto.getResponseTimeout()) maxResponseTimeout = dto.getResponseTimeout();
        }

        final int poolSize = 1 + Math.min(2 * profilingReaderCreatorlist.size(), Util.MAX_THREADS);
        LOG.info("ProfilingReader poolSize:{} ", poolSize );

        //start for each resolved mongod host one profiling reader
        final ThreadFactory threadFactoryProfilingReader = new ThreadFactoryBuilder()
                .setNameFormat("ProfilingReader-%d")
                .setDaemon(true)
                .build();
        final ExecutorService profilingReaderExecutor = Executors.newFixedThreadPool(poolSize, threadFactoryProfilingReader);

        for(ProfilingReaderCreator profilingReaderCreator : profilingReaderCreatorlist){
            final Future<ProfilingReader> futureReader = profilingReaderExecutor.submit(profilingReaderCreator);

            //get the result of the future by a separate thread within responseTimeout
            profilingReaderExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    final ProfiledServerDto dto = profilingReaderCreator.getDto();
                    try{
                        final ProfilingReader reader = futureReader.get(dto.getResponseTimeout(), TimeUnit.MILLISECONDS);
                        addAndStartReader(reader, dto);
                    }
                    catch (InterruptedException | ExecutionException | TimeoutException e){
                        futureReader.cancel(true);
                        final String errMsg = e.getClass().getSimpleName() + " while starting profiling reader within "+dto.getResponseTimeout()+" ms for '"+dto.getLabel()+"' at " + profilingReaderCreator.getAddress() + "/" + profilingReaderCreator.getDatabase();
                        LOG.error("{}", errMsg, e);
                        ApplicationStatusDto.addWebLog(errMsg);
                    }
                }
            });

        }

        profilingReaderExecutor.shutdown();
        long start = System.currentTimeMillis();
        try {
            profilingReaderExecutor.awaitTermination(maxResponseTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            LOG.error("Error while awaiting termination of profilingReaderExecutor for starting profiling readers", e);
        }finally {
            LOG.info("Waited termination of profilingReaderExecutor for {} ms, isTerminated: {}", System.currentTimeMillis()-start, profilingReaderExecutor.isTerminated());
            profilingReaderExecutor.shutdownNow();
            if(writerMongo!=null) writerMongo.closeConnections();
        }

    }


    private void addAndStartReader(ProfilingReader reader, ProfiledServerDto dto){
        if(!isReaderExists(reader.getServerAddress(), reader.getDatabase())) {//dont't start readers twice

            try{
                writeLock.lock();
                readers.add(reader);
                LOG.info("readers.size after add: {} ", readers.size());
            }finally {
                writeLock.unlock();
            }
            if(dto.isEnabled()) {//may be stopped if dto is not "enabled"
                reader.start();
                LOG.info("New ProfilingReader added and started for {}/{}", reader.getServerAddress(), reader.getDatabase());
            }else{
                LOG.info("New ProfilingReader added but not started for {}/{} because it's not enabled", reader.getServerAddress(), reader.getDatabase());
            }
        }else{
            LOG.info("No need to start reader because it already exists for: {}/{}", reader.getServerAddress(), reader.getDatabase());
        }
    }

    public ProfilingWriter getWriter(){
        return writer;
    }

    public BlockingQueue<ProfilingEntry> getJobQueue(){
        return jobQueue;
    }

    public List<ProfilingReader> getProfilingReaders(Set<Integer> ids) {
        final List<ProfilingReader> result = Lists.newLinkedList();

        try{
            readLock.lock();
            for (ProfilingReader reader : readers) {
                if(ids.contains(reader.getInstanceId())) {
                    result.add(reader);
                }
            }
        }finally {
            readLock.unlock();
        }

        return result;
    }

    public boolean isReaderExists(ServerAddress adr, String db) {

        try{
            readLock.lock();
            for (ProfilingReader reader : readers) {
                if(reader.getServerAddress().equals(adr) && reader.getDatabase().equals(db)) {
                    return true;
                }
            }
        }finally {
            readLock.unlock();
        }

        return false;
    }
    
    
    
    private void registerMBean() {
        
        try {
            final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            final ObjectName name = new ObjectName(this.getClass().getPackage().getName() + ":type=" + this.getClass().getSimpleName());
            final StandardMBean mbean = new StandardMBean(this, CollectorManagerMBean.class, false);
            server.registerMBean(mbean, name);
        } catch (MalformedObjectNameException | InstanceAlreadyExistsException | NotCompliantMBeanException | MBeanRegistrationException | NullPointerException e) {
            LOG.error("Error while registering MBean", e);
        }
    }
    
    private void unregisterMBean() {
        try {
            final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            final ObjectName name = new ObjectName(this.getClass().getPackage().getName() + ":type=" + this.getClass().getSimpleName());
            server.unregisterMBean(name);
        } catch (MalformedObjectNameException | MBeanRegistrationException | InstanceNotFoundException | IllegalStateException e) {
            LOG.error("Error while unregistering MBean", e);
        }
    }


    

    
    
    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                terminate();
            }
        });
    }


    private void terminateReadersAndWriters(boolean terminateAll) {
        LOG.info(">>> terminateReadersAndWriters {}", terminateAll );

        final int poolSize = 1 + Math.min(readers.size(), Util.MAX_THREADS);
        LOG.info("Terminator poolSize:{} ", poolSize );

        final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setNameFormat("Terminator-%d")
                .setDaemon(true)
                .build();
        final ExecutorService terminatorExecutor = Executors.newFixedThreadPool(poolSize, threadFactory);
        final List<Future<Long>> futureTerminatorList = new ArrayList<>();

        try {

            final LinkedList<ProfilingReader> toBeRemoved = Lists.newLinkedList();

            try{
                readLock.lock();
                for (ProfilingReader r : readers) {
                    boolean isSameReader = ConfigReader.isProfiledServer(r.getProfiledServerDto());
                    if(terminateAll || !isSameReader) {
                        try {
                            Terminator terminator = new Terminator(r);
                            Future<Long> result = terminatorExecutor.submit(terminator);
                            futureTerminatorList.add(result);
                            LOG.info("will remove reader for {}/{}", r.getServerAddress(), r.getDatabase());
                            toBeRemoved.add(r);
                        } catch (Throwable e) {
                            LOG.error("Error while terminating reader ", e);
                        }
                    }
                }
            }finally {
                readLock.unlock();
            }

            try{
                writeLock.lock();
                readers.removeAll(toBeRemoved);
                LOG.info("readers.size after remove: {} ", readers.size());
            }finally {
                writeLock.unlock();
            }


        } catch (Throwable e) {
            LOG.error("Error while terminating readers ", e);
        }


        boolean isSameWriter = ConfigReader.getCollectorServer().equals(writer.getCollectorServerDto());
        if(terminateAll || !isSameWriter) {
            try {
                if (writer != null) {
                    Terminator terminator = new Terminator(writer);
                    Future<Long> result = terminatorExecutor.submit(terminator);
                    futureTerminatorList.add(result);
                }
            } catch (Throwable e) {
                LOG.error("Error while terminating writers ", e);
            }
        }

        long doneJobs = 0;
        for(Future<Long> futureTerminator : futureTerminatorList){
            try{
                doneJobs += futureTerminator.get();
                LOG.debug("doneJobs {}", doneJobs);
            }
            catch (InterruptedException | ExecutionException e){
                LOG.warn("Exception while getting future terminator", e);
            }
        }
        terminatorExecutor.shutdown();

        if(terminateAll || !isSameWriter) {//take into account writerJobs if writer is terminated
            doneJobsOfRemovedReaders += (doneJobs - writer.getDoneJobs());
            doneJobsOfRemovedWriters += writer.getDoneJobs();
        }else{
            doneJobsOfRemovedReaders += doneJobs;
        }



        LOG.info("<<< terminateReadersAndWriters");

    }



    public void terminate() {

        terminateReadersAndWriters(true);
        
        try {
            unregisterMBean();
        } catch (Throwable e) {
            LOG.error("Error while unregistering MBeans ", e);
        }
        
        stop = true;
        
        interrupt();//need to interrupt when sleeping
    }
    
    public void startup() {
        this.start();
    }
    
    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {
        stop = false;
        try {
        
            startWriter();
            
            startReaders();
        
        
            while(!stop) {
                monitor();
                
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    LOG.error("Exception while sleeping.", e);
                    stop = true;
                }
            }
        }finally {
            terminate();
        }
        LOG.info("Terminated");
    }

    @Override
    public long getNumberOfReadsOfRemovedReaders(){
        return doneJobsOfRemovedReaders;
    }

    @Override
    public long getNumberOfWritesOfRemovedWriters(){
        return doneJobsOfRemovedWriters;
    }

    
    @Override
    public long getNumberOfReads(){
        long result = 0;

        try{
            readLock.lock();
            for (ProfilingReader r : readers) {
                result += r.getDoneJobs();
            }
        }finally {
            readLock.unlock();
        }

        return result;
    }
    
        
    @Override
    public long getNumberOfWrites(){
        if(writer != null) {
            return writer.getDoneJobs();
        }
        return 0;
    }

    @Override
    public Date getRunningSince(){
        if(writer != null) {
            return writer.getRuningSince();
        }
        return new Date();
    }

    public ApplicationStatusDto getApplicationStatus(boolean isAuthenticated) {
        LOG.info(">>> getApplicationStatus isAuthenticated: {} ", isAuthenticated);
        final List<Integer> idList = Lists.newLinkedList();
        try{
            readLock.lock();
            LOG.info("readers.size: {} ", readers.size());
            for (ProfilingReader reader : readers) {
                idList.add(reader.getInstanceId());
            }
        }finally {
            readLock.unlock();
        }
        LOG.info("<<< getApplicationStatus isAuthenticated: {} ", isAuthenticated);
        return getApplicationStatus(idList, isAuthenticated);
    }



    public ApplicationStatusDto getApplicationStatus(List<Integer> idList, boolean isAuthenticated) {
        LOG.info(">>> getApplicationStatus listSize: {} ", idList.size());
        ApplicationStatusDto result = new ApplicationStatusDto();
        HashSet<Integer> idSet = new HashSet<Integer>();
        idSet.addAll(idList);

        final int poolSize = 1 + Math.min(2 * Math.max(idList.size(), readers.size()), Util.MAX_THREADS);
        LOG.info("CollectorStatusDto poolSize:{} ", poolSize );

        final ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setNameFormat("CollectorStatusDto-%d")
                .setDaemon(true)
                .build();
        final ExecutorService executorService = Executors.newFixedThreadPool(poolSize, threadFactory);

        int maxResponseTimeout = 0;
        final ConcurrentHashMap<ServerAddress, ProfilingReader> uniqueServerAdresses = new ConcurrentHashMap<ServerAddress, ProfilingReader>();
        try{
            readLock.lock();
            LOG.info("readers.size: {} ", readers.size());
            for (ProfilingReader reader : readers) {
                if (idSet.size()==0 || idSet.contains(reader.getInstanceId())) {

                    final Future future = executorService.submit(new Runnable() {
                        public void run() {
                            final MongoDbAccessor mongo = reader.getMongoDbAccessor();
                            try {
                                //update only once the replSet status for all profilingReaders having unique serverAddresses
                                if (uniqueServerAdresses.putIfAbsent(reader.getServerAddress(), reader) == null) {
                                    reader.updateReplSetStatus(mongo);
                                }
                                reader.updateProfileStatus(mongo);
                            }finally {
                                mongo.closeConnections();
                            }
                        }
                    });

                    //get the result of the future by a separate thread within responseTimeout
                    executorService.execute(new Runnable() {
                        @Override
                        public void run() {
                            final ProfiledServerDto dto = reader.getProfiledServerDto();
                            try{
                                Object ok = future.get(dto.getResponseTimeout(), TimeUnit.MILLISECONDS);
                                if(ok == null){
                                    LOG.error("Task finished correctly for '"+dto.getLabel()+"' at " + reader.getServerAddress());
                                }else{
                                    LOG.error("Task did not finish correctly for '"+dto.getLabel()+"' at " + reader.getServerAddress());
                                }

                            }
                            catch (InterruptedException | ExecutionException | TimeoutException e){
                                final String errMsg = e.getClass().getSimpleName() + " while getting updated server status within "+dto.getResponseTimeout()+" ms for '"+dto.getLabel()+"' at " + reader.getServerAddress();
                                future.cancel(true);
                                LOG.error("{}", errMsg, e);
                                ApplicationStatusDto.addWebLog(errMsg);
                            }
                        }
                    });

                    if(maxResponseTimeout < reader.getProfiledServerDto().getResponseTimeout()) maxResponseTimeout = reader.getProfiledServerDto().getResponseTimeout();
                }
            }
        }finally {
            readLock.unlock();
        }


        executorService.shutdown();

        long start = System.currentTimeMillis();
        try {
            executorService.awaitTermination(maxResponseTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            LOG.error("Error while awaiting termination of executorService for getting collector statuses", e);
        }finally {
            LOG.info("Waited termination of CollectorStatusDto executorService for {} ms, isTerminated: {}", System.currentTimeMillis() - start, executorService.isTerminated());
            executorService.shutdownNow();
        }

        try{
            readLock.lock();
            for (ProfilingReader reader : readers) {
                if (idSet.size()==0 || idSet.contains(reader.getInstanceId())) {
                    //copy the replSet status from the updatedReader to all profilingReaders having the same serverAddress
                    if (uniqueServerAdresses.containsKey(reader.getServerAddress())) {
                        final ProfilingReader updatedReader = uniqueServerAdresses.get(reader.getServerAddress());
                        reader.setReplSet(updatedReader.getReplSet());
                        reader.setReplSetStatus(updatedReader.getReplicaStatus());
                    }
                    CollectorStatusDto cs = reader.getCollectorStatusDto();
                    result.addCollectorStatus(cs);
                }
            }
        }finally {
            readLock.unlock();
        }



        CollectorServerDto dto = getCollectorServerDto();
        if(dto != null){
            result.setCollectorServerDto(new CollectorServerDto(dto.getHosts(), dto.getDb(), dto.getCollection(), "", ""));//omit user/pw
        }

        result.setNumberOfReads(getNumberOfReads());

        result.setNumberOfWrites(getNumberOfWrites());

        result.setNumberOfReadsOfRemovedReaders(getNumberOfReadsOfRemovedReaders());

        result.setNumberOfWritesOfRemovedWriters(getNumberOfWritesOfRemovedWriters());

        result.setCollectorRunningSince(getRunningSince());

        if(isAuthenticated) {
            result.setConfig(ConfigReader.getConfig());
        }

        result.setLastRefresh(new Date());

        LOG.info("<<< getApplicationStatus");
        return result;
    }

    public void startStopProfilingReaders(List<Integer> idList, boolean stop){

        final LinkedList<ProfilingReader> toBeAdded = Lists.newLinkedList();
        final LinkedList<ProfilingReader> toBeRemoved = Lists.newLinkedList();
        for(int id : idList){
            try{
                readLock.lock();
                for (ProfilingReader reader : readers) {
                    if(id == reader.getInstanceId()){
                        if(stop){
                            reader.terminate();
                        }else if(reader.isStopped()){
                            ProfilingReader newReader = new ProfilingReader(
                                    reader.getInstanceId(),
                                    jobQueue,
                                    reader.getServerAddress(),
                                    reader.getLastTs(),
                                    reader.getProfiledServerDto(),
                                    reader.getDatabase(),
                                    reader.getCollections(),
                                    stop,
                                    reader.getDoneJobs(),
                                    reader.getSlowMs()
                            );
                            LOG.info("will add reader for {}/{}", newReader.getServerAddress(), newReader.getDatabase());
                            toBeAdded.add(newReader);
                            LOG.info("will remove reader for {}/{}", reader.getServerAddress(), reader.getDatabase());
                            toBeRemoved.add(reader);

                            newReader.start();
                        }

                        break;
                    }
                }
            }finally {
                readLock.unlock();
            }

        }
        try{
            writeLock.lock();
            readers.removeAll(toBeRemoved);
            readers.addAll(toBeAdded);
            LOG.info("readers.size after remove and add: {} ", readers.size());
        }finally {
            writeLock.unlock();
        }
    }


    public void setSlowMs(List<Integer> idList, String ms){
        try {
            long slowMs = Long.parseLong(ms);

            for(int id : idList){
                try{
                    readLock.lock();
                    for (ProfilingReader reader : readers) {
                        if(id == reader.getInstanceId()){
                            reader.setSlowMs(1, slowMs);
                            break;
                        }
                    }
                }finally {
                    readLock.unlock();
                }

            }

        } catch (NumberFormatException e) {
            LOG.warn("slowMS must be numeric but was: {}", ms);
        }
    }


    public CollectorServerDto getCollectorServerDto(){
        if(writer != null){
            return writer.getCollectorServerDto();
        }
        return null;
    }
    
    private void monitor() {
        long allReadersDoneJobs = 0;
        final List<ProfilingReader> stoppedReaders = Lists.newArrayList();
        StringBuffer sb = new StringBuffer();
        try{
            readLock.lock();
            for (ProfilingReader r : readers) {
                allReadersDoneJobs += r.getDoneJobs();
                sb.append(r.getServerAddress()+"/"+r.getDatabase());
                if(r.isStopped()) {
                    sb.append("(stopped)");
                    stoppedReaders.add(r);
                }
                sb.append("=");
                sb.append(r.getDoneJobs());
                sb.append(" ");
            }
        }finally {
            readLock.unlock();
        }

        if(!sb.toString().equals(logLine1)) {
            logLine1 = sb.toString();
            LOG.info("Read {}", logLine1);
        }
        final String logLine = "Read all: " + (allReadersDoneJobs+ doneJobsOfRemovedReaders) + " Written: " + writer.getDoneJobs() + " Stopped: " + stoppedReaders.size();
        if(!logLine.equals(logLine2)) {
            logLine2 = logLine; 
            LOG.info(logLine);
        }
    }


    public static void main(String[] args) throws UnknownHostException {
        final CollectorManager result = new CollectorManager();
        result.startup();
    }



}
