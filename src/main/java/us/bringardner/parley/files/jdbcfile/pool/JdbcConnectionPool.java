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
 * ~version~V001.01.47-V000.01.33-V000.01.29-V000.01.26-V000.01.25-V000.00.01-V000.00.00-
 */
package us.bringardner.parley.files.jdbcfile.pool;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


/**
 * A Pool of JDBC Connection objects
 * Creation date: (7/3/02 3:31:35 PM)
 * @author: Tony Bringardner
 */
public class JdbcConnectionPool extends ObjectPool 
{
	//  Looked up for every connection a caller asks for by url, so no lock on the class: closing a
	//  pool (it closes connections, which can hang) must not hold up everyone else's lookup.
	private static volatile Map<String, JdbcConnectionPool> pools = new ConcurrentHashMap<String, JdbcConnectionPool>();

	private String url;
	private String userid;
	private String passwd;


	
	
/**
 * JdbcConnectionPool constructor comment.
 */
public JdbcConnectionPool(String newUrl, String newUserid, String newPasswd) 
{
	url = newUrl;
	userid = newUserid;
	passwd = newPasswd;
}

/**
 * JdbcConnectionPool constructor comment.
 */
public JdbcConnectionPool(String driver, String url, String userid, String passwd)
	throws ClassNotFoundException
{
	this(url,userid, passwd);
	Class.forName(driver);
	
}

/**
 * createObject method comment.
 */
public IManagedObject createObject() throws SQLException {
	
	Connection con = DriverManager.getConnection(url,userid,passwd);
	try {
		return new ManagedJdbcConnection(con);
	} catch(SQLException | RuntimeException ex) {
		//  it was opened, so it has to be closed, or it stays open until it is garbage collected
		try { con.close(); } catch(Exception ignore) {}
		throw ex;
	}
	
}

/**
 * An idle connection is asked if it is still good before it is handed out again.
 */
protected boolean validateObject(Object obj) throws SQLException {
	Connection conn = (Connection)obj;
	return !conn.isClosed() && conn.isValid(2);
}

/**
 * destroyObject method comment.
 */
public void destroyObject(Object obj)
	throws SQLException
{
	Connection conn = (Connection)obj;
	conn.close();
}
/**
 * 
 * Creation date: (7/3/02 3:37:38 PM)
 * @return java.lang.String
 */
public Connection getConnection()
	throws SQLException
{

	ManagedJdbcConnection mcon = null;
	
	try {
		Object obj = getObject();
		
		mcon = (ManagedJdbcConnection)obj;
	} catch(Exception ex) {
		if( ex instanceof InterruptedException ) {
			Thread.currentThread().interrupt();
		}
		throw new SQLException(ex.toString(), ex);
	}

	//  a handle of its own for each checkout (see PooledConnection)
	return new PooledConnection(mcon);
}

static public Connection getConnection(String url, String userid, String passwd)
	throws SQLException
{
	Connection ret = null;
	
	JdbcConnectionPool p=getConnectionPool(url,userid,passwd);
	
	if( p  != null ) {
		ret = p.getConnection();
	}
	if( ret == null ) {
		throw new SQLException("Can't generate connection ");
	}
	
	return ret;	

	
}

static public Connection getConnection(String driver, String url, String userid, String passwd)
	throws SQLException, ClassNotFoundException
{
	Class.forName(driver);	
	return getConnection(url,userid,passwd);	

	
}

public static JdbcConnectionPool getConnectionPool(String url, String userid, String passwd)
	throws SQLException
{
	
	return getConnectionPool(url,userid,passwd,true);

	
}
/**
 * The pool's name, which its thread also takes, so it shows in thread dumps and debuggers:
 * the URL and user only, never the password.
 */
public String getName()
{
	return "jdbc pool "+url+" "+userid;
}
/**
 * 
 * Creation date: (7/3/02 3:37:38 PM)
 * @return java.lang.String
 */
public java.lang.String getPasswd() {
	return passwd;
}
/**
 * Insert the method's description here.
 * Creation date: (7/4/2002 2:58:57 PM)
 * @return java.util.Hashtable
 */
/**
 * Pools are shared by URL, user and password, so a different password never reuses another
 * login. The password is kept in the key only as a SHA-256 digest.
 */
private static String key(String url, String userid, String passwd) {
	String digest = "";
	if( passwd != null ) {
		try {
			byte[] d = java.security.MessageDigest.getInstance("SHA-256")
					.digest(passwd.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			digest = java.util.Base64.getEncoder().encodeToString(d);
		} catch (java.security.NoSuchAlgorithmException e) {
			// every Java platform has SHA-256
			throw new IllegalStateException(e);
		}
	}
	return url+":"+userid+":"+digest;
}

/**
 * Stops and forgets the pool for url, userid and passwd, so the next getConnectionPool makes
 * a new one. A pool whose login failed must not be reused.
 */
public static void removeConnectionPool(String url, String userid, String passwd) {
	JdbcConnectionPool p = pools.remove(key(url, userid, passwd));
	if( p != null ) {
		try {
			//  stop() ends the pool's thread, which destroys everything on its way out
			p.stop();
			p.destroyAll();
		} catch (RuntimeException e) {
			// it's being thrown away anyway
		}
	}
}

public static Map<String, JdbcConnectionPool> getPools() {
	return pools;
}
/**
 * Insert the method's description here.
 * Creation date: (7/3/02 3:37:38 PM)
 * @return java.lang.String
 */
public java.lang.String getUrl() {
	return url;
}
/**
 * Insert the method's description here.
 * Creation date: (7/3/02 3:37:38 PM)
 * @return java.lang.String
 */
public java.lang.String getUserid() {
	return userid;
}
/**
 * Insert the method's description here.
 * Creation date: (7/4/2002 2:58:57 PM)
 * @param newPools java.util.Hashtable
 */
public static void setPools(Map<String, JdbcConnectionPool> newPools) {
	//  looked up from any thread: a map that is not concurrent is wrapped
	pools = (newPools instanceof ConcurrentMap) ? newPools : Collections.synchronizedMap(newPools);
}


public static JdbcConnectionPool getConnectionPool(String driver,String url, String userid, String passwd)
	throws SQLException,ClassNotFoundException
{
	Class.forName(driver);
	
	return getConnectionPool(url,userid,passwd, true);
	
}

public static JdbcConnectionPool getConnectionPool(String driver,String url, String userid, String passwd, boolean create)
	throws SQLException,ClassNotFoundException
{
	Class.forName(driver);
	
	
	return getConnectionPool(url,userid,passwd, create);

	
}

public static JdbcConnectionPool getConnectionPool(String url, String userid, String passwd, boolean create)
	throws SQLException
{
	
	String key = key(url, userid, passwd);

	Map<String, JdbcConnectionPool> map = pools;
	JdbcConnectionPool p = map.get(key);

	if( p == null && create ) {
		//  atomic: two threads asking at once get the same pool, and only one thread is started
		p = map.computeIfAbsent(key, k -> {
			JdbcConnectionPool made = new JdbcConnectionPool(url,userid,passwd);
			made.start();
			return made;
		});
	}
	
	return p;	

	
}
}
