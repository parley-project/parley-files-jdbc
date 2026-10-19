package us.bringardner.parley.files.jdbcfile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import us.bringardner.parley.files.lock.LockBackend;

/**
 * File locks kept by the database, so they reach every process (on any machine) that uses it.
 * <p>
 * Each locked file has a row in {@code file_source.file_lock}, keyed by its {@code fileid}. A lock
 * is a row lock on that row ({@code SELECT ... FOR UPDATE NOWAIT}, or {@code FOR SHARE NOWAIT} for a
 * shared lock), taken in a transaction that stays open on a connection of its own until the lock is
 * released. Nothing else ever writes to that table, so the holder reads and writes the file as
 * usual; and if the holder dies, its connection drops and the database frees the lock.
 * <p>
 * The connection is opened for the lock, not taken from the factory's pool, which closes
 * connections that are idle for long.
 * <p>
 * Only databases that can refuse a row lock without waiting ({@code NOWAIT}) get these locks:
 * PostgreSQL, MySQL 8 and MariaDB 10.3 or later. Others (HSQLDB among them) keep the default,
 * locks within this JVM.
 */
final class JdbcLocks {

	private static final Logger LOG = Logger.getLogger(JdbcLocks.class.getName());

	static final String TABLE = "file_source.file_lock";

	/** The SQL each database needs. */
	enum Dialect {
		POSTGRESQL("FOR UPDATE NOWAIT", "FOR SHARE NOWAIT", true),
		MYSQL("FOR UPDATE NOWAIT", "FOR SHARE NOWAIT", true),
		MARIADB("FOR UPDATE NOWAIT", "LOCK IN SHARE MODE NOWAIT", false);

		final String exclusive;
		final String shared;
		/** Whether it has SKIP LOCKED (MariaDB only from 10.6), used to clear rows of deleted files. */
		final boolean skipLocked;

		Dialect(String exclusive, String shared, boolean skipLocked) {
			this.exclusive = exclusive;
			this.shared = shared;
			this.skipLocked = skipLocked;
		}

		/** Whether a failed lock means "held by someone else" (rather than an error). */
		boolean isBusy(SQLException e) {
			switch (this) {
			case POSTGRESQL:
				return "55P03".equals(e.getSQLState()); // lock_not_available
			case MYSQL:
				return e.getErrorCode() == 3572; // ER_LOCK_NOWAIT
			default:
				return e.getErrorCode() == 3572 || e.getErrorCode() == 1205; // NOWAIT reports a lock wait timeout
			}
		}
	}

	private JdbcLocks() {
	}

	/**
	 * The dialect of a database, or null if it can't lock without waiting.
	 * @param md the database
	 * @return the dialect, or null
	 * @throws SQLException if the database can't be asked
	 */
	static Dialect detect(DatabaseMetaData md) throws SQLException {
		String product = String.valueOf(md.getDatabaseProductName()).toLowerCase(Locale.ROOT);
		String version = String.valueOf(md.getDatabaseProductVersion()).toLowerCase(Locale.ROOT);
		int major = md.getDatabaseMajorVersion();
		int minor = md.getDatabaseMinorVersion();
		if( product.contains("postgres") ) {
			return major >= 10 ? Dialect.POSTGRESQL : null;
		}
		if( product.contains("mariadb") || version.contains("mariadb") ) {
			return major > 10 || (major == 10 && minor >= 3) ? Dialect.MARIADB : null;
		}
		if( product.contains("mysql") ) {
			return major >= 8 ? Dialect.MYSQL : null;
		}
		return null;
	}

