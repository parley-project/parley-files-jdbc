package us.bringardner.parley.files.jdbcfile.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What a borrower can reach from a statement: its own handle on the connection, never the driver's
 * connection, and a transaction opened by running a statement is rolled back on release even when
 * the statement was made before the last commit.
 */
public class ManagedJdbcConnectionStatementTest {

	private List<String> calls;
	private Connection real;

	/** A driver object of the given interface: answers false/0/null, and the real connection to getConnection(). */
	@SuppressWarnings("unchecked")
	private <T> T stub(Class<T> type) {
		return (T) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			String name = method.getName();
			if( name.equals("getConnection") && args == null ) {
				return real;
			}
			if( type == Statement.class || type == PreparedStatement.class || type == CallableStatement.class ) {
				calls.add(name);
			}
			Class<?> r = method.getReturnType();
			return r == boolean.class ? (Object) false : r == int.class ? (Object) 0 : r == long.class ? (Object) 0L : null;
		});
	}

	@BeforeEach
	void setUp() {
		calls = Collections.synchronizedList(new ArrayList<>());
		real = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
				(proxy, method, args) -> {
					String name = method.getName();
					calls.add(name);
					switch (name) {
					case "getAutoCommit":
						return true;
					case "createStatement":
						return stub(Statement.class);
					case "prepareStatement":
						return stub(PreparedStatement.class);
					case "prepareCall":
						return stub(CallableStatement.class);
					case "getMetaData":
						return stub(DatabaseMetaData.class);
					default:
						Class<?> r = method.getReturnType();
						return r == boolean.class ? (Object) false : r == int.class ? (Object) 0 : null;
					}
				});
	}

	private long count(String call) {
		return calls.stream().filter(call::equals).count();
	}

	private JdbcConnectionPool pool() {
		JdbcConnectionPool p = new JdbcConnectionPool("jdbc:fake", "u", "p") {
			@Override
			public IManagedObject createObject() throws SQLException {
				return new ManagedJdbcConnection(real);
			}
		};
		p.setMax(1);
		p.setTimeToWait(300);
		return p;
	}

	@Test
	void aStatementRunAfterACommitIsRolledBackOnRelease() throws Exception {
		ManagedJdbcConnection con = new ManagedJdbcConnection(real);
		con.setAutoCommit(false);
		PreparedStatement ps = con.prepareStatement("update t set x = 1");
		con.commit();
		calls.clear();

		ps.executeUpdate();   // a transaction again, on a statement made before the commit
		con.close();

		assertEquals(1, count("rollback"));
	}

	@Test
	void aStatementNotRunAfterTheCommitSendsNoRollback() throws Exception {
		ManagedJdbcConnection con = new ManagedJdbcConnection(real);
		con.setAutoCommit(false);
		con.prepareStatement("update t set x = 1");
		con.commit();
		calls.clear();

		con.close();

		assertEquals(0, count("rollback"));
	}

	@Test
	void everyStatementAndMetaDataAnswersWithTheBorrowersHandle() throws Exception {
		JdbcConnectionPool pool = pool();
		try {
			Connection h = pool.getConnection();
			assertSame(h, h.createStatement().getConnection());
			assertSame(h, h.createStatement(1, 2).getConnection());
			assertSame(h, h.createStatement(1, 2, 3).getConnection());
			assertSame(h, h.prepareStatement("x").getConnection());
			assertSame(h, h.prepareStatement("x", 1).getConnection());
			assertSame(h, h.prepareStatement("x", new int[0]).getConnection());
			assertSame(h, h.prepareStatement("x", new String[0]).getConnection());
			assertSame(h, h.prepareStatement("x", 1, 2).getConnection());
			assertSame(h, h.prepareStatement("x", 1, 2, 3).getConnection());
			assertSame(h, h.prepareCall("x").getConnection());
			assertSame(h, h.prepareCall("x", 1, 2).getConnection());
			assertSame(h, h.prepareCall("x", 1, 2, 3).getConnection());
			assertSame(h, h.getMetaData().getConnection());
		} finally {
			pool.destroyAll();
		}
	}

	@Test
	void aStatementKeptPastCloseDoesNotLeadToSomeoneElsesConnection() throws Exception {
		JdbcConnectionPool pool = pool();
		try {
			Connection first = pool.getConnection();
			Statement kept = first.createStatement();
			first.close();

			Connection second = pool.getConnection();
			assertSame(first, kept.getConnection());
			assertNotSame(second, kept.getConnection());
			kept.getConnection().close();   // the dead handle: must not give back the second one's connection

			assertEquals(false, second.isClosed());
		} finally {
			pool.destroyAll();
		}
	}
}
