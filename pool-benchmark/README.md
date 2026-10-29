# pool-benchmark

Compares this library's JDBC connection pool (`us.bringardner.parley.files.jdbcfile.pool`) with
HikariCP and Apache Commons DBCP2. It is a standalone Maven project: the pool package depends only
on JDBC, so its sources are compiled straight from `../src/main/java` and the library build is
not involved.

## Build

```
mvn -f pool-benchmark/pom.xml package
```

This makes `pool-benchmark/target/benchmarks.jar`.

## Throughput and latency (JMH)

Borrow and return against an in-memory H2, so the database costs next to nothing and the pool is
what is measured. Pool sizes 10 (smaller than the 40-thread run) and 50, for each pool.

```
./pool-benchmark/run-jmh.sh <output dir>      # 1, 8 and 40 threads, one JSON file each
java -jar pool-benchmark/target/benchmarks.jar -t 8 -p pool=parley    # one pool, one thread count
```

Benchmarks (`PoolBenchmark`): `acquireRelease` (borrow and return), `selectOne` (borrow,
`SELECT 1`, return; throughput and p99).

Single-fork, short runs: differences under about 5% are noise, and results move with whatever else
the machine is doing.

## Cold start and database restart

```
java -cp pool-benchmark/target/benchmarks.jar bench.RestartTest
```

Against an H2 TCP server in the same JVM: time from a new pool to the first answered query, and
what a warm pool does when the server is stopped for a second and started again (one
borrow + `SELECT 1` every 50 ms for 15 s).

Each pool is a fixed-size pool with a 30 s wait and otherwise on its defaults (`Pools.java`).
