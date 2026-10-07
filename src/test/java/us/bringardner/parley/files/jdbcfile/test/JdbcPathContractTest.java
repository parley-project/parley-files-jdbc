package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.FileSourceFactory;
import us.bringardner.parley.files.fileproxy.FileProxyFactory;
import us.bringardner.parley.files.jdbcfile.JdbcFileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * BJL-13: getCanonicalPath() meets the contract the shared isChildOfMine relies on
 * (absolute, "." and ".." resolved; a JDBC file system has no links), and
 * isChildOfMine can't be escaped.
 */
public class JdbcPathContractTest {

	private static final String BASE = "/PathContractTest";

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9006);
		FileSource base = factory().createFileSource(BASE);
		assertTrue(base.getChild("root/sub").mkdirs());
		assertTrue(base.getChild("rootX").mkdirs());
		assertTrue(base.getChild("outside").mkdirs());
		write(base.getChild("root/sub/file.txt"));
		write(base.getChild("rootX/x.txt"));
		write(base.getChild("outside/secret.txt"));
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	private static FileSourceFactory factory() {
		return JdbcTestServer.factory();
	}

	private static FileSource at(String path) throws Exception {
		return factory().createFileSource(BASE+"/"+path);
	}

	private static void write(FileSource file) throws Exception {
		try(OutputStream out = file.getOutputStream()) {
			out.write("data".getBytes(StandardCharsets.UTF_8));
		}
	}

	@Test
	public void pathsAreNormalized() throws Exception {
		assertEquals("/", JdbcFileSource.normalize(""));
		assertEquals("/", JdbcFileSource.normalize("/.."));
		assertEquals("/a/c", JdbcFileSource.normalize("//a/./b/../c/"));
		assertEquals("/a/b", JdbcFileSource.normalize("a\\b"));

		assertEquals(BASE+"/root/sub/file.txt", at("root/./sub/../sub//file.txt").getCanonicalPath());
		assertEquals(BASE+"/outside", at("root/../outside").getAbsolutePath());
		assertEquals("/", factory().createFileSource("/..").getCanonicalPath());
	}

	@Test
	public void getChildTakesPaths() throws Exception {
		FileSource root = at("root");
		assertEquals(BASE+"/root/sub/file.txt", root.getChild("sub/file.txt").getCanonicalPath());
		assertTrue(root.getChild("sub/file.txt").exists());
		assertEquals(BASE, root.getChild("..").getCanonicalPath());
		assertEquals(BASE+"/outside/secret.txt", root.getChild("../outside/secret.txt").getCanonicalPath());
		assertTrue(root.getChild("../outside/secret.txt").exists());
		assertEquals(BASE+"/root", root.getChild(".").getCanonicalPath());
	}

	@Test
	public void dotsNeverBecomeNames() throws Exception {
		FileSource made = at("made/../also-made");
		assertTrue(made.mkdirs());
		assertTrue(at("also-made").isDirectory());
		assertFalse(at("made").exists());
		for(FileSource f : at("").listFiles()) {
			assertFalse(f.getName().equals("..") || f.getName().equals("."), f.getName());
		}
	}

	@Test
	public void isChildOfMineCantBeEscaped() throws Exception {
		FileSource root = at("root");
		assertTrue(root.isChildOfMine(root));
		assertTrue(root.isChildOfMine(at("root/sub")));
		assertTrue(root.isChildOfMine(at("root/sub/file.txt")));
		assertTrue(root.isChildOfMine(root.getChild("sub/../sub/file.txt")));
		assertTrue(root.isChildOfMine(root.getChild("new/../also-new")));

		assertFalse(root.isChildOfMine(root.getChild("..")));
		assertFalse(root.isChildOfMine(root.getChild("../outside/secret.txt")));
		assertFalse(root.isChildOfMine(root.getChild("sub/../../outside/secret.txt")));
		// same name prefix, different directory
		assertFalse(root.isChildOfMine(at("rootX/x.txt")));
		assertFalse(root.isChildOfMine(root.getChild("a/../../rootX/x.txt")));
		assertFalse(root.isChildOfMine(at("")));
		assertFalse(root.isChildOfMine(null));
	}

	@Test
	public void theSharedIsChildOfMineIsUsed() {
		assertThrows(NoSuchMethodException.class,
				() -> JdbcFileSource.class.getDeclaredMethod("isChildOfMine", FileSource.class));
	}

	@Test
	public void sameFileSystemMeansSameDatabase() throws Exception {
		FileSourceFactory mine = factory();
		assertTrue(mine.isSameFileSystem(mine));

		JdbcFileSourceFactory sameDb = new JdbcFileSourceFactory();
		Properties p = mine.getConnectProperties();
		p.setProperty(JdbcFileSourceFactory.JDBC_USERID, "someone-else");
		sameDb.setConnectionProperties(p);
		assertTrue(mine.isSameFileSystem(sameDb));

		JdbcFileSourceFactory otherDb = new JdbcFileSourceFactory();
		p.setProperty(JdbcFileSourceFactory.JDBC_URL, "jdbc:hsqldb:hsql://localhost:1/other");
		otherDb.setConnectionProperties(p);
		assertFalse(mine.isSameFileSystem(otherDb));

		assertFalse(mine.isSameFileSystem(new FileProxyFactory()));
		// a file from another database is never inside
		assertFalse(at("root").isChildOfMine(new FileProxyFactory().createFileSource(BASE+"/root/sub")));
	}
}
