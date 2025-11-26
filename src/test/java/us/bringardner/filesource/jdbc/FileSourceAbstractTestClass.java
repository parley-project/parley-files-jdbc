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
 * ~version~V001.01.47-V000.01.23-V000.01.18-V000.01.20-V000.01.19-V000.01.17-V000.01.16-V000.01.15-V000.01.09-V000.01.05-V000.01.02-V000.01.01-V000.00.05-V000.00.02-V000.00.01-V000.00.00-
 */
package us.bringardner.filesource.jdbc;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

import org.hsqldb.Server;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.TestMethodOrder;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceFactory;
import us.bringardner.io.filesource.jdbcfile.JdbcFileSourceFactory;


@TestMethodOrder(OrderAnnotation.class)
public abstract class FileSourceAbstractTestClass {

	
	public enum Permissions {
		OwnerRead('r'),
		OwnerWrite('w'),
		OwnerExecute('x'),

		GroupRead('r'),
		GroupWrite('w'),
		GroupExecute('x'),

		OtherRead('r'),
		OtherWrite('w'),
		OtherExecute('x');

	    public final char label;

	    private Permissions(char label) {
	        this.label = label;
	    }
	}

	interface TestAction {
		void doSomthing(FileSource dir);
	}

	public static String localTestFileDirPath ;
	public static String remoteTestFileDirPath ;
	public static String localCacheDirPath;	
	public static FileSourceFactory factory;
	public static boolean verbose = false;

	public static Server server;
	public static String databaseName0 = "mainDb";
	public static String databasePath0 =  "mem:mainDb";
	public static String databaseName1 =  "standbyDb";
	public static String databasePath1 =  "mem:standbyDb";
	//public static String databaseUrl ="jdbc:hsqldb:hsql://localhost:9001/mainDb";
	public static String databaseDriverName = "org.hsqldb.jdbc.JDBCDriver";
	public static int timeout = 4000;
	public static String databaseUser = "SA";
	public static String databasePassword = "";
	


	public boolean enableViewer = false;



	public static void setUp(int port) throws Exception {
		String databaseUrl ="jdbc:hsqldb:hsql://localhost:"+port+"/mainDb"+port;
		localTestFileDirPath = "TestFiles";
		localCacheDirPath = "target/TestFiles";
		remoteTestFileDirPath = "TestFiles";
		
		startHSQLDB(port,databaseUrl);

		factory = FileSourceFactory.getFileSourceFactory(JdbcFileSourceFactory.FACTORY_ID);
		Properties p = factory.getConnectProperties();

		p.setProperty(JdbcFileSourceFactory.JDBC_DRIVER, databaseDriverName);
		p.setProperty(JdbcFileSourceFactory.JDBC_URL, databaseUrl);
		p.setProperty(JdbcFileSourceFactory.JDBC_USERID, databaseUser);
		p.setProperty(JdbcFileSourceFactory.JDBC_PASSWORD, databasePassword);

		assertTrue(factory.connect(p));
		try(InputStream in = TestJdbcFileSource.class.getResourceAsStream("/Hsqldb.ddl")) {
			byte[] data = in.readAllBytes();
			
			String ddl = new String(data);
			try(Connection con = ((JdbcFileSourceFactory)factory).getConnection()) {
				try(Statement stmt = con.createStatement()) {
					stmt.executeUpdate(ddl);					
				}
			}
		}
		
		
	/*
		org.hsqldb.util.DatabaseManagerSwing.main(new String[0]);
		
		System.out.println("Waiting for enter pressed");
		System.in.read();
		
		while(true) {
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// Not implemented
				e.printStackTrace();
			}
		}
	*/
	}
	
	public void pauseAndExamine(FileSource dir) throws IOException {
		if( enableViewer) {
			org.hsqldb.util.DatabaseManagerSwing.main(new String[0]);
			while(enableViewer) {
				try {
					Thread.sleep(10000);
				} catch (InterruptedException e) {
					// Not implemented
					e.printStackTrace();
				}
			}
			/*
			if( dir != null && dir.exists()) {
				FileSourceFactory.setDefaultFactory(dir.getFileSourceFactory());
				FileSourceChooserDialog d = new FileSourceChooserDialog();
				d.setModal(true);
				d.openDialogAsBrowser();
				if( verbose) System.out.println("Browsing complete");
			}
			*/
		}
	}
	

