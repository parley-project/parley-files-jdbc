package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/** jdbcAccessTime: by default opening a file for reading writes its access time, set to false it does not. */
public class JdbcAccessTimeTest {

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9056);
		assertTrue(JdbcTestServer.factory().createFileSource("/atime").mkdirs());
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	/** A second factory on the same database with the setting given (null: left out). */
	private static JdbcFileSourceFactory factory(String accessTime) throws Exception {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		Properties p = JdbcTestServer.factory().getConnectProperties();
		if (accessTime == null) {
			p.remove(JdbcFileSourceFactory.JDBC_ACCESS_TIME);
		} else {
			p.setProperty(JdbcFileSourceFactory.JDBC_ACCESS_TIME, accessTime);
		}
		f.setConnectionProperties(p);
		assertTrue(f.connect());
		return f;
	}

	private static void write(JdbcFileSourceFactory f, String path, String text) throws Exception {
		try (OutputStream out = f.createFileSource(path).getOutputStream()) {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void read(JdbcFileSourceFactory f, String path) throws Exception {
		try (InputStream in = f.createFileSource(path).getInputStream()) {
			in.readAllBytes();
		}
	}

	private static long atime(JdbcFileSourceFactory f, String path) throws Exception {
		return f.createFileSource(path).lastAccessTime();
	}

	private static void pause() throws Exception {
		Thread.sleep(1100);
	}

	@Test
	public void readingWritesTheAccessTimeByDefault() throws Exception {
		for (String setting : new String[] { null, "true", "garbage" }) {
			JdbcFileSourceFactory f = factory(setting);
			String path = "/atime/default-" + setting + ".txt";
			write(f, path, "x");
			long before = atime(f, path);
			pause();
			read(f, path);
			assertTrue(atime(factory(setting), path) > before, "setting " + setting);
		}
	}

	@Test
	public void readingLeavesTheAccessTimeAloneWhenFalse() throws Exception {
		for (String setting : new String[] { "false", "FALSE", " false " }) {
			JdbcFileSourceFactory f = factory(setting);
			String path = "/atime/off-" + setting.trim() + ".txt";
			write(f, path, "x");
			long before = atime(f, path);
			pause();
			read(f, path);
			read(f, path);
			assertEquals(before, atime(factory(setting), path), "setting " + setting);
		}
	}

	@Test
	public void writesAndExplicitSettingsStillSetItWhenFalse() throws Exception {
		JdbcFileSourceFactory f = factory("false");
		write(f, "/atime/w.txt", "one");
		long first = atime(f, "/atime/w.txt");
		pause();
		try (OutputStream out = f.createFileSource("/atime/w.txt").getOutputStream(true)) {
			out.write("two".getBytes(StandardCharsets.UTF_8));
		}
		assertTrue(atime(factory("false"), "/atime/w.txt") > first, "an append sets it");

		FileSource file = f.createFileSource("/atime/w.txt");
		long wanted = first - 86_400_000L;
		assertTrue(file.setLastAccessTime(wanted));
		assertEquals(wanted / 1000, atime(factory("false"), "/atime/w.txt") / 1000, "setLastAccessTime still works");
	}
}
