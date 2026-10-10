package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Properties;
import java.util.Set;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.ConnectionSettings;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.IRandomAccessStream;
import us.bringardner.parley.files.ISeekableInputStream;
import us.bringardner.parley.files.StreamOption;
import us.bringardner.parley.files.StreamOptions;
import us.bringardner.parley.files.jdbcfile.JdbcFileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * The connection's default size is for programmers: the connect property "bufferSize" (or
 * setBufferSize), else the JVM default, else 100 KB. No dialog shows it, it isn't written into
 * connections that didn't set it, and a value that can't be used never stops a connection.
 * <p>
 * A stream takes its chunk size when it is opened, from its StreamOptions or else the
 * connection's, and keeps it. Data already stored is read as it was written.
 */
public class JdbcBufferSizeTest {

	private static final String KEY = JdbcFileSourceFactory.PROP_BUFFER_SIZE;

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9008);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	@AfterEach
	void clearSystemProperty() {
		System.clearProperty(JdbcFileSourceFactory.SYSTEM_PROPERTY_BUFFER_SIZE);
	}

	// ------------------------------------------------------------ the setting

	@Test
	void defaultIs100K() {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		assertEquals(100 * 1024, JdbcFileSourceFactory.DEFAULT_BUFFER_SIZE);
		assertEquals(JdbcFileSourceFactory.DEFAULT_BUFFER_SIZE, f.getBufferSize());
		assertEquals(f.getChunk_size(), f.getBufferSize(), "the same value");
	}

	@Test
	void notShownToUsersAndNotWrittenUnlessSet() {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		assertNull(f.getConnectProperties().getProperty(KEY), "unset: saved connections follow the default");
		assertNull(ConnectionSettings.find(f.getConnectionSettings(), KEY), "never a setting in a dialog");

		f.setBufferSize(32 * 1024);
		assertEquals("32768", f.getConnectProperties().getProperty(KEY));
		assertNull(ConnectionSettings.find(f.getConnectionSettings(), KEY), "still not a setting");
	}

	@Test
	void keptInRangeButTheOlderSetterIsNot() {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		f.setBufferSize(1);
		assertEquals(JdbcFileSourceFactory.MIN_BUFFER_SIZE, f.getBufferSize());
		f.setBufferSize(Integer.MAX_VALUE);
		assertEquals(JdbcFileSourceFactory.MAX_BUFFER_SIZE, f.getBufferSize());
		// the tests use a chunk of 100 bytes to force many chunks
		f.setChunk_size(100);
		assertEquals(100, f.getBufferSize());
	}

	@Test
	void roundTripsThroughConnectPropertiesAndTheDialogPath() {
		JdbcFileSourceFactory a = new JdbcFileSourceFactory();
		a.setBufferSize(256 * 1024);
		Properties saved = ConnectionSettings.forConnect(a.getConnectionSettings(), a.getConnectProperties());
		assertEquals("262144", saved.getProperty(KEY));

		JdbcFileSourceFactory b = new JdbcFileSourceFactory();
		b.setConnectionProperties(saved);
		assertEquals(256 * 1024, b.getBufferSize());
		assertEquals("262144", b.getConnectProperties().getProperty(KEY), "still explicit after loading");

		assertEquals(JdbcFileSourceFactory.DEFAULT_BUFFER_SIZE, new JdbcFileSourceFactory().getBufferSize());
	}

	@Test
	void anEmptyOrBadValueKeepsTheSizeAndDoesNotThrow() {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		Properties p = f.getConnectProperties();
		p.setProperty(KEY, "");
		f.setConnectionProperties(p);
		assertEquals(JdbcFileSourceFactory.DEFAULT_BUFFER_SIZE, f.getBufferSize());
		assertNull(f.getConnectProperties().getProperty(KEY), "empty is unset");

		p.setProperty(KEY, "lots");
		f.setConnectionProperties(p);
		assertEquals(JdbcFileSourceFactory.DEFAULT_BUFFER_SIZE, f.getBufferSize());
		assertNull(f.getConnectProperties().getProperty(KEY));

		p.setProperty(KEY, "0");
		f.setConnectionProperties(p);
		assertEquals(JdbcFileSourceFactory.MIN_BUFFER_SIZE, f.getBufferSize(), "kept within range");
	}

	@Test
	void aMissingValueDoesNotResetAnExplicitOne() {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		f.setBufferSize(16 * 1024);
		Properties p = f.getConnectProperties();
		p.remove(KEY);
		f.setConnectionProperties(p);
		assertEquals(16 * 1024, f.getBufferSize());
	}

	@Test
	void jvmDefaultAppliesAndIsNotWrittenOut() {
		System.setProperty(JdbcFileSourceFactory.SYSTEM_PROPERTY_BUFFER_SIZE, "65536");
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		assertEquals(65536, f.getBufferSize());
		assertNull(f.getConnectProperties().getProperty(KEY), "a JVM default isn't frozen into the connection");

		f.setBufferSize(8192);
		assertEquals(8192, f.getBufferSize(), "an explicit value wins");

		System.setProperty(JdbcFileSourceFactory.SYSTEM_PROPERTY_BUFFER_SIZE, "nonsense");
		assertEquals(JdbcFileSourceFactory.DEFAULT_BUFFER_SIZE, new JdbcFileSourceFactory().getBufferSize());
		System.setProperty(JdbcFileSourceFactory.SYSTEM_PROPERTY_BUFFER_SIZE, "10");
		assertEquals(JdbcFileSourceFactory.MIN_BUFFER_SIZE, new JdbcFileSourceFactory().getBufferSize());
		assertFalse(new JdbcFileSourceFactory().getConnectProperties().containsKey(KEY));
	}

	// ------------------------------------------------------------ against the database

	/** A directory of our own: a file directly under the root has no parent row. */
	private static FileSource dir() throws Exception {
		FileSource d = JdbcTestServer.factory().createFileSource("/bufferSizeTests-" + System.nanoTime());
		assertTrue(d.mkdirs(), "can't create " + d);
		return d;
	}

	private static byte[] random(int size, long seed) {
		byte[] b = new byte[size];
		new Random(seed).nextBytes(b);
		return b;
	}

	private static byte[] readAll(FileSource f) throws Exception {
		try (InputStream in = f.getInputStream()) {
			return in.readAllBytes();
		}
	}

	@Test
	void saysWhatItUnderstandsAndWhatItUsesByDefault() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		FileSource file = dir().getChild("defaults.bin");
		assertEquals(Set.of(StreamOption.CHUNK_SIZE), file.supportedStreamOptions());
		assertEquals(f.getChunk_size(), file.getStreamDefaults().chunkSize());
		assertEquals(f.getChunk_size(), file.getStreamDefaults().bufferSize(), "a write fills a row, so the same");
	}

	/** Each stream cuts its writes into rows of its own size; a stream without options uses the connection's. */
	@Test
	void eachStreamWritesRowsOfItsOwnSize() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		int before = f.getChunk_size();
		try {
			f.setChunk_size(8 * 1024);
			JdbcFileSource file = (JdbcFileSource) dir().getChild("rows.bin");

			byte[] a = random(4 * 1024 * 10 + 123, 1);
			try (OutputStream out = file.getOutputStream(false, StreamOptions.chunk(4 * 1024))) {
				out.write(a);
			}
			assertEquals(a.length, file.length());
			assertEquals(11, file.getChunkCount(), "ten full 4 KB rows and the rest");

			// no options: the connection's 8 KB
			byte[] b = random(8 * 1024 * 3 + 5, 2);
			try (OutputStream out = file.getOutputStream(true)) {
				out.write(b);
			}
			assertEquals(11 + 4, file.getChunkCount(), "three full 8 KB rows and the rest");

			// another stream, bigger rows
			byte[] c = random(100 * 1024, 3);
			try (OutputStream out = file.getOutputStream(true, StreamOptions.chunk(64 * 1024))) {
				out.write(c);
			}
			assertEquals(11 + 4 + 2, file.getChunkCount(), "100 KB in 64 KB rows");

			// the connection's value was not touched by any of it
			assertEquals(8 * 1024, f.getChunk_size());

			byte[] all = new byte[a.length + b.length + c.length];
			System.arraycopy(a, 0, all, 0, a.length);
			System.arraycopy(b, 0, all, a.length, b.length);
			System.arraycopy(c, 0, all, a.length + b.length, c.length);
			assertEquals(all.length, file.length());
			assertArrayEquals(all, readAll(file), "rows written with three different sizes read back as one file");
			try (InputStream in = file.getInputStream(a.length + 17)) {
				assertArrayEquals(Arrays.copyOfRange(all, a.length + 17, all.length), in.readAllBytes());
			}
			file.delete();
		} finally {
			f.setChunk_size(before);
		}
	}

	@Test
	void aSizeOutOfRangeIsKeptToTheLimits() throws Exception {
		JdbcFileSource file = (JdbcFileSource) dir().getChild("limits.bin");
		byte[] data = random(5 * 1024 + 7, 4);
		// 10 is below the minimum of 1 KB, so the rows are 1 KB: 5 full ones and the rest
		try (OutputStream out = file.getOutputStream(false, StreamOptions.chunk(10))) {
			out.write(data);
		}
		assertEquals(6, file.getChunkCount());
		assertArrayEquals(data, readAll(file));
		file.delete();
	}

	/** Random access: a stream's chunks have its size, and the file reads back the same through any size. */
	@Test
	void randomAccessWithAChunkOfItsOwn() throws Exception {
		JdbcFileSource file = (JdbcFileSource) dir().getChild("ra.bin");
		byte[] data = random(10_000, 5);
		try (IRandomAccessStream r = file.getRandomAccessStream("rw", StreamOptions.chunk(2048))) {
			r.write(data);
		}
		assertEquals(10_000, file.length());
		assertEquals(5, file.getChunkCount(), "10000 bytes in 2048 byte chunks");
		assertArrayEquals(data, readAll(file));

		for(int chunk : new int[] {1024, 2048, 3000, 65_536}) {
			try (IRandomAccessStream r = file.getRandomAccessStream("r", StreamOptions.chunk(chunk))) {
				for(int pos : new int[] {0, 1, 2047, 2048, 2049, 4095, 4096, 9_900, 9_999}) {
					r.seek(pos);
					byte[] got = new byte[100];
					int n = r.read(got);
					assertTrue(n > 0, "chunk " + chunk + " at " + pos + " read " + n);
					assertArrayEquals(Arrays.copyOfRange(data, pos, pos + n), Arrays.copyOf(got, n), "chunk " + chunk + " at " + pos);
				}
			}
		}
		file.delete();
	}

	@Test
	void growingByASeekUsesTheStreamsChunk() throws Exception {
		JdbcFileSource file = (JdbcFileSource) dir().getChild("grow.bin");
		try (OutputStream out = file.getOutputStream()) {
			out.write(random(10, 6));
		}
		ISeekableInputStream in = file.getSeekableInputStream(StreamOptions.chunk(2 * 1024));
		try {
			in.seek(10 + 5 * 2 * 1024);
		} finally {
			in.close();
		}
		assertEquals(10 + 5 * 2 * 1024, file.length());
		assertEquals(1 + 5, file.getChunkCount(), "the first row and five 2 KB ones");
		file.delete();
	}

	@Test
	void aStreamKeepsItsChunkWhenTheConnectionsChanges() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		int before = f.getChunk_size();
		try {
			JdbcFileSource file = (JdbcFileSource) dir().getChild("fixed.bin");
			f.setChunk_size(4096);
			byte[] data = random(4096 * 3, 7);
			try (OutputStream out = file.getOutputStream()) {
				f.setChunk_size(1024);       // after it was opened
				out.write(data);
			}
			assertEquals(3, file.getChunkCount(), "rows of the 4 KB it was opened with");
			assertArrayEquals(data, readAll(file));
			file.delete();
		} finally {
			f.setChunk_size(before);
		}
	}
}
