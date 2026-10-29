package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * A folder's modified time is when something was last added to it or taken out, and it is set in the
 * same transaction as the create, rename or delete.
 */
public class JdbcFolderTimeTest {

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9052);
		assertTrue(factory().createFileSource("/foldertime/a").mkdirs());
		assertTrue(factory().createFileSource("/foldertime/b").mkdirs());
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

	private static long time(String folder) throws Exception {
		return at(folder).lastModified();
	}

	private static void pause() throws Exception {
		Thread.sleep(1100);   // (times are kept to the second in some databases)
	}

	@Test
	public void creatingAFileTouchesItsFolder() throws Exception {
		long before = time("/foldertime/a");
		pause();
		assertTrue(at("/foldertime/a/one.txt").createNewFile());
		assertTrue(time("/foldertime/a") > before);
	}

	@Test
	public void makingAFolderTouchesItsParent() throws Exception {
		long before = time("/foldertime/a");
		pause();
		assertTrue(at("/foldertime/a/sub").mkdir());
		assertTrue(time("/foldertime/a") > before);
	}

	@Test
	public void aCreateThatLostTheRaceChangesNothing() throws Exception {
		assertTrue(at("/foldertime/a/dup.txt").createNewFile());
		long before = time("/foldertime/a");
		pause();
		assertFalse(at("/foldertime/a/dup.txt").createNewFile());
		assertEquals(before, time("/foldertime/a"));
	}

	@Test
	public void renamingTouchesBothFolders() throws Exception {
		assertTrue(at("/foldertime/a/move.txt").createNewFile());
		long a = time("/foldertime/a");
		long b = time("/foldertime/b");
		pause();
		assertTrue(at("/foldertime/a/move.txt").renameTo(at("/foldertime/b/moved.txt")));
		assertTrue(time("/foldertime/a") > a, "the folder it left");
		assertTrue(time("/foldertime/b") > b, "the folder it went into");
		assertFalse(at("/foldertime/a/move.txt").exists());
		assertTrue(at("/foldertime/b/moved.txt").exists());
	}

	@Test
	public void renamingWithinOneFolderTouchesIt() throws Exception {
		assertTrue(at("/foldertime/b/same.txt").createNewFile());
		long before = time("/foldertime/b");
		pause();
		assertTrue(at("/foldertime/b/same.txt").renameTo(at("/foldertime/b/same2.txt")));
		assertTrue(time("/foldertime/b") > before);
		assertTrue(at("/foldertime/b/same2.txt").exists());
	}

	@Test
	public void aRenameOntoAnExistingNameChangesNothing() throws Exception {
		assertTrue(at("/foldertime/b/x1.txt").createNewFile());
		assertTrue(at("/foldertime/b/x2.txt").createNewFile());
		long before = time("/foldertime/b");
		pause();
		assertFalse(at("/foldertime/b/x1.txt").renameTo(at("/foldertime/b/x2.txt")));
		assertEquals(before, time("/foldertime/b"));
	}
}
