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
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * Where names and links are kept: every name is a row of file_source.dirent that points at an inode (a
 * row of file), a hard link is a second name for the same inode, file.link_count counts the names, the
 * data goes with the last one, and file_link holds the path of a symbolic link. Also the move of a
 * database made before names were separate.
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

	/** The inode id of a path, straight from the database. */
	private static long id(String path) throws Exception {
		String name = path.substring(path.lastIndexOf('/') + 1);
		String folder = path.substring(0, path.lastIndexOf('/'));
		long pid = 0;
		for (String part : folder.split("/")) {
			if (part.isEmpty()) {
				continue;
			}
			pid = one("select inode_id from file_source.dirent where name = ? and parent_id = ?", part, pid);
		}
		return one("select inode_id from file_source.dirent where name = ? and parent_id = ?", name, pid);
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

	private static long names(long inode) throws Exception {
		return one("select count(*) from file_source.dirent where inode_id = ?", inode);
	}

	private static long dataRows(long inode) throws Exception {
		return one("select count(*) from file_source.file_data where fileid = ?", inode);
	}

	/** The path a symbolic link points to; null if the inode has no entry. */
	private static String target(long inode) throws Exception {
		try (Connection con = factory().getConnection();
				PreparedStatement ps = con.prepareStatement("select target_path from file_source.file_link where link_id = ?")) {
			ps.setLong(1, inode);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	private static long entries() throws Exception {
		return one("select count(*) from file_source.file_link");
	}

	private static long allNames() throws Exception {
		return one("select count(*) from file_source.dirent");
	}

	@Test
	public void aHardLinkIsAnotherNameForTheInode() throws Exception {
		write("/links/h1.txt", "data");
		assertEquals(1, linkCount("/links/h1.txt"));
		factory().createLink(at("/links/h1-a.txt"), at("/links/h1.txt"));
		assertEquals(2, linkCount("/links/h1.txt"));
		factory().createLink(at("/links/h1-b.txt"), at("/links/h1.txt"));
		assertEquals(3, linkCount("/links/h1.txt"));

		long inode = id("/links/h1.txt");
		assertEquals(inode, id("/links/h1-a.txt"));
		assertEquals(inode, id("/links/h1-b.txt"));
		assertEquals(3, names(inode));
		assertNull(target(inode));
		assertEquals("data", read("/links/h1-b.txt"));

		assertTrue(at("/links/h1-a.txt").delete());
		assertEquals(2, linkCount("/links/h1.txt"));
		assertTrue(at("/links/h1-b.txt").delete());
		assertEquals(1, linkCount("/links/h1.txt"));
		assertEquals("data", read("/links/h1.txt"));
	}

	@Test
	public void aSymbolicLinkIsAnInodeThatKeepsItsPath() throws Exception {
		write("/links/s1.txt", "target");
		factory().createSymbolicLink(at("/links/s1-link"), at("/links/s1.txt"));
		long link = id("/links/s1-link");
		assertTrue(link != id("/links/s1.txt"));
		assertEquals("/links/s1.txt", target(link));
		assertEquals(1, linkCount("/links/s1.txt"));
		assertEquals("target", read("/links/s1-link"));

		long before = entries();
		assertTrue(at("/links/s1-link").delete());
		assertEquals(before - 1, entries());
		assertEquals(1, linkCount("/links/s1.txt"));
		assertTrue(at("/links/s1.txt").exists());
	}

	@Test
	public void aSymbolicLinkMayPointAtNothing() throws Exception {
		factory().createSymbolicLink(at("/links/dangling"), at("/links/not-there"));
		assertEquals("/links/not-there", target(id("/links/dangling")));
		assertTrue(at("/links/dangling").delete());
	}

	@Test
	public void deletingAnyNameLeavesTheInodeAndItsDataWhereTheyAre() throws Exception {
		write("/links/o.txt", "shared data");
		factory().createLink(at("/links/o-a.txt"), at("/links/o.txt"));
		factory().createLink(at("/links/o-b.txt"), at("/links/o.txt"));
		long inode = id("/links/o.txt");
		long data = dataRows(inode);
		assertTrue(data > 0);

		// the first name is deleted: nothing moves, the others are the same file
		assertTrue(at("/links/o.txt").delete());
		for (String n : new String[] { "o-a.txt", "o-b.txt" }) {
			assertEquals(inode, id("/links/" + n), n);
			assertEquals("shared data", read("/links/" + n), n);
		}
		assertEquals(2, linkCount("/links/o-a.txt"));
		assertEquals(data, dataRows(inode));

		// the data goes with the last name
		assertTrue(at("/links/o-a.txt").delete());
		assertEquals(data, dataRows(inode));
		assertTrue(at("/links/o-b.txt").delete());
		assertEquals(0, dataRows(inode));
		assertEquals(0, one("select count(*) from file_source.file where fileid = ?", inode));
		assertEquals(0, names(inode));
	}

	@Test
	public void aHandleOnAnotherNameKeepsWorking() throws Exception {
		write("/links/live.txt", "still here");
		factory().createLink(at("/links/live-link"), at("/links/live.txt"));
		FileSource kept = at("/links/live-link");
		assertEquals(10, kept.length());
		assertTrue(at("/links/live.txt").delete());
		assertEquals(10, kept.length());
		assertEquals("still here", read("/links/live-link"));
		// a write through the surviving name is the same file
		write("/links/live-link", "changed");
		assertEquals(7, kept.length());
	}

	@Test
	public void renamingOneNameLeavesTheOthers() throws Exception {
		write("/links/r.txt", "rename");
		factory().createLink(at("/links/r-link"), at("/links/r.txt"));
		long inode = id("/links/r.txt");
		assertTrue(at("/links/r.txt").renameTo(at("/links/r-moved.txt")));
		assertFalse(at("/links/r.txt").exists());
		assertEquals(inode, id("/links/r-moved.txt"));
		assertEquals(inode, id("/links/r-link"));
		assertEquals(2, names(inode));
		assertEquals("rename", read("/links/r-link"));
	}

	@Test
	public void aLinkToAMissingFileIsRefusedAndChangesNothing() throws Exception {
		long before = allNames();
		assertThrows(IOException.class, () -> factory().createLink(at("/links/x-link"), at("/links/no-such-file")));
		assertEquals(before, allNames());
		assertFalse(at("/links/x-link").exists());
	}

	@Test
	public void aNameThatIsTakenIsRefusedAndChangesNothing() throws Exception {
		write("/links/t1.txt", "one");
		write("/links/t2.txt", "two");
		long before = allNames();
		assertThrows(IOException.class, () -> factory().createLink(at("/links/t2.txt"), at("/links/t1.txt")));
		assertEquals(before, allNames());
		assertEquals(1, linkCount("/links/t1.txt"), "a refused link is not counted");
		assertEquals("two", read("/links/t2.txt"));
	}

	@Test
	public void everyCountMatchesItsNames() throws Exception {
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
						+ "(select count(*) from file_source.dirent d where d.inode_id = f.fileid) n "
						+ "from file_source.file f")) {
			int rows = 0;
			while (rs.next()) {
				assertEquals(rs.getLong("n"), rs.getLong("link_count"), "inode " + rs.getLong(1));
				rows++;
			}
			assertTrue(rows >= 2);
		}
		try (Connection con = factory().getConnection(); Statement st = con.createStatement();
				ResultSet rs = st.executeQuery("select count(*) from file_source.dirent d "
						+ "where not exists (select 1 from file_source.file f where f.fileid = d.inode_id)")) {
			assertTrue(rs.next());
			assertEquals(0, rs.getLong(1), "no name points at nothing");
		}
	}

	@Test
	public void aLinkMadeWhileAHandleWasOpenIsNotLostWhenTheFileIsDeleted() throws Exception {
		write("/links/stale.txt", "keep me");
		FileSource old = at("/links/stale.txt");
		assertTrue(old.exists());          // (this handle has read the row: it knows one name)
		long inode = id("/links/stale.txt");
		factory().createLink(at("/links/stale-link"), at("/links/stale.txt"));
		assertTrue(old.delete());          // (and now deletes its name, not knowing)
		assertEquals("keep me", read("/links/stale-link"));
		assertFalse(old.exists());
		assertEquals(inode, id("/links/stale-link"), "the link is the file, which did not move");
	}

	@Test
	public void aHardLinkSharesTheDataAndASymbolicLinkHasNone() throws Exception {
		write("/links/nd.txt", "data");
		factory().createLink(at("/links/nd-hard"), at("/links/nd.txt"));
		factory().createSymbolicLink(at("/links/nd-sym"), at("/links/nd.txt"));
		assertTrue(dataRows(id("/links/nd-hard")) > 0);
		assertEquals(dataRows(id("/links/nd.txt")), dataRows(id("/links/nd-hard")));
		assertEquals(0, dataRows(id("/links/nd-sym")));
		assertEquals("data", read("/links/nd-hard"));
		assertEquals("data", read("/links/nd-sym"));
	}

	@Test
	public void aFolderReachedThroughALinkSeesTheChanges() throws Exception {
		assertTrue(at("/links/real").mkdirs());
		write("/links/real/one.txt", "1");
		factory().createSymbolicLink(at("/links/via"), at("/links/real"));
		FileSource kept = at("/links/via");
		assertEquals(1, kept.listFiles().length);

		// a file made through the link is in the folder, whichever way it is listed
		write("/links/via/two.txt", "2");
		assertEquals(2, kept.listFiles().length, "the kept handle of the link");
		assertEquals(2, at("/links/real").listFiles().length, "the folder itself");
		assertEquals("2", read("/links/real/two.txt"));

		// and one made in the folder is seen through the link
		write("/links/real/three.txt", "3");
		assertEquals(3, kept.listFiles().length);

		assertTrue(at("/links/via/two.txt").delete());
		assertEquals(2, kept.listFiles().length);
		assertEquals(2, at("/links/real").listFiles().length);
	}

	@Test
	public void theRealFoldersTimeMovesWhenAFileIsMadeThroughALink() throws Exception {
		assertTrue(at("/links/timed").mkdirs());
		factory().createSymbolicLink(at("/links/timed-via"), at("/links/timed"));
		long before = at("/links/timed").lastModified();
		Thread.sleep(1100);
		write("/links/timed-via/f.txt", "x");
		assertTrue(at("/links/timed").lastModified() > before);
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
			try (Statement st = con.createStatement()) {
				st.executeUpdate("UPDATE file_source.file SET link_count = 1");   // (as migrateToDirents does for a database of the old model)
			}
		}
		write("/links/up.txt", "x");
		factory().createLink(at("/links/up-a"), at("/links/up.txt"));
		assertEquals(2, linkCount("/links/up.txt"));
		assertEquals(id("/links/up.txt"), id("/links/up-a"));
	}

	/** A database of the old model: names in file, and a hard link a row of type "hard" pointing at its owner. */
	@Test
	public void aDatabaseOfTheOldModelIsMovedToDirents() throws Exception {
		try (Connection con = DriverManager.getConnection("jdbc:hsqldb:mem:oldmodel", "SA", ""); Statement st = con.createStatement()) {
			st.executeUpdate("create schema file_source");
			st.executeUpdate("CREATE TABLE file_source.file (FILEID BIGINT NOT NULL UNIQUE generated always as IDENTITY (START WITH 1), "
					+ "PARENTID BIGINT DEFAULT 0, NAME VARCHAR(250) NOT NULL, OWNER VARCHAR(128) NOT NULL, FILE_TYPE VARCHAR(10) NOT NULL, "
					+ "LENGTH BIGINT DEFAULT 0, CHUNK_COUNT INT DEFAULT 0, LINK_COUNT INT DEFAULT 0 NOT NULL, UNIQUE (PARENTID, NAME))");
			st.executeUpdate("CREATE TABLE file_source.file_link (LINK_ID BIGINT NOT NULL PRIMARY KEY, TARGET_ID BIGINT, TARGET_PATH VARCHAR(4096))");
			st.executeUpdate("CREATE TABLE file_source.file_data (CHUNK_NUMBER BIGINT NOT NULL, FILEID BIGINT NOT NULL, LENGTH INT NOT NULL, "
					+ "DATA LONGVARBINARY, primary key (fileid, chunk_number))");
			// 1 root, 2 /a (dir), 3 /a/f (file with two hard links), 4 /a/h1 and 5 /b (the hard links), 6 /a/s (symbolic link)
			st.executeUpdate("insert into file_source.file (name,parentid,owner,file_type,link_count) values('',0,'u','dir',0)");
			st.executeUpdate("insert into file_source.file (name,parentid,owner,file_type,link_count) values('a',1,'u','dir',0)");
			st.executeUpdate("insert into file_source.file (name,parentid,owner,file_type,link_count) values('f',2,'u','file',2)");
			st.executeUpdate("insert into file_source.file (name,parentid,owner,file_type,link_count) values('h1',2,'u','hard',0)");
			st.executeUpdate("insert into file_source.file (name,parentid,owner,file_type,link_count) values('b',1,'u','hard',0)");
			st.executeUpdate("insert into file_source.file (name,parentid,owner,file_type,link_count) values('s',2,'u','link',0)");
			st.executeUpdate("insert into file_source.file_link values (4, 3, null)");
			st.executeUpdate("insert into file_source.file_link values (5, 3, null)");
			st.executeUpdate("insert into file_source.file_link values (6, null, '/a/f')");
			st.executeUpdate("insert into file_source.file_data values (1, 3, 3, X'010203')");
			st.executeUpdate("insert into file_source.file_data values (1, 4, 1, X'03')");

			List<String> done = factory().migrateToDirents(con);
			assertEquals(List.of("dirent", "copy", "drop name, parentid"), done);

			// the names: 1 root, a, f and its two hard links, s
			StringBuilder all = new StringBuilder();
			try (ResultSet rs = st.executeQuery("select parent_id, name, inode_id from file_source.dirent order by 1, 2")) {
				while (rs.next()) {
					all.append(rs.getLong(1)).append('/').append(rs.getString(2)).append("->").append(rs.getLong(3)).append(' ');
				}
			}
			assertEquals(6, count(con, "select count(*) from file_source.dirent"), all.toString());
			assertEquals(3, count(con, "select count(*) from file_source.dirent where inode_id = 3"));
			assertEquals(3, count(con, "select inode_id from file_source.dirent where parent_id = 2 and name = 'h1'"));
			assertEquals(3, count(con, "select inode_id from file_source.dirent where parent_id = 1 and name = 'b'"));
			// the hard link rows are gone with their pointer data and entries, the count is the names
			assertEquals(4, count(con, "select count(*) from file_source.file"));
			assertEquals(3, count(con, "select link_count from file_source.file where fileid = 3"));
			assertEquals(1, count(con, "select link_count from file_source.file where fileid = 2"));
			assertEquals(1, count(con, "select count(*) from file_source.file_data"));
			assertEquals(1, count(con, "select count(*) from file_source.file_link"));
			// the columns are gone
			assertThrows(SQLException.class, () -> st.executeQuery("select name from file_source.file"));
			assertThrows(SQLException.class, () -> st.executeQuery("select parentid from file_source.file"));
			// and it is done once
			assertEquals(List.of(), factory().migrateToDirents(con));
			assertEquals(6, count(con, "select count(*) from file_source.dirent"));
		}
	}

	private static long count(Connection con, String sql) throws SQLException {
		try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			assertTrue(rs.next(), sql);
			return rs.getLong(1);
		}
	}
}
