package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * Links in the database: a symbolic link is a row that holds the path it points to, a hard link a
 * row that shares the data of a file (and takes it over when that row is deleted).
 */
public class JdbcLinkTest {

	private static final AtomicInteger TREES = new AtomicInteger();
	private JdbcFileSourceFactory f;
	private String root;

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9014);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	@BeforeEach
	void tree() throws Exception {
		f = JdbcTestServer.factory();
		root = "/links" + TREES.incrementAndGet();
		assertTrue(f.createFileSource(root).mkdirs());
	}

	private FileSource at(String relative) throws Exception {
		return f.createFileSource(root + "/" + relative);
	}

	private void write(String relative, byte... content) throws Exception {
		try (OutputStream out = at(relative).getOutputStream()) {
			out.write(content);
		}
	}

	private byte[] read(String relative) throws Exception {
		try (InputStream in = at(relative).getInputStream()) {
			return in.readAllBytes();
		}
	}

	@Test
	void theLastHardLinkKeepsTheFile() throws Exception {
		write("owner", (byte) 1, (byte) 2, (byte) 3);
		f.createLink(at("h1"), at("owner"));
		f.createLink(at("h2"), at("owner"));
		assertArrayEquals(new byte[] {1, 2, 3}, read("h1"));

		assertTrue(at("owner").delete(), "the name that held the data goes");
		assertFalse(at("owner").exists());
		for(String n : new String[] {"h1", "h2"}) {
			assertTrue(at(n).isFile(), n);
			assertEquals(3, at(n).length(), n);
			assertArrayEquals(new byte[] {1, 2, 3}, read(n), n);
		}
		// the survivors still share: a write through one is seen by the other
		write("h1", (byte) 9);
		assertArrayEquals(new byte[] {9}, read("h2"));
		assertTrue(at("h1").delete());
		assertArrayEquals(new byte[] {9}, read("h2"));
		assertTrue(at("h2").delete());
		assertFalse(at("h2").exists());
	}

	@Test
	void renamingTheOwnerOrALinkKeepsTheFile() throws Exception {
		write("owner", (byte) 7);
		f.createLink(at("h"), at("owner"));
		f.createSymbolicLink(at("s"), at("owner"));

		assertTrue(at("owner").renameTo(at("moved")));
		assertArrayEquals(new byte[] {7}, read("moved"));
		assertArrayEquals(new byte[] {7}, read("h"), "the hard link follows the file");
		assertFalse(at("s").exists(), "the symbolic link names a path, which is gone");
		assertTrue(at("s").getLinkedTo() != null);

		assertTrue(at("h").renameTo(at("h2")));
		assertArrayEquals(new byte[] {7}, read("h2"));
		assertTrue(at("s").renameTo(at("s2")));
		assertEquals("owner", at("s2").getLinkedTo().getName());
		// a new file at the old path is what the symbolic link points at again
		write("owner", (byte) 5);
		assertArrayEquals(new byte[] {5}, read("s2"));
	}

	@Test
	void writingThroughALinkThatPointsAtNothingCreatesTheTarget() throws Exception {
		f.createSymbolicLink(at("dangling"), at("later"));
		assertFalse(at("dangling").exists());
		assertTrue(at("dangling").getLinkedTo() != null);
		write("dangling", (byte) 4, (byte) 4);
		assertTrue(at("later").isFile());
		assertArrayEquals(new byte[] {4, 4}, read("later"));
		assertArrayEquals(new byte[] {4, 4}, read("dangling"));
		assertTrue(at("dangling").delete());
		assertTrue(at("later").exists(), "deleting the link leaves the file");
	}

	@Test
	void workInsideALinkedDirectory() throws Exception {
		assertTrue(at("real").mkdir());
		f.createSymbolicLink(at("lnk"), at("real"));
		write("lnk/inside.txt", (byte) 1);
		assertTrue(at("real/inside.txt").isFile(), "it went into the directory the link points at");
		assertTrue(at("lnk/sub").mkdir());
		assertTrue(at("real/sub").isDirectory());
		Set<String> names = new TreeSet<>(Arrays.asList(at("lnk").list()));
		assertEquals(Set.of("inside.txt", "sub"), names);
		assertTrue(at("lnk/inside.txt").delete());
		assertFalse(at("real/inside.txt").exists());
		assertEquals(f.createFileSource(root + "/real/sub").getCanonicalPath(), at("lnk/sub").getCanonicalPath());
		assertEquals(root + "/real", at("lnk").getCanonicalPath());
		assertEquals(root + "/lnk", at("lnk").getAbsolutePath());
	}

	@Test
	void aDirectoryHoldingALinkIsNotEmpty() throws Exception {
		assertTrue(at("d").mkdir());
		f.createSymbolicLink(at("d/gone"), at("nothing"));
		assertFalse(at("d").delete());
		assertTrue(Arrays.asList(at("d").list()).contains("gone"), "a link is listed, also one that points at nothing");
		assertTrue(at("d/gone").delete());
		assertTrue(at("d").delete());
	}

	@Test
	void refusals() throws Exception {
		write("file", (byte) 1);
		assertTrue(at("dir").mkdir());
		f.createSymbolicLink(at("s"), at("file"));
		// the link name must be free, and its directory must be there
		assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> f.createSymbolicLink(at("file"), at("dir")));
		assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> f.createLink(at("s"), at("file")));
		assertThrows(java.io.IOException.class, () -> f.createSymbolicLink(at("nodir/x"), at("file")));
		// a hard link is to a file that exists
		assertThrows(java.io.IOException.class, () -> f.createLink(at("h"), at("dir")));
		assertThrows(java.io.IOException.class, () -> f.createLink(at("h"), at("missing")));
		assertNull(at("file").getLinkedTo());
		assertNotNull(at("s").getLinkedTo());
	}

	@Test
	void aSecondConnectionSeesLinksMadeByTheFirst() throws Exception {
		write("target", (byte) 8);
		f.createSymbolicLink(at("ln"), at("target"));
		f.createLink(at("hard"), at("target"));

		JdbcFileSourceFactory other = new JdbcFileSourceFactory();
		java.util.Properties p = f.getConnectProperties();
		other.setConnectionProperties(p);
		assertTrue(other.connect());
		try {
			FileSource ln = other.createFileSource(root + "/ln");
			assertTrue(ln.exists());
			assertEquals("target", ln.getLinkedTo().getName());
			try (InputStream in = ln.getInputStream()) {
				assertArrayEquals(new byte[] {8}, in.readAllBytes());
			}
			try (InputStream in = other.createFileSource(root + "/hard").getInputStream()) {
				assertArrayEquals(new byte[] {8}, in.readAllBytes());
			}
		} finally {
			other.disConnect();
		}
	}
}
