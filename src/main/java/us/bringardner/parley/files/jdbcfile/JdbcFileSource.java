package us.bringardner.parley.files.jdbcfile;

import static us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory.separatorChar;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import us.bringardner.parley.core.BaseObject;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceFilter;
import us.bringardner.parley.files.FileSourceProgress;
import us.bringardner.parley.files.FileSourceRandomAccessStream;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.StreamOption;
import us.bringardner.parley.files.StreamOptions;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.files.lock.LockBackend;
import us.bringardner.parley.files.lock.LockManager;
import us.bringardner.parley.files.lock.LockScope;
import java.util.ArrayDeque;
import java.util.Deque;

public class JdbcFileSource extends BaseObject implements FileSource {

	// field 
	private class FieldValue {		
		long lastUpdate ;
		Object value;

		public FieldValue(Object obj) {
			value = obj;
			lastUpdate = System.currentTimeMillis();
		}

		public boolean hasExprired() {
			return System.currentTimeMillis()-lastUpdate > factory.getFieldTimeToLive();
		}

	}

	private class NamedField extends FieldValue {


		private String name;

		public NamedField(String name,Object obj) {
			super(obj);
			this.name = name;
		}

		@Override
		public boolean hasExprired() {
			return System.currentTimeMillis()-lastUpdate > factory.getFieldTimeToLive(name);
		}
	}

	private static final long serialVersionUID = 1L;
	static final String FILE = "file";
	static final String DIRECTORY = "dir";
	/**
	 * A symbolic link: an inode whose entry in file_link is the path it points to, as on POSIX.
	 * A hard link has no type of its own: it is a second name (a dirent) for an inode that is a file.
	 */
	static final String LINK = "link";


	static final String NAME = "name";
	static final String OWNER = "owner";
	static final String GROUP_NAME = "group_name";
	static final String CREATE_TIME = "create_time";
	static final String CHUNK_COUNT = "chunk_count";
	static final String LINK_COUNT = "link_count";
	static final String CHUNK_NUMBER = "chunk_number";

	static final String LENGTH = "length";
	static final String LAST_ACCESS_DATE = "last_access_date";
	static final String LAST_MODIFIED_DATE = "last_modified_date";
	static final String OWNER_READABLE = "owner_readable";
	static final String OWNER_WRITEABLE = "owner_writeable";
	static final String OWNER_EXECUTABLE = "owner_executable";
	static final String GROUP_READABLE = "group_readable";
	static final String GROUP_WRITEABLE = "group_writeable";
	static final String GROUP_EXECUTABLE = "group_executable";
	static final String OTHER_READABLE = "other_readable";
	static final String OTHER_WRITEABLE = "other_writeable";
	static final String OTHER_EXECUTABLE = "other_executable";

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface NotAField {

	}

	@NotAField()
	private  JdbcFileSourceFactory factory;
	@NotAField()
	private volatile Long fileid;
	@NotAField()
	private  JdbcFileSource parent;
	@NotAField()
	private volatile NamedField kids;
	@NotAField()
	private String name;


	// (one handle is used by several threads at once, e.g. a mail server's maildrop root: the caches
	// may be read and dropped concurrently)
	Map<String,FieldValue> fieldCache = new java.util.concurrent.ConcurrentHashMap<>();


	private Object getFieldValue(String name) throws IOException {
		String sql = "select "+name+" from file_source.file where fileid = ?";
		Object ret = getFieldValue(name,sql);
		return ret;
	}

	private Object getFieldValue(String name,String sql) throws IOException {
		Object ret = null;
		if( exists()) {
			FieldValue val = fieldCache.get(name);

			if( val == null || val.hasExprired() ) {
				long id = rowId();   // of the file this answers for (a link's target), before a connection is held
				try(Connection con = factory.getConnection()) {
					try(PreparedStatement pstmt = con.prepareStatement(sql)) {
						pstmt.setLong(1, id);
						try (ResultSet rs = pstmt.executeQuery()) {
							if( rs.next()) {
								ret = rs.getObject(1);
								fieldCache.put(name, new NamedField(name, ret));
							}
						}
					}
				} catch (SQLException e) {
					throw new IOException(e);
				}
			} else {
				ret = val.value;
			}
		}

		return ret;
	}



	private long getTimeValue(String name) throws IOException {
		long ret = 0;
		Object tmp = getFieldValue(name);
		if( tmp != null ) {
			ret = ((Timestamp)tmp).getTime();
		}
		return ret;
	}



	private boolean getBooleanValue(String name) throws IOException {
		boolean ret = false;
		Object tmp = getFieldValue(name);
		if( tmp != null ) {
			ret = ((Boolean)tmp);
		}
		return ret;
	}

	private String getStringValue(String name) throws IOException {
		String ret = "";
		Object tmp = getFieldValue(name);
		if( tmp != null ) {
			ret = tmp.toString();
		}
		return ret;
	}

	protected JdbcFileSource (JdbcFileSource parent,String name) throws IOException {
		this.factory = parent.factory;
		this.parent = parent;
		this.name = name;
	}



	/**
	 * 
	 * @param factory
	 * @param path - cleaned with {@link #normalize(String)}, so "." and ".." never become names
	 * @throws IOException
	 */
	public JdbcFileSource (JdbcFileSourceFactory factory, String path) throws IOException {
		this.factory = factory;
		initPath(normalize(path));
	}

	/**
	 * The path in canonical form (BJL-13): absolute, "/"-separated ("\\" counts as "/"),
	 * no repeated or trailing separators, "." dropped and ".." removing the element
	 * before it (".." at the root stays at the root). Links are not followed (see
	 * {@link #getCanonicalPath()}). A relative path is taken from the root.
	 */
	public static String normalize(String path) {
		Deque<String> parts = new ArrayDeque<>();
		for(String part : (path == null ? "" : path).replace('\\', '/').split("/")) {
			if( part.isEmpty() || part.equals(".")) {
				continue;
			}
			if( part.equals("..")) {
				parts.pollLast();
			} else {
				parts.addLast(part);
			}
		}
		return "/"+String.join("/", parts);
	}

	private void initPath(String path) throws IOException {
		if( path.isEmpty() || path.equals("/")) {
			// i am root
			name = "";
		} else {
			int idx = path.lastIndexOf(separatorChar);
			if( idx < 0 ) {
				// I don't thin this can happen
				name = path;
			} else {
				name = path.substring(idx+1);
				String parentPath = path.substring(0,idx);
				parent = new JdbcFileSource(factory,parentPath);
			}
		}

	}

	/*
	private WhoAmI whoAmI() throws IOException {
		WhoAmI ret = WhoAmI.Other;
		String owner = getStringValue(OWNER);
		if( factory.getUserId().equals(owner)) {
			ret = WhoAmI.Owner;
		} else {
			String group = getStringValue(GROUP_NAME);
			//TODO: How to manage groups
			if("staff".equals(group)) {
				ret = WhoAmI.Group;
			}
		}

		//staff

		return ret;
	};
*/
	
	@Override
	public boolean canGroupExecute() throws IOException {
		return getBooleanValue(GROUP_EXECUTABLE);
	}

	@Override
	public boolean canGroupRead() throws IOException {
		return getBooleanValue(GROUP_READABLE);
	}

	@Override
	public boolean canGroupWrite() throws IOException {
		return getBooleanValue(GROUP_WRITEABLE);
	}

	@Override
	public boolean canOtherExecute() throws IOException {
		return getBooleanValue(OTHER_EXECUTABLE);
	}

	@Override
	public boolean canOtherRead() throws IOException {
		return getBooleanValue(OTHER_READABLE);
	}

	@Override
	public boolean canOtherWrite() throws IOException {
		return getBooleanValue(OTHER_WRITEABLE);
	}

	@Override
	public boolean canOwnerExecute() throws IOException {
		return getBooleanValue(OWNER_EXECUTABLE);
	}

	@Override
	public boolean canOwnerRead() throws IOException {
		return getBooleanValue(OWNER_READABLE);
	}

	@Override
	public boolean canOwnerWrite() throws IOException {
		return getBooleanValue(OWNER_WRITEABLE);
	}

