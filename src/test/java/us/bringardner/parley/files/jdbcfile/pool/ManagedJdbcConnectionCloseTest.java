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
 * borrower's auto-commit mode put back only when the borrower changed it.
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
								new Class<?>[] { PreparedStatement.class }, (p, m, a) -> null);
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
		con.prepareStatement("select 1");
		con.close();
		assertEquals(0, count("rollback"));
		assertEquals(0, count("setAutoCommit(true)"));
	}

	@Test
	void committedTransactionSendsNoRollbackOnRelease() throws Exception {
		con.setAutoCommit(false);
		con.prepareStatement("update t set x = 1");
		con.commit();
		con.close();
		assertEquals(1, count("commit"));
		assertEquals(0, count("rollback"));
		assertEquals(1, count("setAutoCommit(true)"));
	}

	@Test
	void unfinishedTransactionIsRolledBackOnRelease() throws Exception {
		con.setAutoCommit(false);
		con.prepareStatement("update t set x = 1");
		con.close();
		assertEquals(1, count("rollback"));
		assertEquals(1, count("setAutoCommit(true)"));
	}

	@Test
	void rolledBackTransactionIsNotRolledBackAgain() throws Exception {
		con.setAutoCommit(false);
		con.prepareStatement("update t set x = 1");
		con.rollback();
		con.close();
		assertEquals(1, count("rollback"));
	}

	@Test
	void autoCommitOffWithNoStatementSendsNoRollback() throws Exception {
		con.setAutoCommit(false);
		con.close();
		assertEquals(0, count("rollback"));
		assertEquals(1, count("setAutoCommit(true)"));
	}
}
