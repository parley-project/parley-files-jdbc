package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * One handle used by several threads at once, as a mail server uses its maildrop root: every
 * session finds its user's folder from it. Its cached row id is looked up again when it expires,
 * and when another handle changes the database; a thread using the handle meanwhile must still
 * get the id (it used to see it vanish: a NullPointerException in rowId).
 */
public class JdbcSharedHandleTest {

	private static final int THREADS = 8;
	private static final int ROUNDS = 150;

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9016);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	/**
	 * A file another handle deleted is gone, as java.io.FileInputStream says: a
	 * FileNotFoundException (the SMTP queue takes that for "delivered meanwhile", and any other
	 * IOException for a damaged file).
	 */
	@Test
	public void readingAFileThatIsGoneIsFileNotFound() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		FileSource dir = f.createFileSource("/gone");
		assertTrue(dir.mkdirs());
		FileSource held = dir.getChild("a.env");
		try (OutputStream out = held.getOutputStream()) {
			out.write(1);
		}
		assertTrue(held.exists());
		assertTrue(f.createFileSource("/gone/a.env").delete());
		org.junit.jupiter.api.Assertions.assertThrows(java.io.FileNotFoundException.class, () -> held.getInputStream().close());
		org.junit.jupiter.api.Assertions.assertThrows(java.io.FileNotFoundException.class, () -> dir.getInputStream().close());
	}

	@Test
	public void oneHandleManyThreads() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		f.setFieldTimeToLive(1); // what it knows is stale almost at once
		FileSource dir = f.createFileSource("/shared-handle");
		assertTrue(dir.mkdirs());
		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		for (int t = 0; t < THREADS; t++) {
			final int me = t;
			pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < ROUNDS; i++) {
						// every change through a handle makes the others look their rows up again
						FileSource kid = dir.getChild("f" + me + "-" + i);
						try (OutputStream out = kid.getOutputStream()) {
							out.write(("round " + i).getBytes(StandardCharsets.UTF_8));
						}
						if (!dir.isDirectory() || !kid.exists() || kid.length() == 0) {
							throw new AssertionError("lost track of " + kid + " (directory " + dir.isDirectory() + ")");
						}
						if (i % 10 == 0) {
							dir.listFiles();
						}
						if (i % 3 == 0) {
							kid.delete();
						}
					}
				} catch (Throwable e) {
					errors.add(e);
				}
				return null;
			});
		}
		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(5, TimeUnit.MINUTES), "the threads finished");
		if (!errors.isEmpty()) {
			errors.get(0).printStackTrace();
		}
		assertEquals(Collections.emptyList(), errors.subList(0, Math.min(3, errors.size())), errors.size() + " thread(s) failed");
		// each thread deleted every third file it made
		int kept = ROUNDS - (ROUNDS + 2) / 3;
		assertEquals(THREADS * kept, dir.listFiles().length);
	}
}
