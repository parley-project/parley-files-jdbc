package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/** delete() takes the row, its content and the parent's modified time in one transaction. */
public class JdbcDeleteTest {

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9019);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	private static int rows(String table, long fileid) throws Exception {
		try(Connection con = JdbcTestServer.factory().getConnection();
				PreparedStatement pstmt = con.prepareStatement("select count(*) from file_source." + table + " where fileid = ?")) {
			pstmt.setLong(1, fileid);
			try(ResultSet rs = pstmt.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static FileSource made(JdbcFileSourceFactory f, String path, int bytes) throws Exception {
		FileSource file = f.createFileSource(path);
		try(OutputStream out = file.getOutputStream()) {
			out.write(new byte[bytes]);
		}
		assertTrue(file.exists());
		return file;
	}

	@Test
	public void aDeletedFileLeavesNeitherRowNorContent() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		assertTrue(f.createFileSource("/del1").mkdirs());
		FileSource file = made(f, "/del1/a", 3000);
		long id = ((us.bringardner.parley.files.jdbcfile.JdbcFileSource) file).getFileId();
		assertEquals(1, rows("file", id));
		assertTrue(rows("file_data", id) > 0);

		assertTrue(file.delete());

		assertFalse(file.exists());
		assertFalse(f.createFileSource("/del1/a").exists());
		assertEquals(0, rows("file", id));
		assertEquals(0, rows("file_data", id));
	}

	@Test
	public void deletingTouchesTheParentsModifiedTime() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		assertTrue(f.createFileSource("/del2").mkdirs());
		FileSource file = made(f, "/del2/a", 10);
		FileSource dir = f.createFileSource("/del2");
		long before = dir.lastModified();
		Thread.sleep(1200);   // (the column keeps seconds)

		assertTrue(file.delete());

		assertTrue(f.createFileSource("/del2").lastModified() > before, "the folder was changed by the delete");
	}

	@Test
	public void deletingWhatIsNotThereIsFalse() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		assertTrue(f.createFileSource("/del3").mkdirs());
		assertFalse(f.createFileSource("/del3/none").delete());
	}

	@Test
	public void aFolderWithFilesInIsNotDeleted() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		assertTrue(f.createFileSource("/del4").mkdirs());
		made(f, "/del4/a", 10);

		assertFalse(f.createFileSource("/del4").delete());

		assertTrue(f.createFileSource("/del4/a").exists());
		assertTrue(f.createFileSource("/del4/a").delete());
		assertTrue(f.createFileSource("/del4").delete());
		assertFalse(f.createFileSource("/del4").exists());
	}
}
