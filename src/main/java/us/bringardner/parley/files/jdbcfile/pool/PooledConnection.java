package us.bringardner.parley.files.jdbcfile.pool;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * What a borrower holds: one of these per checkout, over a {@link ManagedJdbcConnection}.
 * <p>
 * The pooled connection is reused, so a reference to it that outlives close() would be a way into
 * somebody else's connection. This handle is what is closed instead: after close(), or after the
 * pool took the connection back (it was in use and idle too long), every call except close()
 * throws and isClosed() answers true, and a second close() does nothing.
 */
@SuppressWarnings({ "rawtypes" })
final class PooledConnection implements Connection {

	private static final String SQL_STATE_CLOSED = "08003";

	private volatile ManagedJdbcConnection target;

	PooledConnection(ManagedJdbcConnection target) {
		this.target = target;
		target.setOwner(this);
	}

	private ManagedJdbcConnection live() throws SQLException {
		ManagedJdbcConnection t = target;
		if( t == null ) {
			throw new SQLNonTransientConnectionException("The connection is closed", SQL_STATE_CLOSED);
		}
		if( t.getOwner() != this || t.isDestroyed() ) {
			throw new SQLNonTransientConnectionException(
					"The pool closed this connection (it was in use but not touched for too long)", SQL_STATE_CLOSED);
		}
		return t;
	}

	private ManagedJdbcConnection liveClientInfo() throws SQLClientInfoException {
		try {
			return live();
		} catch(SQLException ex) {
			throw new SQLClientInfoException(ex.getMessage(), SQL_STATE_CLOSED, 0, Collections.emptyMap(), ex);
		}
	}

	/** Give the connection back. Does nothing the second time. */
	@Override
	public void close() throws SQLException {
		ManagedJdbcConnection t = target;
		if( t != null ) {
			target = null;
			t.returnFrom(this);
		}
	}

	@Override
	public boolean isClosed() throws SQLException {
		ManagedJdbcConnection t = target;
		return t == null || t.getOwner() != this || t.isDestroyed() || t.isClosed();
	}

	public void clearWarnings() throws SQLException {
		live().clearWarnings();
	}

	public void commit() throws SQLException {
		live().commit();
	}

	public Statement createStatement() throws SQLException {
		return live().createStatement();
	}

	public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
		return live().createStatement(resultSetType, resultSetConcurrency);
	}

	public boolean getAutoCommit() throws SQLException {
		return live().getAutoCommit();
	}

	public String getCatalog() throws SQLException {
		return live().getCatalog();
	}

	public DatabaseMetaData getMetaData() throws SQLException {
		return live().getMetaData();
	}

	public int getTransactionIsolation() throws SQLException {
		return live().getTransactionIsolation();
	}

	public java.util.Map getTypeMap() throws SQLException {
		return live().getTypeMap();
	}

	public SQLWarning getWarnings() throws SQLException {
		return live().getWarnings();
	}

	public boolean isReadOnly() throws SQLException {
		return live().isReadOnly();
	}

	public String nativeSQL(String sql) throws SQLException {
		return live().nativeSQL(sql);
	}

	public CallableStatement prepareCall(String sql) throws SQLException {
		return live().prepareCall(sql);
	}

	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
		return live().prepareCall(sql, resultSetType, resultSetConcurrency);
	}

	public PreparedStatement prepareStatement(String sql) throws SQLException {
		return live().prepareStatement(sql);
	}

	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
		return live().prepareStatement(sql, resultSetType, resultSetConcurrency);
	}

	public void rollback() throws SQLException {
		live().rollback();
	}

	public void setAutoCommit(boolean autoCommit) throws SQLException {
		live().setAutoCommit(autoCommit);
	}

	public void setCatalog(String catalog) throws SQLException {
		live().setCatalog(catalog);
	}

	public void setReadOnly(boolean readOnly) throws SQLException {
		live().setReadOnly(readOnly);
	}

	public void setTransactionIsolation(int level) throws SQLException {
		live().setTransactionIsolation(level);
	}

	public void setHoldability(int arg0) throws SQLException {
		live().setHoldability(arg0);
	}

	public int getHoldability() throws SQLException {
		return live().getHoldability();
	}

	public Statement createStatement(int arg0, int arg1, int arg2) throws SQLException {
		return live().createStatement(arg0, arg1, arg2);
	}

	public PreparedStatement prepareStatement(String arg0, int arg1, int arg2, int arg3) throws SQLException {
		return live().prepareStatement(arg0, arg1, arg2, arg3);
	}

	public CallableStatement prepareCall(String arg0, int arg1, int arg2, int arg3) throws SQLException {
		return live().prepareCall(arg0, arg1, arg2, arg3);
	}

	public PreparedStatement prepareStatement(String arg0, int arg1) throws SQLException {
		return live().prepareStatement(arg0, arg1);
	}

	public PreparedStatement prepareStatement(String arg0, int[] arg1) throws SQLException {
		return live().prepareStatement(arg0, arg1);
	}

	public PreparedStatement prepareStatement(String arg0, String[] arg1) throws SQLException {
		return live().prepareStatement(arg0, arg1);
	}

	public void rollback(Savepoint arg0) throws SQLException {
		live().rollback(arg0);
	}

	public void releaseSavepoint(Savepoint arg0) throws SQLException {
		live().releaseSavepoint(arg0);
	}

	public Savepoint setSavepoint() throws SQLException {
		return live().setSavepoint();
	}

	public Savepoint setSavepoint(String arg0) throws SQLException {
		return live().setSavepoint(arg0);
	}

	public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
		live().setTypeMap(map);
	}

	public boolean isWrapperFor(Class<?> arg0) throws SQLException {
		return live().isWrapperFor(arg0);
	}

	public <T> T unwrap(Class<T> arg0) throws SQLException {
		return live().unwrap(arg0);
	}

	public Array createArrayOf(String arg0, Object[] arg1) throws SQLException {
		return live().createArrayOf(arg0, arg1);
	}

	public Blob createBlob() throws SQLException {
		return live().createBlob();
	}

	public Clob createClob() throws SQLException {
		return live().createClob();
	}

	public NClob createNClob() throws SQLException {
		return live().createNClob();
	}

	public SQLXML createSQLXML() throws SQLException {
		return live().createSQLXML();
	}

	public Struct createStruct(String arg0, Object[] arg1) throws SQLException {
		return live().createStruct(arg0, arg1);
	}

	public Properties getClientInfo() throws SQLException {
		return live().getClientInfo();
	}

	public String getClientInfo(String arg0) throws SQLException {
		return live().getClientInfo(arg0);
	}

	public boolean isValid(int arg0) throws SQLException {
		return live().isValid(arg0);
	}

	public void setClientInfo(Properties arg0) throws SQLClientInfoException {
		liveClientInfo().setClientInfo(arg0);
	}

	public void setClientInfo(String arg0, String arg1) throws SQLClientInfoException {
		liveClientInfo().setClientInfo(arg0, arg1);
	}

	public void setSchema(String schema) throws SQLException {
		live().setSchema(schema);
	}

	public String getSchema() throws SQLException {
		return live().getSchema();
	}

	public void abort(Executor executor) throws SQLException {
		live().abort(executor);
	}

	public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
		live().setNetworkTimeout(executor, milliseconds);
	}

	public int getNetworkTimeout() throws SQLException {
		return live().getNetworkTimeout();
	}
}
