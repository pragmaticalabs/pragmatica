ALTER TABLE users ADD COLUMN phone varchar(20);
ALTER TABLE users ADD COLUMN metadata jsonb DEFAULT '{}';
ALTER TABLE users ADD COLUMN tags text[] DEFAULT '{}';
ALTER TABLE users ADD COLUMN is_verified boolean DEFAULT false NOT NULL;
ALTER TABLE users ADD COLUMN created_at timestamptz DEFAULT now() NOT NULL;
