package org.pragmatica.aether.stream.pg;

import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// PostgreSQL-backed storage for stream segments and transactional consumer cursors.
///
/// Provides durable cold-tier storage for sealed segments and exactly-once cursor
/// semantics via PostgreSQL transactions. Designed to integrate with the tiered
/// storage hierarchy as the coldest tier.
///
/// Tables:
///   - `aether_stream_segments`: stores sealed segment byte payloads keyed by stream/partition/offset
///   - `aether_stream_cursors`: stores consumer group cursor positions with UPSERT semantics
public interface PgStreamStore {
    Promise<Unit> storeSegment(String streamName, int partition, long startOffset, long endOffset, byte[] data);
    Promise<Option<byte[]>> readSegment(String streamName, int partition, long startOffset);
    Promise<Integer> deleteExpired(String streamName, Instant cutoff);
    Promise<Unit> commitCursor(String consumerGroup, String streamName, int partition, long offset);
    Promise<Option<Long>> fetchCursor(String consumerGroup, String streamName, int partition);

    static PgStreamStore pgStreamStore(SqlConnector connector) {
        return new PgStore(connector);
    }
}

final class PgStore implements PgStreamStore {
    private static final Logger log = LoggerFactory.getLogger(PgStore.class);

    private static final String INSERT_SEGMENT = "INSERT INTO aether_stream_segments (stream_name, partition_id, start_offset, end_offset, data) VALUES (?, ?, ?, ?, ?)";

    private static final String SELECT_SEGMENT = "SELECT data FROM aether_stream_segments WHERE stream_name = ? AND partition_id = ? AND start_offset = ?";

    private static final String DELETE_EXPIRED = "DELETE FROM aether_stream_segments WHERE stream_name = ? AND created_at < ?";

    private static final String UPSERT_CURSOR = "INSERT INTO aether_stream_cursors (consumer_group, stream_name, partition_id, committed_offset, updated_at) " + "VALUES (?, ?, ?, ?, NOW()) " + "ON CONFLICT (consumer_group, stream_name, partition_id) " + "DO UPDATE SET committed_offset = EXCLUDED.committed_offset, updated_at = NOW()";

    private static final String SELECT_CURSOR = "SELECT committed_offset FROM aether_stream_cursors WHERE consumer_group = ? AND stream_name = ? AND partition_id = ?";

    private static final RowMapper<byte[]> BYTES_MAPPER = row -> row.getBytes("data");

    private static final RowMapper<Long> OFFSET_MAPPER = row -> row.getLong("committed_offset");

    private final SqlConnector connector;

    PgStore(SqlConnector connector) {
        this.connector = connector;
    }

    @Override public Promise<Unit> storeSegment(String streamName,
                                                int partition,
                                                long startOffset,
                                                long endOffset,
                                                byte[] data) {
        return connector.update(INSERT_SEGMENT, streamName, partition, startOffset, endOffset, data).mapToUnit()
                               .onSuccess(_ -> logSegmentStored(streamName, partition, startOffset, endOffset));
    }

    @Override public Promise<Option<byte[]>> readSegment(String streamName, int partition, long startOffset) {
        return connector.queryOptional(SELECT_SEGMENT, BYTES_MAPPER, streamName, partition, startOffset);
    }

    @Override public Promise<Integer> deleteExpired(String streamName, Instant cutoff) {
        return connector.update(DELETE_EXPIRED, streamName, cutoff)
                               .onSuccess(count -> logExpiredDeleted(streamName, count));
    }

    @Override public Promise<Unit> commitCursor(String consumerGroup, String streamName, int partition, long offset) {
        return connector.transactional(tx -> tx.update(UPSERT_CURSOR, consumerGroup, streamName, partition, offset)
                                                      .mapToUnit())
        .onSuccess(_ -> logCursorCommitted(consumerGroup, streamName, partition, offset));
    }

    @Override public Promise<Option<Long>> fetchCursor(String consumerGroup, String streamName, int partition) {
        return connector.queryOptional(SELECT_CURSOR, OFFSET_MAPPER, consumerGroup, streamName, partition);
    }

    private static void logSegmentStored(String streamName, int partition, long startOffset, long endOffset) {
        log.debug("PG segment stored: {}/{}:[{}-{}]", streamName, partition, startOffset, endOffset);
    }

    private static void logExpiredDeleted(String streamName, int count) {
        if (count > 0) {log.info("PG segments expired: stream={} count={}", streamName, count);}
    }

    private static void logCursorCommitted(String consumerGroup, String streamName, int partition, long offset) {
        log.debug("PG cursor committed: {}/{}/{} -> {}",
                  consumerGroup,
                  streamName,
                  partition,
                  offset);
    }
}
