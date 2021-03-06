package com.inmobi.grill.driver.jdbc;

/*
 * #%L
 * Grill Driver for JDBC
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


import com.inmobi.grill.api.GrillException;
import com.inmobi.grill.api.query.QueryHandle;
import com.inmobi.grill.api.query.QueryPrepareHandle;
import com.inmobi.grill.api.query.QueryStatus;
import com.inmobi.grill.api.query.QueryStatus.Status;
import com.inmobi.grill.server.api.driver.DriverQueryPlan;
import com.inmobi.grill.server.api.driver.GrillDriver;
import com.inmobi.grill.server.api.driver.GrillResultSet;
import com.inmobi.grill.server.api.driver.QueryCompletionListener;
import com.inmobi.grill.server.api.query.PreparedQueryContext;
import com.inmobi.grill.server.api.query.QueryContext;

import org.apache.hadoop.conf.Configuration;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.Getter;
import lombok.Setter;
import static com.inmobi.grill.driver.jdbc.JDBCDriverConfConstants.*;
/**
 * This driver is responsible for running queries against databases which can be queried using the JDBC API.
 */
public class JDBCDriver implements GrillDriver {
  public static final Logger LOG = Logger.getLogger(JDBCDriver.class);
  public static final AtomicInteger thid = new AtomicInteger();

  private ConnectionProvider connectionProvider;
  boolean configured = false;
  private ExecutorService asyncQueryPool;
  private ConcurrentHashMap<QueryHandle, JdbcQueryContext> queryContextMap;
  private ConcurrentHashMap<Class<? extends QueryRewriter>, QueryRewriter> rewriterCache;
  private Configuration conf;
  
  /**
   * Data related to a query submitted to JDBCDriver
   */
  protected class JdbcQueryContext {
    @Getter private final QueryContext grillContext;
    @Getter @Setter private Future<QueryResult> resultFuture;
    @Getter @Setter private String rewrittenQuery;
    @Getter @Setter private boolean isPrepared;
    @Getter @Setter private boolean isCancelled;
    @Getter private boolean isClosed;
    @Getter @Setter private QueryCompletionListener listener;
    @Getter @Setter private QueryResult queryResult;
    
    public JdbcQueryContext(QueryContext context) {
      this.grillContext = context;
    }

    public void notifyError(Throwable th) {
      // If query is closed in another thread while the callable is still waiting for result
      // set, then it throws an SQLException in the callable. We don't want to send that exception
      if (listener != null && !isClosed) {
        listener.onError(grillContext.getQueryHandle(), th.getMessage());
      }
    }
    
    public void notifyComplete() {
      if (listener != null) {
        listener.onCompletion(grillContext.getQueryHandle());
      }
    }

    public void closeResult() {
      if (queryResult != null) {
        queryResult.close();
      }
      isClosed = true;
    }
  }

  /**
   * Result of a query and associated resources like statement and connection.
   * After the results are consumed, close() should be called to close the statement and connection
   */
  protected class QueryResult {
    private ResultSet resultSet;
    private Throwable error;
    private Connection conn;
    private Statement stmt;
    private boolean isClosed;
    private JDBCResultSet grillResultSet;

    protected synchronized void close() {
      if (isClosed) {
        return;
      }
      
      try {
        if (stmt != null) {
          try {
            stmt.close();
          } catch (SQLException e) {
            LOG.error("Error closing SQL statement", e);
          }
        }
      } finally {
        if (conn != null) {
          try {
            conn.close();
          } catch (SQLException e) {
            LOG.error("Error closing SQL Connection", e);
          }
        }
      }
      isClosed = true;
    }
    
    protected synchronized GrillResultSet getGrillResultSet() throws GrillException {
      if (error != null) {
        throw new GrillException("Query failed!", error);
      }
      if (grillResultSet == null) {
        grillResultSet = new JDBCResultSet(this, resultSet);
      }
      return grillResultSet;
    }
  }

  /**
   * Callabled that returns query result after running the query. This is used for async queries.
   */
  protected class QueryCallable implements Callable<QueryResult> {
    private final JdbcQueryContext queryContext;
    public QueryCallable(JdbcQueryContext queryContext) {
      this.queryContext = queryContext;
    }

