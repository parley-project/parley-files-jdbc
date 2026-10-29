package us.bringardner.parley.files.jdbcfile.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/**
 * The pool's locks are held for bookkeeping only: closing a connection (a call to the server that
 * may hang) happens with no lock held, and finding a pool by url takes no lock on the class.
 */
public class JdbcConnectionPoolLockTest {

	@Test
	void aHangingCloseDoesNotStallBorrowersOrTheirReleases() throws Exception {
		CountDownLatch closing = new CountDownLatch(1);
		CountDownLatch unblock = new CountDownLatch(1);

		JdbcConnectionPool pool = new JdbcConnectionPool("jdbc:fake", "u", "p") {
			private int made;

			@Override
			public IManagedObject createObject() throws SQLException {
				final boolean hang = made++ == 0;
				return new ManagedJdbcConnection((Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
						new Class<?>[] { Connection.class }, (proxy, method, args) -> {
							switch (method.getName()) {
							case "close":
								if( hang ) {
									closing.countDown();
									unblock.await();
								}
								return null;
							case "getAutoCommit":
								return true;
							default:
								Class<?> type = method.getReturnType();
								return type == boolean.class ? (Object) false : type == int.class ? (Object) 0 : null;
							}
						}));
			}
		};
		pool.setMax(2);
		pool.setValidateAfter(-1);
		ExecutorService ex = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r);
			t.setDaemon(true);
			return t;
		});
		try {
			Connection first = pool.getConnection();
			Connection second = pool.getConnection();

			Thread destroyer = new Thread(pool::destroyAll);
			destroyer.setDaemon(true);
			destroyer.start();
			assertTrue(closing.await(2, TimeUnit.SECONDS), "destroyAll is inside the hung close");

			//  Both need the lock on the pool; neither may wait for the close
			Future<Integer> size = ex.submit((Callable<Integer>) pool::size);
			assertEquals(0, size.get(2, TimeUnit.SECONDS).intValue());
			Future<Object> release = ex.submit(() -> {
				second.close();
				return null;
			});
			release.get(2, TimeUnit.SECONDS);

			unblock.countDown();
			destroyer.join(2000);
			assertFalse(destroyer.isAlive());
			assertTrue(first.isClosed());
		} finally {
			unblock.countDown();
			ex.shutdownNow();
		}
	}

	@Test
	void threadsAskingForTheSamePoolGetTheSameOneAndRemovingItEndsItsThread() throws Exception {
		final String url = "jdbc:fake:lock-test";
		int n = 8;
		ExecutorService ex = Executors.newFixedThreadPool(n);
		try {
			CyclicBarrier together = new CyclicBarrier(n);
			List<Future<JdbcConnectionPool>> found = new ArrayList<>();
			for( int i = 0; i < n; i++ ) {
				found.add(ex.submit(() -> {
					together.await();
					return JdbcConnectionPool.getConnectionPool(url, "u", "p");
				}));
			}
			JdbcConnectionPool first = found.get(0).get(5, TimeUnit.SECONDS);
			for( Future<JdbcConnectionPool> f : found ) {
				assertSame(first, f.get(5, TimeUnit.SECONDS));
			}

			assertTrue(first.awaitStart(2000));
			Thread t = first.getThread();
			JdbcConnectionPool.removeConnectionPool(url, "u", "p");
			t.join(3000);
			assertFalse(t.isAlive(), "the removed pool's thread ended");

			JdbcConnectionPool again = JdbcConnectionPool.getConnectionPool(url, "u", "p");
			assertNotSame(first, again);
			JdbcConnectionPool.removeConnectionPool(url, "u", "p");
		} finally {
			ex.shutdownNow();
		}
	}
}
