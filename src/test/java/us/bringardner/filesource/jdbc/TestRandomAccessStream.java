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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import us.bringardner.io.filesource.FileSource;
import us.bringardner.io.filesource.FileSourceRandomAccessStream;
import us.bringardner.io.filesource.jdbcfile.JdbcFileSource;
import us.bringardner.io.filesource.jdbcfile.JdbcFileSourceFactory;
import us.bringardner.io.filesource.jdbcfile.JdbcRandomAccessIoController;



@TestMethodOrder(OrderAnnotation.class)
public class TestRandomAccessStream extends FileSourceAbstractTestClass {


	enum Action{Write,Read,Seek,SetLength}
	class Entry {
		Action action;
		long value;
		long pointerBefore;
		long pointerAfter;
		long jpointerBefore;
		long jpointerAfter;


		public Entry(Action action) {
			this.action = action;
		}
	}
	
	static int chunk_size = 100;
	static long targetFileSize=500;
	static String testDataString = "0123456789";
	static byte [] testData = testDataString.getBytes();
	static JdbcFileSource file;
	static JdbcFileSource testDir;

	@BeforeAll
	public static void setup() throws Exception {
		FileSourceAbstractTestClass.setUp(9002);
		
		
		FileSource root = factory.listRoots()[0];
		testDir = (JdbcFileSource) root.getChild("UnitTestDir");
		assertTrue(testDir.mkdirs());

		if (factory instanceof JdbcFileSourceFactory) {
			JdbcFileSourceFactory jdbc = (JdbcFileSourceFactory) factory;
			jdbc.setChunk_size(chunk_size);
		}

	
	}

	@AfterAll
	public static void teardown() throws Exception {
		
		if( file.exists()) {
			file.delete();
		}
		FileSourceAbstractTestClass.tearDown();
		
	}

	@Test
	@Order(1)
	public void testCreateFile() throws IOException {
		file = (JdbcFileSource) testDir.getChild("RamUnitTest.txt");
		if( file.exists()) {
			assertTrue(file.delete(),"Could not delete exesting file");
		}

		try (FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(file), "r")){
			assertTrue(false,"should generate an error opening a non existed file for read");
		} catch (Exception e) {
		}


		StringBuilder buf = new StringBuilder();

