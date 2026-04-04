package org.pragmatica.aether.stream.pg;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// PostgreSQL-backed cursor store for exactly-once consumer offset tracking.
///
/// Delegates to PgStreamStore for transactional UPSERT-based cursor persistence.
/// Provides the same commit/fetch contract as the AHSE-based CursorStore but with
/// PostgreSQL transaction guarantees for exactly-once semantics.
public final class PgCursorStore {
    private final PgStreamStore store;

    private PgCursorStore(PgStreamStore store) {
        this.store = store;
    }

    public static PgCursorStore pgCursorStore(PgStreamStore store) {
        return new PgCursorStore(store);
    }

    public Promise<Unit> commit(String consumerGroup, String streamName, int partition, long offset) {
        return store.commitCursor(consumerGroup, streamName, partition, offset);
    }

    public Promise<Option<Long>> fetch(String consumerGroup, String streamName, int partition) {
        return store.fetchCursor(consumerGroup, streamName, partition);
    }
}
