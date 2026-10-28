package us.bringardner.parley.files.jdbcfile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.security.SecureRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import us.bringardner.parley.files.lock.LockBackend;

/**
 * File locks kept by the database, so they reach every process (on any machine) that uses it.
 * <p>
 * A lock covers a region of a file. Each region held is a row in {@code file_source.file_lock_range}
 * (the file's {@code fileid}, the region, shared or not), and the holder keeps that row locked
 * ({@code SELECT ... FOR UPDATE}) in a transaction open on a connection of its own until the lock is
 * released: the row says what is held, and its row lock says the holder is alive. If the holder
 * dies, its connection drops, the database frees the row lock, and the row is left behind as a
 * stale claim that the next locker removes.
 * <p>
 * Taking a lock is done under a short mutex, a row lock on the file's row in
 * {@code file_source.file_lock}, taken without waiting ({@code NOWAIT}, tried again for up to
 * {@code parley.files.jdbc.lockMutexWait} ms, 200 by default; a mutex that stays taken means busy,
 * since a tryLock must not wait): the locker looks at every region that overlaps the one it wants,
 * removes the stale ones (it can row-lock them, so their holder is gone), and either finds one held
 * by a live holder that conflicts (both exclusive, or one of them) and gives up, or inserts its row,
 * locks it on its own connection, and only then lets the mutex go. Nothing else ever writes to these
 * tables, so the holder reads and writes the file as usual.
 * <p>
 * The connection that holds a lock is opened for it, not taken from the factory's pool, which closes
 * connections that are idle for long.
 * <p>
 * Only databases that can refuse a row lock without waiting ({@code NOWAIT}) get these locks:
 * PostgreSQL, MySQL 8 and MariaDB 10.3 or later. Others (HSQLDB among them) keep the default,
 * locks within this JVM.
 */
final class JdbcLocks {

	private static final Logger LOG = Logger.getLogger(JdbcLocks.class.getName());

	/** One row per locked file: the mutex for taking its locks. */
	static final String TABLE = "file_source.file_lock";
	/** One row per region held, kept row-locked by its holder. */
	static final String RANGES = "file_source.file_lock_range";

	private static final SecureRandom IDS = new SecureRandom();

