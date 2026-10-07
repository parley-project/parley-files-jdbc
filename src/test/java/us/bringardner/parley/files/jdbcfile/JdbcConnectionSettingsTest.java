package us.bringardner.parley.files.jdbcfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.ConnectionSetting;
import us.bringardner.parley.files.ConnectionSettings;

/** JDBC's connection settings, described for any UI. */
public class JdbcConnectionSettingsTest {

	@Test
	public void everyPropertyIsDescribedAndThePasswordStaysSecret() {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		List<ConnectionSetting> settings = f.getConnectionSettings();
		for(String key : f.getConnectProperties().stringPropertyNames()) {
			ConnectionSetting s = ConnectionSettings.find(settings, key);
			assertNotNull(s, key+" isn't described");
			assertEquals(f.isSecretProperty(key), s.isSecret(), key);
		}
	}

	@Test
	public void theUrlIsNeeded() {
		JdbcFileSourceFactory f = new JdbcFileSourceFactory();
		Properties p = ConnectionSettings.initialValues(f.getConnectionSettings(), f.getConnectProperties());
		p.setProperty(JdbcFileSourceFactory.JDBC_URL, "");
		assertEquals(List.of("Database URL is required"), f.validateConnection(p));
	}
}
