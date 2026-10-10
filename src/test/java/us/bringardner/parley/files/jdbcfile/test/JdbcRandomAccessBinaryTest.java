package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessStream;

/**
 * Random access returns the bytes as 0..255, like RandomAccessFile. read() used to return the
 * signed byte, so anything of 0x80 or more came back negative, and 0xFF was -1: the end of the
 * file. A block read stops at the first of those, so it returned one byte, or -1 in the middle
 * of a file.
 */
public class JdbcRandomAccessBinaryTest {

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9010);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	private static FileSource file(byte[] data) throws Exception {
		FileSource d = JdbcTestServer.factory().createFileSource("/binaryRa-" + System.nanoTime());
		assertTrue(d.mkdirs());
		FileSource f = d.getChild("data.bin");
		try (OutputStream out = f.getOutputStream()) {
			out.write(data);
		}
		return f;
	}

	@Test
	void singleBytesAreUnsigned() throws Exception {
		byte[] data = new byte[256];
		for(int i = 0; i < 256; i++) {
			data[i] = (byte) i;
		}
		FileSource f = file(data);
		try (IRandomAccessStream r = f.getRandomAccessStream("r")) {
			for(int i = 0; i < 256; i++) {
				assertEquals(i, r.read(), "byte " + i);
			}
			assertEquals(-1, r.read(), "and the end of the file is still -1");
		}
	}

	@Test
	void blockReadsDoNotStopAtAHighByte() throws Exception {
		byte[] data = new byte[10_000];
		new Random(1).nextBytes(data);
		data[0] = (byte) 0xFF;
		data[5000] = (byte) 0x80;
		FileSource f = file(data);
		try (IRandomAccessStream r = f.getRandomAccessStream("r")) {
			byte[] got = new byte[100];
			r.seek(0);
			assertEquals(100, r.read(got));
			assertArrayEquals(Arrays.copyOf(data, 100), got);
			r.seek(5000);
			assertEquals(100, r.read(got));
			assertArrayEquals(Arrays.copyOfRange(data, 5000, 5100), got);
		}
	}

	@Test
	void everyByteValueSurvivesAWriteAndAReadBack() throws Exception {
		byte[] data = new byte[3 * 256];
		for(int i = 0; i < data.length; i++) {
			data[i] = (byte) (i * 7);
		}
		FileSource f = file(new byte[0]);
		try (IRandomAccessStream rw = f.getRandomAccessStream("rw")) {
			rw.write(data);
			rw.seek(0);
			byte[] back = new byte[data.length];
			int got = 0;
			while( got < back.length ) {
				int n = rw.read(back, got, back.length - got);
				assertTrue(n > 0, "read " + n + " at " + got);
				got += n;
			}
			assertArrayEquals(data, back);
		}
	}
}