	@Override
	/**
	 * Orders by path, then by kind, like the other FileSources. It used to compare the other
	 * way round, so files sorted in descending order, and it returned 0 (equal) for anything
	 * that wasn't a JdbcFileSource.
	 */
	public int compareTo(Object arg0) {
		String mine = getAbsolutePath();
		if (arg0 instanceof FileSource) {
			int ret = mine.compareTo(((FileSource) arg0).getAbsolutePath());
			return ret != 0 ? ret : getClass().getName().compareTo(arg0.getClass().getName());
		}
		return mine.compareTo(String.valueOf(arg0));
	}

	/**
	 * Atomic, as the contract requires: the database's key on (parent_id, name) lets only one
	 * of several racing inserts in, and the others return false.
	 */
	@Override
	public boolean createNewFile() throws IOException {
		boolean ret = false;
		if( !rowExists()) {
			// as java.io.File: the directory it goes in has to be there (this was a NullPointerException
			// for a missing parent, and "false" under a plain file)
			if( parent == null || !parent.isDirectory()) {
				throw new IOException("No such file or directory: "+getParent());
			}
			ret = executeInsert(FILE);
		}
		return ret;
	}

	@Override
	public long creationTime() throws IOException {
		return getTimeValue(CREATE_TIME);
	}

	@Override
	public boolean delete() throws IOException {
		boolean ret = false;
		// this name only: deleting a link removes the link, never what it points to, and deleting one
		// name of a hard linked file leaves the file under its other names
		if( rowExists()) {
			String type = ownType();
			if( !isLinkType(type) && isDirectory() && listFiles().length>0) {
				// as java.io.File: a directory that isn't empty isn't deleted, and that is false
				return false;
			}
			long id = fileid;
			int outcome = deleteName(id, type);
			if( outcome != GONE ) {
				if( !FILE.equals(type) ) {
					changedStructure();
				}
				fileid = null;
				forgetResolution();
				changed();
				if( parent != null) {
					parent.dereferenceChilderen();
				}
				ret = true;
			}
		}
		return ret;
	}

	/** What {@link #deleteName} did: the name is gone and so is the inode with its data, the name is gone and others remain, or no name. */
	private static final int DELETED = 1, UNLINKED = 2, GONE = 0;

	/**
	 * The name, one off the inode's link count and, when that was the last name, the inode with its data
	 * and link entry, and the parent folder's modified time, in one transaction. The inode's row is locked
	 * by the count update, so a hard link made at the same moment either sees the file and is counted, or
	 * finds it gone: no name is ever left pointing at nothing.
	 *
	 * @param id the inode this handle found under the name
	 * @param type what the inode is (file, directory or link)
	 */
	private int deleteName(long id, String type) throws IOException {
		Long parentId = null;
		JdbcFileSource folder = parent == null ? null : parent.resolve();   // a linked folder is the one it leads to
		if( folder != null && folder.rowExists() ) {
			parentId = folder.fileid;
		}
		long pid = parentId == null ? 0 : parentId;
		for (int attempt = 1; ; attempt++) {
			try(Connection con = factory.getConnection()) {
				con.setAutoCommit(false);
				try {
					int outcome;
					try(PreparedStatement pstmt = con.prepareStatement(
							"delete from file_source.dirent where parent_id = ? and name = ? and inode_id = ?")) {
						pstmt.setLong(1, pid);
						pstmt.setString(2, name);
						pstmt.setLong(3, id);
						if( pstmt.executeUpdate() != 1 ) {
							con.rollback();
							return GONE;   // deleted or renamed through another handle
						}
					}
					try(PreparedStatement pstmt = con.prepareStatement(
							"update file_source.file set link_count = link_count - 1 where fileid = ?")) {
						pstmt.setLong(1, id);
						pstmt.executeUpdate();
					}
					// the row is deleted only if that was the last name: the count update has locked it, so a
					// hard link made at the same moment is either counted before this or finds the file gone
					boolean last;
					try(PreparedStatement pstmt = con.prepareStatement(
							"delete from file_source.file where fileid = ? and link_count <= 0")) {
						pstmt.setLong(1, id);
						last = pstmt.executeUpdate() == 1;
					}
					if( last ) {
						// (only a file has data, and only a symbolic link has an entry)
						if( FILE.equals(type) ) {
							try(PreparedStatement pstmt = con.prepareStatement("delete from file_source.file_data where fileid = ?")) {
								pstmt.setLong(1, id);
								pstmt.executeUpdate();
							}
						} else if( LINK.equals(type) ) {
							try(PreparedStatement pstmt = con.prepareStatement("delete from file_source.file_link where link_id = ?")) {
								pstmt.setLong(1, id);
								pstmt.executeUpdate();
							}
						}
					}
					outcome = last ? DELETED : UNLINKED;
					if( parentId != null ) {
						// a directory's modified time is when something was last taken out of it
						try(PreparedStatement pstmt = con.prepareStatement(
								"update file_source.file set "+LAST_MODIFIED_DATE+" = ? where fileid = ?")) {
							pstmt.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
							pstmt.setLong(2, parentId);
							pstmt.executeUpdate();
						}
					}
					con.commit();
					fieldCache.clear();
					changed();
					if( parentId != null ) {
						folder.fieldCache.clear();
						folder.changed();
					}
					return outcome;
				} catch (SQLException | RuntimeException e) {
					try {
						con.rollback();
					} catch (SQLException e2) {
						// the connection is gone; what it did is undone with it
					}
					throw e;
				}
			} catch (SQLException e) {
				if( attempt >= TRANSACT_ATTEMPTS || !isRetryable(e) ) {
					throw new IOException(e);
				}
				// a deadlock or serialization failure: the database rolled it back, so do it again
				try {
					Thread.sleep(attempt * 10L + (long) (Math.random() * 20 * attempt));
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new IOException(e);
				}
			}
		}
	}

	@Override
	public void dereferenceChilderen() {
		kids = null;
	}

	/**
	 * As java.io.File: a symbolic link exists if what it points at does (one that points at
	 * nothing is there, but doesn't exist). {@link #rowExists()} is whether this path has an
	 * entry of its own.
	 */
	@Override
	public boolean exists() throws IOException {
		if( !rowExists() ) {
			return false;   // (a path with no row is not a link: nothing to resolve)
		}
		JdbcFileSource r = resolve();
		if( r != this ) {
			return r.exists();
		}
		// a link that could not be followed (a loop, or the file it shared is gone) isn't a file
		return !isLinkType(ownType());
	}

	private static boolean isLinkType(String type) {
		return LINK.equals(type);
	}

	/** This path has a row of its own: a file, a directory, or a link (also one that points at nothing). */
	private boolean rowExists() throws IOException {
		forgetIfChanged();

		// already been queried: the id is trusted for the field time to live, like the attributes.
		// (It used to be kept for good, so a file deleted or renamed through another connection
		// still existed for a handle that had found it.)
		if(fileid != null) {
			if( System.currentTimeMillis() - fileidAt <= factory.getFieldTimeToLive() ) {
				return true;
			}
			// stale: look it up again, keeping the id meanwhile (another thread may be using it)
			fieldCache.clear();
		}

		boolean ret = false;		
		long pid = 0;

		if( parent != null ) {
			// i am root
			if( !parent.exists()) {
				return false;
			} else {
				pid = parent.rowId();   // a linked directory holds what is below it
			}
		} 


		try(Connection con = factory.getConnection()) {
			// (the whole row: its attributes are cached with the id, instead of a query for each one)
			try (PreparedStatement pstmt = con.prepareStatement("select f.*, d.name as name from file_source.dirent d join file_source.file f on f.fileid = d.inode_id where d.name = ? and d.parent_id = ?")){
				pstmt.setString(1, name);
				pstmt.setLong(2, pid);
				try(ResultSet rs = pstmt.executeQuery()) {
					if( rs.next()) {
						ret = setFields(rs);
					} else {
						fileid = null;   // gone (deleted or renamed through another handle)
					}
				}
			}
		} catch (SQLException e) {
			throw new IOException(e);			
		}

		return ret;
	}

