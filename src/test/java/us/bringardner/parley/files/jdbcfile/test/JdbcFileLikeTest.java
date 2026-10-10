package us.bringardner.parley.files.jdbcfile.test;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import us.bringardner.parley.files.FileSource;
import us.bringardner.parley.files.jdbcfile.JdbcFileSourceFactory;
import us.bringardner.parley.files.test.FileLikeBehaviorTests;

/** A database, reached through JdbcFileSource, acts like a java.io.File. */
public class JdbcFileLikeTest extends FileLikeBehaviorTests {

	private static final AtomicInteger TREES = new AtomicInteger();

	private String tree;

	@BeforeAll
	public static void setUp() throws Exception {
		JdbcTestServer.setUp(9012);
	}

	@AfterAll
	static void tearDown() throws Exception {
		JdbcTestServer.tearDown();
	}

	private static JdbcFileSourceFactory factory() {
		return JdbcTestServer.factory();
	}

	@Override
	protected void newTree() throws Exception {
		tree = "/filelike" + TREES.incrementAndGet();
		assertTrue(factory().createFileSource(tree).mkdirs(), "can't create " + tree);
	}

	@Override
	protected FileSource sourceFor(String relative) throws Exception {
		return factory().createFileSource(tree + "/" + relative);
	}
}
