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
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;
import us.bringardner.parley.files.lock.FileSourceLock;
import us.bringardner.parley.files.lock.LockScope;
import us.bringardner.parley.files.test.AbstractLockTests;

/**
 * The lock contract on PostgreSQL, which keeps the locks: CLUSTER scope, so the cross-process cases
 * run a second JVM that connects to the same database. Skipped where the embedded PostgreSQL can't
 * start.
 */
public class JdbcPostgresLockTest extends AbstractLockTests {

	private static final AtomicInteger DIRS = new AtomicInteger();
	private static EmbeddedPostgres pg;
	private static JdbcFileSourceFactory factory;
	private static String url;

	@BeforeAll
	public static void setUp() throws Exception {
		try {
			pg = EmbeddedPostgres.builder().start();
		} catch (IOException | RuntimeException e) {
			assumeTrue(false, "no embedded PostgreSQL here: " + e);
		}
		url = pg.getJdbcUrl("postgres", "postgres");
		try (Connection con = DriverManager.getConnection(url); Statement st = con.createStatement();
				InputStream in = JdbcPostgresLockTest.class.getResourceAsStream("/PostgreSQL.ddl")) {
			// the DDL drops tables first; on a new database those statements fail, which is fine
			for (String sql : new String(in.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
				String s = sql.replaceAll("(?m)^\\s*--.*$", "").trim();
				if( !s.isEmpty() ) {
					try {
						st.execute(s);
					} catch (SQLException e) {
						if( !s.toUpperCase().startsWith("DROP") ) {
							throw e;
						}
					}
				}
			}
		}
		factory = connect(url);
	}

	static JdbcFileSourceFactory connect(String url) throws IOException {
		JdbcFileSourceFactory f = (JdbcFileSourceFactory) FileSourceFactory.getFileSourceFactory(JdbcFileSourceFactory.FACTORY_ID);
		Properties p = f.getConnectProperties();
		p.setProperty(JdbcFileSourceFactory.JDBC_DRIVER, "org.postgresql.Driver");
		p.setProperty(JdbcFileSourceFactory.JDBC_URL, url);
		p.setProperty(JdbcFileSourceFactory.JDBC_USERID, "postgres");
		p.setProperty(JdbcFileSourceFactory.JDBC_PASSWORD, "postgres");
		assertTrue(f.connect(p), "connect to " + url);
		return f;
	}

	@AfterAll
	static void tearDown() throws Exception {
		if( factory != null ) {
			factory.disConnect();
		}
		if( pg != null ) {
			pg.close();
		}
	}

	@Override
	protected FileSource newLockDirectory() throws Exception {
		FileSource d = factory.createFileSource("/locks" + DIRS.incrementAndGet());
		assertTrue(d.mkdirs());
		return d;
	}

	@Override
	protected String workerSpec(FileSource source) throws Exception {
		return URLEncoder.encode(url, StandardCharsets.UTF_8) + "!" + URLEncoder.encode(source.getAbsolutePath(), StandardCharsets.UTF_8);
	}

	/** In the worker JVM: one factory, connected once. */
	private static JdbcFileSourceFactory workerFactory;

	@Override
	protected FileSource fromWorkerSpec(String spec) throws Exception {
		String[] p = spec.split("!");
		synchronized (JdbcPostgresLockTest.class) {
			if( workerFactory == null ) {
				workerFactory = connect(URLDecoder.decode(p[0], StandardCharsets.UTF_8));
			}
		}
		return workerFactory.createFileSource(URLDecoder.decode(p[1], StandardCharsets.UTF_8));
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
		FileSourceLock l = f.tryLock();
		assertNotNull(l);
		try {
			FileSource moved = dir.getChild("moved");
			assertTrue(f.renameTo(moved));
			FileSource again = factory.createFileSource(moved.getAbsolutePath());
			assertThrows(OverlappingFileLockException.class, () -> again.tryLock(), "this JVM holds the file under its new name");
			// as another process sees it: the file's lock row is locked
			try (Connection con = DriverManager.getConnection(url); Statement st = con.createStatement()) {
				con.setAutoCommit(false);
				SQLException e = assertThrows(SQLException.class, () -> st.executeQuery(
						"SELECT r.id FROM file_source.file_lock_range r JOIN file_source.file f ON f.fileid = r.fileid "
								+ "WHERE f.name = 'moved' FOR UPDATE OF r NOWAIT"));
				assertEquals("55P03", e.getSQLState());
				con.rollback();
			}
		} finally {
			l.release();
		}
	}

	/** Lock rows of deleted files are removed when a factory connects, unless one is still held. */
	@Test
	public void rowsOfDeletedFilesAreTidied() throws Exception {
		FileSource gone = file("gone");
		gone.tryLock().release();
		FileSource kept = file("kept");
		FileSourceLock held = kept.tryLock();
		assertNotNull(held);
		try {
			assertTrue(gone.delete());
			assertTrue(kept.delete());
			assertTrue(orphanRows("file_source.file_lock") >= 2);
			JdbcFileSourceFactory other = connect(url);
			other.disConnect();
			assertEquals(1, orphanRows("file_source.file_lock_range"), "the held region is skipped");
			assertEquals(0, orphanRows("file_source.file_lock"), "the files' mutex rows are removed");
		} finally {
			held.release();
		}
		assertFalse(held.isValid());
	}

	private static int orphanRows(String table) throws SQLException {
		return count("SELECT count(*) FROM " + table + " l WHERE NOT EXISTS "
				+ "(SELECT 1 FROM file_source.file f WHERE f.fileid = l.fileid)");
	}

	/** tryLock never waits on the file's mutex: a mutex that stays taken means busy. */
	@Test
	public void tryLockDoesNotWaitForAStuckLocker() throws Exception {
		FileSource f = file("stuck");
		f.tryLock().release(); // makes the file's mutex row
		long fileid;
		try (Connection con = DriverManager.getConnection(url); Statement st = con.createStatement();
				ResultSet rs = st.executeQuery("SELECT fileid FROM file_source.file WHERE name = 'stuck'")) {
			assertTrue(rs.next());
			fileid = rs.getLong(1);
		}
		// another locker, stalled in the middle of taking a lock: it holds the mutex
		try (Connection stuck = DriverManager.getConnection(url)) {
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
		FileSourceLock l = f.tryLock(0, 10, false);
		assertNotNull(l);
		long fileid;
		try (Connection con = DriverManager.getConnection(url); Statement st = con.createStatement();
				ResultSet rs = st.executeQuery("SELECT fileid FROM file_source.file WHERE name = 'stale'")) {
			assertTrue(rs.next());
			fileid = rs.getLong(1);
		}
		l.release();
		// what a holder that died leaves: its row, with no connection keeping it locked
		try (Connection con = DriverManager.getConnection(url); Statement st = con.createStatement()) {
			st.executeUpdate("INSERT INTO file_source.file_lock_range (fileid, id, start_pos, end_pos, shared) VALUES ("
					+ fileid + ", 42, 0, 10, false)");
		}
		FileSourceLock again = f.tryLock(5, 10, false);
		assertNotNull(again, "the stale claim doesn't keep anyone out");
		again.release();
		assertEquals(0, count("SELECT count(*) FROM file_source.file_lock_range WHERE id = 42"), "and it was removed");
	}

	private static int count(String sql) throws SQLException {
		try (Connection con = DriverManager.getConnection(url); Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getInt(1);
		}
	}
}
