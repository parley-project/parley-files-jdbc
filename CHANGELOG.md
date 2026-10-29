# Changelog

## parley-files-jdbc 2.0.0 (unreleased)

### Changed (needs a configuration change)

- **Property names** (Parley 2.0.0): the connection settings `jdbcURL`, `jdbcUserid` and `Name` are `jdbcUrl`,
  `jdbcUser` and `name` (saved connections need the new names); `parley.jdbc.bufferSize` is
  `parley.files.jdbc.bufferSize`, and `JdbcFile.dbid` is `parley.files.jdbc.dbid`.

### Changed (needs a code change)

- **A connection from `JdbcConnectionPool.getConnection()` is a handle of its own for each checkout** (a package
  private `PooledConnection`), no longer the pooled `ManagedJdbcConnection`. Code that cast the connection to
  `ManagedJdbcConnection` must stop; nothing else in the library did. After `close()` the handle is dead:
  `isClosed()` is `true`, every other call throws `SQLException`, and a second `close()` does nothing. Before, a
  connection kept past `close()` kept working, and a late `close()` could free a connection that another caller
  had borrowed since.

### Added

- **`jdbcAccessTime`**, a connection property: `false` is like a file system mounted `noatime`, so opening a file for
  reading does not write its access time (one statement and one commit each). Writes and `setLastAccessTime()` still
  set it. The default, `true`, changes nothing. See the README. B1 (500 messages of 5 KB into a MySQL 8.4 mail store):
  17.4 messages per second without the setting and 17.6 with it left at `true`, 18.3 with `false`; 231 statements per
  message, and 217 with `false` (the 5 access-time updates, and the lookups each one made).
- **A choice of connection pool**: the connection property `jdbcPool` is `parley` (the library's own pool, the
  default), `hikari` (HikariCP) or `dbcp2` (Apache Commons DBCP2). HikariCP and DBCP2 are optional
  dependencies: add the one you choose to your project (asking for one that is missing fails with a message
  that names the library). All three are sized by the `ObjectPool` defaults. See the README.
- `ConnectionPool`, the interface the file system uses (`getConnection`, `isRunning`, `awaitStart`,
  `destroyAll`, `close`), implemented by `JdbcConnectionPool`, `HikariConnectionPool` and
  `Dbcp2ConnectionPool`; and `ConnectionPools.getConnectionPool(type, url, user, password)` and
  `removeConnectionPool(...)`, which share one pool for each kind, URL and login.
- `ObjectPool.setValidateAfter` (default 500 ms; -1 never) and `setCreateTimeout` (default 30 s; 0 no limit),
  with `setDefaultValidateAfter` and `setDefaultCreateTimeout`; and `ObjectPool.validateObject(Object)`, which
  `JdbcConnectionPool` implements with `Connection.isValid`.
- `pool-benchmark/`, a JMH benchmark and a database-restart check that compare the library's pool with
  HikariCP and DBCP2, and `JdbcFileSystemPerfTest`, a file system workload over a chosen pool
  (`-Dparley.perf=true -Dparley.perf.pool=...`; skipped otherwise).

### Changed (no code change needed)

- **Links are kept in `file_source.file_link`, and `file.link_count` counts the hard links that point at a row.**
  Making a link writes its row, its entry, the owner's count and the folder's modified time in one transaction;
  deleting one, or deleting a file whose data a hard link takes over, keeps the entries and counts right. Both are made on
  connect when missing (unless `jdbcUpgradeSchema` is `false`; the README has the statements). Links made by an earlier
  version are not converted. A link row has no data of its own. Whether a path is a link now comes with the row that is
  read anyway, so there is no database-wide check for links any more: before, one link anywhere slowed every operation
  (B1: 11.7 messages per second with one link in the database, 17 without; now 17 either way), a delete scanned every
  hard link in the database, and a delete of a file whose data a hard link takes over was not atomic.
- **A create or rename sets the folders' modified times in the same transaction**, as `delete()` already did, instead of
  one commit each. B1 on a MySQL 8.4 server: 15.6 to 17.0 messages per second for 500 messages of 5 KB.
- **`file_data`'s primary key is changed to `(fileid, chunk_number)` on connect** in a database made with
  `(chunk_number, fileid)` (unless `jdbcUpgradeSchema` is `false`; the README has the statements to run by hand).
  New schemas already had the new order. With the old one every statement on a file's data scanned the whole table.
  A large store waits once while the key is rebuilt.