	private static void startHSQLDB(int port,String databaseUrl) throws Exception {
		server = new Server();
		// turn off HSQLDB logging
		server.setLogWriter(new PrintWriter(new OutputStream() {

			@Override
			public void write(int b) throws IOException {


			}
		}));

		server.setSilent(true);
		server.setDatabaseName(0, databaseName0+port);
		server.setDatabasePath(0, databasePath0+port);
		server.setDatabaseName(1, databaseName1+port);
		server.setDatabasePath(1,databasePath1+port);
		server.setPort(port); 

		server.start();

		long start = System.currentTimeMillis();

		while(System.currentTimeMillis()-start < timeout && server.isNotRunning()) {
			Thread.sleep(100);
		}

		assertTrue(!server.isNotRunning()," Hsqldb did not start within timeout");
		Class.forName(databaseDriverName);
		if( verbose) System.out.println("HsqlServer is running");
		DriverManager.getConnection(databaseUrl, databaseUser, databasePassword);
		if( verbose) System.out.println("HsqlServer connection created");
		
		/*
		org.hsqldb.util.DatabaseManagerSwing.main(new String[0]);
		while(true) {
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				// Not implemented
				e.printStackTrace();
			}
		}
		*/
		
		
	}

	
	static void tearDown() throws Exception {
		if( factory != null){
			factory.disConnect();
			factory = null;
		}
		if( server != null) {
			server.stop();			
		}
		server = null;
	}


	public static void traverseDir(FileSource dir,TestAction action) throws IOException {
		if(verbose) System.out.println(format(dir));
		if( action != null ) {
			action.doSomthing(dir);
		}
		if( dir.isDirectory()) {
			FileSource [] kids = dir.listFiles();
			if( kids != null ) {
				for(FileSource file : kids) {
					traverseDir(file,action);
				}
			}
		}		
	}

	public static void deleteAll(FileSource file) throws IOException {
		if( file.isDirectory()) {
			for(FileSource child : file.listFiles()) {
				deleteAll(child);
			}
		}
		assertTrue(
				file.delete()
				,"Can't delete "+file
				);
	}

	public static String format(FileSource dir) throws IOException {
		String ret = (String.format("factory=%s type=%s exists=%s path=%s read=%s write=%s size=%d",
				dir.getFileSourceFactory().getTypeId(),
				dir.isFile()?"File":dir.isDirectory()?"Dir":"Undefined",
						dir.exists() ? "true":"false",
								dir.getAbsolutePath(),
								dir.canRead()?"true":"false",
										dir.canWrite()?"true":"false",
												dir.length()
				)
				);

		return ret;

	}

	public static void compare(String name,FileSource source, FileSource target) throws IOException {
		assertTrue(source.exists(),"Source file does not exist ("+source.getName()+")");
		assertTrue(
				target.exists(),
				"Target file does not exist ("+
		target.getName()+")"
		
		);

		assertEquals(target.isDirectory(),source.isDirectory(),name+" are not the same type");

		if( source.isDirectory()) {
			FileSource [] kids1 = source.listFiles();
			FileSource [] kids2 = target.listFiles();
			assertEquals(kids1.length,kids2.length,name+" does not have the same number of kids");
			
			for(int idx=0;idx <  kids1.length; idx++ ) {
				compare(name,kids1[idx],kids2[idx]);
			}

		} else {
			assertEquals(source.length(), target.length(),name+" lens are not eq");
			try(InputStream sourceIn = source.getInputStream()) {
				try(InputStream targetIn  = target.getInputStream()) {
					compare(name,sourceIn,targetIn);
				}
			}
		}
	}

