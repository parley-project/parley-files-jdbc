package bench;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.SQLException;

import org.apache.commons.dbcp2.BasicDataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import us.bringardner.parley.files.jdbcfile.pool.JdbcConnectionPool;

/**
 * The three pools behind one small interface, each configured as a fixed-size pool
 * of {@code size} connections with a 30 s wait, and otherwise left on its defaults.
 */
public final class Pools {

	public interface Pool extends Closeable {
		Connection get() throws SQLException;
		@Override
		void close();
	}

	public static final String[] NAMES = { "parley", "hikari", "dbcp2" };

	private Pools() {
	}

	public static Pool create(String name, String url, String user, String pass, int size) throws SQLException {
		switch (name) {
		case "parley":
			return parley(url, user, pass, size);
		case "hikari":
			return hikari(url, user, pass, size);
		case "dbcp2":
			return dbcp2(url, user, pass, size);
		default:
			throw new IllegalArgumentException(name);
		}
	}

	private static Pool parley(String url, String user, String pass, int size) {
		final JdbcConnectionPool p = new JdbcConnectionPool(url, user, pass);
		p.setMax(size);
		p.setMin(size);
		p.setTimeToWait(30_000);
		p.start();
		return new Pool() {
			public Connection get() throws SQLException {
				return p.getConnection();
			}

			public void close() {
				p.stop();
				p.destroyAll();
			}
		};
	}

	private static Pool hikari(String url, String user, String pass, int size) {
		HikariConfig c = new HikariConfig();
		c.setJdbcUrl(url);
		c.setUsername(user);
		c.setPassword(pass);
		c.setMaximumPoolSize(size);
		c.setMinimumIdle(size);
		c.setConnectionTimeout(30_000);
		final HikariDataSource ds = new HikariDataSource(c);
		return new Pool() {
			public Connection get() throws SQLException {
				return ds.getConnection();
			}

			public void close() {
				ds.close();
			}
		};
	}

	private static Pool dbcp2(String url, String user, String pass, int size) throws SQLException {
		final BasicDataSource ds = new BasicDataSource();
		ds.setUrl(url);
		ds.setUsername(user);
		ds.setPassword(pass);
		ds.setMaxTotal(size);
		ds.setMaxIdle(size);
		ds.setMinIdle(size);
		ds.setInitialSize(size);
		ds.setMaxWait(java.time.Duration.ofSeconds(30));
		return new Pool() {
			public Connection get() throws SQLException {
				return ds.getConnection();
			}

			public void close() {
				try {
					ds.close();
				} catch (SQLException e) {
					// being thrown away
				}
			}
		};
	}
}
