package us.bringardner.parley.files.jdbcfile.pool;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Opens a JDBC connection with the driver that is next to this module, whichever class loader
 * that is.
 * <p>
 * {@link DriverManager} only knows the drivers the JVM's class path registered when it started
 * (and those a program loaded by name). In a web application server a driver in the application's
 * own libraries is in a class loader {@code DriverManager} never looks in, so
 * {@code DriverManager.getConnection} fails there with "No suitable driver found" although the
 * driver is on the application's class path. This class asks {@code DriverManager} first, so
 * wherever that works nothing changes, and otherwise looks for the driver through the class loader
 * that loaded this module (and the thread's context class loader), where an application's libraries
 * are, and connects through it.
 */
public final class JdbcDrivers {

	/** The drivers found by a class loader, not by DriverManager, by the URL they were found for */
	private static final ConcurrentMap<String, Driver> found = new ConcurrentHashMap<>();

	private JdbcDrivers() {
	}

	/** (for tests) forget the drivers found, so the next connection looks again */
	static void forgetFound() {
		found.clear();
	}

	/**
	 * Opens a connection as {@code DriverManager.getConnection(url, user, password)} does, with a
	 * driver found through this module's class loader when DriverManager has none for the URL.
	 *
	 * @param user null for none
	 * @param password null for none
	 * @throws SQLException if no driver takes the URL, or it can't connect
	 */
	public static Connection connect(String url, String user, String password) throws SQLException {
		return connect(url, user, password, JdbcDrivers.class.getClassLoader());
	}

	/**
	 * As {@link #connect(String, String, String)}, looking for a driver that DriverManager doesn't
	 * have in the given class loader (and then the thread's context class loader).
	 */
	public static Connection connect(String url, String user, String password, ClassLoader loader) throws SQLException {
		Driver driver = find(url, loader);
		if( driver == null ) {
			// (DriverManager's own "No suitable driver found" message)
			return DriverManager.getConnection(url, user, password);
		}
		Properties info = new Properties();
		if( user != null ) {
			info.put("user", user);
		}
		if( password != null ) {
			info.put("password", password);
		}
		Connection ret = driver.connect(url, info);
		if( ret == null ) {
			throw new SQLException("The driver " + driver.getClass().getName() + " didn't take the URL", "08001");
		}
		return ret;
	}

	/**
	 * The name of the driver class to give a connection pool (HikariCP's and DBCP2's
	 * {@code driverClassName}) for a URL DriverManager can't find a driver for, found as in
	 * {@link #connect}; null when DriverManager has one, or none is found (the pool then does what
	 * it did).
	 */
	public static String driverClassName(String url) {
		try {
			Driver d = find(url, JdbcDrivers.class.getClassLoader());
			return d == null ? null : d.getClass().getName();
		} catch(SQLException ex) {
			return null;
		}
	}

	/**
	 * The driver to open URL with when DriverManager has none for it, else null.
	 */
	static Driver find(String url, ClassLoader loader) throws SQLException {
		if( url == null ) {
			return null;
		}
		try {
			DriverManager.getDriver(url);
			// DriverManager has one: it connects, as it always did
			return null;
		} catch(SQLException none) {
			// look further
		}
		Driver ret = found.get(url);
		if( ret != null ) {
			return ret;
		}
		ret = search(url, loader);
		if( ret == null ) {
			ClassLoader context = Thread.currentThread().getContextClassLoader();
			if( context != null && context != loader ) {
				ret = search(url, context);
			}
		}
		if( ret != null ) {
			found.putIfAbsent(url, ret);
		}
		return ret;
	}

	private static Driver search(String url, ClassLoader loader) throws SQLException {
		ServiceLoader<Driver> drivers = ServiceLoader.load(Driver.class, loader);
		Iterator<Driver> it = drivers.iterator();
		while( true ) {
			try {
				if( !it.hasNext() ) {
					return null;
				}
				Driver d = it.next();
				if( d.acceptsURL(url) ) {
					return d;
				}
			} catch(ServiceConfigurationError | SQLException ex) {
				// a driver that can't be loaded or asked isn't the one: go on to the next
			}
		}
	}
}