	/**
	 * Create the lock table if it is missing.
	 * @param con a connection
	 * @throws SQLException if it can't be created
	 */
	static void createTable(Connection con) throws SQLException {
		try (Statement st = con.createStatement()) {
			st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " (fileid BIGINT NOT NULL PRIMARY KEY)");
		}
		if( !con.getAutoCommit() ) {
			con.commit();
		}
	}

	/**
	 * Remove the rows of files that no longer exist, skipping any that are locked (a holder may still
	 * hold a lock on a file deleted under it). Rows are tiny and file ids are never reused, so this is
	 * only tidying.
	 * @param con a connection
	 * @param dialect the database's dialect
	 * @return how many rows were removed
	 * @throws SQLException if the database refuses
	 */
	static int removeRowsOfDeletedFiles(Connection con, Dialect dialect) throws SQLException {
		if( !dialect.skipLocked ) {
			return 0;
		}
		boolean auto = con.getAutoCommit();
		con.setAutoCommit(false);
		try {
			List<Long> ids = new ArrayList<>();
			try (Statement st = con.createStatement();
					ResultSet rs = st.executeQuery("SELECT l.fileid FROM " + TABLE + " l WHERE NOT EXISTS "
							+ "(SELECT 1 FROM file_source.file f WHERE f.fileid = l.fileid) FOR UPDATE SKIP LOCKED")) {
				while (rs.next()) {
					ids.add(rs.getLong(1));
				}
			}
			try (PreparedStatement ps = con.prepareStatement("DELETE FROM " + TABLE + " WHERE fileid = ?")) {
				for (long id : ids) {
					ps.setLong(1, id);
					ps.addBatch();
				}
				if( !ids.isEmpty() ) {
					ps.executeBatch();
				}
			}
			con.commit();
			return ids.size();
		} catch (SQLException | RuntimeException e) {
			con.rollback();
			throw e;
		} finally {
			con.setAutoCommit(auto);
		}
	}

	/** The database side of one lock on one file. */
	static final class Backend implements LockBackend {
		private final JdbcFileSourceFactory factory;
		private final Dialect dialect;
		private final long fileid;
		private Connection con;

		Backend(JdbcFileSourceFactory factory, Dialect dialect, long fileid) {
			this.factory = factory;
			this.dialect = dialect;
			this.fileid = fileid;
		}

		@Override
		public synchronized boolean acquire(boolean shared) throws IOException {
			ensureRow();
			Connection c = null;
			try {
				c = factory.openLockConnection();
				c.setAutoCommit(false);
				try (PreparedStatement ps = c.prepareStatement("SELECT fileid FROM " + TABLE + " WHERE fileid = ? "
						+ (shared ? dialect.shared : dialect.exclusive))) {
					ps.setLong(1, fileid);
					try (ResultSet rs = ps.executeQuery()) {
						if( !rs.next() ) {
							throw new FileNotFoundException("The file " + fileid + " was deleted while it was being locked");
						}
					}
				} catch (SQLException e) {
					if( dialect.isBusy(e) ) {
						quietlyClose(c);
						return false;
					}
					throw e;
				}
				con = c;
				return true;
			} catch (SQLException e) {
				quietlyClose(c);
				throw new IOException("Can't lock file " + fileid + " in the database: " + e.getMessage(), e);
			} catch (IOException | RuntimeException e) {
				quietlyClose(c);
				throw e;
			}
		}

		/** Make sure the file has a row to lock: insert it, or find it is there already. */
		private void ensureRow() throws IOException {
			try (Connection c = factory.getConnection()) {
				try (PreparedStatement ps = c.prepareStatement("SELECT fileid FROM " + TABLE + " WHERE fileid = ?")) {
					ps.setLong(1, fileid);
					try (ResultSet rs = ps.executeQuery()) {
						if( rs.next() ) {
							return;
						}
					}
				}
				try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (fileid) VALUES (?)")) {
					ps.setLong(1, fileid);
					ps.executeUpdate();
				} catch (SQLException e) {
					// another process inserted it at the same moment: there is a row, which is all we need
					if( e.getSQLState() == null || !e.getSQLState().startsWith("23") ) {
						throw e;
					}
				}
				if( !c.getAutoCommit() ) {
					c.commit();
				}
			} catch (SQLException e) {
				throw new IOException("Can't make the lock row for file " + fileid + ": " + e.getMessage(), e);
			}
		}

		@Override
		public synchronized void release() {
			Connection c = con;
			con = null;
			if( c != null ) {
				try {
					c.rollback(); // ends the transaction, which frees the row lock
				} catch (SQLException e) {
					LOG.log(Level.FINE, "Rolling back the lock on file " + fileid, e);
				}
				quietlyClose(c);
			}
		}

		private static void quietlyClose(Connection c) {
			if( c != null ) {
				try {
					c.close();
				} catch (SQLException e) {
					LOG.log(Level.FINE, "Closing a lock connection", e);
				}
			}
		}

		@Override
		public String toString() {
			return "database lock on file " + fileid;
		}
	}
}
