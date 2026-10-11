package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * A change made through another connection to the database can't be announced to this
 * factory, so a handle that was kept sees it when what it remembered expires: the factory's
 * field time to live, 500 ms unless it is set. (A change through the same factory is seen at
 * once; see FileLikeBehaviorTests.)
 */
public class JdbcExternalChangeTest {

	private static final AtomicInteger TREES = new AtomicInteger();
	private static final int TTL = 150;
	private JdbcFileSourceFactory mine;
	private JdbcFileSourceFactory other;
	private String root;

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9015);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	@BeforeEach
	void connections() throws Exception {
		mine = JdbcTestServer.factory();
		mine.setFieldTimeToLive(TTL);
		other = new JdbcFileSourceFactory();
		Properties p = mine.getConnectProperties();
		other.setConnectionProperties(p);
		assertTrue(other.connect());
		root = "/external" + TREES.incrementAndGet();
		assertTrue(mine.createFileSource(root).mkdirs());
	}

	@AfterEach
	void close() throws Exception {
		other.disConnect();
	}

	private static void pause() throws InterruptedException {
		Thread.sleep(TTL + 100);
	}

	private void write(JdbcFileSourceFactory f, String name, byte... data) throws Exception {
		try (OutputStream out = f.createFileSource(root + "/" + name).getOutputStream()) {
			out.write(data);
		}
	}

	@Test
	void aDeleteByAnotherConnectionIsSeen() throws Exception {
		write(mine, "f.bin", (byte) 1, (byte) 2, (byte) 3);
		FileSource held = mine.createFileSource(root + "/f.bin");
		assertTrue(held.exists());
		assertEquals(3, held.length());

		assertTrue(other.createFileSource(root + "/f.bin").delete());
		pause();
		assertFalse(held.exists(), "the row is gone");
		assertFalse(held.isFile());
		assertEquals(0, held.length());
	}

	@Test
	void aCreateByAnotherConnectionIsSeen() throws Exception {
		FileSource held = mine.createFileSource(root + "/later.bin");
		assertFalse(held.exists());
		write(other, "later.bin", (byte) 9);
		pause();
		assertTrue(held.exists());
		assertEquals(1, held.length());
	}

	@Test
	void aRewriteAndARenameByAnotherConnectionAreSeen() throws Exception {
		write(mine, "a.bin", (byte) 1);
		FileSource held = mine.createFileSource(root + "/a.bin");
		assertEquals(1, held.length());

		write(other, "a.bin", (byte) 1, (byte) 2, (byte) 3, (byte) 4);
		pause();
		assertEquals(4, held.length());
		try (InputStream in = held.getInputStream()) {
			assertArrayEquals(new byte[] {1, 2, 3, 4}, in.readAllBytes());
		}

		assertTrue(other.createFileSource(root + "/a.bin").renameTo(other.createFileSource(root + "/b.bin")));
		pause();
		assertFalse(held.exists());
		assertTrue(mine.createFileSource(root + "/b.bin").exists());
	}
}
