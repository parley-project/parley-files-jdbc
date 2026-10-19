package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.jdbcfile.JdbcFileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.files.lock.LockScope;
import us.bringardner.parley.files.test.AbstractLockTests;

/**
 * The lock contract on a MySQL 8+ or MariaDB 10.3+ server you point it at, which keeps the locks:
 * CLUSTER scope, so the cross-process cases run a second JVM that connects to the same database.
 * <p>
 * Skipped unless these environment variables are set (the worker JVM inherits them):
 * <ul>
 * <li>{@code PARLEY_TEST_MYSQL_URL}, for example {@code jdbc:mysql://host:3306}</li>
 * <li>{@code PARLEY_TEST_MYSQL_USER}</li>
 * <li>{@code PARLEY_TEST_MYSQL_PASSWORD}</li>
 * </ul>
 * It works in the {@code file_source} database. If that database is missing it is created from
 * MySql.ddl; nothing is ever dropped. Everything the test makes is under one directory,
 * {@code /parley-lock-test-<random>}, removed at the end. The user needs rights to create the
 * database and tables the first time, and to read, write and lock rows in it.
 */
public class JdbcMySqlLockTest extends AbstractLockTests {

	private static final String URL = System.getenv("PARLEY_TEST_MYSQL_URL");
	private static final String USER = System.getenv("PARLEY_TEST_MYSQL_USER");
	private static final String PASSWORD = System.getenv("PARLEY_TEST_MYSQL_PASSWORD");
	private static final String ROOT = "/parley-lock-test-" + UUID.randomUUID().toString().substring(0, 8);
	private static final AtomicInteger DIRS = new AtomicInteger();
	private static JdbcFileSourceFactory factory;
	private static boolean mariadb;

