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
 * ~version~V001.01.47-V000.01.32-V000.01.25-V000.01.23-V000.01.22-V000.01.21-V000.01.19-V000.01.18-V000.01.16-V000.01.13-V000.01.11-V000.01.06-V000.01.05-V000.01.04-V000.01.03-V000.01.01-V000.01.00-V000.00.01-V000.00.00-
 */
/*
 * Created on Dec 13, 2004
 *
 */
package us.bringardner.io.filesource.jdbcfile;


import java.awt.Component;
import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import us.bringardner.database.pool.JdbcConnectionPool;
import us.bringardner.database.pool.ObjectPool;
import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceFactory;

/**
 * @author Tony Bringardner
 *
 */
public class JdbcFileSourceFactory extends FileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = "Jdbc";

	public static final String JDBC_DRIVER = "jdbcDriver";
	public static final String JDBC_URL = "jdbcURL";
	public static final String JDBC_USERID = "jdbcUserid";
	public static final String JDBC_PASSWORD = "jdbcPassword";
	public static final String JDBC_CONNECTION_NAME = "Name";
	public static final String TYPE_DIR = "dir";
	public static final String TYPE_FILE = "file";
	public static final String TYPE_ROOT = "root";
	public static final char COLON = ':';
	public static final String DOT_SLASH = "./";
	public static final String SLASH_DOT = "/.";
	public static final String DOT = ".";
	public static final String DOT_DOT = "..";
	//  These are lower case to mimic the File object
	public static final char seperatorChar = '/';
	public static final String seperator = "/";
	public static final char dosSeperatorChar = '\\';

	public static final String pathSeperator = ":";
	public static final char pathSSeperatorChar = ':';


	public static final String STATUS_NEW = "New";
	public static final String STATUS_COMPLETE = "Complete";
	public static final String STATUS_OBSOLETE = "Obsolete";
	public static final long INFINITE_VERSIONS = -1;

	public static final String DEFAULT_CURRENT_DIR = "Default Current Directory";


	public static final String DBID_PROP = "JdbcFile.dbid";
	public  static final String KIDS = "kids";



	//  These are only used to communicate what we need, not as properties to connect
	private static Properties _connectProperties;
	public static final String FILE_TYPE = "file_type";
	



	static {
		//  Tell the world what we need to connect
		_connectProperties = new Properties();
		_connectProperties.setProperty(JDBC_CONNECTION_NAME, "JdbcFileSource");
		_connectProperties.setProperty(JDBC_DRIVER, "");
		_connectProperties.setProperty(JDBC_URL, "");
		_connectProperties.setProperty(JDBC_USERID, "");
		_connectProperties.setProperty(JDBC_PASSWORD, "");
		ObjectPool.setDefaultMax(100); 
	}

	public boolean versionSupported = false;

	private JdbcConnectionPool pool;
	private JdbcFileSource curentDir;
	private JdbcFileSource [] roots;	
	private Properties instanceProperies = new Properties();
	private int fieldTimeToLive = 500;
	private Map<String,Integer> timeToLiveMap = new HashMap<>();
	private int chunk_size = 1024*100;

	
	/**
	 * 
	 */
	public JdbcFileSourceFactory() {
		super();
		setConnectionProperties(_connectProperties);	
		setFieldTimeToLive(KIDS, 1000);
		setFieldTimeToLive(FILE_TYPE, 100000);		
	}

	
	public int getChunk_size() {
		return chunk_size;
	}


	public void setChunk_size(int chunk_size) {
		this.chunk_size = chunk_size;
	}


	public String getUserId() {
		return getConnectProperties().getProperty(JDBC_USERID);
	}

	public int getFieldTimeToLive() {
		return fieldTimeToLive;
	}

	public int getFieldTimeToLive(String name) {
		Integer ret = timeToLiveMap.get(name);
		if( ret == null ) {
			ret = fieldTimeToLive;
		}

		return ret;
	}

	public void setFieldTimeToLive(int fieldTimeToLive) {
		this.fieldTimeToLive = fieldTimeToLive;
	}


	public void setFieldTimeToLive(String name,int ttl) {
		timeToLiveMap.put(name, ttl);
	}



	/**
	 * @return Returns the maxVersion.
	 */
	public  long getMaxAllowedVersion() {
		return 1;
	}
	/**
	 * @param maxVersion The maxVersion to set.
	 */
	public void setMaxAllowedVersion(long maxAllowedVersion) {

	}
	/* Create a JdbcFile Object
	 * @see us.bringardner.io.FileSourceFactory#createFileSource(java.lang.String)
	 */
	public FileSource createFileSource(String name) throws IOException {

		if( !isConnected()) {
			if( !connect()) {
				throw new IOException(FACTORY_ID+" Can't conneted");
			} 
		}

		try {
			FileSource ret = null;
			if( name.startsWith("/")) {
				ret = new JdbcFileSource(this,name);
			} else {
				JdbcFileSource dir = getCurrentDirectory();
				if( dir != null ) {
					if( name.equals(".")) {
						ret = dir;
					} else {
						ret = dir.getChild(name);
					}
				} else {
					ret = new JdbcFileSource( this,name);
				}
			}

			return ret;
		} catch (Exception e) {
			e.printStackTrace();
			throw new IOException(FACTORY_ID+" error creating "+name,e);
		}

	}


	/* Set the current directory for the SimpleJdbcFile
	 * @see us.bringardner.io.FileSourceFactory#setCurrentDirectory(us.bringardner.io.FileSource)
	 */
	@Override
	public void setCurrentDirectory(FileSource dir) throws IOException {
		JdbcFileSource tmp = null;


		tmp = (JdbcFileSource)dir;

		curentDir = tmp;

	}

	/* (non-Javadoc)
	 * @see us.bringardner.io.FileSourceFactory#getTypeId()
	 */
	public String getTypeId() {
		return FACTORY_ID;
	}

	
	@Override
	public  JdbcFileSource getCurrentDirectory() throws IOException {
		if(!isConnected()) {
			if( !connect()) {
				throw new IOException("Can't connect factory");
			}
		}
		if( curentDir == null  ){			
			curentDir = (JdbcFileSource) listRoots()[0];
		}
		return curentDir;
	}

	


	/* (non-Javadoc)
	 * @see us.bringardner.io.filesource.FileSourceFactory#listRoots()
	 */
	@Override
	public FileSource[] listRoots() throws IOException {
		if(roots == null ) {
			roots = new JdbcFileSource[1];
			roots[0] = new JdbcFileSource(this, "/");
			if( !roots[0].exists()) {
				if( !roots[0 ].mkdir()) {
					throw new IOException("Cannot create root file.");
				}
			}
		}
		return roots;
	}


	/* (non-Javadoc)
	 * @see us.bringardner.io.filesource.FileSourceFactory#isVersionSupported()
	 */
	@Override
	public boolean isVersionSupported() {
		return versionSupported;
	}

	public void setVersionSupported(boolean val) {
		versionSupported=val;
	}
	

	@Override
	protected synchronized boolean connectImpl() {
		boolean ret = false;
		if( isConnected() ) {
			return true;
		}

		Properties prop = getConnectProperties();
		JdbcConnectionPool tmp;
		try {
			tmp = JdbcConnectionPool.getConnectionPool(
					prop.getProperty(JDBC_URL),
					prop.getProperty(JDBC_USERID),
					prop.getProperty(JDBC_PASSWORD)
					);
			
			if( tmp == null ) {
				//should never happen
				throw new RuntimeException("Can't get a connection pool");
			}
			
			while(!tmp.hasStarted()) {
				try {
					Thread.sleep(1);
				} catch (InterruptedException e) {
				}
			}
			
			if(tmp !=null && tmp.isRunning() ) {
				try(Connection con = tmp.getConnection()) {
					pool = tmp;
					ret = true;
				}
			}
		} catch (SQLException e) {
			logError("Can't open pool", e);
		}

		
		return ret;
	}


	@Override
	protected void disConnectImpl() {
		if( pool != null ) {
			try {
				pool.destroyAll();
			} catch (Exception e) {
			}
			pool = null;
		}
	}

	@Override
	public Properties getConnectProperties() {
		Properties ret = new Properties();
		ret.putAll(instanceProperies);
		return ret;
	}

	public Connection getConnection() throws IOException {
		try {
			return pool.getConnection();
		} catch (SQLException e) {		
			throw new IOException(e);
		}
	}

	@Override
	public boolean isConnected() {
		boolean ret = pool!= null && pool.isRunning();
		return ret;
	}

	@Override
	public Component getEditPropertiesComponent() {
		return null;
	}



	@Override
	public void setConnectionProperties(URL url) {


		Properties p = new Properties();

		String qs = url.getQuery();

		for(String s : qs.split("[&]")) {
			String vals [] = s.split("[=]");
			if( vals.length == 2) {
				p.setProperty(vals[0], vals[1]);
			}
		}
		String name = p.getProperty(JDBC_CONNECTION_NAME);

		if( name != null && !name.isEmpty()) {
			for(Object key : p.keySet()) {
				String pname = key.toString();
				String val = p.getProperty(pname);
				String newName = name+"."+pname;
				p.setProperty(newName, val);
			}			
		}

		instanceProperies = p;
	}

	@Override
	public void setConnectionProperties(Properties prop) {

		for (Object name : _connectProperties.keySet() )   {
			String key = (String)name;
			String value = prop.getProperty( key );
			if( value == null ) {
				value = "";
			}
			instanceProperies.setProperty(key, value);
		}		
	}

	@Override
	public synchronized FileSourceFactory  createThreadSafeCopy() {
		//  the underlying database is thread safe
		return this;
	}

	@Override
	public String getTitle() {
		return "JDBC FileSource";
	}

	@Override
	public String getURL() {
		//TODO:  fix url
		return "filesource:";
	}

	@Override
	public char getPathSeperatorChar() {
		return ':';
	}

	@Override
	public char getSeperatorChar() {
		return '/';
	}


	@Override
	public FileSource createSymbolicLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new UnsupportedOperationException();
	}


	@Override
	public FileSource createLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		throw new UnsupportedOperationException();
	}




}
