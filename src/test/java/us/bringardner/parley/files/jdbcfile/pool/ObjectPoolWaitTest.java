package us.bringardner.parley.files.jdbcfile.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * A thread waiting for an object of a full pool must get it as soon as another
 * thread releases one, not when its safety-net wait ends, and must give up at
 * the deadline, not up to a whole safety-net wait later.
 */
public class ObjectPoolWaitTest {

	/** A pool of plain objects. */
	static class TestObjectPool extends ObjectPool {
		final AtomicInteger created = new AtomicInteger();
		final AtomicInteger destroyed = new AtomicInteger();

		@Override
		public IManagedObject createObject() {
			return new ManagedObjectImp("object-" + created.incrementAndGet());
		}

		@Override
		public void destroyObject(Object obj) {
			destroyed.incrementAndGet();
		}

		@Override
		public String getName() {
			return "ObjectPoolWaitTest";
		}
	}

	private TestObjectPool pool;

	private TestObjectPool pool(int max, long timeToWait, long timeToSleep) {
		pool = new TestObjectPool();
		pool.setMin(0);
		pool.setMax(max);
		pool.setTimeToWait(timeToWait);
		pool.setTimeToSleep(timeToSleep);
		pool.setDebug(false);
		pool.setAutoDebug(Integer.MAX_VALUE);
		return pool;
	}

	@AfterEach
	public void stop() {
		if( pool != null ) {
			pool.stop();
		}
	}

	@Test
	public void aReleaseWakesTheWaiterAtOnce() throws Exception {
		// a long safety-net wait: only the release can explain a quick answer
		TestObjectPool p = pool(1, 20_000, 10_000);
		IManagedObject held = p.getObject();

		long[] gotAfter = {-1};
		IManagedObject[] got = {null};
		long t0 = System.nanoTime();
		Thread waiter = new Thread(() -> {
			try {
				got[0] = p.getObject();
				gotAfter[0] = (System.nanoTime() - t0) / 1_000_000;
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		waiter.start();
		Thread.sleep(200);
		assertNotNull(held);
		held.release();
		waiter.join(5_000);

		System.out.println("[pool wait] released after 200 ms, waiter had its object after " + gotAfter[0] + " ms");
		assertSame(held, got[0]);
		assertTrue(gotAfter[0] >= 190 && gotAfter[0] < 2_000, "waited " + gotAfter[0] + " ms");
		assertEquals(1, p.created.get(), "reused, not created");
	}

	@Test
	public void givesUpAtTheDeadline() throws Exception {
		TestObjectPool p = pool(1, 300, 10_000);
		p.getObject();

		long t0 = System.nanoTime();
		assertThrows(ObjectCreateException.class, p::getObject);
		long ms = (System.nanoTime() - t0) / 1_000_000;

		System.out.println("[pool wait] timeToWait 300 ms, safety net 10000 ms: gave up after " + ms + " ms");
		assertTrue(ms >= 290 && ms < 1_500, "gave up after " + ms + " ms");
	}

	@Test
	public void aDestroyedObjectMakesRoomForTheWaiter() throws Exception {
		TestObjectPool p = pool(1, 20_000, 10_000);
		IManagedObject held = p.getObject();

		IManagedObject[] got = {null};
		Thread waiter = new Thread(() -> {
			try {
				got[0] = p.getObject();
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		long t0 = System.nanoTime();
		waiter.start();
		Thread.sleep(100);
		held.setDestroyed();
		waiter.join(5_000);
		long ms = (System.nanoTime() - t0) / 1_000_000;

		assertNotNull(got[0]);
		assertTrue(got[0] != held);
		assertTrue(ms < 2_000, "took " + ms + " ms");
	}

	@Test
	public void awaitStartSeesTheThreadStart() throws Exception {
		TestObjectPool p = pool(1, 1_000, 500);
		assertFalse(p.awaitStart(50), "not started yet");
		p.start();
		assertTrue(p.awaitStart(5_000));
		assertTrue(p.hasStarted());
		assertTrue(p.isRunning());
		// a second start() must not start a second thread
		Thread first = p.getThread();
		p.start();
		assertSame(first, p.getThread());
	}

	@Test
	public void stopBeforeStartIsHarmlessAndStopEndsTheThread() throws Exception {
		TestObjectPool p = pool(1, 1_000, 500);
		p.stop();
		p.start();
		assertTrue(p.awaitStart(5_000));
		Thread t = p.getThread();
		p.stop();
		t.join(5_000);
		assertFalse(t.isAlive(), "the maintenance thread should end when stopped, not after its interval");
	}
}
