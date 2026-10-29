package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * The check whether the database holds any link, which every file operation asks for, goes to the
 * database once per interval however many threads ask at the same moment.
 */
public class JdbcLinkCheckTest {

	private static final int THREADS = 12;
	private static final int CALLS = 40;

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9054);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	@Test
	public void manyThreadsAskTheDatabaseOnce() throws Exception {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		Properties p = JdbcTestServer.factory().getConnectProperties();
		f.setConnectionProperties(p);
		assertTrue(f.connect());
		assertEquals(0, f.getLinkChecks());

		List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
		ExecutorService pool = Executors.newFixedThreadPool(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		for (int t = 0; t < THREADS; t++) {
			pool.submit(() -> {
				try {
					start.await();
					for (int i = 0; i < CALLS; i++) {
						f.createFileSource("/").exists();   // (asks whether links are possible)
					}
				} catch (Throwable e) {
					errors.add(e);
				}
				return null;
			});
		}
		start.countDown();
		pool.shutdown();
		assertTrue(pool.awaitTermination(2, TimeUnit.MINUTES));
		assertTrue(errors.isEmpty(), errors.toString());
		// (the run is far shorter than the interval, so one look is all there can be)
		assertEquals(1, f.getLinkChecks());
	}
}
