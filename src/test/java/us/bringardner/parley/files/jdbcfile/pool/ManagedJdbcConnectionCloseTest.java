package us.bringardner.parley.files.jdbcfile.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a release sends to the server: a rollback only when a transaction may be open, and the
 * borrower's auto-commit mode not at all: a change is sent when a statement, a commit or the like
 * needs it, and a release leaves the server's mode as it is.
 */
public class ManagedJdbcConnectionCloseTest {

	private List<String> calls;
	private ManagedJdbcConnection con;

	@BeforeEach
	void setUp() throws Exception {
		calls = new ArrayList<>();
		Connection real = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, method, args) -> {
					String name = method.getName();
					calls.add(name + (args != null && args.length > 0 && args[0] instanceof Boolean ? "(" + args[0] + ")" : ""));
					if( name.equals("getAutoCommit") ) {
						return true;
					}
					if( name.equals("prepareStatement") ) {
						return Proxy.newProxyInstance(getClass().getClassLoader(),
								new Class<?>[] { PreparedStatement.class }, (p, m, a) -> {
									Class<?> t = m.getReturnType();
									return t == boolean.class ? (Object) false : t == int.class ? (Object) 0 : null;
								});
					}
					Class<?> type = method.getReturnType();
					if( type == boolean.class ) {
						return false;
					}
					if( type == int.class ) {
						return 0;
					}
					return null;
				});
		con = new ManagedJdbcConnection(real);
		calls.clear();
	}

	private long count(String call) {
		return calls.stream().filter(call::equals).count();
	}

	@Test
	void autoCommitUseSendsNoRollback() throws Exception {
		con.prepareStatement("select 1").execute();
		con.close();
		assertEquals(0, count("rollback"));
		assertEquals(0, count("setAutoCommit(true)"));
		assertEquals(0, count("setAutoCommit(false)"));
	}

	@Test
	void committedTransactionSendsNoRollbackOnRelease() throws Exception {
		con.setAutoCommit(false);
		assertEquals(0, count("setAutoCommit(false)"));   // only recorded
		con.prepareStatement("update t set x = 1").execute();
		con.commit();
		con.close();
		assertEquals(1, count("setAutoCommit(false)"));
		assertEquals(1, count("commit"));
		assertEquals(0, count("rollback"));
		assertEquals(0, count("setAutoCommit(true)"));   // not put back on release
	}

	@Test
	void unfinishedTransactionIsRolledBackOnRelease() throws Exception {
		con.setAutoCommit(false);
		con.prepareStatement("update t set x = 1").execute();
		con.close();
		assertEquals(1, count("rollback"));
		assertEquals(0, count("setAutoCommit(true)"));
	}

	@Test
	void statementMadeAndNeverRunSendsNothingOnRelease() throws Exception {
		con.setAutoCommit(false);
		con.prepareStatement("update t set x = 1");
		con.close();
		//  (the server never left auto-commit, so a rollback would be an error)
		assertEquals(0, count("rollback"));
		assertEquals(0, count("setAutoCommit(false)"));
	}

	@Test
	void rolledBackTransactionIsNotRolledBackAgain() throws Exception {
		con.setAutoCommit(false);
		con.prepareStatement("update t set x = 1").execute();
		con.rollback();
		con.close();
		assertEquals(1, count("rollback"));
	}

	@Test
	void autoCommitOffWithNoStatementSendsNothing() throws Exception {
		con.setAutoCommit(false);
		con.close();
		assertEquals(0, count("rollback"));
		assertEquals(0, count("setAutoCommit(true)"));
		assertEquals(0, count("setAutoCommit(false)"));
	}

	@Test
	void theModeTheLastBorrowerLeftCostsTheNextNothing() throws Exception {
		con.setAutoCommit(false);
		con.prepareStatement("update t set x = 1").execute();
		con.commit();
		con.close();
		calls.clear();
		//  the next borrower sees auto-commit on, as the connection was opened
		assertEquals(true, con.getAutoCommit());
		con.setAutoCommit(false);
		assertEquals(false, con.getAutoCommit());
		con.prepareStatement("update t set x = 2").execute();
		con.commit();
		con.close();
		assertEquals(0, count("setAutoCommit(false)"));
		assertEquals(0, count("setAutoCommit(true)"));
		assertEquals(1, count("commit"));
	}

	@Test
	void aReaderAfterATransactionGetsAutoCommitBeforeItsFirstStatement() throws Exception {
		con.setAutoCommit(false);
		con.prepareStatement("update t set x = 1").execute();
		con.commit();
		con.close();
		calls.clear();
		con.prepareStatement("select 1").execute();
		assertEquals(1, count("setAutoCommit(true)"));
		con.close();
		assertEquals(0, count("rollback"));
	}

	@Test
	void turningAutoCommitOnWhileATransactionIsOpenCommitsAtOnce() throws Exception {
		con.setAutoCommit(false);
		con.prepareStatement("update t set x = 1").execute();
		calls.clear();
		con.setAutoCommit(true);
		assertEquals(1, count("setAutoCommit(true)"));
		con.close();
		assertEquals(0, count("rollback"));
	}

	@Test
	void commitAndRollbackSendTheModeFirst() throws Exception {
		con.setAutoCommit(false);
		con.commit();
		assertEquals("setAutoCommit(false)", calls.get(0));
		assertEquals("commit", calls.get(1));
	}
}