	private boolean setFields(ResultSet rs) throws SQLException {
		String tmp = rs.getString("name");
		if( !name.equals(tmp)) {
			throw new SQLException("name does not match rs name="+name+" rs="+tmp);
		}
		ResultSetMetaData md = rs.getMetaData();
		// a link's own row says only what it is: its attributes are those of what it leads to,
		// looked up through rowId() when they are asked for
		String type = rs.getString("file_type");
		boolean link = isLinkType(type);
		fieldCache.put("own_type", new NamedField("own_type", type));
		ownTypeValue = type;
		ownTypeAt = System.currentTimeMillis();
		for(int idx=1,sz=md.getColumnCount(); idx <= sz; idx++ ) {
			String f = md.getColumnLabel(idx);
			if( f==null ) {
				f = md.getColumnName(idx);
				if( f == null ) {
					throw new SQLException("Col "+idx+" has no name");
				}
			}
			f = f.toLowerCase();
			if( NAME.equals(f)) {
				continue;
			}

			Object val = rs.getObject(idx);
			if( "fileid".equals(f) ) {
				fileid = val == null ? null : ((Number) val).longValue();
				fileidAt = System.currentTimeMillis();
				if( fileid != null ) {
					seenRowOf = fileid;
					seenRow = factory.rowChangeCount(fileid);
				}
				continue;
			}
			if( LENGTH.equals(f) || CHUNK_COUNT.equals(f) ) {
				// (length and chunk count are counted from the data, not read from this row)
				continue;
			}
			// an attribute: cached as getFieldValue would cache it (for the same time to live)
			if( !link ) {
				fieldCache.put(f, new NamedField(f, val));
			}

		}

		return true;
	}


	@Override
	public String getAbsolutePath() {
		if( parent == null ) {
			return "/";
		} 

		String parentPath =  parent.getAbsolutePath();
		if( parentPath.equals("/")) {
			return parentPath+name;
		}  else {
			return parentPath+"/"+name;
		}

	}

	@Override
	public String getCanonicalPath() throws IOException {
		return canonicalPath(0);
	}

	/** As java.io.File: a symbolic link, or a path through a linked directory, resolves to the target's path. */
	private String canonicalPath(int depth) throws IOException {
		if( depth > 40 ) {
			throw new IOException("Too many levels of symbolic links: " + getAbsolutePath());
		}
		// (a link that leads nowhere is not resolved and keeps its own name, as java.io.File's does)
		if( rowExists() && LINK.equals(ownType()) && exists() && linkTarget() != null ) {
			return linkTarget().canonicalPath(depth+1);
		}
		if( parent == null ) {
			return "/";
		}
		String p = parent.canonicalPath(depth+1);
		return p.equals("/") ? "/" + name : p + "/" + name;
	}

	/**
	 * The factory's change count when this handle last looked at the database; -1 before it did.
	 * A change made through any FileSource of the factory since then drops what this handle
	 * remembers (its row id, attributes, listing), so a handle that was kept sees it, as a
	 * java.io.File does. A change made by another connection is seen when the cached values
	 * expire (see the factory's field time to live).
	 */
	@NotAField()
	private transient volatile long seenChange = -1;
	/** When fileid was found; it is trusted for the factory's field time to live. */
	@NotAField()
	private transient volatile long fileidAt;
	/** What this row is (file, directory, link), as read with the row, and when. */
	@NotAField()
	private transient volatile String ownTypeValue;
	@NotAField()
	private transient volatile long ownTypeAt;

	private void forgetIfChanged() {
		long now = factory.getChangeCount();
		long seen = seenChange;
		boolean forget = false;
		if( seen != now ) {
			seenChange = now;
			forget = seen != -1;
		}
		// a change to this row only (its data, its attributes, its entries)
		Long id = fileid;
		if( id != null ) {
			long rowNow = factory.rowChangeCount(id);
			if( seenRowOf != id ) {
				// found since: what it knows is as new as the lookup
				seenRowOf = id;
				seenRow = rowNow;
			} else if( seenRow != rowNow ) {
				seenRow = rowNow;
				forget = true;
			}
		}
		if( forget ) {
			// stale, not unknown: the id is looked up again, but kept until the answer comes,
			// so another thread using this handle never sees it vanish
			fileidAt = 0;
			fieldCache.clear();
			ownTypeValue = null;
			kids = null;
			resolvedHandle = null;
		}
	}

	/** The row whose change count {@link #seenRow} is, and that count. */
	@NotAField()
	private transient volatile long seenRowOf = -1;
	@NotAField()
	private transient volatile long seenRow;

	/**
	 * Called after this handle changed the database. Others forget what they knew; this one
	 * keeps its own cache, which the change code has already brought up to date.
	 */
	void changed() {
		Long id = fileid;
		if( id != null ) {
			factory.noteRowChange(id);
			seenRowOf = id;
			seenRow = factory.rowChangeCount(id);
		}
	}

	/**
	 * Called after a change that moves what paths lead to (a directory deleted or renamed, a link
	 * made): every handle forgets what it knew. This one keeps its own cache.
	 */
	void changedStructure() {
		factory.noteChange();
		seenChange = factory.getChangeCount();
	}

	@NotAField()
	private transient volatile JdbcFileSource resolvedHandle;
	@NotAField()
	private transient long resolvedAt;
	/** How long the answer of {@link #resolve()} is kept. */
	private static final long RESOLVE_TTL = 2000;

	private void forgetResolution() {
		resolvedHandle = null;
	}

	/**
	 * The path whose row answers for this one: what a symbolic link points at (which may not
	 * exist), or this. (A hard link is another name for the same inode: nothing to follow.) A path that is not a link costs
	 * nothing beyond the row it is found by: its type comes with it.
	 */
	private JdbcFileSource resolve() throws IOException {
		JdbcFileSource r = resolvedHandle;
		if( r != null && System.currentTimeMillis() - resolvedAt < RESOLVE_TTL ) {
			return r;
		}
		r = resolve(0);
		resolvedHandle = r;
		resolvedAt = System.currentTimeMillis();
		return r;
	}

	private JdbcFileSource resolve(int depth) throws IOException {
		if( depth > 40 || !rowExists() ) {
			return this;
		}
		String type = ownType();
		if( LINK.equals(type) ) {
			JdbcFileSource target = linkTarget();
			return target == null ? this : target.resolve(depth+1);
		}
		return this;
	}

	/** The type of this row, whatever it is (the answer for a link is not looked through). */
	private String ownType() throws IOException {
		// (kept apart from fieldCache, which every update clears: an update does not change what a row is)
		String known = ownTypeValue;
		if( known != null && System.currentTimeMillis() - ownTypeAt <= factory.getFieldTimeToLive() ) {
			return known;
		}
		FieldValue val = fieldCache.get("own_type");
		if( val == null || val.hasExprired() ) {
			String type = null;
			try(Connection con = factory.getConnection()) {
				try(PreparedStatement pstmt = con.prepareStatement("select file_type from file_source.file where fileid = ?")) {
					pstmt.setLong(1, fileid);
					try (ResultSet rs = pstmt.executeQuery()) {
						if( rs.next() ) {
							type = rs.getString(1);
						}
					}
				}
			} catch (SQLException e) {
				throw new IOException(e);
			}
			val = new NamedField("own_type", type);
			fieldCache.put("own_type", val);
			ownTypeValue = type;
			ownTypeAt = System.currentTimeMillis();
		}
		return (String) val.value;
	}

	/** This link row's entry in file_link: {target id or null, target path or null}; null if it has none. */
	private Object[] linkEntry() throws IOException {
		try(Connection con = factory.getConnection()) {
			try(PreparedStatement pstmt = con.prepareStatement(
					"select target_id, target_path from file_source.file_link where link_id = ?")) {
				pstmt.setLong(1, fileid);
				try (ResultSet rs = pstmt.executeQuery()) {
					if( rs.next() ) {
						return new Object[] { rs.getObject(1) == null ? null : rs.getLong(1), rs.getString(2) };
					}
				}
			}
		} catch (SQLException e) {
			throw new IOException(e);
		}
		return null;
	}

	/** Where this symbolic link points, or null if it has no entry or no path. */
	private JdbcFileSource linkTarget() throws IOException {
		Object[] entry = linkEntry();
		return entry == null || entry[1] == null ? null : (JdbcFileSource) factory.createFileSource((String) entry[1]);
	}

