package us.bringardner.parley.files.jdbcfile.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a borrower gets from the pool: a handle of its own for each checkout, connections that are
 * checked before they are handed out again, broken ones thrown away, and a connect that is bounded.
 */
public class JdbcConnectionPoolHandleTest {

	/** A stand-in for a driver connection that can be broken on demand. */
	static class Fake {
		volatile boolean valid = true;
		volatile boolean failRollback;
		volatile boolean closed;
		final Connection con = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, method, args) -> {
					switch (method.getName()) {
					case "isValid":
						return valid;
					case "isClosed":
						return closed;
					case "close":
						closed = true;
						return null;
					case "rollback":
						if( failRollback ) {
							throw new SQLException("rollback failed");
						}
						return null;
					case "getAutoCommit":
						return true;
					case "prepareStatement":
						return Proxy.newProxyInstance(getClass().getClassLoader(),
								new Class<?>[] { java.sql.PreparedStatement.class }, (p, m, a) -> null);
					default:
						Class<?> type = method.getReturnType();
						if( type == boolean.class ) {
							return false;
						}
						if( type == int.class ) {
							return 0;
						}
						return null;
					}
				});
	}

	private List<Fake> made;
	private JdbcConnectionPool pool;
	private volatile long connectDelay;

	@BeforeEach
	void setUp() {
		made = Collections.synchronizedList(new ArrayList<>());
		pool = new JdbcConnectionPool("jdbc:fake", "u", "p") {
			@Override
			public IManagedObject createObject() throws SQLException {
				try {
					Thread.sleep(connectDelay);
				} catch(InterruptedException e) {
					throw new SQLException(e);
				}
				Fake f = new Fake();
				made.add(f);
				return new ManagedJdbcConnection(f.con);
			}
		};
		pool.setMax(1);
		pool.setMin(1);
		pool.setTimeToWait(300);
		pool.setValidateAfter(0);
	}

	@AfterEach
	void tearDown() {
		pool.destroyAll();
	}

	@Test
	void everyCheckoutGetsItsOwnHandle() throws Exception {
		Connection a = pool.getConnection();
		a.close();
		Connection b = pool.getConnection();
		assertFalse(a == b);
		assertEquals(1, made.size());
	}

	@Test
	void aClosedHandleIsDeadAndClosingItAgainDoesNotFreeTheNextBorrowersConnection() throws Exception {
		Connection a = pool.getConnection();
		a.close();
		assertTrue(a.isClosed());
		assertThrows(SQLException.class, () -> a.prepareStatement("select 1"));

		Connection b = pool.getConnection();
		a.close();   // late and repeated: must not give b's connection back
		assertFalse(b.isClosed());
		assertThrows(SQLException.class, () -> pool.getConnection(), "b still has the only connection");
		b.close();
		pool.getConnection().close();
	}

	@Test
	void aConnectionTheServerDroppedWhileIdleIsReplaced() throws Exception {
		pool.getConnection().close();
		made.get(0).valid = false;

		Connection c = pool.getConnection();
		assertEquals(2, made.size());
		assertTrue(made.get(0).closed, "the bad one is closed");
		c.prepareStatement("select 1");
		c.close();
	}

	@Test
	void aConnectionWhoseResetFailsIsNotReused() throws Exception {
		Connection c = pool.getConnection();
		c.setAutoCommit(false);
		c.prepareStatement("update t set x = 1");
		made.get(0).failRollback = true;
		c.close();

		assertTrue(made.get(0).closed);
		pool.getConnection().close();
		assertEquals(2, made.size());
		assertEquals(1, pool.size(), "only the replacement is in the pool");
	}

	@Test
	void aSlowConnectIsGivenUpOnAndTheLateConnectionIsClosed() throws Exception {
		pool.setCreateTimeout(100);
		connectDelay = 600;
		long t0 = System.currentTimeMillis();
		assertThrows(SQLException.class, () -> pool.getConnection());
		assertTrue(System.currentTimeMillis() - t0 < 500, "gave up at the limit, not when the connect finished");

		Thread.sleep(1000);
		assertEquals(1, made.size());
		assertTrue(made.get(0).closed, "the connection that arrived late is not leaked");
		assertEquals(0, pool.size());

		//  and the slot is free again
		connectDelay = 0;
		pool.getConnection().close();
	}

	@Test
	void waitersGetAConnectionThatWasDiscarded() throws Exception {
		Connection c = pool.getConnection();
		c.setAutoCommit(false);
		c.prepareStatement("x");
		made.get(0).failRollback = true;

		AtomicInteger got = new AtomicInteger();
		Thread t = new Thread(() -> {
			try {
				pool.setTimeToWait(5000);
				pool.getConnection().close();
				got.incrementAndGet();
			} catch(SQLException e) {
				// reported by the count
			}
		});
		t.start();
		Thread.sleep(100);
		c.close();   // discards, which frees the slot for the waiter
		t.join(3000);
		assertEquals(1, got.get());
	}
}
