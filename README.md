# parley-files-jdbc

A [parley-files](https://github.com/parley-project/parley-files) `FileSource` implementation that
keeps a file system in a database, via JDBC. Part of **Parley**, a family of Java libraries for
implementing internet protocols. Put it on the class path and `FileSourceFactory` finds it through
`ServiceLoader` (`JdbcFileSourceFactory`, type id `Jdbc`).

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-files-jdbc</artifactId>
    <version>1.0.0</version>
</dependency>
```

> parley-files-jdbc was previously `us.bringardner:bjl_file_system_jdbc` (BjlFileSystemJdbc).
> `us.bringardner.io.filesource.jdbcfile` is now `us.bringardner.parley.files.jdbcfile`, and the
> connection pool `us.bringardner.database.pool` is now `us.bringardner.parley.files.jdbcfile.pool`.
> The `jdbc*` connection properties and the database schema are unchanged.
 

## Users and permissions

A JDBC file system's current user is the database user (`jdbcUserid`); every file it
creates is owned by that user, so the owner permissions apply to it. Its group is the
`jdbcGroup` connection property, default `staff` (the schema's default group for new
files); set it empty for no group. Other database users get the group or other
permissions stored with each file.

## Links

Symbolic links and hard links work, and **the schema is unchanged**; two more values are used in
`file_source.file.file_type` (the others are `file` and `dir`):

| `file_type` | What the row is |
|---|---|
| `link` | A symbolic link. Its content (chunk 1 in `file_data`) is the path it points to, as on POSIX. The target needn't exist. |
| `hard` | One more name for a file. Its content (chunk 1) is the `fileid` of the row that holds the data, so it follows a rename. When that row is deleted, the first hard link takes the data and attributes over. |

A path answers for what it leads to: type, length, content, listing, permissions and streams, also below a
linked directory. A write through a link changes the file it leads to (the link stays a link).
`getLinkedTo()` and `getCanonicalPath()` follow a symbolic link; `delete()` and `renameTo()` act on the
link. A link made by another client is seen within a couple of seconds, and a
database that has no links pays nothing extra. See
[Links](https://github.com/parley-project/parley-files#links) in parley-files.

Code or tools that read the tables directly should know that a `link` or `hard` row is not a file with its
own data.

## Locking

Files can be locked as parley-files describes (`lock()`, `tryLock()`, shaped like NIO's `FileChannel.lock()`).
Where the database can, it keeps the locks, so they reach every process on every machine that uses it
(`getLockScope()` is `CLUSTER`):

| Database | Locks |
|---|---|
| PostgreSQL 10+ | in the database (`CLUSTER`) |
| MySQL 8+ | in the database (`CLUSTER`) |
| MariaDB 10.3+ | in the database (`CLUSTER`) |
| HSQLDB, others | this JVM only (`PROCESS`): they can't refuse a row lock without waiting |

Each locked file has a row in `file_source.file_lock`, keyed by its `fileid`. A lock is a row lock on that row
(`SELECT ... FOR UPDATE NOWAIT`, or `FOR SHARE NOWAIT` for a shared lock) in a transaction held open, on a
connection of its own, until the lock is released. Nothing else uses that table, so the holder reads, writes and
renames the file as usual while it holds the lock; the file's own rows are never locked. The lock is on the file,
not its name, so it stays with the file through a rename. If the holder dies, its connection drops and the
database frees the lock.

Each lock holds one database connection, opened for it (not taken from the pool, which closes connections that
are idle for long). A database that closes idle connections (MySQL's `wait_timeout`, 8 hours by default) frees a
lock held longer than that. Rows of deleted files are removed when a factory connects, except rows still locked.

## Upgrading an existing database

The lock table is new in 1.1.0. It is created when the factory connects, unless `jdbcUpgradeSchema` is `false`;
to create it yourself (the `.ddl` files have it too):

```sql
CREATE TABLE file_source.file_lock (FILEID BIGINT NOT NULL PRIMARY KEY);
```

Without it, locks reach only this JVM (a warning is logged).

The `owner` and `group_name` columns were `VARCHAR(10)`, too short for many user ids.
New schemas (the `.ddl` files in `src/main/resources/`) use `VARCHAR(128)`. An existing schema is
widened automatically when the factory connects (the result is logged). To leave the
schema alone, set the connection property `jdbcUpgradeSchema` to `false`; if the database
user may not alter the table, or you'd rather do it yourself, run:

| Database | Statements |
|---|---|
| HSQLDB, SQL:2003 | `ALTER TABLE file_source.file ALTER COLUMN owner SET DATA TYPE VARCHAR(128);`<br>`ALTER TABLE file_source.file ALTER COLUMN group_name SET DATA TYPE VARCHAR(128);` |
| PostgreSQL | `ALTER TABLE file_source.file ALTER COLUMN owner TYPE VARCHAR(128);`<br>`ALTER TABLE file_source.file ALTER COLUMN group_name TYPE VARCHAR(128);` |
| MySQL | `ALTER TABLE file_source.file MODIFY owner VARCHAR(128) NOT NULL;`<br>`ALTER TABLE file_source.file MODIFY group_name VARCHAR(128) DEFAULT 'staff';` |
