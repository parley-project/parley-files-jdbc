package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/** BJL-23: an old schema's VARCHAR(10) owner/group columns are widened on connect. */
public class JdbcSchemaUpgradeTest {

	private static final String LONG_NAME = "a-user-name-longer-than-ten";

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9005);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	private static JdbcFileSourceFactory shared() {
		return JdbcTestServer.factory();
	}

	/** Makes the columns as narrow as before BJL-22. */
	private static void makeOld() throws Exception {
		try(Connection con = shared().getConnection(); Statement st = con.createStatement()) {
			st.executeUpdate("ALTER TABLE file_source.file ALTER COLUMN owner SET DATA TYPE VARCHAR(10)");
			st.executeUpdate("ALTER TABLE file_source.file ALTER COLUMN group_name SET DATA TYPE VARCHAR(10)");
		}
		assertEquals(10, size("OWNER"));
		assertEquals(10, size("GROUP_NAME"));
	}

	private static int size(String column) throws Exception {
		try(Connection con = shared().getConnection();
				ResultSet rs = con.getMetaData().getColumns(null, "FILE\\_SOURCE", "FILE", column)) {
			assertTrue(rs.next(), column);
			return rs.getInt("COLUMN_SIZE");
		}
	}

	private static JdbcFileSourceFactory connect(boolean upgrade) throws Exception {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		Properties p = shared().getConnectProperties();
		p.setProperty(JdbcFileSourceFactory.JDBC_UPGRADE_SCHEMA, ""+upgrade);
		f.setConnectionProperties(p);
		assertTrue(f.connect());
		return f;
	}

	@Test
	public void anOldSchemaIsWidenedOnConnect() throws Exception {
		makeOld();
		connect(true);
		assertEquals(JdbcFileSourceFactory.NAME_COLUMN_SIZE, size("OWNER"));
		assertEquals(JdbcFileSourceFactory.NAME_COLUMN_SIZE, size("GROUP_NAME"));

		// a long owner can now be stored
		FileSource dir = shared().createFileSource("/UpgradeTest");
		if( !dir.exists()) {
			assertTrue(dir.mkdirs());
		}
		FileSource file = dir.getChild("long-owner.txt");
		if( !file.exists()) {
			assertTrue(file.createNewFile());
		}
		assertTrue(file.setOwner(() -> LONG_NAME));
	}

	@Test
	public void theUpgradeCanBeSwitchedOff() throws Exception {
		makeOld();
		connect(false);
		assertEquals(10, size("OWNER"));
		// and on again for the other tests
		connect(true);
		assertEquals(JdbcFileSourceFactory.NAME_COLUMN_SIZE, size("OWNER"));
	}

	@Test
	public void aCurrentSchemaIsLeftAlone() throws Exception {
		try(Connection con = shared().getConnection()) {
			shared().widenNameColumns(con);
			assertEquals(Collections.emptyList(), shared().widenNameColumns(con));
		}
	}

	@Test
	public void theStatementFitsTheDatabase() {
		assertEquals("ALTER TABLE file_source.file ALTER COLUMN owner SET DATA TYPE VARCHAR(128)",
				JdbcFileSourceFactory.alterSql("HSQL Database Engine", "owner"));
		assertEquals("ALTER TABLE file_source.file ALTER COLUMN owner TYPE VARCHAR(128)",
				JdbcFileSourceFactory.alterSql("PostgreSQL", "owner"));
		assertEquals("ALTER TABLE file_source.file MODIFY owner VARCHAR(128) NOT NULL",
				JdbcFileSourceFactory.alterSql("MySQL", "owner"));
		assertEquals("ALTER TABLE file_source.file MODIFY group_name VARCHAR(128) DEFAULT 'staff'",
				JdbcFileSourceFactory.alterSql("MariaDB", "group_name"));
		for(String p : Arrays.asList("Apache Derby", null)) {
			assertTrue(JdbcFileSourceFactory.alterSql(p, "owner").contains("SET DATA TYPE"), String.valueOf(p));
		}
	}
}
