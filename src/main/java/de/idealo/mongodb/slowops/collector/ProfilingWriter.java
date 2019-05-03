/*
 * Copyright (c) 2013 idealo internet GmbH -- all rights reserved.
 */
package de.idealo.mongodb.slowops.collector;

import com.mongodb.BasicDBObject;
import com.mongodb.MongoException;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import de.idealo.mongodb.slowops.dto.ApplicationStatusDto;
import de.idealo.mongodb.slowops.dto.CollectorServerDto;
import de.idealo.mongodb.slowops.monitor.MongoDbAccessor;
import de.idealo.mongodb.slowops.util.ConfigReader;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 
 * 
 * @author kay.agahd
 * @since 25.02.2013
 * @version $Id: $
 * @copyright idealo internet GmbH
 */
public class ProfilingWriter extends Thread implements Terminable{
    
    private static final Logger LOG = LoggerFactory.getLogger(ProfilingWriter.class);
    private static final int RETRY_AFTER_SECONDS = 10;
    
    private final BlockingQueue<ProfilingEntry> jobQueue;
    
    private boolean stop = false;
    private AtomicLong doneJobs = new AtomicLong(0);
    private ProfilingEntry lastJob;
    private CollectorServerDto serverDto;
    private final Date runningSince;

    public ProfilingWriter(BlockingQueue<ProfilingEntry> jobQueue) {
        this.jobQueue = jobQueue;
        serverDto = ConfigReader.getCollectorServer();
        runningSince = new Date();

        final MongoDbAccessor mongo = getMongoDbAccessor();
        try {
            final MongoCollection<Document> profileCollection = getProfileCollection(mongo);

            IndexOptions indexOptions = new IndexOptions();
            indexOptions.background(true);
            LOG.info("Create index {ts:-1, lbl:1} in the background if it does not yet exists");
            profileCollection.createIndex(new BasicDBObject("ts",-1).append("lbl", 1), indexOptions);
            LOG.info("Create index {adr:1, db:1, ts:-1} in the background if it does not yet exists");
            profileCollection.createIndex(new BasicDBObject("adr",1).append("db",1).append("ts", -1), indexOptions);

            LOG.info("ProfilingWriter is ready at {}", serverDto.getHosts());

        } catch (MongoException e) {
            LOG.error("Exception while connecting to: {}", serverDto.getHosts(), e);
        }finally {
            if(mongo!=null) mongo.closeConnections();
        }
    }


    public MongoDbAccessor getMongoDbAccessor(){
        return new MongoDbAccessor(serverDto.getAdminUser(), serverDto.getAdminPw(), serverDto.getSsl(), serverDto.getHosts());
    }

    private MongoCollection<Document> getProfileCollection(MongoDbAccessor mongo){
        final MongoDatabase db = mongo.getMongoDatabase(serverDto.getDb());
        final MongoCollection<Document> result =  db.getCollection(serverDto.getCollection());

        if(result == null) {
            throw new IllegalArgumentException("Can't continue without profile collection for " + serverDto.getHosts());
        }
        return result;
    }
    
    private void init(MongoDbAccessor mongo) {
        LOG.info(">>> init");

        try {
            final MongoCollection<Document> profileCollection = getProfileCollection(mongo);

            IndexOptions indexOptions = new IndexOptions();
            indexOptions.background(true);
            LOG.info("Create index {ts:-1, lbl:1} in the background if it does not yet exists");
            profileCollection.createIndex(new BasicDBObject("ts",-1).append("lbl", 1), indexOptions);
            LOG.info("Create index {adr:1, db:1, ts:-1} in the background if it does not yet exists");
            profileCollection.createIndex(new BasicDBObject("adr",1).append("db",1).append("ts", -1), indexOptions);

        } catch (MongoException e) {
            LOG.error("Exception while connecting to: {}", serverDto.getHosts(), e);
        }
        
        LOG.info("<<< init");
    }
    

    @Override
    public void terminate() {
        stop = true;
        interrupt();//need to interrupt when sleeping or waiting on jobQueue
    }
    
    
    @Override
    public long getDoneJobs() {
        return doneJobs.get();
    }

    public CollectorServerDto getCollectorServerDto(){ return serverDto;}

    public Date getRuningSince() { return runningSince; }
    
    public Date getNewest(MongoDbAccessor mongo, ServerAddress adr, String db) {
        try {
            final MongoCollection<Document> profileCollection = getProfileCollection(mongo);

            if(adr != null) {
                final BasicDBObject query = new BasicDBObject();
                final BasicDBObject fields = new BasicDBObject();
                final BasicDBObject sort = new BasicDBObject();
                query.put("adr", adr.getHost() + ":" + adr.getPort());
                query.put("db", db);
                fields.put("_id", Integer.valueOf(0));
                fields.put("ts", Integer.valueOf(1));
                sort.put("ts", Integer.valueOf(-1));
                
                final MongoCursor<Document> c = profileCollection.find(query).projection(fields).sort(sort).limit(1).iterator();
                try {
                    if(c.hasNext()) {
                        final Document obj = c.next();
                        final Object ts = obj.get("ts");
                        if(ts != null) {
                            return (Date)ts;
                        }
                    }
                }finally {
                	c.close();
                }
            }
        }catch(Exception e) {
            LOG.error("Couldn't get newest entry for {}/{}", new Object[]{adr, db, e});

        }
        return null;
        
    }
    
    private void writeEntries(MongoDbAccessor mongo) {

        final MongoCollection<Document> profileCollection = getProfileCollection(mongo);
        try {
            while(!stop) {
                if(lastJob == null) {
                    lastJob = jobQueue.take();
                }
                profileCollection.insertOne(lastJob.getDocument());
                doneJobs.incrementAndGet();
                lastJob = null;
            }
        }catch(Exception e) {
            LOG.error("Exception occurred, will return and try again.", e);
            return;
        }
    }
    
    /* (non-Javadoc)
     * @see java.lang.Runnable#run()
     */
    @Override
    public void run() {

        MongoDbAccessor mongo = null;
        try {
            while(!stop) {
                
                if(lastJob != null || jobQueue.size() > 0) {
                    mongo = getMongoDbAccessor();
                    init(mongo);
                    writeEntries(mongo);
                }
                
                if(!stop) {
                    try {
                        LOG.debug("sleeping...");
                        Thread.sleep(1000*RETRY_AFTER_SECONDS);
                        
                    } catch (InterruptedException e) {
                        LOG.error("InterruptedException while sleeping: ");
                        stop = true;
                    }
                }
            }
        }finally {
            ApplicationStatusDto.addWebLog("ProfilingWriter terminated");
            if(mongo != null) mongo.closeConnections();
            terminate();
        }
        LOG.info("Run terminated.");
    }


}
