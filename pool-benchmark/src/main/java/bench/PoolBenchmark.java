package bench;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Borrow and return against an in-memory H2, so the database costs next to nothing and what is
 * measured is the pool. Thread count comes from the command line (-t); the pool is shared.
 */
@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 2)
public class PoolBenchmark {

	static final String URL = "jdbc:h2:mem:bench;DB_CLOSE_DELAY=-1";

	@Param({ "parley", "hikari", "dbcp2" })
	public String pool;

	/** 10 is smaller than the 40-thread run (waiters), 50 is not. */
	@Param({ "10", "50" })
	public int size;

	private Pools.Pool p;
	private Connection keepDbAlive;

	@Setup(Level.Trial)
	public void setUp() throws Exception {
		keepDbAlive = java.sql.DriverManager.getConnection(URL, "sa", "");
		p = Pools.create(pool, URL, "sa", "", size);
		//  have every connection open before measuring
		Connection[] all = new Connection[size];
		for (int i = 0; i < size; i++) {
			all[i] = p.get();
		}
		for (Connection c : all) {
			c.close();
		}
	}

	@TearDown(Level.Trial)
	public void tearDown() throws Exception {
		p.close();
		keepDbAlive.close();
	}

	/** Borrow and return, nothing in between. */
	@Benchmark
	@BenchmarkMode(Mode.Throughput)
	public void acquireRelease(Blackhole bh) throws Exception {
		try (Connection c = p.get()) {
			bh.consume(c);
		}
	}

	/** The usual short use: borrow, one trivial query, return. */
	@Benchmark
	@BenchmarkMode({ Mode.Throughput, Mode.SampleTime })
	public void selectOne(Blackhole bh) throws Exception {
		try (Connection c = p.get();
				PreparedStatement ps = c.prepareStatement("SELECT 1");
				ResultSet rs = ps.executeQuery()) {
			rs.next();
			bh.consume(rs.getInt(1));
		}
	}
}
