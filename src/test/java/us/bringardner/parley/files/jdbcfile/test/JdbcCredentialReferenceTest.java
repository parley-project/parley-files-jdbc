package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.credentials.CredentialException;
import us.bringardner.parley.files.credentials.Credentials;
import us.bringardner.parley.files.credentials.EnvCredentialProvider;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * The JDBC password may be a reference (@env:..., @prompt:...), resolved only to log in; a
 * wrong one is asked for again, and the pool never shows the password.
 */
public class JdbcCredentialReferenceTest {

	private static final String URL = "jdbc:hsqldb:mem:credentialRefTest";
	private static final String USER = "BOB";
	private static final String PASSWORD = "right-Pw7";

	@BeforeAll
	public static void createUser() throws Exception {
		Class.forName(JdbcTestServer.DRIVER);
		try (Connection con = DriverManager.getConnection(URL, "SA", ""); Statement st = con.createStatement()) {
			st.execute("CREATE USER "+USER+" PASSWORD '"+PASSWORD+"' ADMIN");
		}
	}

	@AfterEach
	public void defaults() {
		Credentials.restoreDefaults();
		Credentials.setPrompter(null);
	}

	private static Properties props(String password) {
		Properties p = new Properties();
		p.setProperty(JdbcFileSourceFactory.JDBC_DRIVER, JdbcTestServer.DRIVER);
		p.setProperty(JdbcFileSourceFactory.JDBC_URL, URL);
		p.setProperty(JdbcFileSourceFactory.JDBC_USERID, USER);
		p.setProperty(JdbcFileSourceFactory.JDBC_PASSWORD, password);
		p.setProperty(JdbcFileSourceFactory.JDBC_UPGRADE_SCHEMA, "false");
		return p;
	}

	@Test
	public void aReferenceIsResolvedToLogInAndKeptAsIs() throws IOException {
		Credentials.register(new EnvCredentialProvider(Map.of("DB_PW", PASSWORD)::get));
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		try {
			assertTrue(f.connect(props("@env:DB_PW")));
			assertEquals("@env:DB_PW", f.getConnectProperties().getProperty(JdbcFileSourceFactory.JDBC_PASSWORD));
			for(Thread t : Thread.getAllStackTraces().keySet()) {
				assertFalse(t.getName().contains(PASSWORD), "a thread is named with the password: "+t.getName());
			}
		} finally {
			f.disConnect();
		}
	}

	@Test
	public void aWrongPromptedPasswordIsAskedForAgain() throws IOException {
		List<String> answers = new ArrayList<>(List.of("wrong", PASSWORD));
		Credentials.setPrompter((label, req) -> answers.remove(0).toCharArray());
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		try {
			assertFalse(f.connect(props("@prompt:")), "the first answer is wrong");
			// the failed pool was dropped, and the wrong answer wasn't kept
			assertTrue(f.connect(props("@prompt:")));
			assertTrue(answers.isEmpty());
		} finally {
			f.disConnect();
		}
	}

	@Test
	public void aReferenceThatCantBeResolvedFailsTheConnectNamingIt() {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		CredentialException e = assertThrows(CredentialException.class, () -> f.connect(props("@env:NO_SUCH_DB_PW_VAR")));
		assertTrue(e.getMessage().startsWith("@env:NO_SUCH_DB_PW_VAR"), e.getMessage());
	}
}
