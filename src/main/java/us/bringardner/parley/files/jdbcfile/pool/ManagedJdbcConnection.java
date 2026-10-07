/**
 * <PRE>
 * 
 * Copyright Tony Bringarder 1998, 2025 
 * 
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       <A href="http://www.apache.org/licenses/LICENSE-2.0">http://www.apache.org/licenses/LICENSE-2.0</A>
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *  </PRE>
 *   
 *   
 *	@author Tony Bringardner   
 *
 *
 * ~version~V001.01.47-V000.01.25-V000.00.01-V000.00.00-
 */
package us.bringardner.parley.files.jdbcfile.pool;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;

/**
 * 
 * Creation date: (7/4/2002 8:27:35 AM)
 * @author: Tony Bringardner
 */
public class ManagedJdbcConnection extends ManagedObjectImp implements Connection 
{
	private Connection con;

	//  These are the values that were in this object when
	//  it was opened.  They are used to 'reset' the 
	//  object after use
	private boolean autoCommit;
	private boolean readOnly;
	private int isolation;
	//private long lastAccess;
	
	private Object getKeepalive(final Object obj) {
		touch();
		Class<?> c = obj.getClass();
		Class<?>[] i = c.getInterfaces();
		if( i == null || i.length < 1) {
			Class<?> cc = c.getSuperclass();
			while( cc != null ) {
				i = cc.getInterfaces();
				if( !(i == null || i.length < 1)) {
					break;
				}
			}
		}
		Object proxy = java.lang.reflect.Proxy.newProxyInstance(c.getClassLoader(), 
				i, 
				new InvocationHandler() {

			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				touch();
				try {
					Object ret = method.invoke(obj, args);
			        return ret;
			    }  catch (InvocationTargetException ex) {
			        throw ex.getCause();
			    }
				
			}
		});

