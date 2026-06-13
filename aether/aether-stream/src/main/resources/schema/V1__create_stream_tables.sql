CREATE TABLE IF NOT EXISTS aether_stream_segments (
    stream_name TEXT NOT NULL,
    partition_id INTEGER NOT NULL,
    start_offset BIGINT NOT NULL,
    end_offset BIGINT NOT NULL,
    data BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (stream_name, partition_id, start_offset)
);

CREATE TABLE IF NOT EXISTS aether_stream_cursors (
    consumer_group TEXT NOT NULL,
    stream_name TEXT NOT NULL,
    partition_id INTEGER NOT NULL,
    committed_offset BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer_group, stream_name, partition_id)
);

CREATE INDEX IF NOT EXISTS idx_segments_created ON aether_stream_segments (created_at);
CREATE INDEX IF NOT EXISTS idx_segments_stream_partition ON aether_stream_segments (stream_name, partition_id, start_offset);
