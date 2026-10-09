# Changelog

## parley-files-jdbc 1.0.0 (unreleased)

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
