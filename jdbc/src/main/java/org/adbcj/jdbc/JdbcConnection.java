/*
 *   Copyright (c) 2007 Mike Heath.  All rights reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.adbcj.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import org.adbcj.Connection;
import org.adbcj.ConnectionManager;
import org.adbcj.DbException;
import org.adbcj.DbFuture;
import org.adbcj.DbSessionClosedException;
import org.adbcj.DbSessionFuture;
import org.adbcj.Field;
import org.adbcj.PreparedStatement;
import org.adbcj.Result;
import org.adbcj.ResultEventHandler;
import org.adbcj.Type;
import org.adbcj.support.AbstractDbSession;
import org.adbcj.support.DefaultDbSessionFuture;
import org.adbcj.support.DefaultField;
import org.adbcj.support.DefaultResult;
import org.adbcj.support.DefaultValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JdbcConnection extends AbstractDbSession implements Connection {
	
	private final Logger logger = LoggerFactory.getLogger(JdbcConnection.class);
	
	private final JdbcConnectionManager connectionManager;
	private final java.sql.Connection jdbcConnection;
	
	private DbSessionFuture<Void> closeFuture;
	
	public JdbcConnection(JdbcConnectionManager connectionManager, java.sql.Connection jdbcConnection) {
		super(false);
		this.connectionManager = connectionManager;
		this.jdbcConnection = jdbcConnection;
	}

	public ConnectionManager getConnectionManager() {
		return connectionManager;
	}

	public synchronized DbSessionFuture<Void> close(boolean immediate) throws DbException {
		if (!isClosed()) {
			if (immediate) {
				cancelPendingRequests(true);
				DefaultDbSessionFuture<Void> future = new DefaultDbSessionFuture<Void>(this);
				try {
					jdbcConnection.close();
					future.setResult(null);
				} catch (SQLException e) {
					future.setException(e);
				}
				closeFuture = future;
			} else {
				CallableRequest<Void> closeRequest = new CallableRequest<Void>() {
					private boolean started = false;
					private boolean cancelled = false;
					public synchronized Void doCall() throws Exception {
						if (cancelled) {
							return null;
						}
						started = true;
						jdbcConnection.close();
						return null;
					}
					@Override
					public synchronized boolean cancelRequest(boolean mayInterruptIfRunning) {
						if (started) {
							return false;
						}
						cancelled = true;
						unclose();
						return true;
					}
					@Override
					public boolean isPipelinable() {
						return false;
					}
				};
				enqueueRequest(closeRequest);
				closeFuture = closeRequest;
			}
		}
		return closeFuture;
	}

	private synchronized void unclose() {
		this.closeFuture = null;
	}
	
	public boolean isClosed() {
		try {
			return closeFuture != null || jdbcConnection.isClosed();
		} catch (SQLException e) {
			throw new DbException(this, e);
		}
	}

	public <T> DbSessionFuture<T> executeQuery(final String sql, final ResultEventHandler<T> eventHandler, final T accumulator) {
		checkClosed();
		logger.trace("Scheduling query '{}'", sql);
		return enqueueTransactionalRequest(new CallableRequest<T>() {
			@Override
			protected T doCall() throws Exception {
				logger.debug("Executing query '{}'", sql);
				Statement jdbcStatement = jdbcConnection.createStatement();
				java.sql.ResultSet jdbcResultSet = null;
				try {
					// Execute query
					jdbcResultSet = jdbcStatement.executeQuery(sql);
					
					// Fetch meta data
					ResultSetMetaData metaData = jdbcResultSet.getMetaData();
					int columnCount = metaData.getColumnCount();
					List<Field> fields = new ArrayList<Field>(columnCount);
					eventHandler.startFields(accumulator);
					
					for (int i = 1; i <= columnCount; i++) {
						Field field = new DefaultField(
								i - 1,
								metaData.getCatalogName(i),
								metaData.getSchemaName(i),
								metaData.getTableName(i),
								metaData.getTableName(i),
								Type.fromJdbcType(metaData.getColumnType(i)),
								metaData.getColumnLabel(i),
								metaData.getCatalogName(i),
								metaData.getPrecision(i),
								metaData.getScale(i),
								metaData.isAutoIncrement(i),
								metaData.isCaseSensitive(i),
								metaData.isCurrency(i),
								metaData.isDefinitelyWritable(i),
								metaData.isNullable(i) == 1,
								metaData.isReadOnly(i),
								metaData.isSearchable(i),
								metaData.isSigned(i),
								metaData.isWritable(i),
								metaData.getColumnClassName(i)
								);
						fields.add(field);
						eventHandler.field(field, accumulator);
					}
					
					eventHandler.endFields(accumulator);
					
					eventHandler.startResults(accumulator);
					while (jdbcResultSet.next()) {
						eventHandler.startRow(accumulator);
						for (int i = 1; i <= columnCount; i++) {
							Field field = fields.get(i - 1);
							Object value = null;
							switch (field.getColumnType()) {
							case BIGINT:
								value = jdbcResultSet.getLong(i);
								break;
							case INTEGER:
								value = jdbcResultSet.getInt(i);
								break;
							case VARCHAR:
								value = jdbcResultSet.getString(i);
								break;
							default:
								throw new IllegalStateException("Don't know how to handle field to type " + field.getColumnType());
							}
							if (jdbcResultSet.wasNull()) {
								value = null;
							}
							eventHandler.value(new DefaultValue(field, value), accumulator);
						}
						eventHandler.endRow(accumulator);
					}
					eventHandler.endResults(accumulator);
					
					return accumulator;
				} finally {
					if (jdbcResultSet != null) {
						jdbcResultSet.close();
					}
					if (jdbcStatement != null) {
						jdbcStatement.close();
					}
				}
			}
		});
	}
	
	public DbSessionFuture<Result> executeUpdate(final String sql) {
		checkClosed();
		return enqueueTransactionalRequest(new CallableRequest<Result>() {
			public Result doCall() throws Exception {
				Statement statement = jdbcConnection.createStatement();
				try {
					statement.execute(sql);
					List<String> warnings = new LinkedList<String>();
					SQLWarning sqlWarnings = statement.getWarnings();
					while (sqlWarnings != null) {
						warnings.add(sqlWarnings.getLocalizedMessage());
						sqlWarnings = sqlWarnings.getNextWarning();
					}
					return new DefaultResult((long)statement.getUpdateCount(), warnings);
				} finally {
					statement.close();
				}
			}
		});
	}
	
	public DbSessionFuture<PreparedStatement> prepareStatement(String sql) {
		checkClosed();
		// TODO Implement JDBC prepareStatement(String sql)
		throw new IllegalStateException("Not yet implemented");
	}
	
	public DbSessionFuture<PreparedStatement> prepareStatement(Object key, String sql) {
		checkClosed();
		// TODO Implement JDBC prepareStatement(Object key, String sql)
		throw new IllegalStateException("Not yet implemented");
	}

	public DbFuture<Void> ping() {
		checkClosed();
		// TODO Implement JDBC ping()
		throw new IllegalStateException("Not yet implemented");
	}
	
	/*
	 * 
	 * End of API methods 
	 * 
	 */
	
	// *********** Transaction method implementations **************************
	
	@Override
	protected void sendBegin() throws SQLException {
		logger.trace("Sending begin");
		jdbcConnection.setAutoCommit(false);
	}
	
	@Override
	protected Request<Void> createBeginRequest(Transaction transaction) {
		logger.trace("Creating begin request");
		return new CallableRequestWrapper(super.createBeginRequest(transaction));
	}
	
	@Override
	protected void sendCommit() throws SQLException {
		logger.trace("Sending commit");
		jdbcConnection.commit();
	}
	
	@Override
	protected Request<Void> createCommitRequest(Transaction transaction) {
		logger.trace("Creating commit request");
		return new CallableRequestWrapper(super.createCommitRequest(transaction));
	}
	
	@Override
	protected void sendRollback() throws SQLException {
		logger.trace("Sending rollback");
		jdbcConnection.rollback();
	}
	
	@Override
	protected Request<Void> createRollbackRequest() {
		logger.trace("Creating rollback request");
		return new CallableRequestWrapper(super.createRollbackRequest());
	}
	
	// *********** JDBC Specific method implementations ************************
	
	@Override
	protected void checkClosed() {
		if (isClosed()) {
			throw new DbSessionClosedException(this, "Connection is closed");
		}
	}

	private abstract class CallableRequest<E> extends Request<E> implements Callable<E> {
		private Future<E> future = null;
		
		@Override
		public boolean cancelRequest(boolean mayInterruptIfRunning) {
			if (future == null) {
				return true;
			}
			return future.cancel(mayInterruptIfRunning);
		}
		@Override
		final public void execute() {
			logger.trace("In CallableRequest.execute() processing request {}", this);
			this.future = connectionManager.getExecutorService().submit(this);
		}
		
		final public E call() throws Exception {
			if (isCancelled()) {
				return null;
			}
			try {
				E value = doCall();
				complete(value);
				return value;
			} catch (Exception e) {
				error(DbException.wrap(JdbcConnection.this, e));
				if (jdbcConnection.isClosed()) {
					connectionManager.removeConnection(JdbcConnection.this);
				}
				throw e;
			}
		}

		protected abstract E doCall() throws Exception;
		
		@Override
		public boolean isPipelinable() {
			return false;
		}
		
	}

	private class CallableRequestWrapper extends CallableRequest<Void> {

		private final Request<Void> request;
		
		public CallableRequestWrapper(Request<Void> request) {
			this.request = request;
		}
		
		@Override
		public synchronized boolean cancelRequest(boolean mayInterruptIfRunning) {
			if (super.cancel(mayInterruptIfRunning)) {
				return request.cancel(mayInterruptIfRunning);
			}
			return false;
		}
		
		@Override
		protected Void doCall() throws Exception {
			request.invokeExecute();
			return null;
		}
		
		@Override
		public boolean canRemove() {
			return request.canRemove();
		}
		
		@Override
		public boolean isPipelinable() {
			return request.isPipelinable();
		}
	}
}