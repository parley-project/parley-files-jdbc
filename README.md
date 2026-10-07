# parley-files-jdbc

A [parley-files](https://github.com/tony-bringardner/parley-files) `FileSource` implementation that
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

## Upgrading an existing database

The `owner` and `group_name` columns were `VARCHAR(10)`, too short for many user ids.
New schemas (the `.ddl` files in `resources/`) use `VARCHAR(128)`. An existing schema is
widened automatically when the factory connects (the result is logged). To leave the
schema alone, set the connection property `jdbcUpgradeSchema` to `false`; if the database
user may not alter the table, or you'd rather do it yourself, run:

| Database | Statements |
|---|---|
| HSQLDB, SQL:2003 | `ALTER TABLE file_source.file ALTER COLUMN owner SET DATA TYPE VARCHAR(128);`<br>`ALTER TABLE file_source.file ALTER COLUMN group_name SET DATA TYPE VARCHAR(128);` |
| PostgreSQL | `ALTER TABLE file_source.file ALTER COLUMN owner TYPE VARCHAR(128);`<br>`ALTER TABLE file_source.file ALTER COLUMN group_name TYPE VARCHAR(128);` |
| MySQL | `ALTER TABLE file_source.file MODIFY owner VARCHAR(128) NOT NULL;`<br>`ALTER TABLE file_source.file MODIFY group_name VARCHAR(128) DEFAULT 'staff';` |
