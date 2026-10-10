package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.StreamOptions;

/**
 * The input stream reads and skips a row at a time. The file here is stored in 1000 byte rows, so
 * every read below crosses row boundaries at an odd place.
 */
public class JdbcInputStreamBlockReadTest {

	private static final int ROW = 1000;
	private static byte[] data;
	private static FileSource file;

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9013);
		data = new byte[10_357];
		new Random(3).nextBytes(data);
		data[0] = (byte) 0xFF;
		data[ROW] = (byte) 0x80;
		FileSource d = JdbcTestServer.factory().createFileSource("/blockRead-" + System.nanoTime());
		assertTrue(d.mkdirs());
		file = d.getChild("data.bin");
		try (OutputStream out = file.getOutputStream(false, StreamOptions.chunk(ROW))) {
			out.write(data);
		}
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	@Test
	void readAllBytesMatches() throws Exception {
		try (InputStream in = file.getInputStream()) {
			assertArrayEquals(data, in.readAllBytes());
		}
	}

	@Test
	void oddSizedBlocksCrossRows() throws Exception {
		try (InputStream in = file.getInputStream()) {
			byte[] got = new byte[data.length];
			byte[] block = new byte[777];
			int at = 0;
			int n;
			while( (n = in.read(block)) >= 0 ) {
				assertTrue(n > 0);
				System.arraycopy(block, 0, got, at, n);
				at += n;
			}
			assertEquals(data.length, at);
			assertArrayEquals(data, got);
			assertEquals(-1, in.read());
		}
	}

	@Test
	void skipThenRead() throws Exception {
		try (InputStream in = file.getInputStream()) {
			assertEquals(2500, in.skip(2500));
			assertEquals(data[2500] & 0xff, in.read());
			byte[] got = new byte[1500];
			assertEquals(1500, in.read(got));
			assertArrayEquals(Arrays.copyOfRange(data, 2501, 4001), got);
			assertEquals(data.length - 4001, in.skip(Long.MAX_VALUE));
			assertEquals(-1, in.read());
		}
	}

	@Test
	void startingPositions() throws Exception {
		for(long start : new long[] { 0, 1, ROW - 1, ROW, ROW + 1, 5555, data.length - 1, data.length }) {
			try (InputStream in = file.getInputStream(start)) {
				assertArrayEquals(Arrays.copyOfRange(data, (int) start, data.length), in.readAllBytes(), "from " + start);
			}
		}
	}

	@Test
	void zeroLengthReadAndEmptyFile() throws Exception {
		try (InputStream in = file.getInputStream()) {
			assertEquals(0, in.read(new byte[4], 0, 0));
		}
		FileSource empty = file.getParentFile().getChild("empty.bin");
		empty.createNewFile();
		try (InputStream in = empty.getInputStream()) {
			assertEquals(-1, in.read(new byte[10]));
			assertEquals(0, in.skip(10));
		}
	}
}