	/**
	 * How long a tryLock tries for a file's mutex before it calls the file busy (system property
	 * {@code parley.files.jdbc.lockMutexWait}, ms). Lockers hold it only while they look and claim.
	 */
	static final long MUTEX_WAIT_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
			Long.getLong("parley.files.jdbc.lockMutexWait", 200));

	/** The SQL each database needs. */
	enum Dialect {
		POSTGRESQL(true), MYSQL(true), MARIADB(false);

		/** Whether it has SKIP LOCKED (MariaDB only from 10.6), used to clear rows of deleted files. */
		final boolean skipLocked;

		Dialect(boolean skipLocked) {
			this.skipLocked = skipLocked;
		}

		/** Whether a failed NOWAIT lock means "held by someone else" (rather than an error). */
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
	 * Create the lock tables if they are missing.
	 * @param con a connection
	 * @throws SQLException if they can't be created
	 */
	static void createTables(Connection con) throws SQLException {
		try (Statement st = con.createStatement()) {
			st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " (fileid BIGINT NOT NULL PRIMARY KEY)");
			st.executeUpdate("CREATE TABLE IF NOT EXISTS " + RANGES + " (fileid BIGINT NOT NULL, id BIGINT NOT NULL, "
					+ "start_pos BIGINT NOT NULL, end_pos BIGINT NOT NULL, shared BOOLEAN NOT NULL, PRIMARY KEY (fileid, id))");
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
			int n = 0;
			for (String table : new String[] {RANGES, TABLE}) {
				List<long[]> candidates = new ArrayList<>();
				boolean ranges = table.equals(RANGES);
				// Find them without locking, then lock each one by its key. One locking query over the
				// table would lock more than it returns on MySQL (InnoDB locks every row it reads), so
				// it would hold rows of live files, among them a new lock's row before its holder
				// locks it.
				try (Statement st = con.createStatement();
						ResultSet rs = st.executeQuery("SELECT l.fileid" + (ranges ? ", l.id" : "") + " FROM " + table
								+ " l WHERE NOT EXISTS (SELECT 1 FROM file_source.file f WHERE f.fileid = l.fileid)")) {
					while (rs.next()) {
						candidates.add(new long[] {rs.getLong(1), ranges ? rs.getLong(2) : 0});
					}
				}
				List<long[]> rows = new ArrayList<>();
				try (PreparedStatement ps = con.prepareStatement("SELECT fileid FROM " + table + " WHERE fileid = ?"
						+ (ranges ? " AND id = ?" : "") + " FOR UPDATE SKIP LOCKED")) {
					for (long[] r : candidates) {
						ps.setLong(1, r[0]);
						if( ranges ) {
							ps.setLong(2, r[1]);
						}
						try (ResultSet rs = ps.executeQuery()) {
							if( rs.next() ) {
								rows.add(r); // not held: a holder keeps its row locked
							}
						}
					}
				}
				try (PreparedStatement ps = con.prepareStatement("DELETE FROM " + table + " WHERE fileid = ?" + (ranges ? " AND id = ?" : ""))) {
					for (long[] r : rows) {
						ps.setLong(1, r[0]);
						if( ranges ) {
							ps.setLong(2, r[1]);
						}
						ps.addBatch();
					}
					if( !rows.isEmpty() ) {
						ps.executeBatch();
					}
				}
				n += rows.size();
			}
			con.commit();
			return n;
		} catch (SQLException | RuntimeException e) {
			con.rollback();
			throw e;
		} finally {
			con.setAutoCommit(auto);
		}
	}

	private static long end(long position, long size) {
		return size > Long.MAX_VALUE - position ? Long.MAX_VALUE : position + size;
	}

	/** The database side of the regions one JVM holds of one file. */
	static final class Backend implements LockBackend {
		private final JdbcFileSourceFactory factory;
		private final Dialect dialect;
		private final long fileid;
		/** Each region held: its row id and the connection that keeps the row locked. */
		private final Map<String, Held> held = new HashMap<>();

		private static final class Held {
			final long id;
			final Connection con;

			Held(long id, Connection con) {
				this.id = id;
				this.con = con;
			}
		}

		Backend(JdbcFileSourceFactory factory, Dialect dialect, long fileid) {
			this.factory = factory;
			this.dialect = dialect;
			this.fileid = fileid;
		}

		private static String key(long position, long size) {
			return position + "+" + size;
		}

		@Override
		public synchronized boolean acquire(long position, long size, boolean shared) throws IOException {
			ensureMutexRow();
			long start = position;
			long end = end(position, size);
			Connection mutex = null;
			Connection holder = null;
			long id = 0;
			boolean inserted = false;
			try {
				mutex = factory.getConnection();
				mutex.setAutoCommit(false);
				if( !takeMutex(mutex) ) {
					mutex.rollback();
					return false; // another process is taking a lock on this file and doesn't let go: busy
				}
				if( conflicts(mutex, start, end, shared) ) {
					mutex.rollback();
					return false;
				}
				// claim the region: a committed row, which this lock's own connection row-locks before
				// the mutex goes, so no other locker can find it unlocked and take it for stale
				id = insertRow(start, end, shared);
				inserted = true;
				holder = factory.openLockConnection();
				holder.setAutoCommit(false);
				lockOwnRow(holder, id);
				mutex.commit(); // lets the mutex go, with any stale rows removed
				held.put(key(position, size), new Held(id, holder));
				return true;
			} catch (SQLException e) {
				undo(mutex, holder, inserted, id);
				throw new IOException("Can't lock file " + fileid + " in the database: " + e.getMessage(), e);
			} catch (IOException | RuntimeException e) {
				undo(mutex, holder, inserted, id);
				throw e;
			} finally {
				if( mutex != null ) {
					quietlyClose(mutex);
				}
			}
		}

		/**
		 * Row-lock the new region's row on its holder's connection. No other locker touches it (they
		 * wait for the mutex), but something passing over the table can have it for a moment (a
		 * tidy-up), so a refusal is tried again for the mutex's short while before it counts.
		 */
		private void lockOwnRow(Connection holder, long id) throws SQLException, IOException {
			long sleep = 1;
			long deadline = System.nanoTime() + MUTEX_WAIT_NANOS;
			while (true) {
				Savepoint sp = dialect == Dialect.POSTGRESQL ? holder.setSavepoint() : null;
				try (PreparedStatement ps = holder.prepareStatement("SELECT id FROM " + RANGES + " WHERE fileid = ? AND id = ? FOR UPDATE NOWAIT")) {
					ps.setLong(1, fileid);
					ps.setLong(2, id);
					try (ResultSet rs = ps.executeQuery()) {
						if( !rs.next() ) {
							throw new SQLException("The lock row " + id + " of file " + fileid + " vanished");
						}
						return;
					}
				} catch (SQLException e) {
					if( !dialect.isBusy(e) || System.nanoTime() - deadline > 0 ) {
						throw e;
					}
					if( sp != null ) {
						holder.rollback(sp);
					}
				}
				try {
					Thread.sleep(sleep);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new java.nio.channels.FileLockInterruptionException();
				}
				sleep = Math.min(sleep * 2, 10);
			}
		}

		/**
		 * Take the file's mutex without waiting on it: {@code NOWAIT}, tried again for a short while,
		 * since another locker holds it only for the moment it takes to look and claim. A tryLock must
		 * not wait, so a mutex that stays taken (its holder stalled, or cut off and not yet noticed by
		 * the database) means busy.
		 * @return false if it stayed taken
		 */
		private boolean takeMutex(Connection mutex) throws SQLException, IOException {
			long sleep = 1;
			long deadline = System.nanoTime() + MUTEX_WAIT_NANOS;
			while (true) {
				Savepoint sp = dialect == Dialect.POSTGRESQL ? mutex.setSavepoint() : null;
				try (PreparedStatement ps = mutex.prepareStatement("SELECT fileid FROM " + TABLE + " WHERE fileid = ? FOR UPDATE NOWAIT")) {
					ps.setLong(1, fileid);
					try (ResultSet rs = ps.executeQuery()) {
						if( !rs.next() ) {
							throw new FileNotFoundException("The file " + fileid + " was deleted while it was being locked");
						}
						return true;
					}
				} catch (SQLException e) {
					if( !dialect.isBusy(e) ) {
						throw e;
					}
					if( sp != null ) {
						mutex.rollback(sp); // PostgreSQL aborts the transaction on an error otherwise
					}
				}
				if( System.nanoTime() - deadline > 0 ) {
					return false;
				}
				try {
					Thread.sleep(sleep);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new java.nio.channels.FileLockInterruptionException();
				}
				sleep = Math.min(sleep * 2, 10);
			}
		}

		/**
		 * Whether a live holder has a region that overlaps [start, end) and can't share it. Stale
		 * claims (a row that can be row-locked has no live holder) are deleted on the way, in the
		 * mutex's transaction. Called with the mutex held.
		 */
		private boolean conflicts(Connection mutex, long start, long end, boolean shared) throws SQLException {
			List<long[]> overlapping = new ArrayList<>();
			try (PreparedStatement ps = mutex.prepareStatement("SELECT id, shared FROM " + RANGES
					+ " WHERE fileid = ? AND start_pos < ? AND ? < end_pos")) {
				ps.setLong(1, fileid);
				ps.setLong(2, end);
				ps.setLong(3, start);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						overlapping.add(new long[] {rs.getLong(1), rs.getBoolean(2) ? 1 : 0});
					}
				}
			}
			for (long[] row : overlapping) {
				boolean live;
				Savepoint sp = dialect == Dialect.POSTGRESQL ? mutex.setSavepoint() : null;
				try (PreparedStatement ps = mutex.prepareStatement("SELECT id FROM " + RANGES + " WHERE fileid = ? AND id = ? FOR UPDATE NOWAIT")) {
					ps.setLong(1, fileid);
					ps.setLong(2, row[0]);
					try (ResultSet rs = ps.executeQuery()) {
						live = false; // locked it ourselves: nobody holds it
						if( rs.next() ) {
							try (PreparedStatement del = mutex.prepareStatement("DELETE FROM " + RANGES + " WHERE fileid = ? AND id = ?")) {
								del.setLong(1, fileid);
								del.setLong(2, row[0]);
								del.executeUpdate();
							}
						}
					}
				} catch (SQLException e) {
					if( !dialect.isBusy(e) ) {
						throw e;
					}
					if( sp != null ) {
						mutex.rollback(sp); // PostgreSQL aborts the transaction on an error otherwise
					}
					live = true;
				}
				if( live && !(shared && row[1] == 1) ) {
					return true;
				}
			}
			return false;
		}

		private long insertRow(long start, long end, boolean shared) throws SQLException, IOException {
			try (Connection c = factory.getConnection()) {
				c.setAutoCommit(true);
				for (int attempt = 0;; attempt++) {
					long id = IDS.nextLong() & Long.MAX_VALUE;
					try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + RANGES
							+ " (fileid, id, start_pos, end_pos, shared) VALUES (?, ?, ?, ?, ?)")) {
						ps.setLong(1, fileid);
						ps.setLong(2, id);
						ps.setLong(3, start);
						ps.setLong(4, end);
						ps.setBoolean(5, shared);
						ps.executeUpdate();
						return id;
					} catch (SQLException e) {
						if( attempt > 3 || e.getSQLState() == null || !e.getSQLState().startsWith("23") ) {
							throw e;
						}
						// the same random id twice: draw another
					}
				}
			}
		}

		private void undo(Connection mutex, Connection holder, boolean inserted, long id) {
			if( holder != null ) {
				try {
					holder.rollback();
				} catch (SQLException e) {
					LOG.log(Level.FINE, "Rolling back a lock connection", e);
				}
				quietlyClose(holder);
			}
			if( inserted ) {
				deleteRow(id);
			}
			if( mutex != null ) {
				try {
					mutex.rollback();
				} catch (SQLException e) {
					LOG.log(Level.FINE, "Rolling back the lock mutex", e);
				}
			}
		}

		private void deleteRow(long id) {
			try (Connection c = factory.getConnection();
					PreparedStatement ps = c.prepareStatement("DELETE FROM " + RANGES + " WHERE fileid = ? AND id = ?")) {
				ps.setLong(1, fileid);
				ps.setLong(2, id);
				ps.executeUpdate();
				if( !c.getAutoCommit() ) {
					c.commit();
				}
			} catch (SQLException | IOException e) {
				// left as a stale claim: the next locker removes it
				LOG.log(Level.FINE, "Removing lock row " + id, e);
			}
		}

		/** Make sure the file has a mutex row: insert it, or find it is there already. */
		private void ensureMutexRow() throws IOException {
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
		public synchronized void release(long position, long size) {
			Held h = held.remove(key(position, size));
			if( h == null ) {
				return;
			}
			try {
				// the holder's own connection still has the row locked: delete it and end the transaction
				try (PreparedStatement ps = h.con.prepareStatement("DELETE FROM " + RANGES + " WHERE fileid = ? AND id = ?")) {
					ps.setLong(1, fileid);
					ps.setLong(2, h.id);
					ps.executeUpdate();
				}
				h.con.commit();
				// its transaction is over and it holds nothing: the next lock may use it
				factory.reuseLockConnection(h.con);
				return;
			} catch (SQLException e) {
				// the row stays as a stale claim (its row lock ends with the connection), removed by the next locker
				LOG.log(Level.FINE, "Removing lock row " + h.id + " of file " + fileid, e);
				try {
					h.con.rollback();
				} catch (SQLException e2) {
					LOG.log(Level.FINE, "Rolling back a lock connection", e2);
				}
			}
			quietlyClose(h.con);
		}

		@Override
		public synchronized void close() {
			for (String k : new ArrayList<>(held.keySet())) {
				String[] p = k.split("\\+");
				release(Long.parseLong(p[0]), Long.parseLong(p[1]));
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
			return "database locks on file " + fileid;
		}
	}
}
