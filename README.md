# BjlFileSystemJdbc
JDBC implementation of the FileSource interface
 

## Users and permissions

A JDBC file system's current user is the database user (`jdbcUserid`); every file it
creates is owned by that user, so the owner permissions apply to it. Its group is the
`jdbcGroup` connection property, default `staff` (the schema's default group for new
files); set it empty for no group. Other database users get the group or other
permissions stored with each file.

## Upgrading an existing database

The `owner` and `group_name` columns were `VARCHAR(10)`, too short for many user ids.
New schemas (the `.ddl` files in `resources/`) use `VARCHAR(128)`. To widen an existing one:

| Database | Statements |
|---|---|
| HSQLDB, SQL:2003 | `ALTER TABLE file_source.file ALTER COLUMN owner SET DATA TYPE VARCHAR(128);`<br>`ALTER TABLE file_source.file ALTER COLUMN group_name SET DATA TYPE VARCHAR(128);` |
| PostgreSQL | `ALTER TABLE file_source.file ALTER COLUMN owner TYPE VARCHAR(128);`<br>`ALTER TABLE file_source.file ALTER COLUMN group_name TYPE VARCHAR(128);` |
| MySQL | `ALTER TABLE file_source.file MODIFY owner VARCHAR(128) NOT NULL;`<br>`ALTER TABLE file_source.file MODIFY group_name VARCHAR(128) DEFAULT 'staff';` |
