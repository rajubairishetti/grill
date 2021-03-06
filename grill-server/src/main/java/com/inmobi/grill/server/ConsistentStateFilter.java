package com.inmobi.grill.server;

import java.io.IOException;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.apache.hive.service.Service.STATE;

public class ConsistentStateFilter implements ContainerRequestFilter {

  @Override
  public void filter(ContainerRequestContext requestContext) throws IOException {
    if (GrillServices.get().isStopping() ||
      GrillServices.get().getServiceState().equals(STATE.NOTINITED) ||
          GrillServices.get().getServiceState().equals(STATE.STOPPED)) {
      requestContext.abortWith(Response.status(Status.SERVICE_UNAVAILABLE)
          .entity("Server is going down or is getting initialized").build());
    }
  }
}
