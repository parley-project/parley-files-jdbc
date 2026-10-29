package us.bringardner.parley.files.jdbcfile.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Each kind of pool behind ConnectionPools, against an in-memory HSQLDB. */
public class ConnectionPoolsTest {

	private static final AtomicInteger DBS = new AtomicInteger();

	private static String newUrl() {
		return "jdbc:hsqldb:mem:pools"+DBS.incrementAndGet();
	}

	private static int one(ConnectionPool pool) throws SQLException {
		try (Connection c = pool.getConnection();
				Statement s = c.createStatement();
				ResultSet rs = s.executeQuery("VALUES 1")) {
			rs.next();
			return rs.getInt(1);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { ConnectionPools.PARLEY, ConnectionPools.HIKARI, ConnectionPools.DBCP2 })
	void aPoolHandsOutConnectionsAndIsSharedByKey(String kind) throws Exception {
		String url = newUrl();
		ConnectionPool pool = ConnectionPools.getConnectionPool(kind, url, "SA", "");
		try {
			assertTrue(pool.awaitStart(5000));
			assertTrue(pool.isRunning());
			assertEquals(1, one(pool));
			assertSame(pool, ConnectionPools.getConnectionPool(kind, url, "SA", ""));
			assertNotSame(pool, ConnectionPools.getConnectionPool(kind, newUrl(), "SA", ""));
		} finally {
			ConnectionPools.removeConnectionPool(kind, url, "SA", "");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { ConnectionPools.PARLEY, ConnectionPools.HIKARI, ConnectionPools.DBCP2 })
	void destroyAllLeavesThePoolUsable(String kind) throws Exception {
		String url = newUrl();
		ConnectionPool pool = ConnectionPools.getConnectionPool(kind, url, "SA", "");
		try {
			assertEquals(1, one(pool));
			pool.destroyAll();
			assertTrue(pool.isRunning());
			assertEquals(1, one(pool));
		} finally {
			ConnectionPools.removeConnectionPool(kind, url, "SA", "");
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { ConnectionPools.PARLEY, ConnectionPools.HIKARI, ConnectionPools.DBCP2 })
	void removingAPoolClosesItAndTheNextOneIsNew(String kind) throws Exception {
		String url = newUrl();
		ConnectionPool first = ConnectionPools.getConnectionPool(kind, url, "SA", "");
		assertEquals(1, one(first));
		ConnectionPools.removeConnectionPool(kind, url, "SA", "");
		try {
			assertFalse(first.isRunning() && first.awaitStart(100) && isOpen(first), "the removed pool is closed");
			ConnectionPool second = ConnectionPools.getConnectionPool(kind, url, "SA", "");
			assertNotSame(first, second);
			assertEquals(1, one(second));
		} finally {
			ConnectionPools.removeConnectionPool(kind, url, "SA", "");
		}
	}

	/** Whether the pool still hands out connections (the parley pool stays "running" only until its thread ends). */
	private static boolean isOpen(ConnectionPool pool) {
		try {
			one(pool);
			return true;
		} catch(Exception ex) {
			return false;
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { ConnectionPools.HIKARI, ConnectionPools.DBCP2 })
	void aWrongLoginFailsWhenThePoolIsAskedForAndIsNotKept(String kind) throws Exception {
		String url = newUrl();
		//  whoever connects first to a new in-memory HSQLDB is its administrator: make that SA
		try (Connection owner = java.sql.DriverManager.getConnection(url, "SA", "")) {
			assertThrows(SQLException.class, () -> ConnectionPools.getConnectionPool(kind, url, "NOBODY", "wrong"));
			//  the same url with the right login still works
			ConnectionPool good = ConnectionPools.getConnectionPool(kind, url, "SA", "");
			try {
				assertEquals(1, one(good));
			} finally {
				ConnectionPools.removeConnectionPool(kind, url, "SA", "");
			}
		}
	}

	@Test
	void anUnknownKindIsRefused() {
		SQLException ex = assertThrows(SQLException.class,
				() -> ConnectionPools.getConnectionPool("c3p0", newUrl(), "SA", ""));
		assertTrue(ex.getMessage().contains("c3p0"));
	}

	@Test
	void noKindMeansTheLibrarysOwnPool() throws Exception {
		String url = newUrl();
		try {
			assertTrue(ConnectionPools.getConnectionPool(null, url, "SA", "") instanceof JdbcConnectionPool);
			assertTrue(ConnectionPools.getConnectionPool(" ", url, "SA", "") instanceof JdbcConnectionPool);
		} finally {
			ConnectionPools.removeConnectionPool(null, url, "SA", "");
		}
	}
}