		return proxy;
	}
	/**
	 * ManagedJdbcConnection constructor comment.
	 * @param obj java.lang.Object
	 * @param newpool us.bringardner.parley.files.jdbcfile.pool.ObjectPool
	 */
	public ManagedJdbcConnection(Object obj) throws SQLException	{
		super(obj);
		con = (Connection)obj;
		isolation = con.getTransactionIsolation();
		autoCommit = con.getAutoCommit();
		readOnly = con.isReadOnly();
	}
	
	/**
	 * Clears all warnings reported for this <code>Connection</code> object.	
	 * After a call to this method, the method <code>getWarnings</code>
	 * returns null until a new warning is
	 * reported for this Connection.  
	 *
	 * @exception SQLException if a database access error occurs
	 */
	public void clearWarnings() throws SQLException 
	{
		touch();
		con.clearWarnings();	
	}
	/**
	 * Releases a Connection's database and JDBC resources
	 * immediately instead of waiting for
	 * them to be automatically released.
	 *
	 * <P><B>Note:</B> A Connection is automatically closed when it is
	 * garbage collected. Certain fatal errors also result in a closed
	 * Connection.
	 *
	 * @exception SQLException if a database access error occurs
	 */
	public void close() throws SQLException 
	{
		//  Reset these parameters to their original values
		try { con.rollback(); } catch(Exception ex) {}
		try { con.clearWarnings(); } catch(Exception ex) {}
		try { con.setAutoCommit(autoCommit); } catch(Exception ex) {}
		try { con.setTransactionIsolation(isolation); } catch(Exception ex) {}
		try { con.setReadOnly(readOnly); } catch(Exception ex) {}
		touch();
		//  Don't close the connection but release it back to the pool
		release();	
	}
	/**
	 * Makes all changes made since the previous
	 * commit/rollback permanent and releases any database locks
	 * currently held by the Connection. This method should be
	 * used only when auto-commit mode has been disabled.
	 *
	 * @exception SQLException if a database access error occurs
	 * @see #setAutoCommit 
	 */
	public void commit() throws SQLException 
	{
		touch();
		con.commit();
	}
	/**
	 * Creates a <code>Statement</code> object for sending
	 * SQL statements to the database.
	 * SQL statements without parameters are normally
	 * executed using Statement objects. If the same SQL statement 
	 * is executed many times, it is more efficient to use a 
	 * PreparedStatement
	 *
	 * JDBC 2.0
	 *
	 * Result sets created using the returned Statement will have
	 * forward-only type, and read-only concurrency, by default.
	 *
	 * @return a new Statement object 
	 * @exception SQLException if a database access error occurs
	 */
	public Statement createStatement() throws SQLException {
		return (Statement) getKeepalive(con.createStatement());
	}
	
	/**
	 * JDBC 2.0
	 *
	 * Creates a <code>Statement</code> object that will generate
	 * <code>ResultSet</code> objects with the given type and concurrency.
	 * This method is the same as the <code>createStatement</code> method
	 * above, but it allows the default result set
	 * type and result set concurrency type to be overridden.
	 *
	 * @param resultSetType a result set type; see ResultSet.TYPE_XXX
	 * @param resultSetConcurrency a concurrency type; see ResultSet.CONCUR_XXX
	 * @return a new Statement object 
	 * @exception SQLException if a database access error occurs
	 */
	public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
		
		return (Statement) getKeepalive(con.createStatement(resultSetType, resultSetConcurrency));
	}
	
	/**
	 * Gets the current auto-commit state.
	 *
	 * @return the current state of auto-commit mode
	 * @exception SQLException if a database access error occurs
	 * @see #setAutoCommit 
	 */
	public boolean getAutoCommit() throws SQLException {
		touch();
		return con.getAutoCommit();
	}
	/**
	 * Returns the Connection's current catalog name.
	 *
	 * @return the current catalog name or null
	 * @exception SQLException if a database access error occurs
	 */
	public String getCatalog() throws SQLException {
		touch();
		return con.getCatalog();
	}
	/**
	 * Gets the metadata regarding this connection's database.
	 * A Connection's database is able to provide information
	 * describing its tables, its supported SQL grammar, its stored
	 * procedures, the capabilities of this connection, and so on. This
	 * information is made available through a DatabaseMetaData
	 * object.
	 *
	 * @return a DatabaseMetaData object for this Connection 
	 * @exception SQLException if a database access error occurs
	 */
	public DatabaseMetaData getMetaData() throws SQLException {
		touch();
		return con.getMetaData();
	}
	/**
	 * Gets this Connection's current transaction isolation level.
	 *
	 * @return the current TRANSACTION_* mode value
	 * @exception SQLException if a database access error occurs
	 */
	public int getTransactionIsolation() throws SQLException {
		touch();
		return con.getTransactionIsolation();
	}
	/**
	 * JDBC 2.0
	 *
	 * Gets the type map object associated with this connection.
	 * Unless the application has added an entry to the type map,
	 * the map returned will be empty.
	 *
	 * @return the <code>java.util.Map</code> object associated 
	 *         with this <code>Connection</code> object
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public java.util.Map getTypeMap() throws SQLException {
		touch();
		return con.getTypeMap();
	}
	/**
	 * Returns the first warning reported by calls on this Connection.
	 *
	 * <P><B>Note:</B> Subsequent warnings will be chained to this
	 * SQLWarning.
	 *
	 * @return the first SQLWarning or null 
	 * @exception SQLException if a database access error occurs
	 */
	public SQLWarning getWarnings() throws SQLException {
		touch();
		return con.getWarnings();
	}
	/**
	 * Tests to see if a Connection is closed.
	 *
	 * @return true if the connection is closed; false if it's still open
	 * @exception SQLException if a database access error occurs
	 */
	public boolean isClosed() 
			throws SQLException 
	{
		touch();
		return con.isClosed();
	}
	/**
	 * Tests to see if the connection is in read-only mode.
	 *
	 * @return true if connection is read-only and false otherwise
	 * @exception SQLException if a database access error occurs
	 */
	public boolean isReadOnly() throws SQLException {
		touch();
		return con.isReadOnly();
	}
	/**
	 * Converts the given SQL statement into the system's native SQL grammar.
	 * A driver may convert the JDBC sql grammar into its system's
	 * native SQL grammar prior to sending it; this method returns the
	 * native form of the statement that the driver would have sent.
	 *
	 * @param sql a SQL statement that may contain one or more '?'
	 * parameter placeholders
	 * @return the native form of this statement
	 * @exception SQLException if a database access error occurs
	 */
	public String nativeSQL(String sql) throws SQLException {
		touch();
		return con.nativeSQL(sql);
	}
	/**
	 * Creates a <code>CallableStatement</code> object for calling
	 * database stored procedures.
	 * The CallableStatement provides
	 * methods for setting up its IN and OUT parameters, and
	 * methods for executing the call to a stored procedure.
	 *
	 * <P><B>Note:</B> This method is optimized for handling stored
	 * procedure call statements. Some drivers may send the call
	 * statement to the database when the method <code>prepareCall</code>
	 * is done; others
	 * may wait until the CallableStatement is executed. This has no
	 * direct effect on users; however, it does affect which method
	 * throws certain SQLExceptions.
	 *
	 * JDBC 2.0
	 *
	 * Result sets created using the returned CallableStatement will have
	 * forward-only type and read-only concurrency, by default.
	 *
	 * @param sql a SQL statement that may contain one or more '?'
	 * parameter placeholders. Typically this  statement is a JDBC
	 * function call escape string.
	 * @return a new CallableStatement object containing the
	 * pre-compiled SQL statement 
	 * @exception SQLException if a database access error occurs
	 */
	public CallableStatement prepareCall(String sql) throws SQLException {
		
		return (CallableStatement) getKeepalive(con.prepareCall(sql));
	}
	/**
	 * JDBC 2.0
	 *
	 * Creates a <code>CallableStatement</code> object that will generate
	 * <code>ResultSet</code> objects with the given type and concurrency.
	 * This method is the same as the <code>prepareCall</code> method
	 * above, but it allows the default result set
	 * type and result set concurrency type to be overridden.
	 *
	 * @param resultSetType a result set type; see ResultSet.TYPE_XXX
	 * @param resultSetConcurrency a concurrency type; see ResultSet.CONCUR_XXX
	 * @return a new CallableStatement object containing the
	 * pre-compiled SQL statement 
	 * @exception SQLException if a database access error occurs
	 */
	public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
		return (CallableStatement) getKeepalive(con.prepareCall(sql, resultSetType,  resultSetConcurrency));
	}
	
	/**
	 * Creates a <code>PreparedStatement</code> object for sending
	 * parameterized SQL statements to the database.
	 * 
	 * A SQL statement with or without IN parameters can be
	 * pre-compiled and stored in a PreparedStatement object. This
	 * object can then be used to efficiently execute this statement
	 * multiple times.
	 *
	 * <P><B>Note:</B> This method is optimized for handling
	 * parametric SQL statements that benefit from precompilation. If
	 * the driver supports precompilation,
	 * the method <code>prepareStatement</code> will send
	 * the statement to the database for precompilation. Some drivers
	 * may not support precompilation. In this case, the statement may
	 * not be sent to the database until the <code>PreparedStatement</code> is
	 * executed.  This has no direct effect on users; however, it does
	 * affect which method throws certain SQLExceptions.
	 *
	 * JDBC 2.0
	 *
	 * Result sets created using the returned PreparedStatement will have
	 * forward-only type and read-only concurrency, by default.
	 *
	 * @param sql a SQL statement that may contain one or more '?' IN
	 * parameter placeholders
	 * @return a new PreparedStatement object containing the
	 * pre-compiled statement 
	 * @exception SQLException if a database access error occurs
	 */
	public PreparedStatement prepareStatement(String sql) throws SQLException {
		Object ret = getKeepalive(con.prepareStatement(sql));
		return (PreparedStatement) ret;
	}
	/**
	 * JDBC 2.0
	 *
	 * Creates a <code>PreparedStatement</code> object that will generate
	 * <code>ResultSet</code> objects with the given type and concurrency.
	 * This method is the same as the <code>prepareStatement</code> method
	 * above, but it allows the default result set
	 * type and result set concurrency type to be overridden.
	 *
	 * @param resultSetType a result set type; see ResultSet.TYPE_XXX
	 * @param resultSetConcurrency a concurrency type; see ResultSet.CONCUR_XXX
	 * @return a new PreparedStatement object containing the
	 * pre-compiled SQL statement 
	 * @exception SQLException if a database access error occurs
	 */
	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
		
		return (PreparedStatement) getKeepalive(con.prepareStatement(sql,resultSetType,resultSetConcurrency));
	}
	
	/**
	 * Drops all changes made since the previous
	 * commit/rollback and releases any database locks currently held
	 * by this Connection. This method should be used only when auto-
	 * commit has been disabled.
	 *
	 * @exception SQLException if a database access error occurs
	 * @see #setAutoCommit 
	 */
	public void rollback() throws SQLException 
	{
		touch();
		con.rollback();
	}
	/**
	 * Sets this connection's auto-commit mode.
	 * If a connection is in auto-commit mode, then all its SQL
	 * statements will be executed and committed as individual
	 * transactions.  Otherwise, its SQL statements are grouped into
	 * transactions that are terminated by a call to either
	 * the method <code>commit</code> or the method <code>rollback</code>.
	 * By default, new connections are in auto-commit
	 * mode.
	 *
	 * The commit occurs when the statement completes or the next
	 * execute occurs, whichever comes first. In the case of
	 * statements returning a ResultSet, the statement completes when
	 * the last row of the ResultSet has been retrieved or the
	 * ResultSet has been closed. In advanced cases, a single
	 * statement may return multiple results as well as output
	 * parameter values. In these cases the commit occurs when all results and
	 * output parameter values have been retrieved.
	 *
	 * @param autoCommit true enables auto-commit; false disables
	 * auto-commit.  
	 * @exception SQLException if a database access error occurs
	 */
	public void setAutoCommit(boolean autoCommit) throws SQLException 
	{
		touch();
		con.setAutoCommit(autoCommit);
	}
	/**
	 * Sets a catalog name in order to select 	
	 * a subspace of this Connection's database in which to work.
	 * If the driver does not support catalogs, it will
	 * silently ignore this request.
	 *
	 * @exception SQLException if a database access error occurs
	 */
	public void setCatalog(String catalog) throws SQLException 
	{
		touch();
		con.setCatalog(catalog)		;
	}
	/**
	 * Puts this connection in read-only mode as a hint to enable 
	 * database optimizations.
	 *
	 * <P><B>Note:</B> This method cannot be called while in the
	 * middle of a transaction.
	 *
	 * @param readOnly true enables read-only mode; false disables
	 * read-only mode.  
	 * @exception SQLException if a database access error occurs
	 */
	public void setReadOnly(boolean readOnly) throws SQLException 
	{
		touch();
		con.setReadOnly(readOnly)	;
	}
	/**
	 * Attempts to change the transaction
	 * isolation level to the one given.
	 * The constants defined in the interface <code>Connection</code>
	 * are the possible transaction isolation levels.
	 *
	 * <P><B>Note:</B> This method cannot be called while
	 * in the middle of a transaction.
	 *
	 * @param level one of the TRANSACTION_* isolation values with the
	 * exception of TRANSACTION_NONE; some databases may not support
	 * other values
	 * @exception SQLException if a database access error occurs
	 * @see DatabaseMetaData#supportsTransactionIsolationLevel 
	 */
	public void setTransactionIsolation(int level) throws SQLException 
	{
		touch();
		con.setTransactionIsolation(level);
	}
	/**
	 * JDBC 2.0
	 *
	 * Installs the given type map as the type map for
	 * this connection.  The type map will be used for the
	 * custom mapping of SQL structured types and distinct types.
	 *
	 * @param the <code>java.util.Map</code> object to install
	 *        as the replacement for this <code>Connection</code>
	 *        object's default type map
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public void setTypeMapREmove(java.util.Map map) throws SQLException 
	{
		touch();
		con.setTypeMap(map)	;
	}
	/* (non-Javadoc)
	 * @see java.sql.Connection#setHoldability(int)
	 */
	public void setHoldability(int arg0) throws SQLException {
		con.setHoldability(arg0);

	}
	/* (non-Javadoc)
	 * @see java.sql.Connection#getHoldability()
	 */
	public int getHoldability() throws SQLException {

		return con.getHoldability();
	}
	/* (non-Javadoc)
	 * @see java.sql.Connection#createStatement(int, int, int)
	 */
	public Statement createStatement(int arg0, int arg1, int arg2) throws SQLException {

		return con.createStatement(arg0, arg1, arg2);
	}

	/* (non-Javadoc)
	 * @see java.sql.Connection#prepareStatement(java.lang.String, int, int, int)
	 */
	public PreparedStatement prepareStatement(String arg0, int arg1, int arg2, int arg3) throws SQLException {

		return con.prepareStatement(arg0,arg1,arg2,arg3);
	}
	/* (non-Javadoc)
	 * @see java.sql.Connection#prepareCall(java.lang.String, int, int, int)
	 */
	public CallableStatement prepareCall(String arg0, int arg1, int arg2, int arg3) throws SQLException {

		return con.prepareCall( arg0,  arg1,  arg2,  arg3);
	}

	/* (non-Javadoc)
	 * @see java.sql.Connection#prepareStatement(java.lang.String, int)
	 */
	public PreparedStatement prepareStatement(String arg0, int arg1) throws SQLException {

		return con.prepareStatement( arg0,  arg1);
	}
	/* (non-Javadoc)
	 * @see java.sql.Connection#prepareStatement(java.lang.String, int[])
	 */
	public PreparedStatement prepareStatement(String arg0, int[] arg1) throws SQLException {

		return con.prepareStatement( arg0,  arg1);
	}
	/* (non-Javadoc)
	 * @see java.sql.Connection#prepareStatement(java.lang.String, java.lang.String[])
	 */
	public PreparedStatement prepareStatement(String arg0, String[] arg1) throws SQLException {

		return con.prepareStatement( arg0, arg1);
	}

	/* (non-Javadoc)
	 * @see java.sql.Connection#rollback(java.sql.Savepoint)
	 */
	public void rollback(Savepoint arg0) throws SQLException {
		con.rollback(arg0);
	}

	/* (non-Javadoc)
	 * @see java.sql.Connection#releaseSavepoint(java.sql.Savepoint)
	 */
	public void releaseSavepoint(Savepoint arg0) throws SQLException {
		con.releaseSavepoint(arg0);	
	}

	/* (non-Javadoc)
	 * @see java.sql.Connection#setSavepoint()
	 */
	public Savepoint setSavepoint() throws SQLException {

		return con.setSavepoint();
	}

	/* (non-Javadoc)
	 * @see java.sql.Connection#setSavepoint(java.lang.String)
	 */
	public Savepoint setSavepoint(String arg0) throws SQLException {

		return con.setSavepoint(arg0);
	}

	public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
		con.setTypeMap(map);		
	}
	public boolean isWrapperFor(Class<?> arg0) throws SQLException {
		touch();
		return con.isWrapperFor(arg0);
	}
	public <T> T unwrap(Class<T> arg0) throws SQLException {
		touch();
		return con.unwrap(arg0);
	}
	public Array createArrayOf(String arg0, Object[] arg1) throws SQLException {
		touch();
		return con.createArrayOf(arg0, arg1);
	}
	
	public Blob createBlob() throws SQLException {
		touch();
		return con.createBlob();
	}
	public Clob createClob() throws SQLException {
		touch();
		return con.createClob();
	}
	public NClob createNClob() throws SQLException {
		touch();
		return con.createNClob();
	}
	public SQLXML createSQLXML() throws SQLException {
		touch();
		return con.createSQLXML();
	}
	public Struct createStruct(String arg0, Object[] arg1) throws SQLException {
		touch();
		return con.createStruct(arg0, arg1);
	}
	public Properties getClientInfo() throws SQLException {
		touch();
		return con.getClientInfo();
	}
	public String getClientInfo(String arg0) throws SQLException {
		touch();
		return con.getClientInfo(arg0);
	}
	public boolean isValid(int arg0) throws SQLException {
		touch();
		return con.isValid(arg0);
	}
	public void setClientInfo(Properties arg0) throws SQLClientInfoException {
		touch();
		con.setClientInfo(arg0);
	}
	public void setClientInfo(String arg0, String arg1)
			throws SQLClientInfoException {
		touch();
		con.setClientInfo(arg0, arg1);
	}
	
	public void setSchema(String schema) throws SQLException {
		con.setSchema(schema);
	}
	
	public String getSchema() throws SQLException {
		return con.getSchema();
	}
	
	public void abort(Executor executor) throws SQLException {
		con.abort(executor);
	}
	
	public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
		con.setNetworkTimeout(executor, milliseconds);
	}
	
	public int getNetworkTimeout() throws SQLException {
		return con.getNetworkTimeout();
	}	

}
