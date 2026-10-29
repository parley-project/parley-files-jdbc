package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * How fast the file system is over a given connection pool: small files created, statted, read,
 * listed, appended to and deleted by several threads at once. Not part of the normal run:
 * <pre>
 * mvn test -Dtest=JdbcFileSystemPerfTest -Dparley.perf=true -Dparley.perf.pool=hikari
 * </pre>
 * (the pool is parley, hikari or dbcp2). Prints PERF lines: operations per second for each phase,
 * the median of the measured rounds, after warm-up rounds that are not counted.
 */
@EnabledIfSystemProperty(named = "parley.perf", matches = "true")
public class JdbcFileSystemPerfTest {

	private static final String POOL = System.getProperty("parley.perf.pool", "hikari");
	private static final int FILES = Integer.getInteger("parley.perf.files", 200);
	private static final int APPENDS = Integer.getInteger("parley.perf.appends", 100);
	private static final int LISTS = 20;
	private static final int WARMUP = 2;
	private static final int ROUNDS = 4;
	private static final byte[] DATA = new byte[4096];

	static {
		for (int i = 0; i < DATA.length; i++) {
			DATA[i] = (byte) ('a' + i % 26);
		}
	}

	private static final String[] PHASES = { "create", "stat", "read", "list", "append", "delete" };

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9051, POOL);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	@Test
	public void workload() throws Exception {
		JdbcFileSourceFactory factory = JdbcTestServer.factory();
		try (Connection c = factory.getConnection()) {
			System.out.println("PERF pool="+POOL+" connection class "+c.getClass().getName());
		}
		assertTrue(factory.createFileSource("/perf").mkdirs());
		try (OutputStream out = factory.createFileSource("/perf/shared.log").getOutputStream()) {
			// (made empty)
		}
		for (int threads : new int[] { 1, 8 }) {
			List<double[]> rounds = new ArrayList<>();
			for (int r = 0; r < WARMUP + ROUNDS; r++) {
				double[] seconds = round(factory, threads);
				if (r >= WARMUP) {
					rounds.add(seconds);
				}
			}
			double totalOps = 0;
			double totalSeconds = 0;
			for (int p = 0; p < PHASES.length; p++) {
				final int phase = p;
				List<Double> rate = new ArrayList<>();
				for (double[] s : rounds) {
					rate.add(ops(phase, threads) / s[phase]);
				}
				Collections.sort(rate);
				System.out.println(String.format(Locale.ROOT, "PERF pool=%s threads=%d phase=%-6s ops/s=%.0f",
						POOL, threads, PHASES[p], rate.get(rate.size() / 2)));
				totalOps += ops(phase, threads);
			}
			List<Double> all = new ArrayList<>();
			for (double[] s : rounds) {
				double sum = 0;
				for (double x : s) {
					sum += x;
				}
				all.add(sum);
			}
			Collections.sort(all);
			totalSeconds = all.get(all.size() / 2);
			System.out.println(String.format(Locale.ROOT, "PERF pool=%s threads=%d phase=%-6s ops/s=%.0f (round %.2f s)",
					POOL, threads, "TOTAL", totalOps / totalSeconds, totalSeconds));
		}
	}

	/** Operations a phase makes in all, so a rate can be had from its time. */
	private static double ops(int phase, int threads) {
		switch (PHASES[phase]) {
		case "list":
			return (double) LISTS * threads;
		case "append":
			return (double) APPENDS * threads;
		default:
			return (double) FILES * threads;
		}
	}

	/** One pass over every phase with the given number of threads; the seconds each phase took. */
	private double[] round(JdbcFileSourceFactory factory, int threads) throws Exception {
		ExecutorService exec = Executors.newFixedThreadPool(threads);
		try {
			String run = "/perf/r" + System.nanoTime();
			double[] seconds = new double[PHASES.length];
			for (int p = 0; p < PHASES.length; p++) {
				final int phase = p;
				List<Callable<Void>> tasks = new ArrayList<>();
				for (int t = 0; t < threads; t++) {
					final String dir = run + "/t" + t;
					tasks.add(() -> {
						work(factory, PHASES[phase], dir);
						return null;
					});
				}
				if (p == 0) {
					for (int t = 0; t < threads; t++) {
						assertTrue(factory.createFileSource(run + "/t" + t).mkdirs());
					}
				}
				long t0 = System.nanoTime();
				for (Future<Void> f : exec.invokeAll(tasks)) {
					f.get();
				}
				seconds[p] = (System.nanoTime() - t0) / 1e9;
			}
			factory.createFileSource(run).delete();
			return seconds;
		} finally {
			exec.shutdownNow();
		}
	}

	private void work(JdbcFileSourceFactory factory, String phase, String dir) throws Exception {
		switch (phase) {
		case "create":
			for (int i = 0; i < FILES; i++) {
				try (OutputStream out = factory.createFileSource(dir + "/f" + i).getOutputStream()) {
					out.write(DATA);
				}
			}
			break;
		case "stat":
			for (int i = 0; i < FILES; i++) {
				FileSource f = factory.createFileSource(dir + "/f" + i);
				if (!f.exists() || f.length() != DATA.length) {
					throw new IllegalStateException(dir + "/f" + i + " is not as written");
				}
			}
			break;
		case "read":
			for (int i = 0; i < FILES; i++) {
				try (InputStream in = factory.createFileSource(dir + "/f" + i).getInputStream()) {
					if (in.readAllBytes().length != DATA.length) {
						throw new IllegalStateException(dir + "/f" + i + " is short");
					}
				}
			}
			break;
		case "list":
			for (int i = 0; i < LISTS; i++) {
				if (factory.createFileSource(dir).list().length != FILES) {
					throw new IllegalStateException(dir + " lists the wrong number of files");
				}
			}
			break;
		case "append":
			for (int i = 0; i < APPENDS; i++) {
				try (OutputStream out = factory.createFileSource("/perf/shared.log").getOutputStream(true)) {
					out.write((dir + " line " + i + "\n").getBytes(StandardCharsets.UTF_8));
				}
			}
			break;
		case "delete":
			for (int i = 0; i < FILES; i++) {
				if (!factory.createFileSource(dir + "/f" + i).delete()) {
					throw new IllegalStateException("can't delete " + dir + "/f" + i);
				}
			}
			break;
		default:
			throw new IllegalArgumentException(phase);
		}
	}
}
