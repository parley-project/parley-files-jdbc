package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/** BJL-21: the factory names its secret connection properties exactly. */
public class JdbcSecretPropertiesTest {

	@Test
	public void secretPropertiesAreExactlyTheseOnes() {
		JdbcFileSourceFactory factory = new JdbcFileSourceFactory();
		List<String> secrets = Arrays.asList(JdbcFileSourceFactory.JDBC_PASSWORD);
		TreeSet<String> names = new TreeSet<>(Arrays.asList(JdbcFileSourceFactory.JDBC_DRIVER, JdbcFileSourceFactory.JDBC_URL, JdbcFileSourceFactory.JDBC_USERID, JdbcFileSourceFactory.JDBC_CONNECTION_NAME));
		names.addAll(secrets);
		names.addAll(factory.getConnectProperties().stringPropertyNames());
		for(String name : names) {
			assertEquals(secrets.contains(name), factory.isSecretProperty(name), name);
		}
	}
}
