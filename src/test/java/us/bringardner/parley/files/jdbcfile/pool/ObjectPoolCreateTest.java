package us.bringardner.parley.files.jdbcfile.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Opening a connection can take seconds. It must not hold up the threads that
 * only want to get or release one that is already there, the pool must still
 * never grow past its maximum, and a connection that can't be opened must
 * give its slot back.
 */
public class ObjectPoolCreateTest {

	/** A pool whose objects take as long to create as the test says. */
	static class SlowPool extends ObjectPool {
		final AtomicInteger started = new AtomicInteger();
		final AtomicInteger finished = new AtomicInteger();
		final AtomicInteger destroyed = new AtomicInteger();
		volatile long createMillis;
		volatile CountDownLatch gate;
		volatile int failFirst;

		@Override
		public IManagedObject createObject() throws Exception {
			int n = started.incrementAndGet();
			if( n <= failFirst ) {
				throw new java.sql.SQLException("Can't connect (" + n + ")");
			}
			CountDownLatch g = gate;
			if( g != null && n > 1 ) {
				g.await(30, TimeUnit.SECONDS);
			}
			if( createMillis > 0 ) {
				Thread.sleep(createMillis);
			}
			finished.incrementAndGet();
			return new ManagedObjectImp("object-" + n);
		}

		@Override
		public void destroyObject(Object obj) {
			destroyed.incrementAndGet();
		}

		@Override
		public String getName() {
			return "ObjectPoolCreateTest";
		}
	}

	private SlowPool pool;

	private SlowPool pool(int max, long timeToWait) {
		pool = new SlowPool();
		pool.setMin(0);
		pool.setMax(max);
		pool.setTimeToWait(timeToWait);
		pool.setTimeToSleep(10_000);
		pool.setDebug(false);
		pool.setAutoDebug(Integer.MAX_VALUE);
		return pool;
	}

	@AfterEach
	public void cleanup() {
		if( pool != null ) {
			if( pool.gate != null ) {
				pool.gate.countDown();
			}
			pool.stop();
		}
	}

	private static Thread async(Runnable r) {
		Thread t = new Thread(r);
		t.setDaemon(true);
		t.start();
		return t;
	}

	@Test
	public void getAndReleaseAreNotBlockedWhileAConnectionOpens() throws Exception {
		SlowPool p = pool(3, 30_000);
		IManagedObject first = p.getObject();
		p.gate = new CountDownLatch(1);

		// this one sits in createObject() until the gate opens
		IManagedObject[] second = {null};
		Thread slow = async(() -> {
			try {
				second[0] = p.getObject();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		long deadline = System.currentTimeMillis() + 5_000;
		while( p.started.get() < 2 && System.currentTimeMillis() < deadline ) {
			Thread.sleep(5);
		}
		assertEquals(2, p.started.get(), "the second connection should be opening");

		long t0 = System.nanoTime();
		first.release();
		IManagedObject again = p.getObject();
		long ms = (System.nanoTime() - t0) / 1_000_000;
		System.out.println("[pool create] release + get while a connection was opening: " + ms + " ms");

		assertSame(first, again);
		assertTrue(ms < 1_000, "was held up for " + ms + " ms by the connection being opened");
		assertEquals(null, second[0], "still opening");

		p.gate.countDown();
		slow.join(5_000);
		assertNotNull(second[0]);
		assertEquals(2, p.size());
	}

	@Test
	public void connectionsOpenAtTheSameTime() throws Exception {
		SlowPool p = pool(4, 30_000);
		p.createMillis = 400;

		List<Thread> threads = new ArrayList<Thread>();
		long t0 = System.nanoTime();
		for(int i = 0; i < 4; i++) {
			threads.add(async(() -> {
				try {
					p.getObject();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}));
		}
		for(Thread t : threads) {
			t.join(10_000);
		}
		long ms = (System.nanoTime() - t0) / 1_000_000;
		System.out.println("[pool create] 4 connections of 400 ms each: " + ms + " ms");

		assertEquals(4, p.size());
		// one after the other would take 1600 ms
		assertTrue(ms < 1_100, "took " + ms + " ms");
	}

	@Test
	public void neverMoreThanMax() throws Exception {
		SlowPool p = pool(3, 1_500);
		p.createMillis = 100;
		AtomicInteger got = new AtomicInteger();
		AtomicInteger refused = new AtomicInteger();

		List<Thread> threads = new ArrayList<Thread>();
		for(int i = 0; i < 12; i++) {
			threads.add(async(() -> {
				try {
					p.getObject();
					got.incrementAndGet();
				} catch (ObjectCreateException e) {
					refused.incrementAndGet();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}));
		}
		for(Thread t : threads) {
			t.join(10_000);
		}

		assertEquals(3, got.get(), "only max objects can be in use");
		assertEquals(9, refused.get());
		assertEquals(3, p.started.get(), "nothing created past max");
		assertEquals(3, p.size());
	}

	@Test
	public void aFailedConnectionGivesItsSlotBack() throws Exception {
		SlowPool p = pool(1, 5_000);
		p.failFirst = 2;

		assertThrows(java.sql.SQLException.class, p::getObject);
		assertThrows(java.sql.SQLException.class, p::getObject);
		// with max 1, a slot lost to the failures would make this wait out the deadline
		long t0 = System.nanoTime();
		assertNotNull(p.getObject());
		long ms = (System.nanoTime() - t0) / 1_000_000;
		assertTrue(ms < 1_000, "took " + ms + " ms");
		assertEquals(1, p.size());
	}

	@Test
	public void anObjectCreatedAsThePoolIsDestroyedIsNotLeaked() throws Exception {
		SlowPool p = pool(2, 5_000);
		p.getObject();
		p.gate = new CountDownLatch(1);

		Throwable[] error = {null};
		Thread slow = async(() -> {
			try {
				p.getObject();
			} catch (Throwable e) {
				error[0] = e;
			}
		});
		long deadline = System.currentTimeMillis() + 5_000;
		while( p.started.get() < 2 && System.currentTimeMillis() < deadline ) {
			Thread.sleep(5);
		}
		int before = p.destroyed.get();
		p.destroyAll();
		p.gate.countDown();
		slow.join(5_000);

		assertTrue(error[0] instanceof ObjectCreateException, "was " + error[0]);
		assertEquals(0, p.size());
		// the first object, destroyed by destroyAll(), and the one that finished after it
		assertEquals(before + 2, p.destroyed.get());
	}
}
