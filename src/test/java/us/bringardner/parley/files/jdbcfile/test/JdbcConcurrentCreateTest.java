package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
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

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * A folder or file another process made while this one's handle still thought it wasn't there:
 * after the create fails (someone else's row is there), the handle answers from the database, not
 * from what it knew. (Seen on mx1 with two mail servers on one store: the first IMAP login on one
 * made the user's folder while the other's delivery made it; mkdirs failed, as it should, and then
 * isDirectory said no from the handle's cache, so the login failed with "Can't create".)
 */
public class JdbcConcurrentCreateTest {

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9018);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	private static JdbcFileSourceFactory second(JdbcFileSourceFactory one) throws Exception {
		JdbcFileSourceFactory two = new JdbcFileSourceFactory();
		Properties p = one.getConnectProperties();
		two.setConnectionProperties(p);
		assertTrue(two.connect());
		return two;
	}

	@Test
	public void aFolderTheOtherProcessMadeAfterThisHandleLooked() throws Exception {
		JdbcFileSourceFactory one = JdbcTestServer.factory();
		JdbcFileSourceFactory two = second(one);
		try {
			assertTrue(one.createFileSource("/made").mkdirs());
			FileSource mine = one.createFileSource("/made/b1.test/t");
			assertFalse(mine.exists(), "not there yet: the handle knows so");
			assertTrue(two.createFileSource("/made/b1.test/t").mkdirs(), "the other process makes it");

			boolean made = mine.mkdirs();
			assertTrue(made || mine.isDirectory(), "it is there: mkdirs " + made + ", isDirectory " + mine.isDirectory());
			assertTrue(mine.isDirectory());
			assertTrue(mine.exists());
		} finally {
			two.disConnect();
		}
	}

	@Test
	public void aFileTheOtherProcessMadeAfterThisHandleLooked() throws Exception {
		JdbcFileSourceFactory one = JdbcTestServer.factory();
		JdbcFileSourceFactory two = second(one);
		try {
			assertTrue(one.createFileSource("/made-file").mkdirs());
			FileSource mine = one.createFileSource("/made-file/x.txt");
			assertFalse(mine.exists());
			try (OutputStream out = two.createFileSource("/made-file/x.txt").getOutputStream()) {
				out.write('x');
			}
			assertFalse(mine.createNewFile(), "the other process has it");
			assertTrue(mine.isFile(), "and it is a file");
			assertEquals(1, mine.length());
		} finally {
			two.disConnect();
		}
	}

	@Test
	public void aChildOfAFolderBeingMadeByAnotherProcess() throws Exception {
		JdbcFileSourceFactory one = JdbcTestServer.factory();
		JdbcFileSourceFactory two = second(one);
		try {
			for (int round = 0; round < 30; round++) {
				String path = "/children/" + round + "/b1.test";
				List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
				ExecutorService pool = Executors.newFixedThreadPool(4);
				CountDownLatch start = new CountDownLatch(1);
				java.util.concurrent.atomic.AtomicBoolean made = new java.util.concurrent.atomic.AtomicBoolean();
				pool.submit(() -> {
					try {
						start.await();
						two.createFileSource(path).mkdirs();
						made.set(true);
					} catch (Throwable e) {
						errors.add(e);
					}
					return null;
				});
				for (int t = 0; t < 3; t++) {
					pool.submit(() -> {
						try {
							start.await();
							// as SMTP's recipient check: a new handle each time, then its child
							while (!made.get()) {
								one.createFileSource(path).getChild("t");
							}
							one.createFileSource(path).getChild("t");
						} catch (Throwable e) {
							errors.add(e);
						}
						return null;
					});
				}
				start.countDown();
				pool.shutdown();
				assertTrue(pool.awaitTermination(1, TimeUnit.MINUTES));
				assertEquals(Collections.emptyList(), errors, "round " + round);
			}
		} finally {
			two.disConnect();
		}
	}

	@Test
	public void manyProcessesMakeOneFolderAtOnce() throws Exception {
		JdbcFileSourceFactory one = JdbcTestServer.factory();
		JdbcFileSourceFactory two = second(one);
		try {
			for (int round = 0; round < 20; round++) {
				String path = "/race/" + round + "/b1.test/t";
				List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
				ExecutorService pool = Executors.newFixedThreadPool(4);
				CountDownLatch start = new CountDownLatch(1);
				for (int t = 0; t < 4; t++) {
					JdbcFileSourceFactory f = t % 2 == 0 ? one : two;
					pool.submit(() -> {
						try {
							FileSource d = f.createFileSource(path);
							d.exists(); // a handle that has looked, as the mail servers' have
							start.await();
							if (!d.mkdirs() && !d.isDirectory()) {
								throw new AssertionError("Can't create " + path);
							}
						} catch (Throwable e) {
							errors.add(e);
						}
						return null;
					});
				}
				start.countDown();
				pool.shutdown();
				assertTrue(pool.awaitTermination(1, TimeUnit.MINUTES));
				assertEquals(Collections.emptyList(), errors, "round " + round);
			}
		} finally {
			two.disConnect();
		}
	}
}