	/** The id of the row that answers for this path. It is for a file that exists (or is about to). */
	long rowId() throws IOException {
		JdbcFileSource r = resolve();
		// (read once: another thread may look the row up again meanwhile)
		Long id = r.fileid;
		if( id == null || System.currentTimeMillis() - r.fileidAt > factory.getFieldTimeToLive() ) {
			if( !r.rowExists() ) {
				throw new FileNotFoundException(r.getAbsolutePath() + " (No such file or directory)");
			}
			id = r.fileid;
			if( id == null ) {
				throw new FileNotFoundException(r.getAbsolutePath() + " (No such file or directory)");
			}
		}
		return id;
	}

	/**
	 * Makes this path a link to existing.
	 * @param hard true for a second name for the file, false for a symbolic link
	 */
	void makeLink(JdbcFileSource existing, boolean hard) throws IOException {
		if( rowExists() ) {
			throw new java.nio.file.FileAlreadyExistsException(getAbsolutePath());
		}
		if( parent == null || !parent.isDirectory() ) {
			throw new java.nio.file.NoSuchFileException(getAbsolutePath());
		}
		long ownerId = -1;
		String path = null;
		if( hard ) {
			JdbcFileSource owner = existing.resolve();   // a link to a link is a link to the file
			if( !owner.isFile() ) {
				throw new java.nio.file.NoSuchFileException(existing.getAbsolutePath());
			}
			ownerId = owner.rowId();
		} else {
			path = existing.getAbsolutePath();
		}
		forgetResolution();
		if( !insertLinkRows(hard, ownerId, path) || !rowExists() ) {
			throw new IOException("Can't create the link " + getAbsolutePath());
		}
		fieldCache.clear();
		changedStructure();
		parent.dereferenceChilderen();
	}

	/**
	 * A symbolic link is an inode of its own, with its path in file_link; a hard link is only a name, so
	 * the inode's link count goes up. Either way the name, and the folder's modified time, are in the same
	 * transaction. The count is raised only while it is above 0, so a file whose last name is being
	 * deleted is not linked to.
	 *
	 * @return false if the name was taken meanwhile
	 */
	private boolean insertLinkRows(boolean hard, long ownerId, String path) throws IOException {
		long pid = parent.rowId();
		JdbcFileSource folder = parent.resolve();
		try {
			transact(con -> {
				long inode = ownerId;
				if( hard ) {
					try(PreparedStatement pstmt = con.prepareStatement(
							"update file_source.file set link_count = link_count + 1 where fileid = ? and link_count > 0")) {
						pstmt.setLong(1, ownerId);
						if( pstmt.executeUpdate() != 1 ) {
							throw new java.nio.file.NoSuchFileException(getAbsolutePath());
						}
					}
				} else {
					inode = insertInode(con, LINK, false);
					try(PreparedStatement pstmt = con.prepareStatement(
							"insert into file_source.file_link (link_id,target_id,target_path) values(?,null,?)")) {
						pstmt.setLong(1, inode);
						pstmt.setString(2, path);
						pstmt.executeUpdate();
					}
				}
				insertName(con, pid, inode);
				touch(con, pid);
				return null;
			});
		} catch (IOException e) {
			if( isDuplicateKey(e) ) {
				return false;   // another caller made the name first: the key (parent_id, name) lets only one in
			}
			throw e;
		}
		fileid = null;
		fieldCache.clear();
		folder.fieldCache.clear();
		folder.changed();
		if( hard ) {
			factory.noteRowChange(ownerId);   // (it has one more name)
		}
		return true;
	}

	private interface Transaction<T> {
		T run(Connection con) throws SQLException, IOException;
	}

	/**
	 * Runs the body in one transaction: committed if it returns, rolled back if it throws. A deadlock or
	 * serialization failure, which the database has rolled back, is tried again; any other SQL error is
	 * an IOException with the SQLException as its cause.
	 */
	private <T> T transact(Transaction<T> body) throws IOException {
		for (int attempt = 1; ; attempt++) {
			try(Connection con = factory.getConnection()) {
				con.setAutoCommit(false);
				try {
					T ret = body.run(con);
					con.commit();
					return ret;
				} catch (SQLException | IOException | RuntimeException e) {
					try {
						con.rollback();
					} catch (SQLException e2) {
						// the connection is gone; what it did is undone with it
					}
					throw e;
				}
			} catch (SQLException e) {
				if( attempt >= TRANSACT_ATTEMPTS || !isRetryable(e) ) {
					throw new IOException(e);
				}
				// (many creators in one folder all touch its row: they collide, and the later tries wait longer)
				try {
					Thread.sleep(attempt * 10L + (long) (Math.random() * 20 * attempt));
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new IOException(e);
				}
			}
		}
	}

	/** A new inode with one name's worth of link count; the owner is the current user. Returns its id. */
	private long insertInode(Connection con, String type, boolean executable) throws SQLException {
		try(PreparedStatement pstmt = con.prepareStatement(
				"insert into file_source.file (owner,file_type,"+OWNER_EXECUTABLE+",link_count) values(?,?,?,1)",
				Statement.RETURN_GENERATED_KEYS)) {
			pstmt.setString(1, factory.getUserId());
			pstmt.setString(2, type);
			pstmt.setBoolean(3, executable);
			pstmt.executeUpdate();
			try(ResultSet rs = pstmt.getGeneratedKeys()) {
				if( rs.next() ) {
					return rs.getLong(1);
				}
			}
		}
		throw new SQLException("The database did not return the id of the new row");
	}

	/** This path's name in the folder pid, for the inode. The key (parent_id, name) is what makes it atomic. */
	private void insertName(Connection con, long pid, long inode) throws SQLException {
		try(PreparedStatement pstmt = con.prepareStatement("insert into file_source.dirent (parent_id,name,inode_id) values(?,?,?)")) {
			pstmt.setLong(1, pid);
			pstmt.setString(2, name);
			pstmt.setLong(3, inode);
			pstmt.executeUpdate();
		}
	}

	/** A directory's modified time is when something was last added to it or taken out. */
	private static void touch(Connection con, long folderId) throws SQLException {
		if( folderId == 0 ) {
			return;
		}
		try(PreparedStatement pstmt = con.prepareStatement(
				"update file_source.file set "+LAST_MODIFIED_DATE+" = ? where fileid = ?")) {
			pstmt.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
			pstmt.setLong(2, folderId);
			pstmt.executeUpdate();
		}
	}

