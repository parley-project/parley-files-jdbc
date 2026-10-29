package us.bringardner.parley.files.jdbcfile.pool;

import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The pools that are open, one for each kind, URL and login, shared by everyone who asks for it. The
 * kinds:
 * <ul>
 * <li>{@value #PARLEY}: this library's own {@link JdbcConnectionPool} (the default),</li>
 * <li>{@value #HIKARI}: HikariCP ({@link HikariConnectionPool}),</li>
 * <li>{@value #DBCP2}: Apache Commons DBCP2 ({@link Dbcp2ConnectionPool}).</li>
 * </ul>
 * HikariCP and DBCP2 are not dependencies of this library: put the one you choose on the classpath.
 * Sizes and time limits for all three come from the {@link ObjectPool} defaults.
 */
public final class ConnectionPools {

	public static final String PARLEY = "parley";
	public static final String HIKARI = "hikari";
	public static final String DBCP2 = "dbcp2";

	/** The pools that are not JdbcConnectionPool, which keeps its own. */
	private static final ConcurrentMap<String, ConnectionPool> others = new ConcurrentHashMap<String, ConnectionPool>();

	private ConnectionPools() {
	}

	private static String kind(String type) throws SQLException {
		String t = type == null || type.trim().isEmpty() ? PARLEY : type.trim().toLowerCase(Locale.ROOT);
		if( !(PARLEY.equals(t) || HIKARI.equals(t) || DBCP2.equals(t)) ) {
			throw new SQLException("Unknown connection pool '"+type+"': use "+PARLEY+", "+HIKARI+" or "+DBCP2);
		}
		return t;
	}

	/**
	 * The pool of this kind for url, userid and passwd, opened if it is not yet.
	 * @param type {@value #PARLEY}, {@value #HIKARI} or {@value #DBCP2}; null or empty is {@value #PARLEY}
	 * @throws SQLException if the kind is unknown, its library is missing, or the pool can't open
	 */
	@SuppressWarnings("deprecation")
	public static ConnectionPool getConnectionPool(String type, String url, String userid, String passwd)
			throws SQLException {
		String kind = kind(type);
		if( PARLEY.equals(kind) ) {
			return JdbcConnectionPool.getConnectionPool(url, userid, passwd);
		}
		String key = kind+":"+JdbcConnectionPool.key(url, userid, passwd);
		ConnectionPool pool = others.get(key);
		if( pool == null ) {
			try {
				pool = others.computeIfAbsent(key, k -> {
					try {
						return open(kind, url, userid, passwd);
					} catch(SQLException ex) {
						throw new Failed(ex);
					}
				});
			} catch(Failed ex) {
				throw ex.failure;
			}
		}
		return pool;
	}

	/**
	 * Closes the pool and forgets it, so the next getConnectionPool makes a new one. A pool whose
	 * login failed must not be reused.
	 */
	@SuppressWarnings("deprecation")
	public static void removeConnectionPool(String type, String url, String userid, String passwd) {
		String kind;
		try {
			kind = kind(type);
		} catch(SQLException ex) {
			return;
		}
		if( PARLEY.equals(kind) ) {
			JdbcConnectionPool.removeConnectionPool(url, userid, passwd);
			return;
		}
		ConnectionPool pool = others.remove(kind+":"+JdbcConnectionPool.key(url, userid, passwd));
		if( pool != null ) {
			try {
				pool.close();
			} catch(RuntimeException ex) {
				// it's being thrown away anyway
			}
		}
	}

	/**
	 * Made by name, so this class (and so the file system) loads and runs without HikariCP or DBCP2.
	 */
	private static ConnectionPool open(String kind, String url, String userid, String passwd) throws SQLException {
		String adapter;
		String library;
		String probe;
		if( HIKARI.equals(kind) ) {
			adapter = "us.bringardner.parley.files.jdbcfile.pool.HikariConnectionPool";
			library = "HikariCP (com.zaxxer:HikariCP)";
			probe = "com.zaxxer.hikari.HikariDataSource";
		} else {
			adapter = "us.bringardner.parley.files.jdbcfile.pool.Dbcp2ConnectionPool";
			library = "Apache Commons DBCP2 (org.apache.commons:commons-dbcp2)";
			probe = "org.apache.commons.dbcp2.BasicDataSource";
		}
		try {
			Class.forName(probe, false, ConnectionPools.class.getClassLoader());
		} catch(ClassNotFoundException | LinkageError ex) {
			throw new SQLException("The "+kind+" connection pool needs "+library+" on the classpath", ex);
		}
		try {
			return (ConnectionPool) Class.forName(adapter)
					.getConstructor(String.class, String.class, String.class)
					.newInstance(url, userid, passwd);
		} catch(java.lang.reflect.InvocationTargetException ex) {
			Throwable cause = ex.getCause();
			if( cause instanceof SQLException ) {
				throw (SQLException) cause;
			}
			throw new SQLException("Can't open the "+kind+" connection pool: "+cause, cause);
		} catch(ReflectiveOperationException | LinkageError ex) {
			throw new SQLException("Can't open the "+kind+" connection pool: "+ex, ex);
		}
	}

	/** Carries an SQLException out of computeIfAbsent. */
	private static final class Failed extends RuntimeException {
		private static final long serialVersionUID = 1L;
		final SQLException failure;

		Failed(SQLException failure) {
			super(failure);
			this.failure = failure;
		}
	}
}
