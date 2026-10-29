package us.bringardner.parley.files.jdbcfile.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** The same file behaviour as JdbcFileLikeTest, over the hikari connection pool. */
public class JdbcFileLikeHikariTest extends JdbcFileLikeTest {

	@BeforeAll
	public static void setUpPool() throws Exception {
		JdbcTestServer.setUp(9041, "hikari");
	}

	@AfterAll
	static void tearDownPool() throws Exception {
		JdbcTestServer.tearDown();
	}
}
