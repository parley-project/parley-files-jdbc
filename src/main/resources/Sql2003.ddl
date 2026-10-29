create schema file_source;

CREATE TABLE file_source.file (
	FILEID BIGINT NOT NULL UNIQUE generated always as IDENTITY,
	OWNER VARCHAR(128) NOT NULL ,
	GROUP_NAME VARCHAR(128)  DEFAULT 'staff',
	CREATE_TIME TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
	LAST_ACCESS_DATE TIMESTAMP  DEFAULT CURRENT_TIMESTAMP,
	last_modified_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

	OWNER_READABLE BOOLEAN  DEFAULT true,
	OWNER_WRITEABLE BOOLEAN DEFAULT true,
	OWNER_EXECUTABLE BOOLEAN DEFAULT true,

	GROUP_READABLE BOOLEAN DEFAULT true,
	GROUP_WRITEABLE BOOLEAN DEFAULT false,
	GROUP_EXECUTABLE BOOLEAN  DEFAULT false,

	OTHER_READABLE BOOLEAN  DEFAULT true,
	OTHER_WRITEABLE BOOLEAN DEFAULT false,
	OTHER_EXECUTABLE BOOLEAN DEFAULT false,
	FILE_TYPE VARCHAR(10) NOT NULL ,
	DATA LONGVARBINARY,
	LINK_COUNT INT DEFAULT 1 NOT NULL
	) 
	;



-- Names. A dirent is one name for an inode (a row of file): in the folder parent_id (the inode of a
-- directory, 0 for the root itself) it is called name. A hard link is a second dirent for the same
-- inode, so the data, attributes and locks (all keyed by the inode) never move when a name is deleted
-- or renamed. file.link_count is the number of dirents that name the inode, and the data goes with the last.
CREATE TABLE file_source.dirent (
	PARENT_ID BIGINT NOT NULL,
	NAME VARCHAR(250) NOT NULL,
	INODE_ID BIGINT NOT NULL,
	PRIMARY KEY (PARENT_ID, NAME)
	)
	;

CREATE INDEX dirent_inode_idx ON file_source.dirent (INODE_ID)
	;

-- A symbolic link is an inode of type "link" whose target_path is the path it points to (it need not exist).
CREATE TABLE file_source.file_link (
	LINK_ID BIGINT NOT NULL PRIMARY KEY,
	TARGET_ID BIGINT,
	TARGET_PATH VARCHAR(4096)
	)
	;

CREATE INDEX file_link_target_idx ON file_source.file_link (target_id)
	;
-- File locks (FileSource.lock), used by PostgreSQL, MySQL 8 and MariaDB 10.3+, and created on
-- connect if missing. file_lock: one row per locked file, row-locked briefly while a lock is taken.
-- file_lock_range: one row per region held, kept row-locked by its holder until it is released.
CREATE TABLE file_source.file_lock (
	FILEID BIGINT NOT NULL PRIMARY KEY
	)
	;

CREATE TABLE file_source.file_lock_range (
	FILEID BIGINT NOT NULL,
	ID BIGINT NOT NULL,
	START_POS BIGINT NOT NULL,
	END_POS BIGINT NOT NULL,
	SHARED BOOLEAN NOT NULL,
	PRIMARY KEY (FILEID, ID)
	)
	;
