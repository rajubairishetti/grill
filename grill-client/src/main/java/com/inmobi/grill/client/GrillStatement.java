package com.inmobi.grill.client;

/*
 * #%L
 * Grill client
 * %%
 * Copyright (C) 2014 Inmobi
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import com.inmobi.grill.api.APIResult;
import com.inmobi.grill.api.GrillConf;
import com.inmobi.grill.api.query.*;
import com.inmobi.grill.server.api.GrillConfConstants;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.glassfish.jersey.media.multipart.MultiPartFeature;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import java.util.List;

/**
 * Top level class which is used to execute grill queries.
 */
public class GrillStatement {

  private final GrillConnection connection;
  private GrillQuery query;

  public GrillStatement(GrillConnection connection) {
    this.connection = connection;
  }

  public void execute(String sql, boolean waitForQueryToComplete) {
    QueryHandle handle = executeQuery(sql, waitForQueryToComplete);
    this.query = getQuery(handle);
  }

  public void execute(String sql) {
    QueryHandle handle = executeQuery(sql, true);
    this.query = getQuery(handle);
  }

  public QueryHandle executeQuery(String sql, boolean waitForQueryToComplete) {
    QueryHandle handle = executeQuery(sql);

    if (waitForQueryToComplete) {
      waitForQueryToComplete(handle);
    }
    return handle;
  }

  private void waitForQueryToComplete(QueryHandle handle) {
    query = getQuery(handle);
    while (!query.getStatus().isFinished()) {
      query = getQuery(handle);
      try {
        Thread.sleep(connection.getGrillConnectionParams().getQueryPollInterval());
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  private WebTarget getQueryWebTarget(Client client) {
    return client.target(
        connection.getGrillConnectionParams().getBaseConnectionUrl()).path(
        connection.getGrillConnectionParams().getQueryResourcePath()).path("queries");
  }

  public GrillQuery getQuery(QueryHandle handle) {
    Client client = ClientBuilder.newClient();
    WebTarget target = getQueryWebTarget(client);
    return target.path(handle.toString()).queryParam(
        "sessionid", connection.getSessionHandle()).request().get(GrillQuery.class);
  }


  private QueryHandle executeQuery(String sql) {
    if (!connection.isOpen()) {
      throw new IllegalStateException("Grill Connection has to be " +
          "established before querying");
    }

    Client client = ClientBuilder.newBuilder().register(
        MultiPartFeature.class).build();
    FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("sessionid").build(),
        connection.getSessionHandle(), MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        sql));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(),
        "execute"));

    WebTarget target = getQueryWebTarget(client);

    QueryHandle handle = target.request().post(Entity.entity(mp,
        MediaType.MULTIPART_FORM_DATA_TYPE), QueryHandle.class);
    return handle;
  }

  public QueryPlan explainQuery(String sql) {
    if (!connection.isOpen()) {
      throw new IllegalStateException("Grill Connection has to be " +
          "established before querying");
    }

    Client client = ClientBuilder.newBuilder().register(
        MultiPartFeature.class).build();
    FormDataMultiPart mp = new FormDataMultiPart();
    mp.bodyPart(new FormDataBodyPart(
        FormDataContentDisposition.name("sessionid").build(),
        connection.getSessionHandle(), MediaType.APPLICATION_XML_TYPE));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("query").build(),
        sql));
    mp.bodyPart(new FormDataBodyPart(FormDataContentDisposition.name("operation").build(),
        "explain"));

    WebTarget target = getQueryWebTarget(client);

    QueryPlan handle = target.request().post(Entity.entity(mp,
        MediaType.MULTIPART_FORM_DATA_TYPE), QueryPlan.class);
    return handle;
  }


  public List<QueryHandle> getAllQueries() {
    WebTarget target = getQueryWebTarget(ClientBuilder
        .newBuilder().register(MultiPartFeature.class).build());
    List<QueryHandle> handles = target.queryParam("sessionid", connection.getSessionHandle()).request().get(
        new GenericType<List<QueryHandle>>() {
        });
    return handles;
  }
  public QueryResultSetMetadata getResultSetMetaData() {
    return this.getResultSetMetaData(query);
  }

  public QueryResultSetMetadata getResultSetMetaData(GrillQuery query) {
    if (query.getStatus().getStatus() != QueryStatus.Status.SUCCESSFUL) {
      throw new IllegalArgumentException("Result set metadata " +
          "can be only queries for successful queries");
    }
    Client client = ClientBuilder.newClient();

    WebTarget target = getQueryWebTarget(client);

    return target.path(query.getQueryHandle().toString()).
        path("resultsetmetadata").queryParam(
        "sessionid", connection.getSessionHandle()).request().get(
        QueryResultSetMetadata.class);
  }

  public InMemoryQueryResult getResultSet() {
     return this.getResultSet(this.query);
  }

  public InMemoryQueryResult getResultSet(GrillQuery query) {
    if (query.getStatus().getStatus() != QueryStatus.Status.SUCCESSFUL) {
      throw new IllegalArgumentException("Result set metadata " +
          "can be only queries for successful queries");
    }
    Client client = ClientBuilder.newClient();

    WebTarget target = getQueryWebTarget(client);
    return target.path(query.getQueryHandle().toString()).
        path("resultset").queryParam(
        "sessionid", connection.getSessionHandle()).request().get(
        InMemoryQueryResult.class);
  }


  public boolean kill() {
    return this.kill(query);
  }
  public boolean kill(GrillQuery query) {

    if (query.getStatus().isFinished()) {
      return false;
    }

    Client client = ClientBuilder.newClient();
    WebTarget target = getQueryWebTarget(client);

    APIResult result = target.path(query.getQueryHandle().toString()).
        queryParam("sessionid", connection.getSessionHandle()).request().
        delete(APIResult.class);

    if (result.getStatus().equals(APIResult.Status.SUCCEEDED)) {
      return true;
    } else {
      return false;
    }
  }

  public boolean closeResultSet() {
    if (!query.getStatus().isResultSetAvailable()) {
      return false;
    }
    Client client = ClientBuilder.newClient();
    WebTarget target = getQueryWebTarget(client);

    APIResult result = target.path(query.getQueryHandle().toString()).
        path("resultset").queryParam("sessionid", connection.getSessionHandle()).
        request().delete(APIResult.class);

    if (result.getStatus() == APIResult.Status.SUCCEEDED) {
      return true;
    } else {
      return false;
    }
  }

  public boolean isIdle() {
    return query == null || query.getStatus().isFinished();
  }

  public boolean wasQuerySuccessful() {
    return query.getStatus().getStatus().equals(QueryStatus.Status.SUCCESSFUL);
  }

  public QueryStatus getStatus() {
    return getQuery().getStatus();
  }

  public GrillQuery getQuery() {
    return this.query;
  }
}
