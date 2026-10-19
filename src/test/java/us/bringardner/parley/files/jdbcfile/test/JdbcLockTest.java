package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.lock.LockScope;
import us.bringardner.parley.files.test.AbstractLockTests;

/**
 * The lock contract on HSQLDB, which can't refuse a row lock without waiting: its locks stay in
 * this JVM (PROCESS). {@link JdbcPostgresLockTest} covers locks kept by the database.
 */
public class JdbcLockTest extends AbstractLockTests {

	private static final AtomicInteger DIRS = new AtomicInteger();

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9031);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	@Override
	protected FileSource newLockDirectory() throws Exception {
		FileSource d = JdbcTestServer.factory().createFileSource("/locks" + DIRS.incrementAndGet());
		d.mkdirs();
		return d;
	}

	@Test
	public void hsqldbLocksStayInThisJvm() {
		assertEquals(LockScope.PROCESS, dir.getLockScope());
	}
}
