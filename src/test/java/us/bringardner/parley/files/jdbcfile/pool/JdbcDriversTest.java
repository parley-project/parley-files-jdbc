package us.bringardner.parley.files.jdbcfile.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A driver that DriverManager doesn't know, as in a web application whose libraries are in a
 * class loader of their own: the connection is made with the driver found through a class loader.
 */
public class JdbcDriversTest {

	private static final String URL_PREFIX = "jdbc:fakedrv:";

	/** A driver that nothing registered with DriverManager: only a services file names it. */
	public static class FakeDriver implements Driver {
		static final List<Properties> connects = new ArrayList<>();

		@Override
		public Connection connect(String url, Properties info) throws SQLException {
			if( !acceptsURL(url) ) {
				return null;
			}
			synchronized (connects) {
				connects.add((Properties) info.clone());
			}
			return (Connection) Proxy.newProxyInstance(FakeDriver.class.getClassLoader(), new Class<?>[] { Connection.class },
					(proxy, method, args) -> {
						switch( method.getName() ) {
						case "isClosed": return false;
						case "close": return null;
						case "toString": return "fake connection to " + url;
						case "hashCode": return System.identityHashCode(proxy);
						case "equals": return proxy == args[0];
						default: throw new SQLFeatureNotSupportedException(method.getName());
						}
					});
		}

		@Override
		public boolean acceptsURL(String url) {
			return url != null && url.startsWith(URL_PREFIX);
		}

		@Override
		public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
			return new DriverPropertyInfo[0];
		}

		@Override
		public int getMajorVersion() {
			return 1;
		}

		@Override
		public int getMinorVersion() {
			return 0;
		}

		@Override
		public boolean jdbcCompliant() {
			return false;
		}

		@Override
		public Logger getParentLogger() throws SQLFeatureNotSupportedException {
			throw new SQLFeatureNotSupportedException();
		}
	}

	@TempDir
	Path dir;

	private URLClassLoader loader;

	@BeforeEach
	void setUp() throws IOException {
		// the driver's services file is only in this class loader, which DriverManager never looks in
		Path services = dir.resolve("META-INF/services");
		Files.createDirectories(services);
		Files.write(services.resolve("java.sql.Driver"), (FakeDriver.class.getName() + "\n").getBytes(StandardCharsets.UTF_8));
		loader = new URLClassLoader(new URL[] { dir.toUri().toURL() }, JdbcDriversTest.class.getClassLoader());
		synchronized (FakeDriver.connects) {
			FakeDriver.connects.clear();
		}
		JdbcDrivers.forgetFound();
	}

	@AfterEach
	void tearDown() throws IOException {
		loader.close();
		JdbcDrivers.forgetFound();
	}

	@Test
	void driverManagerDoesNotKnowTheDriver() {
		SQLException e = assertThrows(SQLException.class, () -> DriverManager.getConnection(URL_PREFIX + "a", "u", "p"));
		assertTrue(e.getMessage().contains("No suitable driver"), e.getMessage());
	}

	@Test
	void connectsWithTheDriverTheClassLoaderFinds() throws SQLException {
		try (Connection c = JdbcDrivers.connect(URL_PREFIX + "b", "me", "secret", loader)) {
			assertNotNull(c);
			assertTrue(c.toString().contains(URL_PREFIX + "b"), c.toString());
		}
		synchronized (FakeDriver.connects) {
			assertEquals(1, FakeDriver.connects.size());
			assertEquals("me", FakeDriver.connects.get(0).getProperty("user"));
			assertEquals("secret", FakeDriver.connects.get(0).getProperty("password"));
		}
	}

	@Test
	void noUserOrPasswordIsNotSent() throws SQLException {
		try (Connection c = JdbcDrivers.connect(URL_PREFIX + "c", null, null, loader)) {
			assertNotNull(c);
		}
		synchronized (FakeDriver.connects) {
			assertFalse(FakeDriver.connects.get(0).containsKey("user"));
			assertFalse(FakeDriver.connects.get(0).containsKey("password"));
		}
	}

	@Test
	void theThreadsContextClassLoaderIsLookedInToo() throws SQLException {
		// a web application's libraries are its threads' context class loader
		ClassLoader before = Thread.currentThread().getContextClassLoader();
		Thread.currentThread().setContextClassLoader(loader);
		try (Connection c = JdbcDrivers.connect(URL_PREFIX + "d", "u", "p")) {
			assertNotNull(c);
		} finally {
			Thread.currentThread().setContextClassLoader(before);
		}
	}

	@Test
	void theDriverFoundIsKeptForTheNextConnection() throws SQLException {
		try (Connection c = JdbcDrivers.connect(URL_PREFIX + "e", "u", "p", loader)) {
			assertNotNull(c);
		}
		// found before, so this needs no class loader that has it
		try (Connection c = JdbcDrivers.connect(URL_PREFIX + "e", "u", "p", getClass().getClassLoader())) {
			assertNotNull(c);
		}
		synchronized (FakeDriver.connects) {
			assertEquals(2, FakeDriver.connects.size());
		}
	}

	@Test
	void aDriverDriverManagerHasIsLeftToDriverManager() throws SQLException {
		// HSQLDB is registered with DriverManager: nothing changes for it
		try (Connection c = JdbcDrivers.connect("jdbc:hsqldb:mem:jdbcdrivers", "SA", "", loader)) {
			assertNotNull(c);
			assertFalse(Proxy.isProxyClass(c.getClass()));
			assertFalse(c.isClosed());
		}
		assertEquals(null, JdbcDrivers.driverClassName("jdbc:hsqldb:mem:jdbcdrivers"));
	}

	@Test
	void noDriverAnywhereIsDriverManagersError() {
		SQLException e = assertThrows(SQLException.class, () -> JdbcDrivers.connect("jdbc:nodriver:x", "u", "p", loader));
		assertTrue(e.getMessage().contains("No suitable driver"), e.getMessage());
	}
}
