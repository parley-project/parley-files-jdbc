package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.attribute.UserPrincipal;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.FileSourceUser;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * BJL-22: permissions are decided by the database user, not the operating system
 * user, so these pass whatever the OS user and its groups are.
 */
public class JdbcPermissionsTest {

	/** Longer than the old VARCHAR(10) owner column. */
	private static final String OTHER_USER = "someone-else-with-a-long-name";

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9004);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	private static FileSourceFactory factory() {
		return JdbcTestServer.factory();
	}

	@Test
	public void theCurrentUserIsTheDatabaseUser() {
		FileSourceUser me = factory().whoAmI();
		assertEquals(JdbcTestServer.USER, me.getName());
		assertTrue(me.hasGroup(JdbcFileSourceFactory.DEFAULT_GROUP));
	}

	@Test
	public void aNewFileBelongsToMeAndIsWritable() throws Exception {
		FileSource file = newFile("mine.txt");
		assertEquals(factory().whoAmI().getName(), file.getOwner().getName());
		assertTrue(file.canRead());
		assertTrue(file.canWrite());
	}

	@Test
	public void anotherUsersFileUsesGroupThenOtherPermissions() throws Exception {
		FileSource file = newFile("theirs.txt");
		assertTrue(file.setOwner(principal(OTHER_USER)));
		FileSource reread = reread(file);
		assertEquals(OTHER_USER, reread.getOwner().getName());
		// same group (staff): group-write is on by default
		assertTrue(reread.canWrite());

		Properties p = factory().getConnectProperties();
		String group = p.getProperty(JdbcFileSourceFactory.JDBC_GROUP);
		try {
			p.setProperty(JdbcFileSourceFactory.JDBC_GROUP, "");
			factory().setConnectionProperties(p);
			assertTrue(factory().whoAmI().getGroups().isEmpty());
			// no group: 'other', which can read but not write by default
			reread = reread(file);
			assertTrue(reread.canRead());
			assertFalse(reread.canWrite());
		} finally {
			p.setProperty(JdbcFileSourceFactory.JDBC_GROUP, group);
			factory().setConnectionProperties(p);
		}
	}

	@Test
	public void theGroupDefaultsToStaffWhenNotGiven() {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		f.setConnectionProperties(new Properties());
		assertEquals(JdbcFileSourceFactory.DEFAULT_GROUP, f.getConnectProperties().getProperty(JdbcFileSourceFactory.JDBC_GROUP));
	}

	private static FileSource newFile(String name) throws Exception {
		FileSource dir = factory().createFileSource("/PermissionsTest");
		if( !dir.exists() ) {
			assertTrue(dir.mkdirs());
		}
		FileSource file = dir.getChild(name);
		if( !file.exists() ) {
			assertTrue(file.createNewFile());
		}
		return file;
	}

	/** A fresh object, after the cached column values have expired. */
	private static FileSource reread(FileSource file) throws Exception {
		Thread.sleep(1100);
		return factory().createFileSource(file.getAbsolutePath());
	}

	private static UserPrincipal principal(String name) {
		return () -> name;
	}
}
