package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * JdbcFileOutputStream: bytes written one at a time and in blocks of any size come back as written,
 * across chunk boundaries and while the buffer grows; a closed stream refuses writes; a stream makes a
 * new file empty and keeps or empties an old one as asked.
 */
public class JdbcOutputStreamTest {

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9057);
		assertTrue(factory().createFileSource("/outstream").mkdirs());
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	private static JdbcFileSourceFactory factory() {
		return JdbcTestServer.factory();
	}

	private static FileSource file(String name) throws Exception {
		return factory().createFileSource("/outstream/" + name);
	}

	private static byte[] read(FileSource f) throws Exception {
		try (InputStream in = f.getInputStream()) {
			return in.readAllBytes();
		}
	}

	private static byte[] bytes(int n, long seed) {
		byte[] b = new byte[n];
		new Random(seed).nextBytes(b);
		return b;
	}

	@Test
	public void blocksAndSingleBytesAcrossChunksComeBackAsWritten() throws Exception {
		int before = factory().getChunk_size();
		factory().setChunk_size(100);   // many small rows
		try {
			byte[] all = bytes(1234, 1);
			FileSource f = file("mixed.bin");
			try (OutputStream out = f.getOutputStream()) {
				int at = 0;
				int[] sizes = { 1, 99, 100, 101, 250, 3, 0, 1, 200, 7 };
				for (int i = 0; at < all.length; i++) {
					int n = Math.min(sizes[i % sizes.length], all.length - at);
					if (n == 1) {
						out.write(all[at] & 0xff);
					} else {
						out.write(all, at, n);
					}
					at += n;
				}
			}
			assertEquals(all.length, f.length());
			assertArrayEquals(all, read(f));
		} finally {
			factory().setChunk_size(before);
		}
	}

	@Test
	public void aWriteBiggerThanTheFirstBufferAndThanSeveralChunks() throws Exception {
		int before = factory().getChunk_size();
		factory().setChunk_size(20000);   // the buffer grows from its first size to this
		try {
			byte[] all = bytes(70000, 2);
			FileSource f = file("big.bin");
			try (OutputStream out = f.getOutputStream()) {
				out.write(all);
			}
			assertArrayEquals(all, read(f));
			// the same in small steps, which grows the buffer a step at a time
			FileSource g = file("big-steps.bin");
			try (OutputStream out = g.getOutputStream()) {
				for (int at = 0; at < all.length; at += 777) {
					out.write(all, at, Math.min(777, all.length - at));
				}
			}
			assertArrayEquals(all, read(g));
		} finally {
			factory().setChunk_size(before);
		}
	}

	@Test
	public void aClosedStreamRefusesWritesAndClosesAgainQuietly() throws Exception {
		FileSource f = file("closed.txt");
		OutputStream out = f.getOutputStream();
		out.write("kept".getBytes(StandardCharsets.UTF_8));
		out.close();
		out.close();
		out.flush();   // (as a FileOutputStream's)
		assertThrows(IOException.class, () -> out.write(1));
		assertThrows(IOException.class, () -> out.write(new byte[3]));
		assertThrows(IOException.class, () -> out.write(new byte[3], 0, 3));
		assertEquals("kept", new String(read(f), StandardCharsets.UTF_8), "nothing was added after the close");
	}

	@Test
	public void badRangesAreRefused() throws Exception {
		FileSource f = file("range.bin");
		try (OutputStream out = f.getOutputStream()) {
			assertThrows(IndexOutOfBoundsException.class, () -> out.write(new byte[4], 3, 2));
			assertThrows(IndexOutOfBoundsException.class, () -> out.write(new byte[4], -1, 2));
			out.write(new byte[4], 4, 0);   // nothing, at the end
		}
		assertEquals(0, f.length());
	}

	@Test
	public void aNewFileIsMadeEmptyAndAnOldOneIsKeptOrEmptied() throws Exception {
		FileSource f = file("modes.txt");
		f.getOutputStream().close();
		assertTrue(f.exists());
		assertEquals(0, f.length());
		try (OutputStream out = f.getOutputStream()) {
			out.write("one".getBytes(StandardCharsets.UTF_8));
		}
		try (OutputStream out = f.getOutputStream(true)) {
			out.write("+two".getBytes(StandardCharsets.UTF_8));
		}
		assertEquals("one+two", new String(read(f), StandardCharsets.UTF_8));
		try (OutputStream out = f.getOutputStream(false)) {
			out.write("new".getBytes(StandardCharsets.UTF_8));
		}
		assertEquals("new", new String(read(f), StandardCharsets.UTF_8), "emptied by a stream that doesn't append");
		assertTrue(f instanceof JdbcFileSource);
	}

	@Test
	public void flushStoresWhatIsHeld() throws Exception {
		FileSource f = file("flush.txt");
		try (OutputStream out = f.getOutputStream()) {
			out.write("abc".getBytes(StandardCharsets.UTF_8));
			out.flush();
			assertEquals("abc", new String(read(f), StandardCharsets.UTF_8), "a flush is a row");
			out.write("def".getBytes(StandardCharsets.UTF_8));
		}
		assertEquals("abcdef", new String(read(f), StandardCharsets.UTF_8));
	}
}