	/**
	 * Compare the bytes of two input streams
	 * 
	 * @param in1
	 * @param in2
	 * @throws IOException
	 */
	public static void compare(String name,InputStream in1, InputStream in2) throws IOException {
		//  in1 & in2 will be closed by the java try / auto close in the calling function
		// use a small buffer to get multiple reads 
		BufferedInputStream bin1 = new BufferedInputStream(in1);
		BufferedInputStream bin2 = new BufferedInputStream(in2);

		int ch = bin1.read();
		int pos = 0;
		while( ch > 0) {
			assertEquals(ch, bin2.read(),name+" compare pos="+pos);
			pos++;
			ch = bin1.read();				
		}
		assertEquals(ch, bin2.read(),name+" compare pos="+pos);

	}

	public static void copy(FileSource from, FileSource to) throws IOException {
		FileSource parent = to.getParentFile();
		if( parent != null && !parent.exists()) {
			parent.mkdirs();
		} 
		if( from.isDirectory()) {
			FileSource [] kids = from.listFiles();
			if( kids != null ) {
				for(FileSource f : kids) {
					copy(f,to.getChild(f.getName()));
				}
			}
		} else {
			try(InputStream in = from.getInputStream()) {
				try(OutputStream out = to.getOutputStream()) {
					copy(in,out);		
				}
			}
			
		}
	}

	// 336-518-5261 Alan's Diana

	public static void copy(InputStream in, OutputStream out) throws IOException {
		// use a small buffer to get multiple reads 
		byte [] data = new byte[1024];
		int got = 0;

		try {
			while( (got=in.read(data)) >= 0) {
				if( got > 0 ) {
					out.write(data,0,got);
				}
			}

		} finally {
			try {
				out.close();
			} catch (Exception e) {
			}
			try {
				in.close();
			} catch (Exception e) {
			}

		}
	}


	public void renameAndValidate(FileSource source, FileSource target) throws IOException {
		assertTrue(
				source.renameTo(target)
				,"Can't rename "+source+" to "+target);			
		assertTrue(
				target.exists()
				,"New file does not exist after rename");
		assertFalse(
				source.exists()
				,"remoteFile still exists after rename");		
	}

	
	
	public boolean setPermission(Permissions p, FileSource file,boolean b) throws IOException {
		boolean ret = false;
		switch (p) {
		case OwnerRead: 	ret = file.setOwnerReadable(b); break;
		case OwnerWrite:	ret = file.setOwnerWritable(b); break;
		case OwnerExecute:	ret = file.setOwnerExecutable(b); break;

		case GroupRead: 	ret = file.setGroupReadable(b); break;
		case GroupWrite:	ret = file.setGroupWritable(b); break;
		case GroupExecute:	ret = file.setGroupExecutable(b); break;

		case OtherRead: 	ret = file.setOtherReadable(b); break;
		case OtherWrite:	ret = file.setOtherWritable(b); break;
		case OtherExecute:	ret = file.setOtherExecutable(b); break;

		default:
			throw new RuntimeException("Invalid permision="+p);
		}
		
		return ret;
	}
	
	public boolean getPermission(Permissions p, FileSource file) throws IOException {
		boolean ret = false;
		switch (p) {
		case OwnerRead:    ret = file.canOwnerRead(); break;
		case OwnerWrite:   ret = file.canOwnerWrite(); break;
		case OwnerExecute: ret = file.canOwnerExecute(); break;
		
		case GroupRead:    ret = file.canGroupRead(); break;
		case GroupWrite:   ret = file.canGroupWrite(); break;
		case GroupExecute: ret = file.canGroupExecute(); break;
		
		case OtherRead:    ret = file.canOtherRead(); break;
		case OtherWrite:   ret = file.canOtherWrite(); break;
		case OtherExecute: ret = file.canOtherExecute(); break;
		
		default:
			throw new RuntimeException("Invalid permision="+p);			
		}
		return ret;
	}
	
	public void changeAndValidatePermission(Permissions p, FileSource file) throws IOException {
		
		//Get the current value		
		boolean b = getPermission(p, file);
		
		// toggle it 
		assertTrue(setPermission(p, file, !b),"set permission failed p="+p);
		boolean b2 = getPermission(p, file);
		assertEquals(b2, !b,"permision did not change p="+p);
		
		// Set it back
		assertTrue(setPermission(p, file, b),"reset permission failed p="+p);		
		assertEquals(getPermission(p, file), b,"permision did not change back to original p="+p);
		
		
	}

}
