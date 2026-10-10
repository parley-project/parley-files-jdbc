package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;

/**
 * Like java.io.File, two handles for one file are equal and hash the same, so they can be put
 * in a Set or used as a key. "One file" is the same path in the same database.
 */
public class JdbcFileSourceEqualityTest {

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9011);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	private static FileSource dir() throws Exception {
		FileSource d = JdbcTestServer.factory().createFileSource("/equality-" + System.nanoTime());
		assertTrue(d.mkdirs());
		return d;
	}

	@Test
	void twoHandlesForOnePathAreEqual() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		FileSource d = dir();
		FileSource a = f.createFileSource(d.getAbsolutePath() + "/a.txt");
		FileSource b = f.createFileSource(d.getAbsolutePath() + "/a.txt");
		assertTrue(a != b, "two handles");
		assertEquals(a, b);
		assertEquals(b, a, "symmetric");
		assertEquals(a.hashCode(), b.hashCode());
		assertEquals(a, a, "reflexive");
		// found through the parent too
		assertEquals(a, d.getChild("a.txt"));
		assertEquals(a.hashCode(), d.getChild("a.txt").hashCode());
	}

	@Test
	void differentPathsAreNotEqual() throws Exception {
		FileSource d = dir();
		assertNotEquals(d.getChild("a.txt"), d.getChild("b.txt"));
		assertNotEquals(d, d.getChild("a.txt"));
	}

	@Test
	void theSameDatabaseThroughAnotherFactoryIsTheSameFile() throws Exception {
		JdbcFileSourceFactory first = JdbcTestServer.factory();
		FileSource d = dir();
		FileSource a = first.createFileSource(d.getAbsolutePath() + "/shared.txt");

		JdbcFileSourceFactory second = new JdbcFileSourceFactory();
		Properties p = first.getConnectProperties();
		assertTrue(second.connect(p), "can't connect a second factory to the same database");
		try {
			FileSource b = second.createFileSource(d.getAbsolutePath() + "/shared.txt");
			assertEquals(a, b);
			assertEquals(a.hashCode(), b.hashCode());
		} finally {
			second.disConnect();
		}
	}

	@Test
	void notEqualToNullOrToAnotherKindOfObject() throws Exception {
		FileSource a = dir().getChild("a.txt");
		assertFalse(a.equals(null));
		assertFalse(a.equals(a.getAbsolutePath()));
		assertFalse(a.equals(new java.io.File(a.getAbsolutePath())));
	}

	@Test
	void worksInSetsAndAsKeys() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		FileSource d = dir();
		Set<FileSource> set = new HashSet<>();
		set.add(f.createFileSource(d.getAbsolutePath() + "/a.txt"));
		set.add(f.createFileSource(d.getAbsolutePath() + "/a.txt"));
		set.add(f.createFileSource(d.getAbsolutePath() + "/b.txt"));
		assertEquals(2, set.size());
		assertTrue(set.contains(d.getChild("b.txt")));
	}

	@Test
	void theRootIsEqualToTheRoot() throws Exception {
		JdbcFileSourceFactory f = JdbcTestServer.factory();
		assertEquals(f.createFileSource("/"), f.createFileSource("/"));
	}

	// ------------------------------------------------------------ ordering

	@Test
	void sortsAscendingByPath() throws Exception {
		FileSource d = dir();
		FileSource a = d.getChild("a.txt");
		FileSource b = d.getChild("b.txt");
		FileSource c = d.getChild("c.txt");
		assertTrue(a.compareTo(b) < 0, "a before b");
		assertTrue(b.compareTo(a) > 0, "and b after a");

		java.util.TreeSet<FileSource> sorted = new java.util.TreeSet<>(java.util.List.of(c, a, b));
		assertEquals(java.util.List.of(a, b, c), new java.util.ArrayList<>(sorted));
	}

	@Test
	void orderingAgreesWithEquality() throws Exception {
		FileSource d = dir();
		FileSource a = d.getChild("a.txt");
		FileSource same = JdbcTestServer.factory().createFileSource(d.getAbsolutePath() + "/a.txt");
		assertEquals(0, a.compareTo(same));
		assertEquals(0, same.compareTo(a));
		assertEquals(a, same);
	}

	@Test
	void somethingThatIsNotAFileSourceIsNotEqualToIt() throws Exception {
		// it used to answer 0 (equal) to anything that wasn't a JdbcFileSource
		FileSource a = dir().getChild("a.txt");
		assertTrue(a.compareTo("zzz") < 0, "compared by path, as text");
		assertTrue(a.compareTo("/") > 0);
		assertTrue(a.compareTo(a.getAbsolutePath()) == 0, "its own path as text is the same path");
		assertTrue(a.compareTo(new java.io.File("/aaa")) != 0);
	}
}
