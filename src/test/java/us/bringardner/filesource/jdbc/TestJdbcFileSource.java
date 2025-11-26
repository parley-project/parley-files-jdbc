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
 * ~version~V001.01.47-V000.01.28-V000.01.25-V000.01.23-V000.01.22-V000.01.18-V000.01.10-V000.01.06-V000.01.02-V000.01.01-V000.00.05-V000.00.03-V000.00.01-V000.00.00-
 */
package us.bringardner.filesource.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceFactory;


@TestMethodOrder(OrderAnnotation.class)
public class TestJdbcFileSource extends FileSourceAbstractTestClass {

	
	
	@BeforeAll
	public static void setUp() throws Exception {
				FileSourceAbstractTestClass.setUp(9001);
	}

	@AfterAll
	static void tearDownAfterClass() throws Exception {
		FileSourceAbstractTestClass.tearDown();
	}

	

	
	
	@Test
	@Order(13)
	public void testAppend() throws IOException {
		
		
		
		FileSource remoteDir = factory.createFileSource(remoteTestFileDirPath);
		assertTrue(remoteDir.mkdirs(),"Could not create remote testDir");
		byte [] data = "happy\n".getBytes();
		FileSource file = remoteDir.getChild("Happy.txt");
		OutputStream out = file.getOutputStream();
			for(int idx=0; idx< 10; idx++ ) {
				out.write(data);
			}						
		out.close();
		enableViewer=true;
		
		//pauseAndExamine(remoteDir);
		long len1 = file.length();
		assertEquals(data.length*10, len1,"First length wrong");
		
		out = file.getOutputStream(true);
			for(int idx=0; idx< 10; idx++ ) {
				out.write(data);
			}						
		out.close();
		long len2 = file.length();
		assertEquals(data.length*20, len2,"Second length wrong");
		
		assertTrue(file.delete(),"Could not delete "+file);
		
	}

	
	@Test()
	@Order(10)
	public void test2() throws Exception {
		
		
		
		FileSource localTestDir = FileSourceFactory.getDefaultFactory().createFileSource(localTestFileDirPath);
		assertTrue( localTestDir.exists(),"Local test dir does not exist");
		assertTrue(localTestDir.isDirectory(),"Local test dir is not a dir");
		FileSource remoteDir = factory.createFileSource(remoteTestFileDirPath);
		assertTrue(remoteDir.mkdirs(),"Could not create remote testDir");
		if( verbose) System.out.println("Remote dir created");

		
		// get the files
		FileSource[] list = localTestDir.listFiles();

		//  copy each to the remote system
		for (FileSource src : list) {
			if( src.isFile()) {				
				copy(src,remoteDir.getChild(src.getName()));
			}
		}
		if( verbose) System.out.println("Copy done");
		pauseAndExamine(remoteDir);
		
		//  Validate everything
		for (FileSource src : list) {
			if( src.isFile()) {
				String name2 = src.getName();
				compare(name2,src,remoteDir.getChild(name2));
			}
		}
		if( verbose) System.out.println("Compare done");

		


		//  get the list from the remote factory
		list = remoteDir.listFiles();

		//  Remove them
		for (FileSource src : list) {
			assertTrue(src.delete(),"Can't delete the test directory.");
		}

		list = remoteDir.listFiles();
		if( verbose) System.out.println("Delete files done...  list2="+list.length);

		if( list.length > 0 ) {
			//.fuse_hidden
			//  unfortunate bug in ubuntu that creates a hidden file when you delete from a directory
		} else { 
			// now remove the remote test directory
			boolean done = remoteDir.delete();
			assertTrue(done,"Can't delete the test directory.");
		}
		if( verbose) System.out.println("All delete done");



	}


	
	@Test
	@Order(1)
	public void testRoots() throws IOException {
		
		FileSource [] roots = factory.listRoots();
		assertNotNull(roots,"Roots are null");
		assertTrue(roots.length>0,"No roots files ");

	}

