ALTER TABLE users ALTER COLUMN email TYPE varchar(320);
ALTER TABLE users ALTER COLUMN name SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);
ALTER TABLE users RENAME COLUMN name TO display_name;
