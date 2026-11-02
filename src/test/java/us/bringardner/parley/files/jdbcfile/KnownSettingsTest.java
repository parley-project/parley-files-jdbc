package us.bringardner.parley.files.jdbcfile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import us.bringardner.parley.net.server.KnownSettings;

/**
 * To-Do 2.16: parley-files-jdbc's list of settings (META-INF/parley/settings.list) has every name its
 * code spells out, so a server's start-up check never warns about a real setting.
 */
public class KnownSettingsTest {

	@Test
	public void everyConstantIsListed() throws Exception {
		List<String> found = new ArrayList<>(KnownSettings.settingConstants(JdbcFileSourceFactory.class));
		// declared but not read by anything
		found.remove(JdbcFileSourceFactory.DBID_PROP);
		assertEquals(List.of(), KnownSettings.load().undeclared(found));
	}
}