	@Test
	@Order(2) 
	public void replicateTestDir() throws IOException {
		
		FileSource _localDir = FileSourceFactory.getDefaultFactory().createFileSource(localTestFileDirPath);
		assertTrue(_localDir.isDirectory(),"local test dir does not exist ="+_localDir);

		FileSource cacheDir = FileSourceFactory.getDefaultFactory().createFileSource(localCacheDirPath);
		if( cacheDir.exists()) {
			deleteAll(cacheDir);			
		}
		assertFalse(cacheDir.exists(),"local cache dir already exists ="+cacheDir);

		//  Make a copy of the local test directory
		copy(_localDir,cacheDir);

		FileSource remoteDir = factory.createFileSource(remoteTestFileDirPath);
		traverseDir(remoteDir, null);
		if( !remoteDir.exists()) {
			assertTrue(remoteDir.mkdirs(),"Cannot create remote directory"+remoteDir);			
		}
		traverseDir(remoteDir, null);



		for(FileSource source : cacheDir.listFiles()) {
			String nm = source.getName();
			FileSource dest = remoteDir.getChild(nm);
			copy(source, dest);
			compare("Copy to remote dir", source, dest);			
		}
		traverseDir(remoteDir, null);

		
		if(verbose) System.out.println("Rename files\n");
		for(FileSource remoteFile : remoteDir.listFiles()) {
			String fileName = remoteFile.getName();
			FileSource localFile = cacheDir.getChild(fileName);
			compare(fileName, localFile, remoteFile);
			FileSource remoteParent = remoteFile.getParentFile();
			
			FileSource renamedFile = remoteParent.getChild(fileName+".changed");
			
			renameAndValidate(remoteFile,renamedFile);
						
			compare(fileName, localFile, renamedFile);
			
			renameAndValidate(renamedFile,remoteFile);
			
			
			compare(fileName, localFile, remoteFile);
			
		}



		//  delete the roots files
		deleteAll(cacheDir);
		deleteAll(remoteDir);
		traverseDir(remoteDir, null);

	}

	@Test
	@Order(3)
	public void testPermissions() throws IOException {
		FileSource remoteDir = factory.createFileSource(remoteTestFileDirPath);
		if( !remoteDir.exists()) {
			assertTrue(remoteDir.mkdirs(),"Can't create dirs for "+remoteTestFileDirPath);
		}
		
		FileSource file = remoteDir.getChild("TestPermissions.txt");
		try(OutputStream out = file.getOutputStream()) {
			out.write("Put some data in the file".getBytes());
		}
		

		for(Permissions p : Permissions.values()) {
			//  if we turn off owner write we won't be able to turn it back on.
			if( p != Permissions.OwnerWrite) {
				changeAndValidatePermission(p,file);
			}
		}
		
		assertTrue(file.delete(),"Can't delete "+file);
		
	}


	@Test
	@Order(12)
	public void testThree() throws Exception {
		
		 //user.name
		// u=rwx,g=rx,o=rx				022
		//System.getProperties().list(System.out);
		//File file = new File(testFileDir).getCanonicalFile();
		File file = File.createTempFile("prefix", "suffix");
		FileOutputStream out = new FileOutputStream(file);
		out.write("Hello\n".getBytes());
		out.close();
		PosixFileAttributeView view2 = Files.getFileAttributeView(file.toPath(), PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
		if (view2 != null) {
			PosixFileAttributes at = view2.readAttributes();
			at.creationTime();
			at.fileKey();
			at.group();
			at.owner();
			Set<PosixFilePermission> perm = at.permissions();
			for(PosixFilePermission p : perm) {
				switch (p) {
				case OWNER_READ:
				case OWNER_WRITE:
				case OWNER_EXECUTE:
				case OTHERS_READ:
				case OTHERS_WRITE:
				case OTHERS_EXECUTE:
				case GROUP_READ:
				case GROUP_WRITE:
				case GROUP_EXECUTE:
					
					break;
				default:
					throw new IllegalArgumentException("Unexpected value: " + p);
				}
				
			}
		}
		UserPrincipal owner = java.nio.file.Files.getOwner(file.toPath());
		if( verbose) System.out.println("owner="+owner);
		if( verbose) System.out.println(""+file.canExecute()+" "+file.canRead()+" "+file.canWrite());
		file.delete();
	}


	


}
