package us.bringardner.parley.files.jdbcfile.pool;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * What the file system needs from a pool of JDBC connections: this library's own
 * ({@link JdbcConnectionPool}), HikariCP or Apache Commons DBCP2. Choose one with
 * {@link ConnectionPools#getConnectionPool(String, String, String, String)}.
 */
public interface ConnectionPool {

	/**
	 * A connection to give back with close(). Waits for one if the pool is at its maximum, up to the
	 * pool's wait limit.
	 */
	Connection getConnection() throws SQLException;

	/** Whether the pool is open: false once {@link #close()} was called. */
	boolean isRunning();

	/**
	 * Wait until the pool is ready to hand out connections (this library's own starts a thread).
	 * @return false if the time ran out or the pool was stopped first
	 */
	boolean awaitStart(long timeoutMillis) throws InterruptedException;

	/** Close the connections the pool has. The pool stays open and makes new ones when asked. */
	void destroyAll();

	/** Close the pool for good. A pool shared by {@link ConnectionPools} is closed by removing it there. */
	void close();
}