- **A connection that sat idle is checked before it is handed out again** (500 ms or more; `isValid`), and one
  that fails is closed and replaced. After a database restart or a firewall cutting idle connections the pool
  recovers at once; it used to hand out the dead connections until they reached their longest life.
- A connection whose release-time reset fails (a rollback, or restoring auto-commit, isolation, read-only,
  catalog or schema) is closed instead of returned to the pool. The catalog and the schema are now put back too.
- **Opening a connection is limited** to 30 seconds (`setCreateTimeout`). One that finishes after the limit is
  closed, and the caller and the slot it held are free; before, a hung connect held both for good.
- Connections are closed with no lock held (`destroyAll`, the pool's thread, cleanup in `getObject`), so a
  `close()` that hangs on a dead socket no longer stalls every borrower and every release. The static pool
  registry is a `ConcurrentHashMap` with no class lock: threads that ask for the same pool at once get one pool and
  one thread, and `removeConnectionPool` no longer holds a lock while it closes connections.
- A statement made before a `commit()` and run after it marks a transaction open, so the release rolls it back.
- `Statement.getConnection()` and `DatabaseMetaData.getConnection()` answer with the borrower's handle, not the
  driver's connection, which a caller could close behind the pool's back. Statements kept past `close()` answer
  with their own, dead, handle. The overloads that returned unwrapped driver statements (`createStatement` with
  three arguments, four forms of `prepareStatement`, `prepareCall` with four) go through the same wrapper.
- The pool logs through `System.Logger` instead of `System.out` and `System.err`. `getConnection()` failures keep
  their cause (`SQLException.getCause()`).
- `JdbcConnectionPool.setPools` wraps a map that is not concurrent.

### Fixed

- A connection that was borrowed but not yet used could be closed by the pool's thread at its next pass as
  "in use and idle too long" (its last-access time was zero, or the time of its previous release). The idle time
  now counts from the checkout.
- The proxy around a driver statement looped forever when the driver's statement class had no direct interfaces,
  and could fail with a `ClassCastException` when it declared only a vendor interface. It now implements every
  interface of the class and its superclasses.
- `removeConnectionPool` closed the connections but left the pool's thread running; the thread now ends.
- A connection opened but not wrapped (its first statements failed) was never closed.
- The pool-exhausted message read "Pool size(40 is > max(40)".

### Deprecated

- The static `JdbcConnectionPool.getConnectionPool(...)` and `removeConnectionPool(...)` methods: use
  `ConnectionPools`, which also chooses the kind of pool. They still work.

## parley-files-jdbc 1.1.0 (unreleased)

Needs parley-files 1.1.0.

### Added

- File locks, regions included (`lock()`, `tryLock()`, see parley-files). On PostgreSQL 10+, MySQL 8+ and
  MariaDB 10.3+ the database keeps them (`getLockScope()` is `CLUSTER`): each region held is a row in
  the new `file_source.file_lock_range` table, kept row-locked on a connection of its own until the lock
  is released, and taken under a short mutex on the file's row in `file_source.file_lock`. The holder
  works on the file as usual, the lock stays with the file through a rename, and a dead holder's regions
  are free again (its row is no longer locked, and the next locker removes it). Other databases (HSQLDB)
  lock within this JVM (`PROCESS`).
- The `file_source.file_lock` and `file_source.file_lock_range` tables, in every `.ddl` file and created
  on connect when missing (unless `jdbcUpgradeSchema` is `false`). Rows of deleted files are removed on
  connect.

### Changed (no code change needed)

- The output stream is `Syncable` (parley-files): `sync()` writes the chunk it holds, committed like every
  write, so `FileChannel.force()` no longer returns with data still in the buffer.
- `createNewFile()` and `mkdir()` return `false` to a caller that loses a race for the same path (the
  database's unique key let another insert in first), as FileSource's contract requires; it was an
  `IOException`.

## parley-files-jdbc 1.0.0 (2026-10-16)

BjlFileSystemJdbc (`us.bringardner:bjl_file_system_jdbc` 1.0.0-SNAPSHOT) is now **parley-files-jdbc**,
part of the Parley library family. The code is the same; only names changed.

### Changed (needs a code change)

- Maven coordinates: `us.bringardner:bjl_file_system_jdbc` is now `us.bringardner.parley:parley-files-jdbc`.
- Packages: `us.bringardner.io.filesource.jdbcfile` is now `us.bringardner.parley.files.jdbcfile`;
  the connection pool `us.bringardner.database.pool` is now `us.bringardner.parley.files.jdbcfile.pool`.
  The package keeps the name `jdbcfile` so the `jdbcfile:` URL handler keeps its protocol name.
- `ServiceLoader` registration: `META-INF/services/us.bringardner.parley.files.FileSourceFactory`.
- Module name (`Automatic-Module-Name`): `us.bringardner.parley.files.jdbcfile`.
- Dependencies: `bjl_core`, `bjl_io` and `bjl_file_system` are now `parley-core`, `parley-io` and `parley-files`.
- The factory describes its settings with `getConnectionSettings()` (see parley-files) instead of
  `getEditPropertiesComponent()`.
- The module no longer uses Swing. `gui.DriverBrowserDialog`, an empty dialog that nothing used,
  is removed.
- `listFiles(ProgressMonitor)` is now `listFiles(FileSourceProgress)`.
- `JdbcFileSourceFactory`'s path constants are spelled correctly: `separatorChar`, `separator`,
  `dosSeparatorChar`, `pathSeparator` and `pathSeparatorChar` (were `seperatorChar`, `seperator`,
  `dosSeperatorChar`, `pathSeperator` and `pathSSeperatorChar`), and the factory implements parley-files'
  `getSeparatorChar()` and `getPathSeparatorChar()`.
- `JdbcFileOutputStream.chunkSize`, a static field with no effect, is removed: a stream's buffer is the
  factory's `getBufferSize()`, and its row size the factory's chunk size or the stream's `StreamOption.CHUNK_SIZE`.
- `pool.TestPool`, an interactive program for trying the connection pool by hand, is no longer in the library.

### Unchanged

- The factory type id (`Jdbc`), the `jdbc*` connection properties and the database schema.

### Added

- **Links.** `createSymbolicLink` and `createLink` work, with no change to the schema: a symbolic link is a
  row of type `link` whose content (chunk 1) is the path it points to, and a hard link a row of type `hard`
  whose content is the file id of the row that holds the data (so it follows a rename, and takes the data
  over when that row is deleted). A path answers for what it leads to (type, content, length, listing,
  permissions, streams), also below a linked directory; `getLinkedTo()` and `getCanonicalPath()` follow a
  symbolic link; deleting or renaming a link moves only the link; writing through one that leads nowhere
  makes the target. A database with no links pays nothing extra, and a link made by another client is seen
  within a couple of seconds.
- `bufferSize` (also `parley.jdbc.bufferSize`) and per stream sizes: a stream can be opened with its own
  `StreamOption.CHUNK_SIZE`, which is how it cuts what it writes into rows; data already stored is read as
  it was written.
- A FileSource that was kept sees a change made through any other FileSource of the same factory at once
  (see parley-files' `getChangeCount()`). A handle's row id expires after the field time to live (500 ms
  unless set), so a file another connection deleted or renamed is seen as gone; it was kept for good.
  `setFieldTimeToLive(name, ms)` now takes effect (a `FILE_TYPE` time of 100 seconds that never did is
  removed).
- `JdbcFileInputStream` reads and skips a row at a time (`read(byte[], int, int)`, `skip`, `available`).

### Changed (no code change needed)

- The seekable input stream is read-only and returns bytes as 0..255. A seek past the end used to grow the
  file with zeros, `read()` returned -1 for the byte `0xFF` (end of file to a reader), and
  `read(buf, off, len)` read from `pointer + off`. The random access stream had the same sign problem.
- In the way of `java.io.File`: `listFiles(filter)` and `list(filter)` accept null and are null for what
  isn't a directory; opening an output stream below a missing directory or a file throws
  `FileNotFoundException`; `mkdirs`, `mkdir` below a file, `createNewFile`, `delete` of a directory that
  isn't empty, `renameTo` (itself, a missing directory), the permission setters (false for a missing path;
  `setReadOnly` clears write bits) and a directory's modified time, which changes when a child is added,
  deleted or renamed.
- `getContentType()` is the type for the extension, or null; it was an empty string.
- A link that leads nowhere keeps its own name as its canonical path.
- The connection pool wakes waiters when a connection is released instead of polling.
