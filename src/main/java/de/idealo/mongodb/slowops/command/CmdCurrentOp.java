package de.idealo.mongodb.slowops.command;

import com.google.common.collect.Lists;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import de.idealo.mongodb.slowops.collector.ProfilingReader;
import de.idealo.mongodb.slowops.dto.CommandResultDto;
import de.idealo.mongodb.slowops.dto.ProfiledServerDto;
import de.idealo.mongodb.slowops.dto.TableDto;
import de.idealo.mongodb.slowops.monitor.MongoDbAccessor;
import de.idealo.mongodb.slowops.util.Util;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by kay.agahd on 29.06.17.
 */
public abstract class CmdCurrentOp implements ICommand {

    private static final Logger LOG = LoggerFactory.getLogger(CmdCurrentOp.class);


    private final CommandResultDto commandResultDto;

    public CmdCurrentOp() {
        commandResultDto = new CommandResultDto();
        commandResultDto.setTableHeader(Lists.newArrayList("dbs label",
        		"opid",
                "microsecs running",
                "secs running",
                "op",
                "ns",
                "command",
                "planSummary",
                "numYield",
                "active",
                "tag"));

        commandResultDto.setJsonFormattedColumn(6);

    }

    public abstract DBObject getQuery(ProfilingReader profilingReader);

    @Override
    public CommandResultDto getCommandResultDto() {
        return commandResultDto;
    }


    @Override
    public TableDto runCommand(ProfilingReader profilingReader, MongoDbAccessor mongoDbAccessor) {
        final TableDto table = new TableDto();

        try{
            final Document commandResultDoc = mongoDbAccessor.runCommand("admin", getQuery(profilingReader));

            if(commandResultDoc != null){
                Object inprog = commandResultDoc.get("inprog");
                if(inprog instanceof ArrayList) {
                    final List inprogList = (ArrayList) inprog;
                    for (Object entry : inprogList) {
                        if (entry instanceof Document) {
                            final Document entryDoc = (Document) entry;
                            final List<Object> row = Lists.newArrayList();
                            row.add(profilingReader.getProfiledServerDto().getLabel());
                            row.add("" + entryDoc.get("opid"));
                            row.add(Util.getNumber(entryDoc,"microsecs_running", 0));
                            row.add(Util.getNumber(entryDoc, "secs_running", 0));//may be Long (v4) or Integer (v3.4)
                            row.add(entryDoc.getString("op"));
                            row.add(entryDoc.getString("ns"));
                            final String originatingCommand = getJson(entryDoc, "originatingCommand");
                            row.add(originatingCommand.equals("")?getJson(entryDoc, "command"):originatingCommand);
                            row.add(entryDoc.getString("planSummary"));
                            row.add(Util.getNumber(entryDoc, "numYields", 0));
                            row.add(entryDoc.getBoolean("active"));
                            row.add(parseCommandTag(originatingCommand.equals("")?getJson(entryDoc, "command"):originatingCommand));
                            table.addRow(row);

                        }
                    }
                }
            }
        }
        catch (Exception e){
            LOG.warn("Exception while running command", e);
        }

        return table;
    }



    private String getJson(Document entryDoc, String key){
        final Object obj = entryDoc.get(key);
        if(obj instanceof Document){
            Document doc = (Document) obj;
            return doc.toJson();
        }
        return "";
    }

    private String parseCommandTag(String command){
        Pattern pattern = Pattern.compile("\\$comment: \\\\\"(.*?)\\\\\"");
        Matcher matcher = pattern.matcher(command);
        if (matcher.find()) {
            String commentValue = matcher.group(1);
            return commentValue;
        }
        return "N/A";
    }
}
