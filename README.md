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
 

## Connection pool

The file system takes its connections from a pool. The connection property `jdbcPool` chooses which:

| `jdbcPool` | Pool | Needs on the classpath |
|---|---|---|
| `parley` (default) | this library's own | nothing |
| `hikari` | [HikariCP](https://github.com/brettwooldridge/HikariCP) | `com.zaxxer:HikariCP` |
| `dbcp2` | [Apache Commons DBCP2](https://commons.apache.org/proper/commons-dbcp/) | `org.apache.commons:commons-dbcp2` |

### Configuring a pool

Set `jdbcPool` with the other connection properties (it is an advanced setting in a connection dialog):

```java
JdbcFileSourceFactory factory = (JdbcFileSourceFactory) FileSourceFactory.getFileSourceFactory("Jdbc");
Properties p = factory.getConnectProperties();
p.setProperty("jdbcUrl", "jdbc:postgresql://db.example.com/files");
p.setProperty("jdbcUser", "files");
p.setProperty("jdbcPassword", "...");
p.setProperty("jdbcPool", "hikari");        // parley (the default), hikari or dbcp2
factory.connect(p);
```

HikariCP and DBCP2 are optional dependencies, so add the one you choose to your project (these are the
versions the tests use):

```xml
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
<!-- or -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-dbcp2</artifactId>
    <version>2.12.0</version>
</dependency>
```

Asking for a pool whose library is missing fails with a message that names the library.

Sizes and time limits are the same for all three pools and come from the `ObjectPool` defaults, read when the
pool is first made. Set them before `connect()` and after the factory class is loaded (its static initializer
sets the maximum to 100):

| `ObjectPool` default | Value | Used for |
|---|---|---|
| `setDefaultMax` | 100 | most connections open at once |
| `setDefaultMin` | 1 | connections kept when idle |
| `setDefaultTimeToWait` | 5 s | how long a caller waits for a connection when all are in use |
| `setDefaultMaxTTL` | 1 hour | longest a connection lives |
| `setDefaultExpire` | 5 min | idle time after which a spare connection is closed |

A pool is shared by everyone who asks for the same kind, URL and login. Code that uses a pool directly gets
one from `ConnectionPools.getConnectionPool(type, url, user, password)`, which answers a `ConnectionPool`
(`getConnection`, `destroyAll`, `close`). The static methods on `JdbcConnectionPool` still work and are
deprecated.

### How the parley pool compares

All three pools check a connection that sat idle before handing it out again, and replace one that has gone
bad. After the database server is stopped for a second and started again, a warm pool of 5 made one request
every 50 ms for 15 seconds: none of the three failed a request. (The library's own pool used to hand out the
dead connections for as long as they lived.) The library's own pool also gives up on a connect that takes
longer than 30 seconds, so one hung connect does not hold a caller and its slot for good; For HikariCP and DBCP2
see their own documentation on connect timeouts.

**The file system.** `JdbcFileSystemPerfTest` creates, stats, reads, lists, appends to and deletes small files
through the factory, from 1 and from 8 threads, against an in-memory HSQLDB. Operations per second over the
whole workload (higher is better; HikariCP and DBCP2 ran twice, in either order):

| Threads | parley | HikariCP | DBCP2 |
|---|---|---|---|
| 1 | 764 | 670, 767 | 357, 362 |
| 8 | 1932 | 1905, 1967 | 1144, 1178 |

On this workload the library's own pool is as fast as HikariCP, and both are about 1.7 to 2.1 times faster
than DBCP2.

**The pool alone.** `pool-benchmark/` (JMH, an in-memory H2) measures only the pool: millions of operations per
second, pool of 50 connections (higher is better):

| | parley | HikariCP | DBCP2 |
|---|---|---|---|
| borrow and return, 1 thread | 6.1 | 7.9 | 1.9 |
| borrow and return, 8 threads | 2.8 | 50.2 | 1.8 |
| borrow, `SELECT 1`, return, 1 thread | 1.07 | 1.47 | 0.40 |
| borrow, `SELECT 1`, return, 8 threads | 2.28 | 4.44 | 0.67 |
| same, 99th percentile latency, 8 threads | 30 µs | 4 µs | 55 µs |

With one thread the library's own pool is about three quarters of HikariCP's speed. It does not scale with
threads: every borrow and return takes one lock, so with 8 or more threads and nothing else to do it is far
behind HikariCP, and its 99th percentile latency under contention is several times worse. Once each borrow
has a query to run (the last rows) the gap is about a factor of two, and in the file system, where every
operation does real database work, it is not measurable. DBCP2 was the slowest throughout.

**A mail server on MySQL.** The B1 test sends 500 messages of 5 KB through the SMTP server into a JDBC mail
store on MySQL 8.4 and reads them back over IMAP, on a 2-vCPU server that also ran MySQL and the client. Messages
per second until all are in the mailbox (higher is better; three runs of each pool, the order rotated):

| SMTP connections | parley | HikariCP | DBCP2 |
|---|---|---|---|
| 1 | 17.3, 17.7, 17.5 | 18.4, 17.6, 17.4 | 15.7, 15.5, 15.6 |
| 8 | 15.7, 15.8, 15.9 | 16.2, 17.0, 16.2 | 14.7, 14.2, 14.4 |

With one connection the library's own pool and HikariCP are level, within the spread of their runs. With 8,
HikariCP is about 4% faster and every one of its runs beat every run of the library's pool. DBCP2 is about 10%
slower in both, and sends 19 more statements per message (250, against 231). A statement takes 0.1 to 2 ms
and a pool operation well under a microsecond, so the one lock that holds the library's own pool back in
the pool-alone test costs little here: delivery into the mailbox is the limit.

The first two tables are single runs on one machine against in-memory databases. A database reached over a
network makes every operation slower, so the pool matters less. To measure your own setup, run the file system test with
`mvn test -Dtest=JdbcFileSystemPerfTest -Dparley.perf=true -Dparley.perf.pool=hikari` (or `dbcp2`, `parley`),
and see `pool-benchmark/README.md` for the pool benchmark.

**Which to use.** The library's own pool is the default: it needs no dependency, it does what the file system
needs, and on the mail server test it was level with HikariCP for one connection and about 4% behind with 8.
Choose HikariCP if many threads borrow connections at once, or if you already run it and want one pool to
configure. DBCP2 works but was the slowest in every test, about 10% behind on the mail server test.

## Users and permissions

A JDBC file system's current user is the database user (`jdbcUser`); every file it
creates is owned by that user, so the owner permissions apply to it. Its group is the
`jdbcGroup` connection property, default `staff` (the schema's default group for new
files); set it empty for no group. Other database users get the group or other
permissions stored with each file.

## Links

Symbolic links and hard links work. A link is a row of its own in `file_source.file`, and where it leads is
kept in `file_source.file_link`:

| `file_type` | What the row is | Its entry in `file_link` |
|---|---|---|
| `link` | A symbolic link, as on POSIX. The target needn't exist. | `target_path`: the path it points to |
| `hard` | One more name for a file, so it follows a rename of the file. | `target_id`: the `fileid` of the row that holds the data |

A link row has no data of its own. `file.link_count` counts the hard links that point at a row, so deleting a file
that none points at needs no extra lookup. When a file that hard links point at is deleted, the first of them
(the lowest `link_id`) takes the data and attributes over and the others are repointed, in one transaction; the
other names of a file are found through the index on `file_link (target_id)`.

A path answers for what it leads to: type, length, content, listing, permissions and streams, also below a
linked directory. A write through a link changes the file it leads to (the link stays a link).
`getLinkedTo()` and `getCanonicalPath()` follow a symbolic link; `delete()` and `renameTo()` act on the
link. A link made by another client is seen within a couple of seconds.

Whether a path is a link comes with the row that is read for it, so a path that is not a link costs nothing
extra, and a database with links is as fast as one without (B1: 17 messages per second with one link in the
database, as without). See [Links](https://github.com/parley-project/parley-files#links) in parley-files.

Code or tools that read the tables directly should know that a `link` or `hard` row is not a file with its
own data. Links made by an earlier version, which kept their target in the first chunk of the row's data, are
not converted: delete them and make them again.

## Locking

In short, for `JdbcFileSourceFactory`:

| | |
|---|---|
| Locks | `CLUSTER` on PostgreSQL 10+, MySQL 8+, MariaDB 10.3+ (every machine using the database); `PROCESS` (this JVM) on HSQLDB and others. Range locks work. |
| Atomic create | `createNewFile()` and `mkdir()` are atomic (the unique key on parentid, name) |
| Capabilities (`getCapabilities()`) | lock scope as above; atomic create and mkdir; replace (`renameTo(dest, true)`) not atomic: delete, then rename; durable sync (each write is committed); operations in one order |
| FileChannel | through the file's streams and random access |
| `map()` | `READ_ONLY` and `PRIVATE` map a local copy of the region, read when it is mapped; `READ_WRITE` throws `UnsupportedOperationException` |
| `force()` | writes the chunk a stream holds; each write is committed |


Files can be locked as parley-files describes (`lock()`, `tryLock()`, shaped like NIO's `FileChannel.lock()`).
Where the database can, it keeps the locks, so they reach every process on every machine that uses it
(`getLockScope()` is `CLUSTER`):

| Database | Locks |
|---|---|
| PostgreSQL 10+ | in the database (`CLUSTER`) |
| MySQL 8+ | in the database (`CLUSTER`) |
| MariaDB 10.3+ | in the database (`CLUSTER`) |
| HSQLDB, others | this JVM only (`PROCESS`): they can't refuse a row lock without waiting |

A lock covers a region of a file (the whole file unless a region is asked for). Each region held is a row in
`file_source.file_lock_range` (the file's `fileid`, the region, shared or not), and the holder keeps that row
locked (`SELECT ... FOR UPDATE`) in a transaction open, on a connection of its own, until the lock is released:
the row says what is held, its row lock says the holder is alive. A lock is taken under a short mutex, a row lock
on the file's row in `file_source.file_lock`, taken without waiting (`NOWAIT`, retried for up to 200 ms, set with
the system property `parley.files.jdbc.lockMutexWait`; a mutex that stays taken means busy, since `tryLock` must not
wait): the locker checks the regions that overlap its own, removes stale
ones (rows nobody keeps locked: their holder died), and either finds a live one it can't share and gives up, or
adds its row, locks it, and lets the mutex go. Nothing else uses these tables, so the holder reads, writes and
renames the file as usual while it holds the lock; the file's own rows are never locked. The lock is on the file,
not its name, so it stays with the file through a rename. If the holder dies, its connection drops and the
database frees its row lock, so its region is free.

Each lock holds one database connection, opened for it (not taken from the pool, which closes connections that
are idle for long). A database that closes idle connections (MySQL's `wait_timeout`, 8 hours by default) frees a
lock held longer than that. Rows of deleted files are removed when a factory connects, except rows still locked. Each lock held keeps one
connection open, so many locks held at once need a database that allows as many connections.

## Upgrading an existing database

The lock tables are new in 1.1.0. They are created when the factory connects, unless `jdbcUpgradeSchema` is
`false`; to create them yourself (the `.ddl` files have them too):

```sql
CREATE TABLE file_source.file_lock (FILEID BIGINT NOT NULL PRIMARY KEY);
CREATE TABLE file_source.file_lock_range (FILEID BIGINT NOT NULL, ID BIGINT NOT NULL, START_POS BIGINT NOT NULL,
    END_POS BIGINT NOT NULL, SHARED BOOLEAN NOT NULL, PRIMARY KEY (FILEID, ID));
```

Without them, locks reach only this JVM (a warning is logged).

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

The primary key of `file_source.file_data` is `(fileid, chunk_number)`. A table made with the older
`(chunk_number, fileid)` makes every read, append and delete of a file's data scan the whole table, and the
cost grows with the store. The key is changed when the factory connects (the same `jdbcUpgradeSchema`
setting applies, and the change is logged). Rebuilding the key reads the whole table, so a large store waits
once while it runs. To do it yourself:

| Database | Statements |
|---|---|
| HSQLDB, SQL:2003 | `ALTER TABLE file_source.file_data DROP PRIMARY KEY;`<br>`ALTER TABLE file_source.file_data ADD PRIMARY KEY (fileid, chunk_number);` |
| PostgreSQL | `ALTER TABLE file_source.file_data DROP CONSTRAINT "file_data_pkey", ADD PRIMARY KEY (fileid, chunk_number);` |
| MySQL | `ALTER TABLE file_source.file_data DROP PRIMARY KEY, ADD PRIMARY KEY (fileid, chunk_number);` |

The link column and table (see Links) are made when the
factory connects (the same `jdbcUpgradeSchema` setting applies, and it is logged), and new schemas made from the
`.ddl` files have them. A database that already holds links made by an earlier version is not converted: delete
those links and make them again. To make them yourself, on any of the databases:

```sql
ALTER TABLE file_source.file ADD COLUMN link_count INT DEFAULT 0 NOT NULL;
CREATE TABLE file_source.file_link (LINK_ID BIGINT NOT NULL PRIMARY KEY, TARGET_ID BIGINT, TARGET_PATH VARCHAR(4096));
CREATE INDEX file_link_target_idx ON file_source.file_link (target_id);
```
