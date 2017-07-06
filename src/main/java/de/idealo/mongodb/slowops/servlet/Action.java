/*
 * Copyright (c) 2013 idealo internet GmbH -- all rights reserved.
 */
package de.idealo.mongodb.slowops.servlet;


import com.google.common.collect.Lists;
import de.idealo.mongodb.slowops.collector.CollectorManagerInstance;
import de.idealo.mongodb.slowops.dto.ApplicationStatusDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.util.List;


@Path("/action")
public class Action {

	private static final Logger LOG = LoggerFactory.getLogger(Action.class);


	@GET
	@Produces({MediaType.APPLICATION_JSON})
	public ApplicationStatusDto getApplicationJSON(@QueryParam("cmd") String cmd, @QueryParam("p") String p, @QueryParam("ms") String ms, @Context HttpServletRequest req) {
		LOG.debug(">>> getApplicationJSON cmd: {} p: {} ms:{}", new Object[]{cmd, p, ms});
        final List<Integer> pList = Lists.newArrayList();
        final boolean isAuthenticated = ApplicationStatus.isAuthenticated(req);

        if(p != null) {
			String[] params = p.split(",");
			if (params.length > 0) {
				for (String param : params) {
					try {
						pList.add(Integer.parseInt(param));
					} catch (NumberFormatException e) {
						LOG.warn("Parameter should but is not numeric: {}", param);
					}
				}
			}

			if (pList.size() > 0) {
                if(isAuthenticated) {
                    if ("start".equals(cmd)) {
                        CollectorManagerInstance.startStopProfilingReaders(pList, false);
                    } else if ("stop".equals(cmd)) {
                        CollectorManagerInstance.startStopProfilingReaders(pList, true);
                    } else if ("slowms".equals(cmd)) {
                        CollectorManagerInstance.setSlowMs(pList, ms);
                    }
                }
				LOG.debug("<<<< getApplicationJSON");
				return CollectorManagerInstance.getApplicationStatus(pList, isAuthenticated);
			}
		}else if ("rc".equals(cmd)){
            LOG.debug("<<<<< getApplicationJSON");
            return CollectorManagerInstance.getApplicationStatus(pList, isAuthenticated);
        }
		LOG.debug("<<< getApplicationJSON");
		return CollectorManagerInstance.getApplicationStatus(isAuthenticated);
	}


}
