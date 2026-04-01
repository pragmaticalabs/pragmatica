CREATE TYPE priority_level AS ENUM ('low', 'medium', 'high', 'critical');

CREATE TABLE tasks (
    id bigserial NOT NULL,
    title text NOT NULL,
    priority priority_level DEFAULT 'medium' NOT NULL,
    assignees text[] DEFAULT '{}',
    labels text[] DEFAULT '{}',
    scores integer[] DEFAULT '{}',
    created_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_tasks_labels ON tasks USING gin (labels);