		try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(file), "rw")) {
			assertTrue(file.exists(),"should create a new file when opening a non existed file for rw");
			while(
					ram.length()
					<targetFileSize) {
				ram.write(testData);
				buf.append(testDataString);
			}
		} catch (Exception e) {
			System.out.println(e);
		}

		int sz = (int)file.length();
		byte buffer [] = new byte[sz];
		try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(file), "rw")) {
			ram.readFully(buffer);
		}
		
		
		
		String text = new String(buffer);
		
		assertTrue(buf.toString().equals(text),"Did not read the expected value");
	}

	@Test
	@Order(2)
	public void testSeekAndRead() throws IOException {
		int len = (int)file.length();

		try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(file), "rw")) {
			for(int idx = 0; idx<len; idx++) {
				int pos = idx % testData.length;
				int expect = testData[pos];				
				ram.seek(idx);
				int actual = ram.read();

				assertEquals(expect, actual,"Sequential forward seek read the wrong value after seek to "+idx+" pos="+pos);
			}

			for(int idx = len-1; idx>=0; idx--) {
				int pos = idx % testData.length;
				int expect = testData[pos];				
				ram.seek(idx);
				int actual = ram.read();
				assertEquals(expect, actual,"Sequential reverese seek read the wrong value after seek to "+idx+" pos="+pos);
			}

		}

		try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(file), "rw")) {
			int tries = 0;
			Random r = new Random();
			int targetTries = 1000;

			while( tries++ < targetTries) {
				int idx = r.nextInt(len);
				int pos = idx % testData.length;
				int expect = testData[pos];				
				ram.seek(idx);
				int actual = ram.read();
				assertEquals(expect, actual,"Read the wrong value after seek to "+idx+" pos="+pos+" tries="+tries);
			}
		}
	}


	@Test
	@Order(3)
	public void testSetLength() throws IOException {
		int len = (int)file.length();
		assertTrue(len > 0," Test file is empty" );

		try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(file), "rw")) {
			ram.setLength(len*2);
			assertEquals(len*2,file.length(),"Wrong value set len to *2" );
			ram.setLength(len);
			assertEquals(len,file.length(),"Wrong value set len to len" );
			ram.setLength(len/2);
			assertEquals(len/2,file.length(),"Wrong value set len to 1/2" );

		}		
	}

	@Test
	@Order(4)
	public void compareWithJava() throws IOException {
		File jdir = new File("target").getAbsoluteFile();
		File jfile = new File(jdir,"RamFile.txt");
		if( jfile.exists()) {
			assertTrue(jfile.delete(),"Can't delet existing java file "+jfile);
		}

		if( file.exists()) {
			assertTrue(file.delete(),"Can't delet existing jdbc file "+file);
		}

		// make both file the same
		try(RandomAccessFile jram = new RandomAccessFile(jfile, "rw")){
			try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(file), "rw")) {
				int cnt = 0;
				while(cnt < targetFileSize){
					jram.write(testData);
					ram.write(testData);
					cnt+= testData.length;
				}		

			}
		}

		boolean replay = false;
		if( !replay ) {
			testAndCompare(file,jfile);
		} else {
			replay(file,jfile);
		}
		assertTrue(jfile.delete(),"Can't delet existing java file "+jfile);



	}

	private void replay(JdbcFileSource file2, File jfile) throws IOException {
		String steps[] = 
				(
						 //"End engths donte match Steps = 20\n"
						 "	Seek,631,ram(0,631) jram(0,631)\n"
						 + "	Read,0,ram(631,631) jram(631,631)\n"
						 + "	Read,0,ram(631,631) jram(631,631)\n"
						 + "	SetLength,345,ram(631,345) jram(631,345)\n"
						 + "	Write,0,ram(345,355) jram(345,355)\n"
						 + "	Read,0,ram(355,355) jram(355,355)\n"
						 + "	SetLength,283,ram(355,283) jram(355,283)\n"
						 + "	Read,0,ram(283,283) jram(283,283)\n"
						 + "	SetLength,295,ram(283,283) jram(283,283)\n"
						 + "	SetLength,378,ram(283,283) jram(283,283)\n"
						 + "	Write,0,ram(283,293) jram(283,293)\n"
						 + "	Write,0,ram(293,303) jram(293,303)\n"
						 + "	SetLength,229,ram(303,229) jram(303,229)\n"
						 + "	Write,0,ram(229,239) jram(229,239)\n"
						 + "	SetLength,183,ram(239,183) jram(239,183)\n"
						 + "	Read,0,ram(183,183) jram(183,183)\n"
						 + "	Write,0,ram(183,193) jram(183,193)\n"
						 + "	Read,0,ram(193,193) jram(193,193)\n"
						 + "	Seek,135,ram(193,135) jram(193,135)\n"
						 + "	Read,0,ram(135,145) jram(135,145)\n"
						 + ""
						
						).split("\n");

		System.out.println("steps = "+steps.length);
		byte [] data = new byte[testData.length];
		byte [] jdata = new byte[testData.length];
		System.out.println("fileid="+file.getFileId());

		try(RandomAccessFile jram = new RandomAccessFile(jfile, "rw")){
			try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(file), "rw")) {

				@SuppressWarnings("unused")
				Action last = null;
				int cnt = 0;
				for(String step : steps) {
					if( cnt == steps.length-1) {
						System.out.println("Ready");
					}
					String [] parts = step.split("[,]");
					Action a = Action.valueOf(parts[0].trim());
					int value = Integer.parseInt(parts[1].trim());
					switch (a) {
					case Read:
						int ji1=jram.read(jdata);System.out.println(""+cnt+" "+a+": jram len="+jram.length()+" ptr="+jram.getFilePointer());
						String js1=new String(jdata);
						int i1=ram.read(data);
						String s1=new String(data);
						if( ji1 != i1)  {
							System.out.println("i1="+i1+" ji1="+ji1);
						} else if(!s1.equals(js1)){
							System.out.println("s1="+s1+" js1="+js1);
						}
						
						System.out.println(""+cnt+" "+a+": ram len="+
								ram.length()+" ptr="+
								ram.getFilePointer());
						break;
					case Write:
						jram.write(testData);System.out.println(""+cnt+" "+a+": jram="+jram.length()+" "+jram.getFilePointer());
						System.out.println("before "+cnt+" "+a+": ram="+
								ram.length()+" "+
								ram.getFilePointer());
						
						ram.write(testData);													
						System.out.println("after "+cnt+" "+a+": ram="+
								ram.length()+" "+
								ram.getFilePointer());
						break;
					case Seek:
						jram.seek(value);System.out.println(""+cnt+" "+a+" "+value+" jram="+jram.length()+" "+jram.getFilePointer());
						ram.seek(value);
						System.out.println(""+cnt+" "+a+" "+value+": ram="+
								ram.length()+" "+
								ram.getFilePointer());
						break;
					case SetLength:
						jram.setLength(value);
						System.out.println(""+cnt+" "+a+" "+value+": jram="+jram.length()+" "+jram.getFilePointer());
						ram.setLength(value);				
						System.out.println(""+cnt+" "+a+" "+value+": ram="+
						ram.length()
						+" "+ram.getFilePointer());
						break;
					default:
						break;
					}
					cnt++;
					last = a;
				}
				System.out.println("jram length="+jram.length()+" "+jram.getFilePointer());
				System.out.println("ram length="+ram.length()+" "+ram.getFilePointer());
			}
			
		}
	}

	private void testAndCompare(JdbcFileSource file, File jfile) throws IOException {

		Random r = new Random();
		byte [] data = new byte[testData.length];
		byte [] jdata = new byte[testData.length];
		List<Entry> list = new ArrayList<>();

		try(RandomAccessFile jram = new RandomAccessFile(jfile, "rw")){
			try(FileSourceRandomAccessStream ram = new FileSourceRandomAccessStream(new JdbcRandomAccessIoController(file), "rw")) {

				int actions = 20;
				while((--actions) >= 0) {
					int ai = r.nextInt(Action.values().length);
					Action action = Action.values()[ai];
					Entry entry = new Entry(action);

					list.add(entry);
					entry.jpointerBefore = jram.getFilePointer();
					entry.pointerBefore = ram.getFilePointer();

					switch(action) {
					case Read:
						int ji = jram.read(jdata);
						int i = -1;
						try {
							i  = ram.read(data);	
						} catch (Exception e) {
							print("Exception", list);
							throw e;
						}
						
						if( ji != i) {
							print("data Length",list);
						}
						assertEquals(ji, i,"Read count not the same");
						for (int idx = 0; idx < jdata.length; idx++) {
							if( jdata[idx]!= data[idx]) {
								print("data at "+idx,list);
							}
							assertEquals(jdata[idx], data[idx],"Data wrong at idx="+idx+" actions = "+list);
						}

						break;

					case Write:
						jram.write(testData);
						ram.write(testData);					
						break;
					case Seek:
						long len = ram.length();
						if( len == 0 ) {
							len = 10;
						}
						long bounds = (long)(len*1.5);
						long pos = r.nextInt((int)bounds);
						entry.value = pos;
						jram.seek(pos);
						ram.seek(pos);
						break;
					case SetLength:
						len = ram.length();
						if( len == 0 ) {
							len = 10;
						}

						bounds = (long)(len*1.5);
						pos = r.nextInt((int)bounds);
						entry.value = pos;
						jram.setLength(pos);
						try {
							ram.setLength(pos);	
						} catch (Exception e) {
							print(e.toString(),list);
							throw e;
						}


						if( jram.length() != ram.length()) {
							print("file length",list);
						}
						assertEquals(jram.length(), ram.length(),"Length not the same actions="+list);
						break;
					default:throw new IOException("Unknown action="+action);
					}
					entry.jpointerAfter = jram.getFilePointer();
					entry.pointerAfter = ram.getFilePointer();
					if( jram.getFilePointer() != ram.getFilePointer()) {
						print("pointer",list);
					} 

					assertEquals(jram.getFilePointer(), ram.getFilePointer(),"Filepointer not the same actions="+list);

				}
				/**
				 * RandomAccessFile seems to write data to the storage immediately.
				 * The overhead of that is too high for sending data over the wire so
				 * JdbcRam.. will delay potentially until close.  So there are times
				 * when the file lengths won;t match.  But, they should match once jdbc stream is closed.  
				 */
				if( jram.length()!= ram.length()) {
					print("End engths donte match", list);
				}
				assertEquals(jram.length(), ram.length(),"Length not the same actions="+list);
			} catch (Exception e) {
				print(e.toString(),list);
				throw e;
			}

		}
	}

	private void print(String type, List<Entry> list) {
		System.out.println(type+" Steps = "+list.size());
		for(Entry e: list) {
			System.out.println("\t"+e.action+","+e.value+",ram("+e.pointerBefore+","+e.pointerAfter+") jram("+e.jpointerBefore+","+e.jpointerAfter+")");
		}

	}
}
