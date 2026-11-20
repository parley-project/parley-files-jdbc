package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * A symbolic link is a file that is a link, as on Linux and macOS (D78): its owner, group, times, permission
 * bits and length are its own, and what it is asked to do (read, write, chmod) is done to what it leads to.
 */
public class JdbcLinkAttributesTest {

	private static final AtomicInteger TREES = new AtomicInteger();
	private static final long TARGET_TIME = 1_500_000_000_000L;
	private static final long NEW_TIME = 1_700_000_000_000L;
	private JdbcFileSourceFactory f;
	private String root;
	private FileSource target;
	private FileSource link;

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9058);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	@BeforeEach
	void tree() throws Exception {
		f = JdbcTestServer.factory();
		root = "/linkattrs" + TREES.incrementAndGet();
		assertTrue(f.createFileSource(root).mkdirs());
		target = f.createFileSource(root + "/real.txt");
		try (OutputStream out = target.getOutputStream()) {
			out.write(new byte[] {1, 2, 3, 4, 5});
		}
		assertTrue(target.setLastModifiedTime(TARGET_TIME));
		link = f.createSymbolicLink(f.createFileSource(root + "/ln"), target);
	}

	private static UserPrincipal user(String name) {
		return () -> name;
	}

	private static GroupPrincipal group(String name) {
		return () -> name;
	}

	@Test
	void aLinkHasItsOwnOwnerGroupAndTimes() throws Exception {
		assertTrue(target.setOwner(user("someone")));
		assertTrue(target.setGroup(group("staff2")));
		assertNotEquals(TARGET_TIME, link.lastModified(), "a new link is made now, not when its file was");
		assertNotEquals("someone", link.getOwner().getName());

		assertTrue(link.setOwner(user("linkowner")));
		assertTrue(link.setGroup(group("linkgroup")));
		assertTrue(link.setLastModifiedTime(NEW_TIME));
		assertTrue(link.setLastAccessTime(NEW_TIME));

		assertEquals("linkowner", link.getOwner().getName());
		assertEquals("linkgroup", link.getGroup().getName());
		assertEquals(NEW_TIME, link.lastModified());
		assertEquals(NEW_TIME, link.lastAccessTime());
		assertEquals("someone", target.getOwner().getName(), "what the link leads to is not changed");
		assertEquals("staff2", target.getGroup().getName());
		assertEquals(TARGET_TIME, target.lastModified());
	}

	@Test
	void aLinkIsRwxrwxrwxAndChmodChangesTheFile() throws Exception {
		assertTrue(target.setOwnerExecutable(false));
		assertTrue(target.setGroupWritable(false));
		assertTrue(link.canGroupWrite() && link.canOwnerExecute(), "the link's own bits");
		assertTrue(link.setOtherWritable(false), "chmod follows the link");
		assertFalse(target.canOtherWrite());
		assertTrue(link.canOtherWrite(), "the link's own bit is unchanged");
		// whether the user may use it is the file's
		assertEquals(target.canExecute(), link.canExecute());
		assertEquals(target.canWrite(), link.canWrite());
	}

	@Test
	void aLinkIsAsLongAsItsTextAndItsContentIsTheFiles() throws Exception {
		assertEquals(target.getAbsolutePath().length(), link.length(), "the path it points to");
		assertEquals(5, link.contentLength());
		assertEquals(5, target.length());
		try (java.io.InputStream in = link.getInputStream()) {
			assertEquals(5, in.readAllBytes().length, "reading through the link gives the file");
		}
	}
}
