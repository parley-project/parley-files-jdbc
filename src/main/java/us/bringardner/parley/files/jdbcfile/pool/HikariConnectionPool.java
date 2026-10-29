package us.bringardner.parley.files.jdbcfile.pool;

import java.sql.Connection;
import java.sql.SQLException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

/**
 * A {@link ConnectionPool} over HikariCP, sized from the {@link ObjectPool} defaults (maximum, minimum,
 * wait, longest life, idle expiry). Needs HikariCP on the classpath; it is not a dependency of this library.
 */
public final class HikariConnectionPool implements ConnectionPool {

	private final HikariDataSource ds;

	/**
	 * Opens the pool, and a first connection with it, so a wrong URL or login fails here.
	 * @throws SQLException if the pool can't open
	 */
	public HikariConnectionPool(String url, String userid, String passwd) throws SQLException {
		HikariConfig c = new HikariConfig();
		c.setJdbcUrl(url);
		if( userid != null ) {
			c.setUsername(userid);
		}
		if( passwd != null ) {
			c.setPassword(passwd);
		}
		int max = ObjectPool.getDefaultMax();
		c.setMaximumPoolSize(max);
		c.setMinimumIdle(Math.min(ObjectPool.getDefaultMin(), max));
		c.setConnectionTimeout(Math.max(250, ObjectPool.getDefaultTimeToWait()));
		long life = ObjectPool.getDefaultMaxTTL();
		if( life > 0 ) {
			c.setMaxLifetime(Math.max(30_000, life));
		}
		long idle = ObjectPool.getDefaultExpire();
		if( idle > 0 ) {
			c.setIdleTimeout(Math.max(10_000, idle));
		}
		try {
			ds = new HikariDataSource(c);
		} catch(RuntimeException ex) {
			throw new SQLException("Can't open a HikariCP pool: "+ex.getMessage(), ex);
		}
	}

	public Connection getConnection() throws SQLException {
		return ds.getConnection();
	}

	public boolean isRunning() {
		return !ds.isClosed();
	}

	public boolean awaitStart(long timeoutMillis) {
		//  it opened in the constructor
		return !ds.isClosed();
	}

	public void destroyAll() {
		HikariPoolMXBean pool = ds.getHikariPoolMXBean();
		if( pool != null ) {
			//  idle connections now, the ones in use as they come back
			pool.softEvictConnections();
		}
	}

	public void close() {
		ds.close();
	}
}
