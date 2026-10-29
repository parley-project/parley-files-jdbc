package bench;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.h2.tools.Server;

/**
 * Two checks the throughput numbers can't show, against a real TCP connection to an H2 server in
 * this JVM:
 * <ol>
 * <li>time from "new pool" to the first query's answer (cold start), and</li>
 * <li>what happens to a warm pool when the database server is stopped and started again.</li>
 * </ol>
 */
public class RestartTest {

	static final int PORT = 9192;
	static final String URL = "jdbc:h2:tcp://localhost:" + PORT + "/mem:rt";
	static final int SIZE = 5;

	static Server server;

	static void startServer() throws Exception {
		server = Server.createTcpServer("-tcpPort", String.valueOf(PORT), "-ifNotExists").start();
	}

	static void stopServer() {
		server.stop();
	}

	/** Borrow, run SELECT 1, return. Throws on any failure. */
	static void query(Pools.Pool p) throws Exception {
		try (Connection c = p.get();
				PreparedStatement ps = c.prepareStatement("SELECT 1");
				ResultSet rs = ps.executeQuery()) {
			rs.next();
		}
	}

	public static void main(String[] args) throws Exception {
		startServer();
		System.out.println("== 1. Cold start: new pool (size " + SIZE + ") to first query answered, ms ==");
		for (String name : Pools.NAMES) {
			List<Double> ms = new ArrayList<>();
			for (int i = 0; i < 15; i++) {
				long t0 = System.nanoTime();
				Pools.Pool p = Pools.create(name, URL, "sa", "", SIZE);
				query(p);
				double e = (System.nanoTime() - t0) / 1e6;
				p.close();
				if (i >= 5) {
					ms.add(e); // the first five are JIT and class loading
				}
			}
			ms.sort(null);
			System.out.printf("%-7s median %.1f  min %.1f  max %.1f%n", name, ms.get(ms.size() / 2), ms.get(0),
					ms.get(ms.size() - 1));
		}

		System.out.println();
		System.out.println("== 2. Database restart: warm pool of " + SIZE + ", server stopped 1 s, then started ==");
		System.out.println("   one borrow+SELECT 1 every 50 ms for 15 s after the server is back (5 s per-attempt limit)");
		ExecutorService ex = Executors.newCachedThreadPool(r -> {
			Thread t = new Thread(r);
			t.setDaemon(true);
			return t;
		});
		for (String name : Pools.NAMES) {
			Pools.Pool p = Pools.create(name, URL, "sa", "", SIZE);
			//  have all the connections open
			Connection[] all = new Connection[SIZE];
			for (int i = 0; i < SIZE; i++) {
				all[i] = p.get();
				try (PreparedStatement ps = all[i].prepareStatement("SELECT 1"); ResultSet rs = ps.executeQuery()) {
					rs.next();
				}
			}
			for (Connection c : all) {
				c.close();
			}

			stopServer();
			Thread.sleep(1000);
			startServer();

			long t0 = System.nanoTime();
			int attempts = 0, failures = 0, consecutiveOk = 0;
			long firstOkMs = -1, recoveredMs = -1, lastFailMs = -1;
			String firstError = null;
			while ((System.nanoTime() - t0) < 15_000_000_000L) {
				attempts++;
				long now = (System.nanoTime() - t0) / 1_000_000;
				Future<Object> f = ex.submit((Callable<Object>) () -> {
					query(p);
					return null;
				});
				try {
					f.get(5, TimeUnit.SECONDS);
					if (firstOkMs < 0) {
						firstOkMs = now;
					}
					if (++consecutiveOk == 3 && recoveredMs < 0) {
						recoveredMs = now;
					}
				} catch (TimeoutException e) {
					f.cancel(true);
					failures++;
					consecutiveOk = 0;
					lastFailMs = now;
					if (firstError == null) {
						firstError = "attempt hung > 5 s";
					}
				} catch (Exception e) {
					failures++;
					consecutiveOk = 0;
					lastFailMs = now;
					if (firstError == null) {
						Throwable c = e.getCause() != null ? e.getCause() : e;
						firstError = c.getClass().getSimpleName() + ": " + String.valueOf(c.getMessage()).replace('\n', ' ');
					}
				}
				Thread.sleep(50);
			}
			System.out.printf("%-7s attempts %d, failed %d, first success at %s ms, 3 in a row at %s ms, last failure at %s ms%n",
					name, attempts, failures, firstOkMs < 0 ? "never" : firstOkMs,
					recoveredMs < 0 ? "never" : recoveredMs, lastFailMs < 0 ? "none" : lastFailMs);
			if (firstError != null) {
				System.out.println("        first error: " + firstError);
			}
			p.close();
		}
		stopServer();
		System.exit(0);
	}
}
