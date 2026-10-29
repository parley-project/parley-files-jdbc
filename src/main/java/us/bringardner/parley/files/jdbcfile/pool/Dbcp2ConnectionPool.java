package us.bringardner.parley.files.jdbcfile.pool;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;

import org.apache.commons.dbcp2.BasicDataSource;

/**
 * A {@link ConnectionPool} over Apache Commons DBCP2, sized from the {@link ObjectPool} defaults
 * (maximum, minimum, wait, longest life, idle expiry). Needs commons-dbcp2 on the classpath; it is not a
 * dependency of this library.
 */
public final class Dbcp2ConnectionPool implements ConnectionPool {

	private final BasicDataSource ds = new BasicDataSource();

	/**
	 * Opens the pool and one connection with it, so a wrong URL or login fails here.
	 * @throws SQLException if the pool can't open
	 */
	public Dbcp2ConnectionPool(String url, String userid, String passwd) throws SQLException {
		ds.setUrl(url);
		if( userid != null ) {
			ds.setUsername(userid);
		}
		if( passwd != null ) {
			ds.setPassword(passwd);
		}
		int max = ObjectPool.getDefaultMax();
		ds.setMaxTotal(max);
		ds.setMaxIdle(max);
		ds.setMinIdle(Math.min(ObjectPool.getDefaultMin(), max));
		ds.setMaxWait(Duration.ofMillis(ObjectPool.getDefaultTimeToWait()));
		long life = ObjectPool.getDefaultMaxTTL();
		if( life > 0 ) {
			ds.setMaxConn(Duration.ofMillis(life));
		}
		long idle = ObjectPool.getDefaultExpire();
		if( idle > 0 ) {
			ds.setMinEvictableIdle(Duration.ofMillis(idle));
			ds.setDurationBetweenEvictionRuns(Duration.ofMillis(ObjectPool.getDefaultInterval()));
		}
		try (Connection first = ds.getConnection()) {
			// opened, and closed to the pool
		} catch(SQLException | RuntimeException ex) {
			try { ds.close(); } catch(Exception ignore) {}
			throw ex;
		}
	}

	public Connection getConnection() throws SQLException {
		return ds.getConnection();
	}

	public boolean isRunning() {
		return !ds.isClosed();
	}

	public boolean awaitStart(long timeoutMillis) {
		return !ds.isClosed();
	}

	public void destroyAll() {
		try {
			//  closes every connection; the next getConnection() opens the pool again
			ds.restart();
		} catch(SQLException | RuntimeException ex) {
			// being thrown away anyway
		}
	}

	public void close() {
		try {
			ds.close();
		} catch(SQLException ex) {
			// being thrown away anyway
		}
	}
}