    @Override
    public QueryResult call() {
      Statement stmt = null;
      Connection conn = null;
      QueryResult result = new QueryResult();
      queryContext.setQueryResult(result);
      
      try {
        conn = getConnection(queryContext.getGrillContext().getConf());
        result.conn = conn;
      } catch (GrillException e) {
        LOG.error("Error obtaining connection: " + e.getMessage(), e);
        result.error = e;
      }

      if (conn != null) {
        try {
          stmt = getStatement(conn);
          result.stmt = stmt;
          result.resultSet = stmt.executeQuery(queryContext.getRewrittenQuery());
          queryContext.notifyComplete();
        } catch (SQLException sqlEx) {
          if (queryContext.isClosed()) {
            LOG.info("Ignored exception on already closed query: "
                + queryContext.getGrillContext().getQueryHandle() +" - " + sqlEx);
          } else {
            LOG.error(
                "Error executing SQL query: " + queryContext.getGrillContext().getQueryHandle()
                    + " reason: " + sqlEx.getMessage(), sqlEx);
            result.error = sqlEx;
            queryContext.notifyError(sqlEx);
          }
        }
      }
      return result;
    }
    
    public Statement getStatement(Connection conn) throws SQLException {
      Statement stmt = 
          queryContext.isPrepared() ? conn.prepareStatement(queryContext.getRewrittenQuery())
              : conn.createStatement();
      stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
      return stmt;
    }
  }
  
  public static class DummyQueryRewriter implements QueryRewriter {
    @Override
    public String rewrite(Configuration conf, String query) throws GrillException {
      return query;
    }
  }

  /**
   * Get driver configuration
   */
  @Override
  public Configuration getConf() {
    return conf;
  }

  /**
   * Configure driver with {@link org.apache.hadoop.conf.Configuration} passed
   *
   * @param conf The configuration object
   */
  @Override
  public void configure(Configuration conf) throws GrillException {
    this.conf = conf;
    init(conf);
    configured = true;
    LOG.info("JDBC Driver configured");
  }

  protected void init(Configuration conf) throws GrillException {
    queryContextMap = new ConcurrentHashMap<QueryHandle, JdbcQueryContext>();
    rewriterCache = new ConcurrentHashMap<Class<? extends QueryRewriter>, QueryRewriter>();
    asyncQueryPool = Executors.newCachedThreadPool(new ThreadFactory() {
      @Override
      public Thread newThread(Runnable runnable) {
        Thread th = new Thread(runnable);
        th.setName("grill-driver-jdbc-" + thid.incrementAndGet());
        return th;
      }
    });
    
    Class<? extends ConnectionProvider> cpClass = conf.getClass(JDBC_CONNECTION_PROVIDER, 
        DataSourceConnectionProvider.class, ConnectionProvider.class);
    try {
      connectionProvider = cpClass.newInstance();
    } catch (Exception e) {
      LOG.error("Error initializing connection provider: " + e.getMessage(), e);
      throw new GrillException(e);
    }
  }
  
  protected void checkConfigured() throws IllegalStateException {
    if (!configured) 
      throw new IllegalStateException("JDBC Driver is not configured!");
  }

  protected synchronized Connection getConnection(Configuration conf) throws GrillException {
    try {
      return connectionProvider.getConnection(conf);
    } catch (SQLException e) {
      throw new GrillException(e);
    }
  }

  protected synchronized QueryRewriter getQueryRewriter(Configuration conf) throws GrillException {
    QueryRewriter rewriter;
    Class<? extends QueryRewriter> queryRewriterClass = 
        conf.getClass(JDBC_QUERY_REWRITER_CLASS, DummyQueryRewriter.class, QueryRewriter.class);
    if (rewriterCache.containsKey(queryRewriterClass)) {
      rewriter =  rewriterCache.get(queryRewriterClass);
    } else {
      try {
        rewriter = queryRewriterClass.newInstance();
      } catch (Exception e) {
        LOG.error("Unable to create rewriter object", e);
        throw new GrillException(e);
      }
      rewriterCache.put(queryRewriterClass, rewriter);
    }
    return rewriter;
  }
  
  protected JdbcQueryContext getQueryContext(QueryHandle handle) throws GrillException {
     JdbcQueryContext ctx = queryContextMap.get(handle);
     if (ctx == null) {
       throw new GrillException("Query not found:" + handle.getHandleId());
     }
     return ctx;
  }

