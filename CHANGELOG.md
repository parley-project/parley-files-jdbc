# Changelog

## parley-files-jdbc 1.0.0 (unreleased)

BjlFileSystemJdbc (`us.bringardner:bjl_file_system_jdbc` 1.0.0-SNAPSHOT) is now **parley-files-jdbc**,
part of the Parley library family. The code is the same; only names changed.

### Changed (needs a code change)

- Maven coordinates: `us.bringardner:bjl_file_system_jdbc` is now `us.bringardner.parley:parley-files-jdbc`.
- Packages: `us.bringardner.io.filesource.jdbcfile` (and `.gui`) is now `us.bringardner.parley.files.jdbcfile`;
  the connection pool `us.bringardner.database.pool` is now `us.bringardner.parley.files.jdbcfile.pool`.
  The package keeps the name `jdbcfile` so the `jdbcfile:` URL handler keeps its protocol name.
- `ServiceLoader` registration: `META-INF/services/us.bringardner.parley.files.FileSourceFactory`.
- Module name (`Automatic-Module-Name`): `us.bringardner.parley.files.jdbcfile`.
- Dependencies: `bjl_core`, `bjl_io` and `bjl_file_system` are now `parley-core`, `parley-io` and `parley-files`.
- The factory describes its settings with `getConnectionSettings()` (see parley-files) instead of
  `getEditPropertiesComponent()`; outside the `gui` package the module no longer uses Swing.
- `listFiles(ProgressMonitor)` is now `listFiles(FileSourceProgress)`.

### Unchanged

- The factory type id (`Jdbc`), the `jdbc*` connection properties and the database schema.
