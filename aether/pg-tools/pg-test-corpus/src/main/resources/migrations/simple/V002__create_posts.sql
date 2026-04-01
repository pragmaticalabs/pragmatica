CREATE TABLE posts (
    id bigserial NOT NULL,
    user_id bigint NOT NULL,
    title text NOT NULL,
    body text,
    published boolean DEFAULT false NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_posts_user_id ON posts (user_id);
