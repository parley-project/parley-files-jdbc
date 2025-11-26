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
 * ~version~V001.01.47-
 */
package us.bringardner.filesource.jdbc;

import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.jdbcfile.JdbcFileSource;
import us.bringardner.io.filesource.jdbcfile.JdbcFileSourceFactory;
import us.bringardner.io.filesource.jdbcfile.JdbcRandomAccessIoController;


@TestMethodOrder(OrderAnnotation.class)
public class TestRandomAccessIoBuffer extends FileSourceAbstractTestClass {



	static int chunk_size = 100;
	static long targetFileSize=1000;
	static String testDataString = "0123456789";
	static byte [] testData = testDataString.getBytes();
	static FileSource file;
	static FileSource testDir;

	@BeforeAll
	public static void setup() throws Exception {
		FileSourceAbstractTestClass.setUp(9003);
		testDir = factory.createFileSource(remoteTestFileDirPath);
		assertTrue(testDir.mkdirs());
		if (factory instanceof JdbcFileSourceFactory) {
			JdbcFileSourceFactory jdbc = (JdbcFileSourceFactory) factory;
			jdbc.setChunk_size(chunk_size);
		}
	}

	@AfterAll
	public static void teardown() throws Exception {
		FileSourceAbstractTestClass.tearDown();
				
	}

	@Test
	@Order(1)
	public void testCreateFile() throws IOException {
		file = (JdbcFileSource) testDir.getChild("RamIoBuffer.txt");
		int cnt = 0;

		try(OutputStream out = file.getOutputStream()) {
			while( cnt < targetFileSize) {
				out.write(testData);
				cnt+=testData.length;
			}
		}

	

	}

	@Test
	@Order(2)
	public void testSeekAndRead() throws IOException {
		long pointer = 0;
		long len = file.length();
		try(JdbcRandomAccessIoController buf = new JdbcRandomAccessIoController((JdbcFileSource) file)){
			while( pointer < len) {
				int idx = ((int)pointer) % testData.length;
				int expect = testData[idx];
				int i = buf.read(pointer++);
				assertEquals((char)expect, (char)i,"Read not correct pointer="+pointer);
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		//  do some random reads
		Random r = new Random();
		int tries = 0;
		int doTries = 40;
		try(JdbcRandomAccessIoController buf = new JdbcRandomAccessIoController((JdbcFileSource) file)){
			while(tries < doTries) {
				long pos = r.nextInt((int)len);
				int idx = ((int)pos) % testData.length;
				int expect = testData[idx];
				int i = buf.read(pos);
				assertEquals((char)expect, (char)i,"Read not correct pos="+pos);
				tries ++;
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
	}


	@Test
	@Order(3)
	public void testSeekAndWrite() throws IOException {
		long len = file.length();
		Random r = new Random();
		Map<Long,Integer> changes = new HashMap<>();
		byte data [] = "abcdefghij".getBytes();
		
		int tries = 0;
		int doTries = 40;
		try(JdbcRandomAccessIoController buf = new JdbcRandomAccessIoController((JdbcFileSource) file)){
			while(tries < doTries) {
				long pos = r.nextInt((int)len);
				int idx = ((int)pos) % data.length;
				int expect = testData[idx];
				buf.write(pos, (byte) expect);
				changes.put(pos, expect);
				tries ++;
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		try(JdbcRandomAccessIoController buf = new JdbcRandomAccessIoController((JdbcFileSource) file)){
			for(long pos : changes.keySet()) {
				int idx = ((int)pos) % data.length;
				int expect = testData[idx];
				int i = buf.read(pos);
				assertEquals((char)expect, (char)i,"Changed Read not correct pos="+pos);				
			}
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		

		
		
	}
	
	@Test
	@Order(4)
	public void testWritePastEnd() throws IOException {
		long len = file.length();
		
		try(JdbcRandomAccessIoController buf = new JdbcRandomAccessIoController((JdbcFileSource) file)){
			long blen = buf.length();
			assertEquals(len, blen,"Starting lengths do not match");
			buf.write(blen+10,(byte) 'x');
			blen = buf.length();
			assertEquals(len+11, blen,"Add 10 lengths do not match");
			buf.save();
			long len2 = file.length();
			assertEquals(blen,len2,"Add 10 filoe lengths do not match");
			
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	
		 len = file.length();
		
		try(JdbcRandomAccessIoController buf = new JdbcRandomAccessIoController((JdbcFileSource) file)){
			buf.setLength(len+150);
			long len2 = file.length();
			assertEquals(len+150,len2,"set len +100 file lengths do not match");
			
			buf.setLength(len+10);
			long len3 = file.length();
			assertEquals(len+10,len3,"set back to len file lengths do not match");
			
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	
		
		
		
	}
	
}
