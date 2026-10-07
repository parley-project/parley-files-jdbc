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
import java.util.Hashtable;
import java.util.Map;


/**
 * A Pool of JDBC Connection objects
 * Creation date: (7/3/02 3:31:35 PM)
 * @author: Tony Bringardner
 */
public class JdbcConnectionPool extends ObjectPool 
{
	private static Map<String, JdbcConnectionPool> pools = new Hashtable<String, JdbcConnectionPool>();

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
	ManagedJdbcConnection ret = new ManagedJdbcConnection(con);
	
	return ret;
	
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
		throw new SQLException(ex.toString());
	}

	return mcon;
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

public static synchronized  JdbcConnectionPool getConnectionPool(String url, String userid, String passwd)
	throws SQLException
{
	
	return getConnectionPool(url,userid,passwd,true);

	
}
public String getName()
{
	return url+":"+userid+":"+passwd;
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
	pools = newPools;
}


public static synchronized  JdbcConnectionPool getConnectionPool(String driver,String url, String userid, String passwd)
	throws SQLException,ClassNotFoundException
{
	Class.forName(driver);
	
	return getConnectionPool(url,userid,passwd, true);
	
}

public static synchronized  JdbcConnectionPool getConnectionPool(String driver,String url, String userid, String passwd, boolean create)
	throws SQLException,ClassNotFoundException
{
	Class.forName(driver);
	
	
	return getConnectionPool(url,userid,passwd, create);

	
}

public static synchronized  JdbcConnectionPool getConnectionPool(String url, String userid, String passwd, boolean create)
	throws SQLException
{
	
	String key = url+":"+userid+":"+url;

	JdbcConnectionPool p = null;

	if( (p=(JdbcConnectionPool)pools.get(key)) == null ) {
		if( create ) {
			p = new JdbcConnectionPool(url,userid,passwd);
			pools.put(key,p);
			p.setDeamon(true);
			p.start();
		}
		
	}
	
	return p;	

	
}
}
