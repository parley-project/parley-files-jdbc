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
package us.bringardner.parley.files.jdbcfile;


import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.files.jdbcfile.pool.JdbcConnectionPool;
import us.bringardner.parley.files.jdbcfile.pool.ObjectPool;
import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceUser;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
	/** The group the database user belongs to for file permissions; empty means none. */
	public static final String JDBC_GROUP = "jdbcGroup";
	/** The schema's default GROUP_NAME for new files. */
	public static final String DEFAULT_GROUP = "staff";
	/** "false" leaves an older schema alone; otherwise narrow owner/group columns are widened on connect. */
	public static final String JDBC_UPGRADE_SCHEMA = "jdbcUpgradeSchema";
	/** Width of the owner and group_name columns (the SQL standard identifier length). */
	public static final int NAME_COLUMN_SIZE = 128;
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
		_connectProperties.setProperty(JDBC_GROUP, DEFAULT_GROUP);
		_connectProperties.setProperty(JDBC_UPGRADE_SCHEMA, "true");
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

	/**
	 * The current user as this file system sees it: the database user (jdbcUserid),
	 * which is also the owner recorded for every file this factory creates, with
	 * jdbcGroup (default "staff", the schema's default group) as its only group.
	 * <p>
	 * The inherited version returned the operating system user, which never matches a
	 * file's owner, so owner permissions never applied and whether a file could be
	 * written depended on the OS user happening to be in a group called staff.
	 */
	@Override
	public FileSourceUser whoAmI() {
		String id = getUserId();
		String group = getConnectProperties().getProperty(JDBC_GROUP, "");
		if( group.isEmpty() ) {
			FileSourceUser ret = new FileSourceUser();
			ret.setId(-1);
			ret.setName(id == null ? "" : id);
			return ret;
		}
		// -1: not a numeric id (and never mistaken for root)
		return new FileSourceUser(-1, id == null ? "" : id, -1, group);
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
	 * @see us.bringardner.parley.files.FileSourceFactory#createFileSource(java.lang.String)
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
	 * @see us.bringardner.parley.files.FileSourceFactory#setCurrentDirectory(us.bringardner.parley.files.FileSource)
	 */
	@Override
	public void setCurrentDirectory(FileSource dir) throws IOException {
		JdbcFileSource tmp = null;


		tmp = (JdbcFileSource)dir;

		curentDir = tmp;

	}

	/* (non-Javadoc)
	 * @see us.bringardner.parley.files.FileSourceFactory#getTypeId()
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
	 * @see us.bringardner.parley.files.FileSourceFactory#listRoots()
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
	 * @see us.bringardner.parley.files.FileSourceFactory#isVersionSupported()
	 */
	@Override
	public boolean isVersionSupported() {
		return versionSupported;
	}

	public void setVersionSupported(boolean val) {
		versionSupported=val;
	}
	

	/**
	 * Widens file_source.file.owner and group_name to VARCHAR(NAME_COLUMN_SIZE) when
	 * the schema predates BJL-22 (VARCHAR(10)), so user names longer than 10
	 * characters can be stored. Runs on connect unless jdbcUpgradeSchema is "false".
	 * Never fails the connection: problems (e.g. no ALTER rights, an unknown database)
	 * are logged, and the README has the statements to run by hand.
	 *
	 * @return the columns that were widened
	 */
	public List<String> widenNameColumns(Connection con) {
		List<String> ret = new ArrayList<>();
		try {
			DatabaseMetaData md = con.getMetaData();
			String product = md.getDatabaseProductName();
			for(String column : new String[] {"owner", "group_name"}) {
				int size = columnSize(md, column);
				if( size > 0 && size < NAME_COLUMN_SIZE ) {
					try(Statement st = con.createStatement()) {
						st.executeUpdate(alterSql(product, column));
					}
					if( !con.getAutoCommit()) {
						con.commit();
					}
					ret.add(column);
					logInfo("Widened file_source.file."+column+" from VARCHAR("+size+") to VARCHAR("+NAME_COLUMN_SIZE+")");
				}
			}
		} catch (SQLException | RuntimeException e) {
			logWarn("Can't widen the owner/group columns of file_source.file (see README, Upgrading an existing database): "+e);
		}
		return ret;
	}

	/** The column's declared size, or -1 if it isn't found. */
	private static int columnSize(DatabaseMetaData md, String column) throws SQLException {
		String esc = md.getSearchStringEscape();
		java.util.function.UnaryOperator<String> pattern = n -> esc == null || esc.isEmpty() ? n : n.replace("_", esc+"_");
		// identifier case and "schema" differ: HSQLDB stores upper case, PostgreSQL lower,
		// and MySQL treats file_source as a database (catalog), not a schema
		String[][] tries = {
				{null, "FILE_SOURCE", "FILE", column.toUpperCase(Locale.ROOT)},
				{null, "file_source", "file", column},
				{"file_source", null, "file", column}};
		for(String[] t : tries) {
			try(ResultSet rs = md.getColumns(t[0], t[1] == null ? null : pattern.apply(t[1]), t[2], pattern.apply(t[3]))) {
				if( rs.next()) {
					return rs.getInt("COLUMN_SIZE");
				}
			}
		}
		return -1;
	}

	/**
	 * The same database: the same JDBC URL. Every user of a database shares its
	 * file_source tables, so paths from such factories name the same files.
	 */
	@Override
	public boolean isSameFileSystem(FileSourceFactory other) {
		if( other == this ) {
			return true;
		}
		if( !(other instanceof JdbcFileSourceFactory)) {
			return false;
		}
		String mine = getConnectProperties().getProperty(JDBC_URL, "").trim();
		String theirs = other.getConnectProperties().getProperty(JDBC_URL, "").trim();
		return !mine.isEmpty() && mine.equals(theirs);
	}

	/**
	 * The statement that widens a name column for the given database product
	 * (DatabaseMetaData.getDatabaseProductName()); SQL standard syntax unless it's
	 * MySQL/MariaDB or PostgreSQL.
	 */
	public static String alterSql(String product, String column) {
		String p = product == null ? "" : product.toLowerCase(Locale.ROOT);
		String type = "VARCHAR("+NAME_COLUMN_SIZE+")";
		if( p.contains("mysql") || p.contains("mariadb")) {
			// MODIFY restates the whole column
			String rest = column.equals("owner") ? " NOT NULL" : " DEFAULT '"+DEFAULT_GROUP+"'";
			return "ALTER TABLE file_source.file MODIFY "+column+" "+type+rest;
		}
		if( p.contains("postgres")) {
			return "ALTER TABLE file_source.file ALTER COLUMN "+column+" TYPE "+type;
		}
		return "ALTER TABLE file_source.file ALTER COLUMN "+column+" SET DATA TYPE "+type;
	}

	/** The pool's thread starts at once; this only keeps a failure from waiting forever. */
	private static final long POOL_START_WAIT_MS = 30_000;

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
			
			try {
				if( !tmp.awaitStart(POOL_START_WAIT_MS) ) {
					throw new SQLException("The connection pool did not start in "+POOL_START_WAIT_MS+" ms");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new SQLException("Interrupted while the connection pool was starting", e);
			}
			
			if(tmp !=null && tmp.isRunning() ) {
				try(Connection con = tmp.getConnection()) {
					pool = tmp;
					ret = true;
					if( !"false".equalsIgnoreCase(prop.getProperty(JDBC_UPGRADE_SCHEMA, "true").trim())) {
						widenNameColumns(con);
					}
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

	/**
	 * Only the password. (A JDBC URL can also carry credentials, e.g. ?password=...;
	 * put them in jdbcPassword instead, or they will be saved and shown.)
	 */
	@Override
	public boolean isSecretProperty(String name) {
		return JDBC_PASSWORD.equals(name);
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
	public List<ConnectionSetting> getConnectionSettings() {
		return List.of(
				ConnectionSetting.text(JDBC_CONNECTION_NAME, "Name"),
				ConnectionSetting.text(JDBC_DRIVER, "Driver class")
					.withDescription("Empty to let JDBC find the driver for the URL"),
				ConnectionSetting.text(JDBC_URL, "Database URL").asRequired(),
				ConnectionSetting.text(JDBC_USERID, "User"),
				ConnectionSetting.secret(JDBC_PASSWORD, "Password"),
				ConnectionSetting.text(JDBC_GROUP, "Group").withDefault(DEFAULT_GROUP).asAdvanced(),
				ConnectionSetting.bool(JDBC_UPGRADE_SCHEMA, "Upgrade the schema").withDefault("true").asAdvanced());
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
				// not given: the group and the upgrade switch keep their defaults, everything else is empty
				value = JDBC_GROUP.equals(key) || JDBC_UPGRADE_SCHEMA.equals(key) ? _connectProperties.getProperty(key) : "";
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