	private static boolean isSqlState(SQLException e, String prefix) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if( t instanceof SQLException ) {
				String state = ((SQLException) t).getSQLState();
				if( state != null && state.startsWith(prefix) ) {
					return true;
				}
			}
		}
		return false;
	}

	public int getChunkCount() throws IOException {
		int ret = 0;
		
		// exists() looks the file up and sets fileid; a fresh handle used to answer 0 for any file
		if( exists()) {
			Object tmp = getFieldValue(CHUNK_COUNT,"select count(chunk_number) from file_source.file_data where fileid = ?");
			if( tmp != null ) {
				ret = ((Number)tmp).intValue();
			} 
		}
		
		
		return ret;
	}

	@Override
	public FileSource getChild(String arg0) throws IOException {
		// a path that doesn't exist yet can still get children (then mkdirs), as in
		// the other file systems; only an existing regular file can't. One look decides: exists()
		// first, then the type of the row it found (isDirectory() then exists() were two looks,
		// so a folder another process made in between was "not a directory" yet "there": seen on
		// mx1 as SMTP's 451 for a recipient whose folder the other server was making)
		if( !exists() || isDirectory()) {
			if( arg0.isEmpty() || arg0.equals(".") || arg0.equals("..") || arg0.indexOf('/') >= 0 || arg0.indexOf('\\') >= 0 ) {
				// a path, not a name: "sub/file.txt" or "../x" used to become one element
				return new JdbcFileSource(factory, getAbsolutePath()+"/"+arg0);
			}
			return new JdbcFileSource(this, arg0);
		}
		throw new IOException("Children are not allowed ");
	}

	/**
	 * The MIME type for the name's extension, from the table every FileSource shares, or null when
	 * it isn't in it or there is no extension. (It was "" for every file, where the other file
	 * systems answer null for a type they don't know.)
	 */
	@Override
	public String getContentType() {
		return us.bringardner.parley.files.fileproxy.FileProxy.getContentType(getName());
	}

	@Override
	public long getCreateDate() throws IOException {
		return getTimeValue(CREATE_TIME);
	}

	@Override
	public FileSourceFactory getFileSourceFactory() {
		return factory;
	}

	public long getFileId() {
		try {
			return rowId();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public GroupPrincipal getGroup() throws IOException {
		String tmp =  getStringValue(GROUP_NAME);
		return new GroupPrincipal() {

			@Override
			public String getName() {				
				return tmp;
			}
		};
	}

	@Override
	public InputStream getInputStream() throws IOException {
		return getInputStream(0);
	}


	@Override
	public InputStream getInputStream(long startingPosition) throws IOException {
		if( !isFile()) {
			// as java.io.FileInputStream: a caller tells "gone" (another process took it) by the type
			throw new FileNotFoundException(getAbsolutePath() + (exists() ? " (Is a directory)" : " (No such file or directory)"));
		}
		long len = length();
		if( startingPosition>=len) {
			startingPosition = len;
		}
		if( factory.updatesAccessTime() ) {
			setLastAccessTime(System.currentTimeMillis());
		}
		return new JdbcFileInputStream(this,startingPosition);
	}

	@Override
	public FileSource getLinkedTo() throws IOException {
		if( rowExists() && LINK.equals(ownType()) ) {
			return linkTarget();
		}
		return null;
	}

	@Override
	public long getMaxVersion() throws IOException {
		return 0;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return getOutputStream(false);
	}



	@Override
	public OutputStream getOutputStream(boolean append) throws IOException {
		return openOutput(append, 0);
	}

	// ---- streams with their own sizes (see StreamOptions)

	/**
	 * CHUNK_SIZE: how a stream cuts what it writes into rows, which is also the unit random
	 * access reads and writes and a seek past the end grows the file in. Data already stored is
	 * read as it was written, whatever the size is now: each row keeps its own length.
	 * <p>
	 * BUFFER_SIZE isn't one: a stream's buffer is the row it is filling, so it is the chunk size.
	 */
	@Override
	public java.util.Set<StreamOption<?>> supportedStreamOptions() {
		return java.util.Collections.singleton(StreamOption.CHUNK_SIZE);
	}

	/** What a stream opened without options uses: the factory's chunk size, for both. */
	@Override
	public StreamOptions getStreamDefaults() {
		int size = ((JdbcFileSourceFactory)getFileSourceFactory()).getChunk_size();
		return StreamOptions.NONE.withBufferSize(size).withChunkSize(size);
	}

	/** The chunk size asked for in options, kept to the factory's limits; 0 if there isn't one. */
	private static int chunkIn(StreamOptions options) {
		Integer size = StreamOptions.orNone(options).get(StreamOption.CHUNK_SIZE);
		return size == null || size <= 0 ? 0 : JdbcFileSourceFactory.clampBufferSize(size);
	}

	@Override
	public OutputStream getOutputStream(boolean append, StreamOptions options) throws IOException {
		return openOutput(append, chunkIn(options));
	}

	@Override
	public ISeekableInputStream getSeekableInputStream(StreamOptions options) throws IOException {
		return new JdbcFileSourceSeekableInputStream(this, chunkIn(options));
	}

	@Override
	public IRandomAccessStream getRandomAccessStream(String mode, StreamOptions options) throws IOException {
		return new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(this, chunkIn(options)), mode);
	}

	/** @param chunkSize the rows this stream stores, or 0 for the factory's chunk size */
	private OutputStream openOutput(boolean append, int chunkSize) throws IOException {
		// (the row is looked up once: a path with no row costs a lookup each time it is asked about)
		boolean hasRow = rowExists();
		if( hasRow ) {
			JdbcFileSource r = resolve();
			if( r != this ) {
				return r.openOutput(append, chunkSize);   // a link that points at nothing creates its target
			}
			if(isDirectory()) {
				throw new IOException("Cannot write to a directory");
			}
		}

		if( !hasRow || !exists()) {
			// as a FileOutputStream: the directory above must be there, and be a directory
			FileSource above = getParentFile();
			if( above == null || !above.isDirectory() ) {
				throw new FileNotFoundException(getAbsolutePath() + " (No such file or directory)");
			}
			if(!executeInsert(FILE)) {
				throw new IOException("Can't create the file ");
			}
			if(!exists()) {
				throw new IOException("file still does not exist ");
			}
			// this is a file so it always has a parent
			parent.dereferenceChilderen();
		}

		// this should never happen
		if( fileid == null ) {
			throw new IOException("filleid is null");
		}


		return new JdbcFileOutputStream(this, append, chunkSize);

	}

	@Override
	public UserPrincipal getOwner() throws IOException {
		String tmp =  getStringValue(OWNER);
		return new UserPrincipal() {

			@Override
			public String getName() {
				return tmp;
			}
		};
	}

	@Override
	public String getParent()  {
		try {
			return getParentFile().getAbsolutePath();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public FileSource getParentFile() throws IOException {
		return parent;
	}

	@Override
	public ISeekableInputStream getSeekableInputStream() throws IOException {		
		return new JdbcFileSourceSeekableInputStream(this);
	}

	@Override
	public String getTitle() throws IOException {
		return "SimpleJdbcFileSource";
	}

	@Override
	public long getVersion() throws IOException {
		return 0;
	}

	@Override
	public long getVersionDate() throws IOException {
		return getCreateDate();
	}

	@Override
	public boolean isDirectory() throws IOException {
		return exists() 
				&& DIRECTORY.equals(getStringValue(JdbcFileSourceFactory.FILE_TYPE));

	}

	@Override
	public boolean isFile() throws IOException {
		return exists() && FILE.equals(getStringValue(JdbcFileSourceFactory.FILE_TYPE));
	}

	@Override
	public boolean isVersionSupported() throws IOException {
		return false;
	}

	@Override
	public long lastAccessTime() throws IOException {
		return getTimeValue(LAST_ACCESS_DATE);
	}

	@Override
	public long lastModified() throws IOException {
		return getTimeValue(LAST_MODIFIED_DATE);
	}

	@Override
	public long length() throws IOException {

		long ret = 0;

		// exists() looks the file up and sets fileid; a fresh handle used to answer 0 for any file
		if( exists()) {
			Object tmp = getFieldValue(LENGTH, "select sum(length) from file_source.file_data where fileid = ?");
			if( tmp != null ) {
				if (tmp instanceof Number) {
					ret = ((Number) tmp).longValue();					
				} else {
					throw new IOException("Invalid returnm type for length = "+tmp.getClass());
				}
			} 
		}

		return ret;
	}

	@Override
	public String[] list() throws IOException {
		if( isDirectory()) {
			FileSource [] files = listFiles();
			String [] ret = new String[files.length];
			for (int idx = 0; idx < ret.length; idx++) {
				ret[idx] = files[idx].getName();
			}
			return ret;
		}

		// as java.io.File: null when this isn't a directory (missing, or a plain file)
		return null;
	}

	@Override
	public String[] list(FileSourceFilter filter) throws IOException {
		// as java.io.File.list(filter): null when this isn't a directory, and no filter lists everything
		FileSource[] files = listFiles();
		if( files == null ) {
			return null;
		}
		List<String> ret = new ArrayList<>();
		for(FileSource file : files) {
			if( filter == null || filter.accept(file) ) {
				ret.add(file.getName());
			}
		}
		return ret.toArray(new String[ret.size()]);
	}

	@Override
	public FileSource[] listFiles() throws IOException {
		// exists() loads fileid; without it a fresh object threw a NullPointerException.
		// Nothing there: null, as java.io.File.listFiles() returns.
		if( !exists()) {
			return null;
		}
		// as java.io.File: a plain file has no list (it was an empty one)
		if( !isDirectory()) {
			return null;
		}
		NamedField known = kids;
		if( known == null || isDirectory() || known.hasExprired()) {
			// (whole rows: what is asked about each entry next, its type, length, time, is already there)
			String sql = "select f.*, d.name as name from file_source.dirent d join file_source.file f on f.fileid = d.inode_id where d.parent_id=? order by d.name";

			long dirId = rowId();   // a linked directory lists what is in the one it links to
			try {
				try(Connection con = factory.getConnection()) {
					try(PreparedStatement pstmt = con.prepareStatement(sql)) {
						pstmt.setLong(1, dirId);
						try(ResultSet rs = pstmt.executeQuery()) {
							List<JdbcFileSource> list = new ArrayList<>();
							while(rs.next()) {
								String nm = rs.getString(NAME);
								JdbcFileSource kid = new JdbcFileSource(this, nm);
								kid.setFields(rs);
								list.add(kid);
							}
							known = new NamedField(JdbcFileSourceFactory.KIDS,list.toArray(new JdbcFileSource[list.size()]));
							kids = known;
						}
					}
				}
			} catch(SQLException e) {
				throw new IOException(e);
			}
		}
		return ((JdbcFileSource[])known.value);
	}

	@Override
	public FileSource[] listFiles(FileSourceFilter filter) throws IOException {
		FileSource[] files = listFiles();
		if( files == null ) {
			return null;   // not a directory, as java.io.File.listFiles(filter)
		}
		List<FileSource> list = new ArrayList<>();
		for(FileSource file : files) {
			if( filter == null || filter.accept(file) ) {
				list.add(file);
			}
		}
		return list.toArray(new JdbcFileSource[list.size()]);
	}

	@Override
	public FileSource[] listFiles(FileSourceProgress monitor) throws IOException {
		// TODO:  add monitoring
		return listFiles();
	}

	/** Atomic, as {@link #createNewFile()} is: the key on (parent_id, name) decides. */
	@Override
	public boolean mkdir() throws IOException {
		if( 
				rowExists() || 
				(parent !=null 
				&& !parent.isDirectory())) {   // a directory goes in a directory (a file "exists" too)
			return false;
		}

		boolean ret =  executeInsert(DIRECTORY);
		if( parent != null) {
			parent.dereferenceChilderen();
		}

		return ret;
	}

	/**
	 * Runs a change to this path's row and, in the same transaction, sets the modified time of the
	 * folders it moved things in or out of: as a file system does, a directory's modified time is when
	 * something was last added to it or taken out. One commit for the change and the times, not one each.
	 * The folders are written in id order, so two changes touching the same folders take their locks in
	 * the same order; a deadlock is retried.
	 *
	 * @param folders the folders to touch if the statement changed one row (null or without a row: skipped)
	 * @return the number of rows the statement changed
	 */
	private int executeUpdateTouching(String sql, Object[] args, JdbcFileSource ... folders) throws IOException {
		java.util.TreeMap<Long, JdbcFileSource> touch = new java.util.TreeMap<>();
		for (JdbcFileSource folder : folders) {
			JdbcFileSource f = folder == null ? null : folder.resolve();   // a linked folder is the one it leads to
			if( f != null && f.rowExists() ) {
				touch.put(f.fileid, f);
			}
		}
		for (int attempt = 1; ; attempt++) {
			try(Connection con = factory.getConnection()) {
				con.setAutoCommit(false);
				try {
					int ret;
					try(PreparedStatement pstmt = con.prepareStatement(sql)) {
						for (int idx = 0; idx < args.length; idx++) {
							pstmt.setObject(idx+1, args[idx]);
						}
						ret = pstmt.executeUpdate();
					}
					if( ret == 1 && !touch.isEmpty() ) {
						Timestamp time = new Timestamp(System.currentTimeMillis());
						try(PreparedStatement pstmt = con.prepareStatement(
								"update file_source.file set "+LAST_MODIFIED_DATE+" = ? where fileid = ?")) {
							for (Long id : touch.keySet()) {
								pstmt.setTimestamp(1, time);
								pstmt.setLong(2, id);
								pstmt.executeUpdate();
							}
						}
					}
					con.commit();
					fieldCache.clear();
					changed();
					if( ret == 1 ) {
						for (JdbcFileSource f : touch.values()) {
							f.fieldCache.clear();
							f.changed();
						}
					}
					return ret;
				} catch (SQLException | RuntimeException e) {
					try {
						con.rollback();
					} catch (SQLException e2) {
						// the connection is gone; what it did is undone with it
					}
					throw e;
				}
			} catch (SQLException e) {
				if( attempt >= APPEND_ATTEMPTS || !isRetryable(e) ) {
					throw new IOException(e);
				}
				// a deadlock or serialization failure: the database rolled it back, so do it again
				try {
					Thread.sleep(attempt * 5L + (long) (Math.random() * 10));
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new IOException(e);
				}
			}
		}
	}

	private boolean executeInsert(String file_type) throws IOException {
		// as a file made with the usual umask: only a directory starts executable
		boolean executable = DIRECTORY.equals(file_type);
		long pid = parent == null ? 0 : parent.rowId();
		JdbcFileSource folder = parent == null ? null : parent.resolve();   // a linked folder is the one it leads to
		forgetResolution();
		long inode;
		try {
			inode = transact(con -> {
				long id = insertInode(con, file_type, executable);
				insertName(con, pid, id);
				touch(con, pid);
				return id;
			});
		} catch (IOException e) {
			if( isDuplicateKey(e) ) {
				// another caller made it first: the key (parent_id, name) let only one insert in, which is
				// what keeps createNewFile() and mkdir() atomic; the others are told false
				fieldCache.clear();
				changed();
				return false;
			}
			throw e;
		}
		fieldCache.clear();
		if( FILE.equals(file_type) ) {
			knowNewFile(inode);
		}
		changed();
		if( folder != null && folder.rowExists() ) {
			folder.fieldCache.clear();
			folder.changed();
		}
		return true;
	}

	/**
	 * What a file just made is, without looking it up: its id, that it is a file and that it is empty.
	 * (Its other attributes are read when asked for.) A caller that makes a file to write to it would
	 * look the row up, and sum the length of its data, only to find what it was just told.
	 */
	private void knowNewFile(long inode) {
		long now = System.currentTimeMillis();
		fileid = inode;
		fileidAt = now;
		ownTypeValue = FILE;
		ownTypeAt = now;
		fieldCache.put("own_type", new NamedField("own_type", FILE));
		fieldCache.put(JdbcFileSourceFactory.FILE_TYPE, new NamedField(JdbcFileSourceFactory.FILE_TYPE, FILE));
		fieldCache.put(LENGTH, new NamedField(LENGTH, 0L));
		fieldCache.put(CHUNK_COUNT, new NamedField(CHUNK_COUNT, 0L));
	}


	/** The insert broke a unique key (SQLSTATE class 23, integrity constraint violation). */
	private static boolean isDuplicateKey(IOException e) {
		for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
			if( t instanceof SQLException ) {
				String state = ((SQLException) t).getSQLState();
				return state != null && state.startsWith("23");
			}
		}
		return false;
	}

	@Override
	public boolean mkdirs() throws IOException {
		// as java.io.File: true only if it was created (with any parents it needed); false when
		// there is already an entry (a directory, a file or a link) at the path
		if( rowExists()) {
			return false;
		}
		if( parent != null && !parent.ensureDirectory()) {
			return false;
		}
		// (false if another process made it meanwhile: it isn't this call's)
		return mkdir();
	}

	/**
	 * True if there is a directory at this path afterwards: it was there, it and its parents were
	 * made, or another process made it at the same time (its insert won: its directory is as good
	 * as ours, so the rest of the path is made below it). A parent another process was making used
	 * to stop the whole mkdirs, so two mail servers making one user's folder at once had one fail.
	 */
	private boolean ensureDirectory() throws IOException {
		if( rowExists()) {
			return isDirectory();
		}
		if( parent != null && !parent.ensureDirectory()) {
			return false;
		}
		return mkdir() || isDirectory();
	}

	@Override
	public void refresh() throws IOException {
	}

	@Override
	public boolean renameTo(FileSource arg0) throws IOException {
		boolean ret = false;
		// the entry of this path itself: a link is moved, not what it points to
		if( rowExists() && parent != null && equals(arg0) ) {
			return true;
		}
		if(rowExists() && arg0 instanceof JdbcFileSource) {
			JdbcFileSource file = (JdbcFileSource) arg0;
			if( !file.rowExists() && parent != null && file.parent != null) {
				if(file.parent.isDirectory()) {
					String sql = "update file_source.dirent set parent_id = ? , name = ? where parent_id = ? and name = ?";
					long destParent = file.parent.rowId();
					long srcParent = parent.rowId();
					boolean dir = isDirectory();
					if((executeUpdateTouching(sql, new Object[] {destParent,file.name,srcParent,name}, parent, file.parent)==1)) {
						if( dir ) {
							changedStructure();
						}
						fileid = null;
						forgetResolution();
						file.forgetResolution();
						ret = file.rowExists();
					}
				}
			}
		}
		return ret;
	}

	@Override
	public boolean setCreateTime(long arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set create_time=? where fileid=?";
		if( executeUpdate(sql, new Timestamp(arg0),rowId()) != 1) {
			return false;
		};
		return true;
	}

	@Override
	public boolean setExecutable(boolean arg0) throws IOException {
		return setOwnerExecutable(arg0);
	}

	@Override
	public boolean setExecutable(boolean arg0, boolean ownerOnly) throws IOException {
		// as java.io.File: the owner's bit, and the group's and other's too unless ownerOnly
		// (the test was the wrong way round, and the SQL had no commas)
		if( !exists() ) {
			return false;
		}
		if( ownerOnly ) {
			return setOwnerExecutable(arg0);
		}
		String sql = "update file_source.file set owner_executable = ?, group_executable = ?, other_executable = ? where fileid = ?";
		return executeUpdate(sql, arg0, arg0, arg0, rowId()) == 1;
	}

	@Override
	public boolean setGroup(GroupPrincipal arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set group_name = ? where fileid = ?";
		if(executeUpdate(sql,arg0,rowId()) != 1) {
			return false;
		}
		return true;
	}

	@Override
	public boolean setGroupExecutable(boolean arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set group_executable = ? where fileid = ?";
		if(executeUpdate(sql,arg0,rowId()) != 1) {
			return false;
		}
		return true;
	}

	private int executeUpdate(String sql, Object ... args) throws IOException {
		int ret = 0;
		try(Connection con = factory.getConnection()) {
			try(PreparedStatement pstmt = con.prepareStatement(sql)) {
				for (int idx = 0; idx < args.length; idx++) {
					pstmt.setObject(idx+1, args[idx]);
				}
				ret = pstmt.executeUpdate();
			}
			fieldCache.clear();
			changed();
		} catch (SQLException e) {
			throw new IOException(e);
		}
		return ret;
	}


	@Override
	public boolean setGroupReadable(boolean arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set group_readable = ? where fileid = ?";
		if(executeUpdate(sql,arg0,rowId()) != 1) {
			return false;
		}
		return true;
	}

	@Override
	public boolean setGroupWritable(boolean arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set group_writeable = ? where fileid = ?";
		if(executeUpdate(sql,arg0,rowId()) != 1) {
			return  false;
		}
		return true;
	}

	public boolean updateAccessAndModTime(long arg0) throws IOException {

		if(exists() ) {
			Timestamp ts = new Timestamp(arg0);
			String sql = "update file_source.file set last_access_date = ?,last_modified_date=? where fileid = ?";
			if(executeUpdate(sql,ts,ts,rowId()) !=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setLastAccessTime(long arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set last_access_date = ? where fileid = ?";
		if(executeUpdate(sql,new Timestamp(arg0),rowId()) !=1) {
			return false;
		}
		return true;
	}

	@Override
	public boolean setLastModifiedTime(long arg0) throws IOException {
		// as java.io.File.setLastModified: false for a path that doesn't exist. (This updated a column
		// called last_modified, which isn't there, so it threw for every file that did exist.)
		if( !exists() ) {
			return false;
		}
		Timestamp time = new Timestamp(arg0);
		String sql = "update file_source.file set "+LAST_MODIFIED_DATE+" = ? where fileid = ?";
		if( executeUpdate(sql,time,rowId()) !=1) {
			return false;
		}
		setFieldCache(LAST_MODIFIED_DATE, time);
		return true;
	}

	@Override
	public boolean setOtherExecutable(boolean arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set other_executable = ? where fileid = ?";
		if( executeUpdate(sql,arg0,rowId()) !=1) {
			return false;
		}
		return true;
	}

	@Override
	public boolean setOtherReadable(boolean arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set other_readable = ? where fileid = ?";
		if(executeUpdate(sql,arg0,rowId())!=1) {
			return false;
		}
		return true;
	}

	@Override
	public boolean setOtherWritable(boolean arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set other_writeable = ? where fileid = ?";
		if(executeUpdate(sql,arg0,rowId())!=1) {
			return false;
		}
		return true;
	}

	@Override
	public boolean setOwner(UserPrincipal arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set owner = ? where fileid = ?";
		if(executeUpdate(sql,arg0.getName(),rowId())!=1) {
			return false;
		}
		return true;
	}

	@Override
	public boolean setOwnerExecutable(boolean arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set owner_executable = ? where fileid = ?";
		if(executeUpdate(sql,arg0,rowId())!=1) {
			return false;
		}
		return true;
	}

	@Override
	public boolean setOwnerReadable(boolean arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set owner_readable = ? where fileid = ?";
		if(executeUpdate(sql,arg0,rowId())!=1) {
			return false;
		}
		return true;
	}

	@Override
	public boolean setOwnerWritable(boolean arg0) throws IOException {
		// as java.io.File: there is nothing to set at a path that doesn't exist
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set owner_writeable = ? where fileid = ?";
		if(executeUpdate(sql,arg0,rowId())!=1) {
			return false;
		}
		return true;
	}

	@Override
	public boolean setReadOnly() throws IOException {
		// as java.io.File.setReadOnly: nobody can write it, and nothing else changes (this also took
		// away every execute bit, and had no space before "where"); false for a path that isn't there
		if( !exists() ) {
			return false;
		}
		String sql = "update file_source.file set "+OWNER_WRITEABLE+" = ?, "+GROUP_WRITEABLE+" = ?, "
				+OTHER_WRITEABLE+" = ? where fileid = ?";
		return executeUpdate(sql, false, false, false, rowId()) == 1;
	}

	@Override
	public boolean setReadable(boolean arg0) throws IOException {
		return setOwnerReadable(arg0);
	}

	@Override
	public boolean setReadable(boolean arg0, boolean ownerOnly) throws IOException {
		// as java.io.File: the owner's bit, and the group's and other's too unless ownerOnly
		// (the test was the wrong way round, and the SQL had no commas)
		if( !exists() ) {
			return false;
		}
		if( ownerOnly ) {
			return setOwnerReadable(arg0);
		}
		String sql = "update file_source.file set owner_readable = ?, group_readable = ?, other_readable = ? where fileid = ?";
		return executeUpdate(sql, arg0, arg0, arg0, rowId()) == 1;
	}

	@Override
	public boolean setVersion(long arg0, boolean arg1) throws IOException {
		return false;
	}

	@Override
	public boolean setVersionDate(long arg0) throws IOException {
		return false;
	}

	@Override
	public boolean setWritable(boolean arg0) throws IOException {
		return setOwnerWritable(arg0);
	}

	@Override
	public boolean setWritable(boolean arg0, boolean ownerOnly) throws IOException {
		// as java.io.File: the owner's bit, and the group's and other's too unless ownerOnly
		// (the test was the wrong way round, and the SQL had no commas)
		if( !exists() ) {
			return false;
		}
		if( ownerOnly ) {
			return setOwnerWritable(arg0);
		}
		String sql = "update file_source.file set owner_writeable = ?, group_writeable = ?, other_writeable = ? where fileid = ?";
		return executeUpdate(sql, arg0, arg0, arg0, rowId()) == 1;
	}

	@Override
	public URL toURL() throws MalformedURLException {
		// TODO Auto-generated method stub
		return null;
	}

	public String toString() {
		return getAbsolutePath();
	}

	/**
	 * Equal when it is the same path in the same database, as with the other FileSources:
	 * like java.io.File, two handles for one file are equal.
	 */
	@Override
	public boolean equals(Object obj) {
		if( this == obj ) {
			return true;
		}
		if( !(obj instanceof JdbcFileSource) ) {
			return false;
		}
		JdbcFileSource f = (JdbcFileSource) obj;
		return getAbsolutePath().equals(f.getAbsolutePath()) && factory.isSameFileSystem(f.factory);
	}

	/** Hashes the path, which equals() compares. */
	@Override
	public int hashCode() {
		return getAbsolutePath().hashCode();
	}


	@Override
	public boolean isHidden() throws IOException {
		return name.startsWith(".");
	}

	public long length(boolean refreash) throws IOException {
		if( refreash ) {
			removeCache(LENGTH,CHUNK_COUNT);
		}
		return length();
	}

	private void removeCache(String ... args) {
		for(String name : args) {
			fieldCache.remove(name);
		}		
	}

	public void truncate() throws IOException  {

		long len = length();
		if( len > 0 ) {
			long id = rowId();
			try(Connection con = factory.getConnection()) {
				Timestamp time = new Timestamp(System.currentTimeMillis());
				try(PreparedStatement pstmt = con.prepareStatement("delete from file_source.file_data where fileid=?")) {
					pstmt.setLong(1, id);
					pstmt.executeUpdate();					
				}

				
				// field 
				setFieldCache(LENGTH,0);
				setFieldCache(CHUNK_COUNT,0);
				setFieldCache(LAST_ACCESS_DATE, time);
				setFieldCache(LAST_MODIFIED_DATE, time);
				changed();
			} catch (SQLException e) {
				throw new IOException(e);
			}
		}
	}


	private void setFieldCache(String name, Object value) {
		fieldCache.put(name, new FieldValue(value));		
	}

	/**
	 * Adds a chunk at the end. One transaction that first updates the file's own row, which locks it
	 * in every database, so appenders take turns, also in other processes (the mail servers sharing a
	 * store append to the same history files). The chunk number is then counted from what is stored.
	 * It used to come from what this handle last knew: two appenders took the same number, and the
	 * second deleted the first one's chunk to put its own in.
	 */
	public void appendData(int length,byte[] data) throws IOException {
		long id = rowId();
		for (int attempt = 1; ; attempt++) {
			try {
				long chunk = appendOnce(id, length, data);
				setFieldCache(CHUNK_COUNT, (int) chunk);
				removeCache(LENGTH);
				changed();
				return;
			} catch (SQLException e) {
				if( attempt >= APPEND_ATTEMPTS || !isRetryable(e) ) {
					throw new IOException(e);
				}
				// a deadlock or serialization failure: the database rolled it back, so do it again
				try {
					Thread.sleep(attempt * 5L + (long) (Math.random() * 10));
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw new IOException(e);
				}
			}
		}
	}

	private static final int APPEND_ATTEMPTS = 5;
	/** More than an append gets: a name goes into a folder that other creators are writing too. */
	private static final int TRANSACT_ATTEMPTS = 12;

	/** A deadlock or serialization failure (SQLSTATE class 40): retried as the databases advise. */
	private static boolean isRetryable(SQLException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if( t instanceof SQLException ) {
				String state = ((SQLException) t).getSQLState();
				if( state != null && state.startsWith("40") ) {
					return true;
				}
			}
		}
		return false;
	}

	private long appendOnce(long id, int length, byte[] data) throws SQLException, IOException {
		try(Connection con = factory.getConnection()) {
			con.setAutoCommit(false);
			try {
				Timestamp time = new Timestamp(System.currentTimeMillis());
				// first the file's row: the lock that makes appenders take turns
				try(PreparedStatement pstmt = con.prepareStatement(
						"update file_source.file set length=length+?, "+LAST_ACCESS_DATE+"=?, "+LAST_MODIFIED_DATE+"=? where fileid=?")) {
					pstmt.setLong(1, length);
					pstmt.setTimestamp(2, time);
					pstmt.setTimestamp(3, time);
					pstmt.setLong(4, id);
					if(pstmt.executeUpdate()!=1) {
						throw new FileNotFoundException(getAbsolutePath() + " (No such file or directory)");
					}
				}
				long chunk = 1;
				try(PreparedStatement pstmt = con.prepareStatement(
						"select max(chunk_number) from file_source.file_data where fileid = ?")) {
					pstmt.setLong(1, id);
					try(ResultSet rs = pstmt.executeQuery()) {
						if( rs.next() ) {
							chunk = rs.getLong(1) + 1;   // (null, no chunks yet: 0)
						}
					}
				}
				try(PreparedStatement pstmt = con.prepareStatement("insert into file_source.file_data "
						+ "(chunk_number,fileid,length,data) values(?,?,?,?)")) {
					pstmt.setLong(1, chunk);
					pstmt.setLong(2, id);
					pstmt.setInt(3, length);
					pstmt.setBytes(4, data);
					if(pstmt.executeUpdate()!=1) {
						throw new IOException("Could not insert chunk="+chunk);
					}
				}
				// (file.chunk_count is not written: the count of chunks is read from the data, so keeping it
				// cost a statement for each append and nothing else)
				con.commit();
				setFieldCache(LAST_ACCESS_DATE, time);
				setFieldCache(LAST_MODIFIED_DATE, time);
				return chunk;
			} catch (SQLException | IOException | RuntimeException e) {
				try {
					con.rollback();
				} catch (SQLException e2) {
					// the connection is gone; what it did is undone with it
				}
				throw e;
			}
		}
	}

	public byte[] getChunk(long chunk) throws IOException {
		if( getChunkCount() < chunk ) {
			return null;
		}
		return getChunk(rowId(), chunk);
	}

	/** One chunk of the file with this inode id, or null past its last chunk or when the file is gone. */
	byte[] getChunk(long id, long chunk) throws IOException {
		byte [] ret = null;
		try(Connection con = factory.getConnection()) {
			try(PreparedStatement pstmt = con.prepareStatement(
					"select length,data from file_source.file_data where chunk_number=? and fileid=? ")) {
				pstmt.setLong(1, chunk);
				pstmt.setLong(2, id);
				try(ResultSet rs = pstmt.executeQuery()) {
					if( rs.next()) {
						int len = rs.getInt(1);
						byte [] tmp = rs.getBytes(2);
						if( len != tmp.length) {
							byte tmp2 [] = new byte[len];
							System.arraycopy(tmp2,0,tmp,0,len);
							tmp = tmp2;
						}
						ret = tmp;
					}
				}
			}
		} catch (SQLException e) {
			throw new IOException(e);
		}
		return ret;
	}

	/** Opens the file with this inode id, as {@link #getInputStream(long)} but not through the name. */
	InputStream openInode(long id, long startingPosition) throws IOException {
		return new JdbcFileInputStream(this, id, startingPosition);
	}


	@Override
	public IRandomAccessStream getRandomAccessStream(String mode) throws IOException {
		return new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(this), mode);
	}

	// ------------------------------------------------------------------ locking

	/**
	 * {@link LockScope#CLUSTER} where the database keeps the locks (PostgreSQL, MySQL 8, MariaDB 10.3
	 * or later, with the lock table): every process on every machine that uses the database.
	 * Otherwise {@link LockScope#PROCESS}.
	 * @since 1.1.0
	 */
	@Override
	public LockScope getLockScope() {
		return factory.lockDialect() != null ? LockScope.CLUSTER : LockScope.PROCESS;
	}

	/**
	 * A database: locks as {@link #getLockScope()} says; createNewFile and mkdir atomic (the unique
	 * key on parent and name decides); each write committed, so sync is durable; one database, so
	 * operations are applied in one order. Replace isn't atomic yet (delete, then rename).
	 * @since 1.1.0
	 */
	@Override
	public us.bringardner.parley.files.FileSourceCapabilities getCapabilities() {
		return us.bringardner.parley.files.FileSourceCapabilities.defaults(getLockScope()).withDurableSync(true)
				.withOrderedOperations(true);
	}

	/**
	 * A lock on a region of the file (a link's target, for a link), kept by the database where it can
	 * (see {@link #getLockScope()}): a row for the region in {@code file_source.file_lock_range}, kept
	 * row-locked on a connection of its own until the lock is released (see {@code JdbcLocks}). The
	 * file's own rows are never locked, so the holder reads and writes it as usual. The lock is on the
	 * file, not its name: it stays with the file when it is renamed.
	 * @since 1.1.0
	 */
	@Override
	public FileSourceLock tryLock(long position, long size, boolean shared) throws IOException {
		size = LockManager.checkRange(position, size);
		LockManager.requireExists(this);
		long id = rowId();
		JdbcLocks.Dialect dialect = factory.lockDialect();
		String key = "jdbc|" + factory.getConnectProperties().getProperty(JdbcFileSourceFactory.JDBC_URL, "") + "|" + id;
		LockBackend backend = dialect == null ? LockBackend.inProcess() : new JdbcLocks.Backend(factory, dialect, id);
		return LockManager.get().tryAcquire(this, key, position, size, shared, backend);
	}

}