	@BeforeAll
	public static void setUp() throws Exception {
		assumeTrue(URL != null && !URL.isBlank(), "PARLEY_TEST_MYSQL_URL isn't set");
		try (Connection con = open(); Statement st = con.createStatement()) {
			mariadb = String.valueOf(con.getMetaData().getDatabaseProductVersion()).toLowerCase().contains("mariadb")
					|| String.valueOf(con.getMetaData().getDatabaseProductName()).toLowerCase().contains("mariadb");
			boolean exists;
			try (ResultSet rs = con.getMetaData().getCatalogs()) {
				exists = false;
				while (rs.next()) {
					exists |= "file_source".equalsIgnoreCase(rs.getString(1));
				}
			}
			if( !exists ) {
				try (InputStream in = JdbcMySqlLockTest.class.getResourceAsStream("/MySql.ddl")) {
					for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
						String s = sql.replaceAll("(?m)^\\s*--.*$", "").trim();
						if( !s.isEmpty() && !s.toUpperCase().startsWith("DROP") ) {
							st.execute(s);
						}
					}
				}
			}
		}
		factory = connect();
		assertTrue(factory.createFileSource(ROOT).mkdirs(), "make " + ROOT);
	}

	private static Connection open() throws SQLException {
		return DriverManager.getConnection(URL, USER, PASSWORD);
	}

	static JdbcFileSourceFactory connect() throws IOException {
		JdbcFileSourceFactory f = (JdbcFileSourceFactory) FileSourceFactory.getFileSourceFactory(JdbcFileSourceFactory.FACTORY_ID);
		Properties p = f.getConnectProperties();
		p.setProperty(JdbcFileSourceFactory.JDBC_DRIVER, URL.startsWith("jdbc:mariadb:") ? "org.mariadb.jdbc.Driver" : "com.mysql.cj.jdbc.Driver");
		p.setProperty(JdbcFileSourceFactory.JDBC_URL, URL);
		p.setProperty(JdbcFileSourceFactory.JDBC_USERID, USER == null ? "" : USER);
		p.setProperty(JdbcFileSourceFactory.JDBC_PASSWORD, PASSWORD == null ? "" : PASSWORD);
		assertTrue(f.connect(p), "connect to " + URL);
		return f;
	}

	@AfterAll
	static void tearDown() throws Exception {
		if( factory != null ) {
			try {
				deleteTree(factory.createFileSource(ROOT));
			} finally {
				factory.disConnect();
			}
		}
	}

	private static void deleteTree(FileSource f) throws IOException {
		if( f.isDirectory() ) {
			FileSource[] kids = f.listFiles();
			if( kids != null ) {
				for (FileSource k : kids) {
					deleteTree(k);
				}
			}
		}
		if( f.exists() ) {
			f.delete();
		}
	}

	@Override
	protected FileSource newLockDirectory() throws Exception {
		FileSource d = factory.createFileSource(ROOT + "/locks" + DIRS.incrementAndGet());
		assertTrue(d.mkdirs());
		return d;
	}

	@Override
	protected String workerSpec(FileSource source) throws Exception {
		return source.getAbsolutePath();
	}

	/** In the worker JVM: one factory, connected once, from the same environment variables. */
	private static JdbcFileSourceFactory workerFactory;

	@Override
	protected FileSource fromWorkerSpec(String spec) throws Exception {
		synchronized (JdbcMySqlLockTest.class) {
			if( workerFactory == null ) {
				workerFactory = connect();
			}
		}
		return workerFactory.createFileSource(spec);
	}

	private static long idOf(FileSource f) {
		return ((JdbcFileSource) f).getFileId();
	}

	/** Whether the server refused a NOWAIT row lock because someone else holds it. */
	private static boolean lockRefused(SQLException e) {
		return e.getErrorCode() == 3572 || (mariadb && e.getErrorCode() == 1205);
	}

	// ------------------------------------------------------------------ what the database adds

	@Test
	public void theDatabaseKeepsTheLocks() {
		assertEquals(LockScope.CLUSTER, dir.getLockScope());
	}

	/** The point of a lock table: the holder writes, renames and adds files while it holds the lock. */
	@Test
	public void theHolderWorksOnTheFileAsUsual() throws Exception {
		FileSource f = file("busy");
		FileSource sub = dir.getChild("sub");
		assertTrue(sub.mkdirs());
		ExecutorService ex = Executors.newSingleThreadExecutor();
		try (FileSourceLock l = f.lock(); FileSourceLock dl = sub.lock()) {
			Future<?> work = ex.submit(() -> {
				try (OutputStream out = f.getOutputStream()) {
					out.write("written while locked".getBytes(StandardCharsets.UTF_8));
				}
				writeBytes(sub.getChild("new.txt"), new byte[] {1, 2, 3});
				FileSource renamed = dir.getChild("renamed");
				assertTrue(f.renameTo(renamed));
				assertTrue(renamed.renameTo(f));
				return null;
			});
			work.get(30, TimeUnit.SECONDS); // would time out if the work waited on the lock
			assertTrue(l.isValid() && dl.isValid());
		} finally {
			ex.shutdownNow();
		}
		assertEquals(20, f.length());
		assertEquals(3, sub.getChild("new.txt").length());
	}

	/** The lock is on the file, so it stays with the file through a rename. */
	@Test
	public void theLockFollowsARename() throws Exception {
		FileSource f = file("moving");
		long fileid = idOf(f);
		FileSourceLock l = f.tryLock();
		assertNotNull(l);
		try {
			FileSource moved = dir.getChild("moved");
			assertTrue(f.renameTo(moved));
			FileSource again = factory.createFileSource(moved.getAbsolutePath());
			assertThrows(OverlappingFileLockException.class, () -> again.tryLock(), "this JVM holds the file under its new name");
			// as another process sees it: the file's region row is locked
			try (Connection con = open()) {
				con.setAutoCommit(false);
				try (PreparedStatement ps = con.prepareStatement("SELECT id FROM file_source.file_lock_range WHERE fileid = ? FOR UPDATE NOWAIT")) {
					ps.setLong(1, fileid);
					SQLException e = assertThrows(SQLException.class, () -> ps.executeQuery().close());
					assertTrue(lockRefused(e), "refused as held: " + e.getErrorCode() + " " + e);
				} finally {
					con.rollback();
				}
			}
		} finally {
			l.release();
		}
	}

	/** Lock rows of deleted files are removed when a factory connects, unless one is still held. */
	@Test
	public void rowsOfDeletedFilesAreTidied() throws Exception {
		FileSource gone = file("gone");
		long goneId = idOf(gone);
		gone.tryLock().release();
		FileSource kept = file("kept");
		long keptId = idOf(kept);
		FileSourceLock held = kept.tryLock();
		assertNotNull(held);
		try {
			assertTrue(gone.delete());
			assertTrue(kept.delete());
			assertEquals(2, rows("file_source.file_lock", goneId, keptId));
			JdbcFileSourceFactory other = connect();
			other.disConnect();
			assertEquals(1, rows("file_source.file_lock_range", goneId, keptId), "the held region is skipped");
			assertEquals(0, rows("file_source.file_lock", goneId, keptId), "the files' mutex rows are removed");
		} finally {
			held.release();
		}
		assertFalse(held.isValid());
	}

	private static int rows(String table, long a, long b) throws SQLException {
		return count("SELECT count(*) FROM " + table + " WHERE fileid IN (" + a + ", " + b + ")");
	}

	/** tryLock never waits on the file's mutex: a mutex that stays taken means busy. */
	@Test
	public void tryLockDoesNotWaitForAStuckLocker() throws Exception {
		FileSource f = file("stuck");
		long fileid = idOf(f);
		f.tryLock().release(); // makes the file's mutex row
		// another locker, stalled in the middle of taking a lock: it holds the mutex
		try (Connection stuck = open()) {
			stuck.setAutoCommit(false);
			try (Statement st = stuck.createStatement()) {
				st.executeQuery("SELECT fileid FROM file_source.file_lock WHERE fileid = " + fileid + " FOR UPDATE").close();
			}
			long start = System.nanoTime();
			assertNull(f.tryLock(), "busy while the mutex is taken");
			long ms = (System.nanoTime() - start) / 1_000_000;
			assertTrue(ms < 5000, "came back in " + ms + " ms, not waiting for the stuck locker");
			stuck.rollback();
		}
		FileSourceLock l = f.tryLock();
		assertNotNull(l, "free once the stuck locker lets go");
		l.release();
	}

	/** A holder that dies leaves its row behind; the next locker finds it stale and takes the region. */
	@Test
	public void aDeadHoldersRowIsTakenOver() throws Exception {
		FileSource f = file("stale");
		long fileid = idOf(f);
		FileSourceLock l = f.tryLock(0, 10, false);
		assertNotNull(l);
		l.release();
		long id = ThreadLocalRandom.current().nextLong(1L << 40, Long.MAX_VALUE);
		// what a holder that died leaves: its row, with no connection keeping it locked
		try (Connection con = open(); Statement st = con.createStatement()) {
			st.executeUpdate("INSERT INTO file_source.file_lock_range (fileid, id, start_pos, end_pos, shared) VALUES ("
					+ fileid + ", " + id + ", 0, 10, false)");
		}
		FileSourceLock again = f.tryLock(5, 10, false);
		assertNotNull(again, "the stale claim doesn't keep anyone out");
		again.release();
		assertEquals(0, count("SELECT count(*) FROM file_source.file_lock_range WHERE fileid = " + fileid + " AND id = " + id), "and it was removed");
	}

	private static int count(String sql) throws SQLException {
		try (Connection con = open(); Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		}
	}
}
