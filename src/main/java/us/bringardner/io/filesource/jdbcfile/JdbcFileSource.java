package us.bringardner.io.filesource.jdbcfile;

import static us.bringardner.io.filesource.jdbcfile.JdbcFileSourceFactory.seperatorChar;

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
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.swing.ProgressMonitor;

import us.bringardner.core.BaseObject;
import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceFactory;
import us.bringardner.io.filesource.FileSourceFilter;
import us.bringardner.io.filesource.FileSourceRandomAccessStream;
import us.bringardner.io.filesource.IRandomAccessStream;
import us.bringardner.io.filesource.ISeekableInputStream;

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


	static final String NAME = "name";
	static final String OWNER = "owner";
	static final String GROUP_NAME = "group_name";
	static final String CREATE_TIME = "create_time";
	static final String CHUNK_COUNT = "chunk_count";
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
	private  Long fileid;
	@NotAField()
	private  JdbcFileSource parent;
	@NotAField()
	private  NamedField kids;
	@NotAField()
	private String name;


	Map<String,FieldValue> fieldCache = new HashMap<>();


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
				try(Connection con = factory.getConnection()) {
					try(PreparedStatement pstmt = con.prepareStatement(sql)) {
						pstmt.setLong(1, fileid);
						try (ResultSet rs = pstmt.executeQuery()) {
							if( rs.next()) {
								ret = rs.getObject(1);
								fieldCache.put(name, new FieldValue(ret));	
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
	 * @param path - a clean path with dots expanded
	 * @throws IOException
	 */
	public JdbcFileSource (JdbcFileSourceFactory factory, String path) throws IOException {
		this.factory = factory;
		initPath(path);
	}

	private void initPath(String path) throws IOException {
		if( path.isEmpty() || path.equals("/")) {
			// i am root
			name = "";
		} else {
			int idx = path.lastIndexOf(seperatorChar);
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
	public int compareTo(Object arg0) {
		if (arg0 instanceof JdbcFileSource) {
			JdbcFileSource file = (JdbcFileSource) arg0;
			return file.getAbsolutePath().compareTo(getAbsolutePath());
		}
		return 0;
	}

	@Override
	public boolean createNewFile() throws IOException {
		boolean ret = false;
		if( !exists()) {
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
		if( exists()) {
			if( isDirectory() && listFiles().length>0) {
				throw new IOException("Can't delete directory withg children");
			}

			String sql = "delete from file_source.file where fileid = ?";
			if( executeUpdate(sql, fileid)== 1) {
				truncate();
				fileid = null;
				if( parent != null) {
					parent.dereferenceChilderen();
				}
				ret = true;
			}
		}

		return ret;
	}

	@Override
	public void dereferenceChilderen() {
		kids = null;
	}

	@Override
	public boolean exists() throws IOException {

		// already been queried
		if(fileid != null) {
			return true;
		}

		boolean ret = false;		
		long pid = 0;

		if( parent != null ) {
			// i am root
			if( !parent.exists()) {
				return false;
			} else {
				pid = parent.fileid;
			}
		} 


		try(Connection con = factory.getConnection()) {
			try (PreparedStatement pstmt = con.prepareStatement("select name,fileid from file_source.file where name = ? and parentid=?")){
				pstmt.setString(1, name);
				pstmt.setLong(2, pid);
				try(ResultSet rs = pstmt.executeQuery()) {
					if( rs.next()) {
						ret = setFields(rs);
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
			try {
				Field field = getClass().getDeclaredField(f);
				field.setAccessible(true);
				field.set(this, val);

			} catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
				throw new SQLException(e);
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
		return getAbsolutePath();
	}

	public int getChunkCount() throws IOException {
		int ret = 0;
		
		if( fileid != null) {
			Object tmp = getFieldValue(CHUNK_COUNT,"select count(chunk_number) from file_source.file_data where fileid = ?");
			if( tmp != null ) {
				ret = ((Number)tmp).intValue();
			} 
		}
		
		
		return ret;
	}

	@Override
	public FileSource getChild(String arg0) throws IOException {
		if( isDirectory()) {
			return new JdbcFileSource(this, arg0);
		}
		throw new IOException("Children are not allowed ");
	}

	@Override
	public String getContentType() {
		return "";
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
		return fileid;
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
			throw new IOException("Not a file");
		}
		long len = length();
		if( startingPosition>=len) {
			startingPosition = len;
		}
		setLastAccessTime(System.currentTimeMillis());
		return new JdbcFileInputStream(this,startingPosition);
	}

	@Override
	public FileSource getLinkedTo() throws IOException {
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
		if(isDirectory()) {
			throw new IOException("Cannot write to a directory");
		}


		if( !exists()) {
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


		return new JdbcFileOutputStream(this, append);

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
	public boolean isChildOfMine(FileSource arg0) throws IOException {
		return arg0.getCanonicalPath().startsWith(getCanonicalPath());
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

		if( fileid != null) {
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

		return new String[0];
	}

	@Override
	public String[] list(FileSourceFilter filter) throws IOException {
		List<String> ret = new ArrayList<>();
		for(FileSource file:listFiles()) {
			if(filter.accept(file)) {
				ret.add(file.getName());
			}
		}


		return ret.toArray(new String[ret.size()]);
	}

	@Override
	public FileSource[] listFiles() throws IOException {
		if( kids == null || isDirectory() || kids.hasExprired()) {
			String sql = "select name,fileid from file_source.file where parentid=?";

			try {
				try(Connection con = factory.getConnection()) {
					try(PreparedStatement pstmt = con.prepareStatement(sql)) {
						pstmt.setLong(1, fileid);
						try(ResultSet rs = pstmt.executeQuery()) {
							List<JdbcFileSource> list = new ArrayList<>();
							while(rs.next()) {
								String nm = rs.getString(NAME);
								JdbcFileSource kid = new JdbcFileSource(this, nm);
								kid.setFields(rs);
								list.add(kid);
							}
							kids= new NamedField(JdbcFileSourceFactory.KIDS,list.toArray(new JdbcFileSource[list.size()]));
						}
					}
				}
			} catch(SQLException e) {
				throw new IOException(e);
			}
		}
		return ((JdbcFileSource[])kids.value);
	}

	@Override
	public FileSource[] listFiles(FileSourceFilter filter) throws IOException {
		List<FileSource> list = new ArrayList<>();
		for(FileSource file : listFiles()) {
			if(filter.accept(file)) {
				list.add(file);
			}
		}
		return list.toArray(new JdbcFileSource[list.size()]);
	}

	@Override
	public FileSource[] listFiles(ProgressMonitor monitor) throws IOException {
		// TODO:  add monitoring
		return listFiles();
	}

	@Override
	public boolean mkdir() throws IOException {
		if( 
				exists() || 
				(parent !=null 
				&& !parent.exists())) {
			return false;
		}

		boolean ret =  executeInsert(DIRECTORY);
		if( parent != null) {
			parent.dereferenceChilderen();
		}

		return ret;
	}

	private boolean executeInsert(String file_type) throws IOException {

		String owner = factory.getUserId();

		String sql = "insert into file_source.file (name,parentid,owner,file_type) values(?,?,?,?)";

		long pid = parent == null ? 0 : parent.fileid;
		return executeUpdate(sql, name,pid,owner,file_type) == 1;
	}


	@Override
	public boolean mkdirs() throws IOException {
		boolean ret = exists();
		if( !ret) {
			if( parent == null) { 
				ret = mkdir();
			} else 	if( parent.mkdirs()) {
				ret = mkdir();
			}
		}

		return ret;
	}

	@Override
	public void refresh() throws IOException {
	}

	@Override
	public boolean renameTo(FileSource arg0) throws IOException {
		boolean ret = false;

		if(exists() && !arg0.exists()) {
			if (arg0 instanceof JdbcFileSource) {
				JdbcFileSource file = (JdbcFileSource) arg0;
				//  Can't rename root or rename to root
				if( parent != null && file.parent != null) {
					if(file.parent.fileid != null || file.parent.mkdirs()) {
						String sql = "update file_source.file set parentid = ? , name = ? where fileid=?";
						if((executeUpdate(sql, file.parent.fileid,file.name,fileid)==1)) {
							fileid = null;
							ret = arg0.exists();
						}
					}
				}
			}
		}

		return ret;
	}

	@Override
	public boolean setCreateTime(long arg0) throws IOException {
		if( exists()) {
			String sql = "update file_source.file set create_time=? where fileid=?";
			if( executeUpdate(sql, new Timestamp(arg0),fileid) != 1) {
				return false;
			};
		}
		return true;
	}

	@Override
	public boolean setExecutable(boolean arg0) throws IOException {
		return setOwnerExecutable(arg0);
	}

	@Override
	public boolean setExecutable(boolean arg0, boolean ownerOnly) throws IOException {
		boolean ret = false;
		if( !ownerOnly) {
			ret = setExecutable(arg0);
		} else {
			if( exists()) {
				String sql = "update file_source.file set owner_executable = ? group_executable = ? other_executable = ? where fileid = ?";
				if( executeUpdate(sql, arg0,arg0,arg0,fileid) == 1) {
					ret = true;
				}
			}
		}
		return ret;
	}

	@Override
	public boolean setGroup(GroupPrincipal arg0) throws IOException {
		if(exists() ) {
			String sql = "update file_source.file set group_name = ? where fileid = ?";
			if(executeUpdate(sql,arg0,fileid) != 1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setGroupExecutable(boolean arg0) throws IOException {
		if(exists() ) {
			String sql = "update file_source.file set group_executable = ? where fileid = ?";
			if(executeUpdate(sql,arg0,fileid) != 1) {
				return false;
			}
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
		} catch (SQLException e) {
			throw new IOException(e);
		}
		return ret;
	}


	@Override
	public boolean setGroupReadable(boolean arg0) throws IOException {
		if(exists() ) {
			String sql = "update file_source.file set group_readable = ? where fileid = ?";
			if(executeUpdate(sql,arg0,fileid) != 1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setGroupWritable(boolean arg0) throws IOException {

		if(exists() ) {
			String sql = "update file_source.file set group_writeable = ? where fileid = ?";
			if(executeUpdate(sql,arg0,fileid) != 1) {
				return  false;
			}
		}
		return true;
	}

	public boolean updateAccessAndModTime(long arg0) throws IOException {

		if(exists() ) {
			Timestamp ts = new Timestamp(arg0);
			String sql = "update file_source.file set last_access_date = ?,last_modified_date=? where fileid = ?";
			if(executeUpdate(sql,ts,ts,fileid) !=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setLastAccessTime(long arg0) throws IOException {
		if(exists() ) {
			String sql = "update file_source.file set last_access_date = ? where fileid = ?";
			if(executeUpdate(sql,new Timestamp(arg0),fileid) !=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setLastModifiedTime(long arg0) throws IOException {

		if(exists() ) {
			String sql = "update file_source.file set last_modified = ? where fileid = ?";
			if( executeUpdate(sql,new Timestamp(arg0),fileid) !=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setOtherExecutable(boolean arg0) throws IOException {
		if(exists() ) {
			String sql = "update file_source.file set other_executable = ? where fileid = ?";
			if( executeUpdate(sql,arg0,fileid) !=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setOtherReadable(boolean arg0) throws IOException {

		if(exists() ) {
			String sql = "update file_source.file set other_readable = ? where fileid = ?";
			if(executeUpdate(sql,arg0,fileid)!=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setOtherWritable(boolean arg0) throws IOException {
		if(exists() ) {
			String sql = "update file_source.file set other_writeable = ? where fileid = ?";
			if(executeUpdate(sql,arg0,fileid)!=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setOwner(UserPrincipal arg0) throws IOException {
		if(exists() ) {
			String sql = "update file_source.file set owner = ? where fileid = ?";
			if(executeUpdate(sql,arg0.getName(),fileid)!=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setOwnerExecutable(boolean arg0) throws IOException {
		if(exists() ) {
			String sql = "update file_source.file set owner_executable = ? where fileid = ?";
			if(executeUpdate(sql,arg0,fileid)!=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setOwnerReadable(boolean arg0) throws IOException {
		if(exists() ) {
			String sql = "update file_source.file set owner_readable = ? where fileid = ?";
			if(executeUpdate(sql,arg0,fileid)!=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setOwnerWritable(boolean arg0) throws IOException {
		if(exists() ) {
			String sql = "update file_source.file set owner_writeable = ? where fileid = ?";
			if(executeUpdate(sql,arg0,fileid)!=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setReadOnly() throws IOException {
		if( exists()) {
			String sql = "update file_source.file set "+
					"owner_executable =? , owner_writeable=? , group_executable=? , group_writeable =?, other_executable =?, other_writeable=?"
					+"where fileid = ?";
			if(executeUpdate(sql, false, false ,false , false , false , false,fileid)!=1) {
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean setReadable(boolean arg0) throws IOException {
		return setOwnerReadable(arg0);
	}

	@Override
	public boolean setReadable(boolean arg0, boolean ownerOnly) throws IOException {
		boolean ret = false;
		if( !ownerOnly) {
			ret = setReadable(arg0);
		} else {
			if( exists()) {
				String sql = "update file_source.file set owner_readable = ? group_readable = ? other_readable = ? where fileid = ?";
				if( executeUpdate(sql, arg0,arg0,arg0,fileid) == 1) {
					ret = true;
				}
			}
		}
		return ret;
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
		boolean ret = false;
		if( !ownerOnly) {
			ret = setWritable(arg0);
		} else {
			if( exists()) {
				String sql = "update file_source.file set owner_writeable = ? group_writeable = ? other_writeable = ? where fileid = ?";
				if( executeUpdate(sql, arg0,arg0,arg0,fileid) == 1) {
					ret = true;
				}
			}
		}
		return ret;
	}

	@Override
	public URL toURL() throws MalformedURLException {
		// TODO Auto-generated method stub
		return null;
	}

	public String toString() {
		return getAbsolutePath();
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
			try(Connection con = factory.getConnection()) {
				Timestamp time = new Timestamp(System.currentTimeMillis());
				try(PreparedStatement pstmt = con.prepareStatement("delete from file_source.file_data where fileid=?")) {
					pstmt.setLong(1, fileid);
					pstmt.executeUpdate();					
				}

				
				// field 
				setFieldCache(LENGTH,0);
				setFieldCache(CHUNK_COUNT,0);
				setFieldCache(LAST_ACCESS_DATE, time);
				setFieldCache(LAST_MODIFIED_DATE, time);
			} catch (SQLException e) {
				throw new IOException(e);
			}
		}
	}


	private void setFieldCache(String name, Object value) {
		fieldCache.put(name, new FieldValue(value));		
	}

	public void appendData(int length,byte[] data) throws IOException {
		long chunk = getChunkCount()+1;
		try(Connection con = factory.getConnection()) {
			try(PreparedStatement pstmt = con.prepareStatement("insert into file_source.file_data "
					+ "(chunk_number,fileid,length,data) "
					+ "values(?,?,?,?)")) 
			{
				pstmt.setLong(1, chunk);
				pstmt.setLong(2, fileid);
				pstmt.setInt(3, length);
				pstmt.setBytes(4, data);

				try {					 
					if(pstmt.executeUpdate()!=1) {
						throw new IOException("Could not insert chunk="+chunk);
					}	
				} catch (SQLException e) {
					//  if we have an abort we can end up with a dangling row in the data table
					if( e.getMessage().toLowerCase().contains("duplicate")) {
						try(PreparedStatement pstmt2 = con.prepareStatement(
								"delete from file_source.file_data "
										+ " where chunk_number=? and fileid=?")) {

							pstmt2.setLong(1, chunk);
							pstmt2.setLong(2, fileid);

							if( pstmt2.executeUpdate()==1) {
								if(pstmt.executeUpdate()!=1) {
									throw new IOException("Could not insert chunk="+chunk);
								}		
							};					
						}
					}
				}

			}
			Timestamp time = new Timestamp(System.currentTimeMillis());
			try(PreparedStatement pstmt = con.prepareStatement(
					"update file_source.file set chunk_count=?, length=length+?, "+LAST_ACCESS_DATE+"=?, "+LAST_MODIFIED_DATE+"=? where fileid=?")) {


				pstmt.setInt(1, (int)chunk);
				pstmt.setLong(2, length);
				pstmt.setTimestamp(3, time);
				pstmt.setTimestamp(4, time);
				pstmt.setLong(5, fileid);
				if(pstmt.executeUpdate()!=1) {
					throw new IOException("Counld not update chunk count");
				}
			}
			removeCache(LENGTH,CHUNK_COUNT);
			setFieldCache(LAST_ACCESS_DATE, time);
			setFieldCache(LAST_MODIFIED_DATE, time);
		} catch (SQLException e) {
			throw new IOException(e);
		}

	}

	public byte[] getChunk(long chunk) throws IOException {
		byte [] ret = null;
		int max = getChunkCount();
		if( max >= chunk) {
			try(Connection con = factory.getConnection()) {
				try(PreparedStatement pstmt = con.prepareStatement(
						"select length,data from file_source.file_data where chunk_number=? and fileid=? ")) {
					pstmt.setLong(1, chunk);
					pstmt.setLong(2, fileid);
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
		}

		return ret;
	}


	@Override
	public IRandomAccessStream getRandomAccessStream(String mode) throws IOException {
		return new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(this), mode);
	}

}


