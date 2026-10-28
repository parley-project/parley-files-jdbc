package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * Several processes appending lines to one file at once, as the mail servers sharing a store append
 * to the queue history: every line must be there once, whole. (Each append used to take the next
 * chunk number from what its handle knew; two appenders took the same number, and the second
 * deleted the first one's chunk to put in its own.)
 */
public class JdbcConcurrentAppendTest {

	private static final int THREADS = 4;
	private static final int LINES = 100;

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9017);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	@Test
	public void twoProcessesAppendToOneFile() throws Exception {
		JdbcFileSourceFactory one = JdbcTestServer.factory();
		JdbcFileSourceFactory two = new JdbcFileSourceFactory();
		Properties p = one.getConnectProperties();
		two.setConnectionProperties(p);
		assertTrue(two.connect());
		try {
			assertTrue(one.createFileSource("/append").mkdirs());
			try (OutputStream out = one.createFileSource("/append/history.jsonl").getOutputStream()) {
				// (made empty)
			}
			List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
			ExecutorService pool = Executors.newFixedThreadPool(2 * THREADS);
			CountDownLatch start = new CountDownLatch(1);
			List<String> expected = new ArrayList<>();
			for (JdbcFileSourceFactory f : Arrays.asList(one, two)) {
				for (int t = 0; t < THREADS; t++) {
					final String who = (f == one ? "one" : "two") + "-" + t;
					for (int i = 0; i < LINES; i++) {
						expected.add(who + " line " + i);
					}
					pool.submit(() -> {
						try {
							start.await();
							for (int i = 0; i < LINES; i++) {
								// a new handle each time, as QueueHistory opens the file for each line
								FileSource file = f.createFileSource("/append/history.jsonl");
								try (OutputStream out = file.getOutputStream(true)) {
									out.write((who + " line " + i + "\n").getBytes(StandardCharsets.UTF_8));
								}
							}
						} catch (Throwable e) {
							errors.add(e);
						}
						return null;
					});
				}
			}
			start.countDown();
			pool.shutdown();
			assertTrue(pool.awaitTermination(5, TimeUnit.MINUTES), "the appenders finished");
			if (!errors.isEmpty()) {
				errors.get(0).printStackTrace();
			}
			assertEquals(Collections.emptyList(), errors.subList(0, Math.min(3, errors.size())), errors.size() + " append(s) failed");

			String text;
			try (InputStream in = two.createFileSource("/append/history.jsonl").getInputStream()) {
				text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n")));
			Collections.sort(lines);
			Collections.sort(expected);
			assertEquals(expected.size(), lines.size(), "the number of lines");
			assertEquals(expected, lines);
		} finally {
			two.disConnect();
		}
	}
}