  protected String rewriteQuery(String query, Configuration conf) throws GrillException {
    QueryRewriter rewriter = getQueryRewriter(conf);
    String rewrittenQuery = rewriter.rewrite(conf, query);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Query: " + query + " rewrittenQuery: " + rewrittenQuery);
    }
    return rewrittenQuery;
  }

  /**
   * Explain the given query
   *
   * @param query The query should be in HiveQL(SQL like)
   * @param conf  The query configuration
   * @return The query plan object;
   * @throws com.inmobi.grill.api.GrillException
   */
  @Override
  public DriverQueryPlan explain(String query, Configuration conf) throws GrillException {
    checkConfigured();
    //TODO
    return null;
  }

  /**
   * Prepare the given query
   *
   * @param pContext
   * @throws com.inmobi.grill.api.GrillException
   */
  @Override
  public void prepare(PreparedQueryContext pContext) throws GrillException {
    checkConfigured();
    // Only create a prepared statement and then close it
    String rewrittenQuery = rewriteQuery(pContext.getUserQuery(), pContext.getConf());
    Connection conn = null;
    PreparedStatement stmt = null;
    try {
      conn = getConnection(pContext.getConf());
      stmt = conn.prepareStatement(rewrittenQuery);
    } catch (SQLException sql) {
      throw new GrillException(sql);
    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (SQLException e) {
          LOG.error("Error closing statement: " + pContext.getPrepareHandle(), e);
        }
      }
      
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException e) {
          LOG.error("Error closing connection: " + pContext.getPrepareHandle(), e);
        }
      }
    }
    LOG.info("Prepared: " + pContext.getPrepareHandle());
  }

  /**
   * Explain and prepare the given query
   *
   * @param pContext
   * @return The query plan object;
   * @throws com.inmobi.grill.api.GrillException
   */
  @Override
  public DriverQueryPlan explainAndPrepare(PreparedQueryContext pContext) throws GrillException {
    checkConfigured();
    //TODO
    return null;
  }

  /**
   * Close the prepare query specified by the prepared handle,
   * releases all the resources held by the prepared query.
   *
   * @param handle The query handle
   * @throws com.inmobi.grill.api.GrillException
   */
  @Override
  public void closePreparedQuery(QueryPrepareHandle handle) throws GrillException {
    checkConfigured();
    // Do nothing
  }

  /**
   * Blocking execute of the query
   *
   * @param context
   * @return returns the result set
   * @throws com.inmobi.grill.api.GrillException
   */
  @Override
  public GrillResultSet execute(QueryContext context) throws GrillException {
    checkConfigured();
    String rewrittenQuery = rewriteQuery(context.getUserQuery(), context.getConf());
    JdbcQueryContext queryContext = new JdbcQueryContext(context);
    queryContext.setPrepared(false);
    queryContext.setRewrittenQuery(rewrittenQuery);
    QueryResult result = new QueryCallable(queryContext).call();
    LOG.info("Execute " + context.getQueryHandle());
    return result.getGrillResultSet();
  }

  /**
   * Asynchronously execute the query
   *
   * @param context The query context
   * 
   * @throws com.inmobi.grill.api.GrillException
   */
  @Override
  public void executeAsync(QueryContext context) throws GrillException {
    checkConfigured();
    String rewrittenQuery = rewriteQuery(context.getUserQuery(), context.getConf());
    JdbcQueryContext jdbcCtx = new JdbcQueryContext(context);
    jdbcCtx.setRewrittenQuery(rewrittenQuery);
    try {
      Future<QueryResult> future = asyncQueryPool.submit(new QueryCallable(jdbcCtx));
      jdbcCtx.setResultFuture(future);
    } catch (RejectedExecutionException e) {
      LOG.error("Query execution rejected: " + context.getQueryHandle() + " reason:" 
          + e.getMessage(), e);
      throw new GrillException("Query execution rejected: " + context.getQueryHandle() + " reason:" 
          + e.getMessage(), e);
    }
    queryContextMap.put(context.getQueryHandle(), jdbcCtx);
    LOG.info("ExecuteAsync: " + context.getQueryHandle());
  }

  /**
   * Register for query completion notification
   *
   * @param handle
   * @param timeoutMillis
   * @param listener
   * @throws com.inmobi.grill.api.GrillException
   */
  @Override
  public void registerForCompletionNotification(QueryHandle handle, long timeoutMillis, 
      QueryCompletionListener listener) throws GrillException {
    checkConfigured();
    getQueryContext(handle).setListener(listener);
  }

  /**
   * Get status of the query, specified by the handle
   *
   * @param handle The query handle
   * @return query status
   */
  @Override
  public QueryStatus getStatus(QueryHandle handle) throws GrillException {
    checkConfigured();
    JdbcQueryContext ctx = getQueryContext(handle);
    QueryStatus status;
    
    if (ctx.getResultFuture().isDone()) {
      // Since future is already done, this call should not block
      if (ctx.isCancelled()) {
        status = 
            new QueryStatus(1.0, Status.CANCELED, handle.getHandleId() + " cancelled", true, null, null);
      } else if (ctx.getQueryResult() != null && ctx.getQueryResult().error != null) {
        status = new QueryStatus(1.0, Status.FAILED, ctx.getQueryResult().error.getMessage(), false, null, null);
      } else {
        status = 
            new QueryStatus(1.0, Status.SUCCESSFUL, handle.getHandleId() + " successful", true, null, null);
      }
    } else {
      status = new QueryStatus(0.0, Status.RUNNING, handle.getHandleId() + " is running", false, null, null);
    }
    return status;
  }

  /**
   * Fetch the results of the query, specified by the handle
   *
   * @param context
   * 
   * @return returns the {@link GrillResultSet}.
   */
  @Override
  public GrillResultSet fetchResultSet(QueryContext context) throws GrillException {
    checkConfigured();
    JdbcQueryContext ctx = getQueryContext(context.getQueryHandle());
    if (ctx.isCancelled()) {
      throw new GrillException("Result set not available for cancelled query "
          + context.getQueryHandle());
    }
    
    Future<QueryResult> future = ctx.getResultFuture();
    QueryHandle queryHandle = context.getQueryHandle();
    
    try {
      return future.get().getGrillResultSet();
    } catch (InterruptedException e) {
      throw new GrillException("Interrupted while getting resultset for query "
          + queryHandle.getHandleId(), e);
    } catch (ExecutionException e) {
      throw new GrillException("Error while executing query "
          + queryHandle.getHandleId() + " in background", e);
    } catch (CancellationException e) {
      throw new GrillException("Query was already cancelled "
          + queryHandle.getHandleId(), e);
    }
  }

  /**
   * Close the resultset for the query
   *
   * @param handle The query handle
   * @throws com.inmobi.grill.api.GrillException
   */
  @Override
  public void closeResultSet(QueryHandle handle) throws GrillException {
    checkConfigured();
    getQueryContext(handle).closeResult();
  }

  /**
   * Cancel the execution of the query, specified by the handle
   *
   * @param handle The query handle.
   * @return true if cancel was successful, false otherwise
   */
  @Override
  public boolean cancelQuery(QueryHandle handle) throws GrillException {
    checkConfigured();
    JdbcQueryContext context = getQueryContext(handle);
    boolean cancelResult = context.getResultFuture().cancel(true);
    context.setCancelled(true);
    context.closeResult();
    LOG.info("Cancelled query: " + handle);
    return cancelResult;
  }

  /**
   * Close the query specified by the handle, releases all the resources
   * held by the query.
   *
   * @param handle The query handle
   * @throws com.inmobi.grill.api.GrillException
   */
  @Override
  public void closeQuery(QueryHandle handle) throws GrillException {
    checkConfigured();
    try {
      JdbcQueryContext ctx = getQueryContext(handle);
      ctx.getResultFuture().cancel(true);
      ctx.closeResult();
    } finally {
      queryContextMap.remove(handle);
    }
    LOG.info("Closed query " + handle.getHandleId());
  }

  /**
   * Close the driver, releasing all resouces used up by the driver
   *
   * @throws com.inmobi.grill.api.GrillException
   */
  @Override
  public void close() throws GrillException {
    checkConfigured();
    try {
      for (QueryHandle query : new ArrayList<QueryHandle>(queryContextMap.keySet())) {
        try {
          closeQuery(query);
        } catch (GrillException e) {
          LOG.warn("Error closing query : " + query.getHandleId(), e);
        }
      }
    } finally {
      queryContextMap.clear();
    }
  }

  @Override
  public void readExternal(ObjectInput arg0) throws IOException,
      ClassNotFoundException {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void writeExternal(ObjectOutput arg0) throws IOException {
    // TODO Auto-generated method stub
    
  }
}
