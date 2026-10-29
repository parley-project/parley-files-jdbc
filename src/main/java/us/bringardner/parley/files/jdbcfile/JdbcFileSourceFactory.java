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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import us.bringardner.parley.files.jdbcfile.pool.ConnectionPool;
import us.bringardner.parley.files.jdbcfile.pool.ConnectionPools;
import us.bringardner.parley.files.jdbcfile.pool.ObjectPool;
import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceUser;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FileSource factory for a file system kept in a database (tables {@code file_source.file} (the inodes), {@code dirent} (the names) and {@code file_data}), through JDBC.
 * <p>
 * <h2>Locks, atomic create and FileChannel</h2>
 * <ul>
 * <li><b>Locks:</b> {@code CLUSTER} scope on PostgreSQL 10+, MySQL 8+ and MariaDB 10.3+ with the lock tables ({@code file_source.file_lock}, {@code file_lock_range}, created on connect): every process on every machine that uses the database. Each region held is a row kept row-locked by its holder's own connection (one database connection per lock held); a dead holder's regions are free again. Range locks work. On other databases (HSQLDB) {@code PROCESS} scope: this JVM only.</li>
 * <li><b>Atomic create:</b> {@code createNewFile()} and {@code mkdir()} are atomic: the primary key of {@code file_source.dirent} (parent_id, name) lets one of several racing inserts in, and the others are told false.</li>
 * <li><b>FileChannel:</b> reads and writes through the file's streams and random access.</li>
 * <li><b>map():</b> {@code READ_ONLY} and {@code PRIVATE} map a local copy of the region, read when it is mapped; {@code READ_WRITE} throws {@code UnsupportedOperationException} (a copy can't write back).</li>
 * <li><b>force():</b> writes the chunk a stream still holds; every write is committed, so that is as durable as the database makes it.</li>
 * </ul>
 * See parley-files' README ("Locking") for the rules every file system keeps.
 *
 * @author Tony Bringardner
 */
public class JdbcFileSourceFactory extends FileSourceFactory {

	private static final long serialVersionUID = 1L;

	public static final String FACTORY_ID = "Jdbc";

	public static final String JDBC_DRIVER = "jdbcDriver";
	public static final String JDBC_URL = "jdbcUrl";
	public static final String JDBC_USERID = "jdbcUser";
	public static final String JDBC_PASSWORD = "jdbcPassword";
	public static final String JDBC_CONNECTION_NAME = "name";
	/** The group the database user belongs to for file permissions; empty means none. */
	public static final String JDBC_GROUP = "jdbcGroup";
	/** The schema's default GROUP_NAME for new files. */
	public static final String DEFAULT_GROUP = "staff";
	/**
	 * "false" leaves an older schema alone; otherwise, on connect, narrow owner/group columns are
	 * widened and the lock tables (file_source.file_lock, file_lock_range) are created where locks are kept in the database.
	 */
	public static final String JDBC_UPGRADE_SCHEMA = "jdbcUpgradeSchema";
	/**
	 * "false" is like a file system mounted noatime: opening a file for reading does not write its access time
	 * (each is a statement and a commit, for a time nothing may ever read). Writes still set it, and so does
	 * setLastAccessTime. Anything else, and the default, writes it when a file is opened for reading.
	 */
	public static final String JDBC_ACCESS_TIME = "jdbcAccessTime";
	/** Which connection pool: parley (the default), hikari or dbcp2. See ConnectionPools. */
	public static final String JDBC_POOL = "jdbcPool";
	/**
	 * Bytes in one chunk: what a write buffers before it is stored as one row of
	 * file_data, and what random access reads and writes at a time. See
	 * {@link #setBufferSize(int)}.
	 * <p>
	 * This is for programmers: it isn't in {@link #getConnectionSettings()}, so no dialog
	 * shows it, and {@link #getConnectProperties()} only has it when it was set, so saved
	 * connections keep following the default. The JVM-wide default is the system property
	 * {@value #SYSTEM_PROPERTY_BUFFER_SIZE}.
	 */
	public static final String PROP_BUFFER_SIZE = "bufferSize";
	public static final String SYSTEM_PROPERTY_BUFFER_SIZE = "parley.files.jdbc.bufferSize";
	public static final int DEFAULT_BUFFER_SIZE = 1024*100;
	public static final int MIN_BUFFER_SIZE = 1024;
	public static final int MAX_BUFFER_SIZE = 16*1024*1024;
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
	public static final char separatorChar = '/';
	public static final String separator = "/";
	public static final char dosSeparatorChar = '\\';

	public static final String pathSeparator = ":";
	public static final char pathSeparatorChar = ':';


	public static final String STATUS_NEW = "New";
	public static final String STATUS_COMPLETE = "Complete";
	public static final String STATUS_OBSOLETE = "Obsolete";
	public static final long INFINITE_VERSIONS = -1;

	public static final String DEFAULT_CURRENT_DIR = "Default Current Directory";


	public static final String DBID_PROP = "parley.files.jdbc.dbid";
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
		_connectProperties.setProperty(JDBC_ACCESS_TIME, "true");
		_connectProperties.setProperty(JDBC_POOL, ConnectionPools.PARLEY);
		ObjectPool.setDefaultMax(100); 
	}

	public boolean versionSupported = false;

	private ConnectionPool pool;
	/** How this factory logged in, for the connections of its own that locks open (the pool may be any kind). */
	private volatile String loginUrl, loginUser, loginPassword;
	/** How this database keeps file locks; null if it can't (the locks then reach only this JVM). */
	private volatile JdbcLocks.Dialect lockDialect;
	private JdbcFileSource curentDir;
	private JdbcFileSource [] roots;	
	private Properties instanceProperies = new Properties();
	private int fieldTimeToLive = 500;
	private Map<String,Integer> timeToLiveMap = new HashMap<>();
	private int chunk_size = defaultBufferSize();
	/** True once the size was set (setChunk_size, setBufferSize or PROP_BUFFER_SIZE), not just defaulted. */
	private volatile boolean chunkSizeExplicit;

	
	/**
	 * 
	 */
	public JdbcFileSourceFactory() {
		super();
		setConnectionProperties(_connectProperties);	
		// What a handle remembers (its row id, attributes, listing) is kept for fieldTimeToLive, 500 ms
		// unless set. A change made through this factory is seen at once; one made by another
		// connection is seen after that time. (A longer time for FILE_TYPE used to be set here; it
		// never took effect, and it would have kept a deleted file's type for 100 seconds.)
		setFieldTimeToLive(KIDS, 1000);
	}

	
	public int getChunk_size() {
		return chunk_size;
	}


	public void setChunk_size(int chunk_size) {
		this.chunk_size = chunk_size;
		this.chunkSizeExplicit = true;
	}

	/**
	 * The default for new factories: the system property {@value #SYSTEM_PROPERTY_BUFFER_SIZE}
	 * if it is a number (kept within MIN_BUFFER_SIZE and MAX_BUFFER_SIZE), otherwise
	 * DEFAULT_BUFFER_SIZE.
	 */
	public static int defaultBufferSize() {
		String v = System.getProperty(SYSTEM_PROPERTY_BUFFER_SIZE);
		if( v != null && !v.trim().isEmpty()) {
			try {
				return clampBufferSize(Long.parseLong(v.trim()));
			} catch (NumberFormatException e) {
				// a bad value uses the default
			}
		}
		return DEFAULT_BUFFER_SIZE;
	}

	/** @return size kept within {@link #MIN_BUFFER_SIZE} and {@link #MAX_BUFFER_SIZE} */
	static int clampBufferSize(long size) {
		return (int) Math.max(MIN_BUFFER_SIZE, Math.min(MAX_BUFFER_SIZE, size));
	}

	/**
	 * The chunk size, which is also the size of the buffer a write fills before storing a
	 * row, so a copy loop should read and write about this much at a time. Data already
	 * stored keeps the chunks it was written in (each row records its length). It is the
	 * same value as {@link #getChunk_size()}.
	 */
	public int getBufferSize() {
		return chunk_size;
	}

	/**
	 * @param size bytes, kept within {@link #MIN_BUFFER_SIZE} and {@link #MAX_BUFFER_SIZE}
	 * (setChunk_size doesn't limit it); streams read it when they are opened
	 */
	public void setBufferSize(int size) {
		setChunk_size(clampBufferSize(size));
	}

	/** Takes PROP_BUFFER_SIZE out of values; sets the size from it unless it is empty or not a number. */
	private void applyBufferSize(Properties values) {
		String v = (String) values.remove(PROP_BUFFER_SIZE);
		if( v != null && !v.trim().isEmpty()) {
			// No dialog shows this, so a bad value can't be fixed there and must not stop
			// the connection: say so and keep the size as it is.
			try {
				setBufferSize(clampBufferSize(Long.parseLong(v.trim())));
			} catch (NumberFormatException e) {
				logError("Ignoring "+PROP_BUFFER_SIZE+" '"+v+"': not a number");
			}
		}
	}


	public String getUserId() {
		return getConnectProperties().getProperty(JDBC_USERID);
	}

	/**
	 * The current user as this file system sees it: the database user (jdbcUser),
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

	/**
	 * Makes file_source.file_data's primary key (fileid, chunk_number) when the table was made with
	 * (chunk_number, fileid): every statement on the table filters on fileid, and with the old order
	 * each one scans the whole table. Runs on connect unless jdbcUpgradeSchema is "false". Never fails
	 * the connection: problems (e.g. no ALTER rights, an unknown database) are logged, and the README
	 * has the statement to run by hand. Rebuilding the key reads the whole table, so a large store
	 * waits (and the table is locked) for that long, once.
	 *
	 * @return true if the key was changed
	 */
	public boolean reorderDataKey(Connection con) {
		try {
			DatabaseMetaData md = con.getMetaData();
			List<String> key = dataKey(md);
			if( key.size() != 2 || !key.get(0).equalsIgnoreCase("chunk_number") || !key.get(1).equalsIgnoreCase("fileid")) {
				return false;
			}
			logInfo("Changing file_source.file_data's primary key from (chunk_number, fileid) to (fileid, chunk_number); a large store takes a while");
			try(Statement st = con.createStatement()) {
				for(String sql : reorderKeySql(md.getDatabaseProductName(), dataKeyName(md))) {
					st.executeUpdate(sql);
				}
			}
			if( !con.getAutoCommit()) {
				con.commit();
			}
			logInfo("Changed file_source.file_data's primary key to (fileid, chunk_number)");
			return true;
		} catch (SQLException | RuntimeException e) {
			logWarn("Can't change the primary key of file_source.file_data (see README, Upgrading an existing database): "+e);
			return false;
		}
	}

	/** The columns of file_data's primary key, in key order; empty if there is none or the table isn't found. */
	private static List<String> dataKey(DatabaseMetaData md) throws SQLException {
		return dataKeyColumns(md, false);
	}

	/** The name of file_data's primary key constraint, or null. */
	private static String dataKeyName(DatabaseMetaData md) throws SQLException {
		List<String> ret = dataKeyColumns(md, true);
		return ret.isEmpty() ? null : ret.get(0);
	}

	private static List<String> dataKeyColumns(DatabaseMetaData md, boolean name) throws SQLException {
		// identifier case and "schema" differ, as for columnSize()
		String[][] tries = {
				{null, "FILE_SOURCE", "FILE_DATA"},
				{null, "file_source", "file_data"},
				{"file_source", null, "file_data"}};
		for(String[] t : tries) {
			java.util.TreeMap<Short, String> columns = new java.util.TreeMap<>();
			String keyName = null;
			try(ResultSet rs = md.getPrimaryKeys(t[0], t[1], t[2])) {
				while( rs.next()) {
					columns.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
					keyName = rs.getString("PK_NAME");
				}
			}
			if( !columns.isEmpty()) {
				return name ? Arrays.asList(keyName) : new ArrayList<>(columns.values());
			}
		}
		return new ArrayList<>();
	}

	/**
	 * The statements that make file_data's primary key (fileid, chunk_number) for the given database
	 * product; SQL standard syntax unless it's MySQL/MariaDB or PostgreSQL.
	 * @param keyName the constraint's name (used for PostgreSQL), or null
	 */
	public static List<String> reorderKeySql(String product, String keyName) {
		String p = product == null ? "" : product.toLowerCase(Locale.ROOT);
		String add = "ADD PRIMARY KEY (fileid, chunk_number)";
		String table = "ALTER TABLE file_source.file_data ";
		if( p.contains("mysql") || p.contains("mariadb")) {
			return Arrays.asList(table+"DROP PRIMARY KEY, "+add);
		}
		if( p.contains("postgres")) {
			String name = keyName == null || keyName.isEmpty() ? "file_data_pkey" : keyName;
			return Arrays.asList(table+"DROP CONSTRAINT \""+name.replace("\"", "\"\"")+"\", "+add);
		}
		return Arrays.asList(table+"DROP PRIMARY KEY", table+add);
	}

	/**
	 * Makes what links are kept in when it is missing: the column file.link_count (how many names a row has) and the table file_link (a hard link's target row, a symbolic link's target path),
	 * with an index on its target. Runs on connect unless jdbcUpgradeSchema is "false". Never fails the
	 * connection: problems (e.g. no rights to alter a table) are logged, and the README has the
	 * statements to run by hand. A database that already holds links made by an older version is not
	 * converted.
	 *
	 * @return what was made (the column, the table)
	 */
	public List<String> addLinkSchema(Connection con) {
		List<String> ret = new ArrayList<>();
		try {
			DatabaseMetaData md = con.getMetaData();
			try(Statement st = con.createStatement()) {
				if( columnSize(md, "link_count") < 0 ) {
					st.executeUpdate("ALTER TABLE file_source.file ADD COLUMN link_count INT DEFAULT 0 NOT NULL");
					ret.add("file.link_count");
				}
				if( !linkTableExists(md) ) {
					st.executeUpdate(FILE_LINK_TABLE_SQL);
					st.executeUpdate(FILE_LINK_INDEX_SQL);
					ret.add("file_link");
				}
			}
			if( !con.getAutoCommit()) {
				con.commit();
			}
			if( !ret.isEmpty() ) {
				logInfo("Made what links are kept in: "+ret);
			}
		} catch (SQLException | RuntimeException e) {
			logWarn("Can't make file.link_count and file_link (see README, Upgrading an existing database): "+e);
		}
		return ret;
	}

	public static final String FILE_LINK_TABLE_SQL = "CREATE TABLE file_source.file_link ("
			+ "LINK_ID BIGINT NOT NULL PRIMARY KEY, TARGET_ID BIGINT, TARGET_PATH VARCHAR(4096))";
	public static final String FILE_LINK_INDEX_SQL = "CREATE INDEX file_link_target_idx ON file_source.file_link (target_id)";

	private static boolean linkTableExists(DatabaseMetaData md) throws SQLException {
		return tableExists(md, "file_link");
	}

	private static boolean tableExists(DatabaseMetaData md, String table) throws SQLException {
		// identifier case and "schema" differ, as for columnSize()
		String[][] tries = {
				{null, "FILE_SOURCE", table.toUpperCase(Locale.ROOT)},
				{null, "file_source", table},
				{"file_source", null, table}};
		for(String[] t : tries) {
			try(ResultSet rs = md.getTables(t[0], t[1], t[2], null)) {
				if( rs.next()) {
					return true;
				}
			}
		}
		return false;
	}

	/** Whether opening a file for reading writes its access time (see {@link #JDBC_ACCESS_TIME}); read at connect. */
	private volatile boolean updateAccessTime = true;

	boolean updatesAccessTime() {
		return updateAccessTime;
	}

	/** The pool's thread starts at once; this only keeps a failure from waiting forever. */
	private static final long POOL_START_WAIT_MS = 30_000;

	@Override
	protected synchronized boolean connectImpl() throws IOException {
		boolean ret = false;
		if( isConnected() ) {
			return true;
		}

		Properties prop = getConnectProperties();
		ConnectionPool tmp;
		String poolType = prop.getProperty(JDBC_POOL, ConnectionPools.PARLEY);
		// the setting may be a reference (@keychain:...): resolved only to log in. The pool
		// keeps the password to open further connections, as a JDBC driver needs.
		String setting = prop.getProperty(JDBC_PASSWORD);
		String password = null;
		if( setting != null ) {
			char[] pw = secret(JDBC_PASSWORD, setting);
			password = new String(pw);
			Arrays.fill(pw, '\0');
		}
		try {
			tmp = ConnectionPools.getConnectionPool(
					poolType,
					prop.getProperty(JDBC_URL),
					prop.getProperty(JDBC_USERID),
					password
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
					loginUrl = prop.getProperty(JDBC_URL);
					loginUser = prop.getProperty(JDBC_USERID);
					loginPassword = password;
					pool = tmp;
					ret = true;
					updateAccessTime = !"false".equalsIgnoreCase(prop.getProperty(JDBC_ACCESS_TIME, "true").trim());
					boolean upgrade = !"false".equalsIgnoreCase(prop.getProperty(JDBC_UPGRADE_SCHEMA, "true").trim());
					if( upgrade ) {
						widenNameColumns(con);
						reorderDataKey(con);
						addLinkSchema(con);
					}
					lockDialect = setUpLocks(con, upgrade);
				}
			}
		} catch (SQLException e) {
			logError("Can't open pool", e);
		}

		if( !ret ) {
			// a pool that couldn't log in is useless: drop it rather than keep a dead thread
			ConnectionPools.removeConnectionPool(poolType, prop.getProperty(JDBC_URL), prop.getProperty(JDBC_USERID), password);
		}
		return ret;
	}


	/**
	 * Find whether this database can keep file locks, and make its lock table if it is missing (and
	 * upgrading is allowed). Never fails the connection: without the table, locks reach only this JVM.
	 * @return the dialect, or null
	 */
	private JdbcLocks.Dialect setUpLocks(Connection con, boolean upgrade) {
		JdbcLocks.Dialect dialect;
		try {
			dialect = JdbcLocks.detect(con.getMetaData());
		} catch (SQLException | RuntimeException e) {
			logWarn("Can't tell whether the database can keep file locks; they reach only this JVM: "+e);
			return null;
		}
		if( dialect == null ) {
			return null;
		}
		try {
			if( upgrade ) {
				JdbcLocks.createTables(con);
			}
			try(Statement st = con.createStatement()) {
				// both tables are there
				st.executeQuery("SELECT fileid FROM "+JdbcLocks.TABLE+" WHERE 1 = 0").close();
				st.executeQuery("SELECT fileid FROM "+JdbcLocks.RANGES+" WHERE 1 = 0").close();
			}
		} catch (SQLException | RuntimeException e) {
			logWarn("No lock tables ("+JdbcLocks.TABLE+", "+JdbcLocks.RANGES+", see README); file locks reach only this JVM: "+e);
			return null;
		}
		try {
			int n = JdbcLocks.removeRowsOfDeletedFiles(con, dialect);
			if( n > 0 ) {
				logInfo("Removed "+n+" lock rows of deleted files");
			}
		} catch (SQLException | RuntimeException e) {
			logWarn("Can't tidy "+JdbcLocks.TABLE+" (harmless): "+e);
		}
		return dialect;
	}

	/** @return how this database keeps file locks, or null if they reach only this JVM */
	JdbcLocks.Dialect lockDialect() {
		return isConnected() ? lockDialect : null;
	}

	/**
	 * A connection of its own for one lock, not from the pool: the pool closes connections that are
	 * idle for long, and a lock's connection is idle for as long as the lock is held.
	 */
	Connection openLockConnection() throws SQLException {
		ConnectionPool p = pool;
		if( p == null ) {
			throw new SQLException("Not connected");
		}
		// one a released lock left (opening one is a handshake and several statements: a mail
		// server takes a lock for every message it stores)
		Connection c;
		while( (c = idleLockConnections.pollFirst()) != null ) {
			try {
				if( c.isValid(2) ) {
					return c;
				}
			} catch (SQLException e) {
				// closed by the server (its wait_timeout): open another
			}
			closeQuietly(c);
		}
		return java.sql.DriverManager.getConnection(loginUrl, loginUser, loginPassword);
	}

	/**
	 * Changes to single rows, counted per row (a row's id picks one of these counters; two rows may
	 * share one, which only makes a handle look its row up again sooner). A change to a file's data
	 * or attributes, or an entry added to or removed from a directory, marks only the rows involved,
	 * so the handles of other files keep what they know: the global change count (noteChange) made
	 * every handle forget everything at every write, and a mail server writes all the time.
	 */
	private final java.util.concurrent.atomic.AtomicLongArray rowChanges = new java.util.concurrent.atomic.AtomicLongArray(ROW_CHANGE_STRIPES);
	static final int ROW_CHANGE_STRIPES = 4096;

	void noteRowChange(long fileid) {
		rowChanges.incrementAndGet(rowStripe(fileid));
	}

	long rowChangeCount(long fileid) {
		return rowChanges.get(rowStripe(fileid));
	}

	private static int rowStripe(long fileid) {
		return (int) Math.floorMod(fileid ^ (fileid >>> 32), (long) ROW_CHANGE_STRIPES);
	}

	/** At most this many lock connections are kept for reuse; more are closed. */
	static final int IDLE_LOCK_CONNECTIONS = 16;
	private final java.util.concurrent.ConcurrentLinkedDeque<Connection> idleLockConnections = new java.util.concurrent.ConcurrentLinkedDeque<>();

	/** A lock connection whose lock was released (its transaction ended): kept for the next lock. */
	void reuseLockConnection(Connection c) {
		if( pool == null || idleLockConnections.size() >= IDLE_LOCK_CONNECTIONS ) {
			closeQuietly(c);
			return;
		}
		idleLockConnections.addFirst(c);
		if( pool == null ) {
			closeIdleLockConnections(); // disconnected meanwhile
		}
	}

	private void closeIdleLockConnections() {
		Connection c;
		while( (c = idleLockConnections.pollFirst()) != null ) {
			closeQuietly(c);
		}
	}

	private static void closeQuietly(Connection c) {
		try {
			c.close();
		} catch (SQLException e) {
			// gone already
		}
	}

	@Override
	protected void disConnectImpl() {
		lockDialect = null;
		closeIdleLockConnections();
		if( pool != null ) {
			try {
				pool.destroyAll();
			} catch (Exception e) {
			}
			pool = null;
		}
		loginPassword = null;
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
		if( chunkSizeExplicit ) {
			ret.setProperty(PROP_BUFFER_SIZE, ""+chunk_size);
		}
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
				ConnectionSetting.bool(JDBC_UPGRADE_SCHEMA, "Upgrade the schema").withDefault("true").asAdvanced(),
				ConnectionSetting.bool(JDBC_ACCESS_TIME, "Write access times on read").withDefault("true")
					.withDescription("false is like noatime: opening a file for reading does not write its access time")
					.asAdvanced(),
				ConnectionSetting.choice(JDBC_POOL, "Connection pool", ConnectionPools.PARLEY, ConnectionPools.HIKARI, ConnectionPools.DBCP2)
					.withDefault(ConnectionPools.PARLEY)
					.withDescription("hikari and dbcp2 need HikariCP or Commons DBCP2 on the classpath")
					.asAdvanced());
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

		applyBufferSize(p);
		instanceProperies = p;
	}

	@Override
	public void setConnectionProperties(Properties prop) {

		for (Object name : _connectProperties.keySet() )   {
			String key = (String)name;
			String value = prop.getProperty( key );
			if( value == null ) {
				// not given: the group, the upgrade switch and the access time switch keep their defaults, everything else is empty
				value = JDBC_GROUP.equals(key) || JDBC_UPGRADE_SCHEMA.equals(key) || JDBC_ACCESS_TIME.equals(key) || JDBC_POOL.equals(key) ? _connectProperties.getProperty(key) : "";
			}
			instanceProperies.setProperty(key, value);
		}
		// not one of the settings above, so it has to be read here or it would be dropped
		Properties given = new Properties();
		String size = prop.getProperty(PROP_BUFFER_SIZE);
		if( size != null ) {
			given.setProperty(PROP_BUFFER_SIZE, size);
			applyBufferSize(given);
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
	public char getPathSeparatorChar() {
		return ':';
	}

	@Override
	public char getSeparatorChar() {
		return '/';
	}


	// ---- links (see JdbcFileSource.LINK, the file_link table, and file_source.dirent for hard links)

	private JdbcFileSource local(FileSource f) throws IOException {
		if( f instanceof JdbcFileSource && ((JdbcFileSource) f).getFileSourceFactory() == this ) {
			return (JdbcFileSource) f;
		}
		if( f instanceof JdbcFileSource && isSameFileSystem(((JdbcFileSource) f).getFileSourceFactory()) ) {
			return (JdbcFileSource) f;
		}
		throw new IOException("A link has to be between files of one database: " + f);
	}

	/** A symbolic link is an inode that holds the path it points to. The path doesn't have to exist. */
	@Override
	public FileSource createSymbolicLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		JdbcFileSource link = local(newFileLink);
		link.makeLink(local(existingFile), false);
		return link;
	}

	/** A hard link is another name (a dirent) for a file's inode: the file is there until its last name is deleted. */
	@Override
	public FileSource createLink(FileSource newFileLink, FileSource existingFile) throws IOException {
		JdbcFileSource link = local(newFileLink);
		link.makeLink(local(existingFile), true);
		return link;
	}

}
