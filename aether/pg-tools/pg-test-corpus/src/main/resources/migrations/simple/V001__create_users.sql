CREATE TABLE users (
    id bigserial NOT NULL,
    name text NOT NULL,
    email varchar(255) NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    updated_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_users_email ON users (email);
