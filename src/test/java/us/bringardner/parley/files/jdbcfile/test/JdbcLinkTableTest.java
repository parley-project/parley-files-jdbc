package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * Where links are kept: file_link has an entry for every link (the owner's row for a hard link, the
 * path for a symbolic one) and file.link_count counts the hard links that point at a row, through
 * making, deleting and handing over a file's data.
 */
public class JdbcLinkTableTest {

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9055);
		assertTrue(factory().createFileSource("/links").mkdirs());
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	private static JdbcFileSourceFactory factory() {
		return JdbcTestServer.factory();
	}

	private static FileSource at(String path) throws Exception {
		return factory().createFileSource(path);
	}

	private static void write(String path, String text) throws Exception {
		try (OutputStream out = at(path).getOutputStream()) {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static String read(String path) throws Exception {
		try (InputStream in = at(path).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** The row id of a path, straight from the database. */
	private static long id(String path) throws Exception {
		String name = path.substring(path.lastIndexOf('/') + 1);
		String folder = path.substring(0, path.lastIndexOf('/'));
		long pid = 0;
		for (String part : folder.split("/")) {
			if (part.isEmpty()) {
				continue;
			}
			pid = one("select fileid from file_source.file where name = ? and parentid = ?", part, pid);
		}
		return one("select fileid from file_source.file where name = ? and parentid = ?", name, pid);
	}

	private static long one(String sql, Object... args) throws Exception {
		try (Connection con = factory().getConnection(); PreparedStatement ps = con.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next(), sql);
				return rs.getLong(1);
			}
		}
	}

	private static long linkCount(String path) throws Exception {
		return one("select link_count from file_source.file where fileid = ?", id(path));
	}

	/** The entry of a link: {target_id or null, target_path or null}; null if it has none. */
	private static Object[] entry(long linkId) throws Exception {
		try (Connection con = factory().getConnection();
				PreparedStatement ps = con.prepareStatement("select target_id, target_path from file_source.file_link where link_id = ?")) {
			ps.setLong(1, linkId);
			try (ResultSet rs = ps.executeQuery()) {
				if (!rs.next()) {
					return null;
				}
				return new Object[] { rs.getObject(1) == null ? null : rs.getLong(1), rs.getString(2) };
			}
		}
	}

	private static long entries() throws Exception {
		return one("select count(*) from file_source.file_link");
	}

	@Test
	public void aHardLinkIsCountedByItsOwner() throws Exception {
		write("/links/h1.txt", "data");
		assertEquals(0, linkCount("/links/h1.txt"));
		factory().createLink(at("/links/h1-a.txt"), at("/links/h1.txt"));
		assertEquals(1, linkCount("/links/h1.txt"));
		factory().createLink(at("/links/h1-b.txt"), at("/links/h1.txt"));
		assertEquals(2, linkCount("/links/h1.txt"));

		Object[] e = entry(id("/links/h1-a.txt"));
		assertEquals(id("/links/h1.txt"), e[0]);
		assertNull(e[1]);
		assertEquals("data", read("/links/h1-b.txt"));

		assertTrue(at("/links/h1-a.txt").delete());
		assertEquals(1, linkCount("/links/h1.txt"));
		assertTrue(at("/links/h1-b.txt").delete());
		assertEquals(0, linkCount("/links/h1.txt"));
		assertEquals("data", read("/links/h1.txt"));
	}

	@Test
	public void aSymbolicLinkKeepsItsPathAndCountsNothing() throws Exception {
		write("/links/s1.txt", "target");
		factory().createSymbolicLink(at("/links/s1-link"), at("/links/s1.txt"));
		Object[] e = entry(id("/links/s1-link"));
		assertNull(e[0]);
		assertEquals("/links/s1.txt", e[1]);
		assertEquals(0, linkCount("/links/s1.txt"));
		assertEquals("target", read("/links/s1-link"));

		long before = entries();
		assertTrue(at("/links/s1-link").delete());
		assertEquals(before - 1, entries());
		assertEquals(0, linkCount("/links/s1.txt"));
		assertTrue(at("/links/s1.txt").exists());
	}

	@Test
	public void aSymbolicLinkMayPointAtNothing() throws Exception {
		factory().createSymbolicLink(at("/links/dangling"), at("/links/not-there"));
		assertEquals("/links/not-there", entry(id("/links/dangling"))[1]);
		assertTrue(at("/links/dangling").delete());
	}

	@Test
	public void deletingTheOwnerHandsTheDataToAHardLink() throws Exception {
		write("/links/o.txt", "shared data");
		factory().createLink(at("/links/o-a.txt"), at("/links/o.txt"));
		factory().createLink(at("/links/o-b.txt"), at("/links/o.txt"));
		factory().createLink(at("/links/o-c.txt"), at("/links/o.txt"));
		assertEquals(3, linkCount("/links/o.txt"));

		assertTrue(at("/links/o.txt").delete());
		// one of the links is the file now; the others are entries that point at it
		int files = 0;
		long heir = -1;
		for (String n : new String[] { "o-a.txt", "o-b.txt", "o-c.txt" }) {
			assertEquals("shared data", read("/links/" + n));
			if (entry(id("/links/" + n)) == null) {
				files++;
				heir = id("/links/" + n);
			}
		}
		assertEquals(1, files, "exactly one name holds the data");
		assertEquals(2, one("select link_count from file_source.file where fileid = ?", heir));
		for (String n : new String[] { "o-a.txt", "o-b.txt", "o-c.txt" }) {
			Object[] e = entry(id("/links/" + n));
			if (e != null) {
				assertEquals(heir, e[0]);
			}
		}
		// and the last names go one by one
		for (String n : new String[] { "o-a.txt", "o-b.txt", "o-c.txt" }) {
			assertTrue(at("/links/" + n).delete());
		}
	}

	@Test
	public void aLinkToAMissingFileIsRefusedAndChangesNothing() throws Exception {
		long before = entries();
		assertThrows(IOException.class, () -> factory().createLink(at("/links/x-link"), at("/links/no-such-file")));
		assertEquals(before, entries());
		assertFalse(at("/links/x-link").exists());
	}

	@Test
	public void aNameThatIsTakenIsRefusedAndChangesNothing() throws Exception {
		write("/links/t1.txt", "one");
		write("/links/t2.txt", "two");
		long before = entries();
		assertThrows(IOException.class, () -> factory().createLink(at("/links/t2.txt"), at("/links/t1.txt")));
		assertEquals(before, entries());
		assertEquals(0, linkCount("/links/t1.txt"));
		assertEquals("two", read("/links/t2.txt"));
	}

	@Test
	public void everyCountMatchesItsEntries() throws Exception {
		write("/links/c1.txt", "a");
		write("/links/c2.txt", "b");
		for (int i = 0; i < 4; i++) {
			factory().createLink(at("/links/c1-" + i), at("/links/c1.txt"));
		}
		factory().createLink(at("/links/c2-0"), at("/links/c2.txt"));
		assertTrue(at("/links/c1-1").delete());
		assertTrue(at("/links/c1.txt").delete());
		try (Connection con = factory().getConnection(); Statement st = con.createStatement();
				ResultSet rs = st.executeQuery("select f.fileid, f.link_count, "
						+ "(select count(*) from file_source.file_link l where l.target_id = f.fileid) n "
						+ "from file_source.file f where f.link_count <> 0 or exists (select 1 from file_source.file_link l2 where l2.target_id = f.fileid)")) {
			int rows = 0;
			while (rs.next()) {
				assertEquals(rs.getLong("n"), rs.getLong("link_count"), "row " + rs.getLong(1));
				rows++;
			}
			assertTrue(rows >= 2);
		}
	}

	@Test
	public void theLinkSchemaIsMadeOnConnectWhenMissing() throws Exception {
		JdbcFileSourceFactory f = factory();
		try (Connection con = f.getConnection(); Statement st = con.createStatement()) {
			st.executeUpdate("DROP TABLE file_source.file_link");
			st.executeUpdate("ALTER TABLE file_source.file DROP COLUMN link_count");
		}
		try (Connection con = f.getConnection()) {
			List<String> made = f.addLinkSchema(con);
			assertEquals(List.of("file.link_count", "file_link"), made);
			assertEquals(List.of(), f.addLinkSchema(con), "nothing left to make");
		}
		write("/links/up.txt", "x");
		factory().createLink(at("/links/up-a"), at("/links/up.txt"));
		assertEquals(1, linkCount("/links/up.txt"));
		assertEquals(id("/links/up.txt"), entry(id("/links/up-a"))[0]);
	}
}
