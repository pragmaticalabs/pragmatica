CREATE TABLE events (
    id bigserial NOT NULL,
    event_type text NOT NULL,
    payload jsonb NOT NULL,
    metadata jsonb DEFAULT '{}' NOT NULL,
    created_at timestamptz DEFAULT now() NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_events_payload ON events USING gin (payload);
CREATE INDEX idx_events_type_created ON events (event_type, created_at DESC);
CREATE INDEX idx_events_active ON events (created_at) WHERE event_type != 'archived';
CREATE INDEX idx_events_payload_type ON events ((payload->>'type'));
