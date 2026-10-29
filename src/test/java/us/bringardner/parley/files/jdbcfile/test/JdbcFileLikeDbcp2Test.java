package us.bringardner.parley.files.jdbcfile.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** The same file behaviour as JdbcFileLikeTest, over the dbcp2 connection pool. */
public class JdbcFileLikeDbcp2Test extends JdbcFileLikeTest {

	@BeforeAll
	public static void setUpPool() throws Exception {
		JdbcTestServer.setUp(9042, "dbcp2");
	}

	@AfterAll
	static void tearDownPool() throws Exception {
		JdbcTestServer.tearDown();
	}
}
