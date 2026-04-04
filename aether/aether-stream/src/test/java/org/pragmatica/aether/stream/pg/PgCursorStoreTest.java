package org.pragmatica.aether.stream.pg;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.pg.PgCursorStore.pgCursorStore;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


class PgCursorStoreTest {

    private static final String STREAM = "test-stream";
    private static final String GROUP = "test-group";
    private static final int PARTITION = 0;

    private InMemoryPgStreamStore backing;
    private PgCursorStore cursorStore;

    @BeforeEach
    void setUp() {
        backing = new InMemoryPgStreamStore();
        cursorStore = pgCursorStore(backing);
    }

    @Nested
    class CommitAndFetch {

        @Test
        void commit_fetch_returnsCommittedOffset() {
            cursorStore.commit(GROUP, STREAM, PARTITION, 42L).await();

            var result = cursorStore.fetch(GROUP, STREAM, PARTITION).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(opt -> {
                      assertThat(opt.isPresent()).isTrue();
                      opt.onPresent(offset -> assertThat(offset).isEqualTo(42L));
                  });
        }

        @Test
        void commit_updatesOffset_onSubsequentCommit() {
            cursorStore.commit(GROUP, STREAM, PARTITION, 10L).await();
            cursorStore.commit(GROUP, STREAM, PARTITION, 100L).await();

            var result = cursorStore.fetch(GROUP, STREAM, PARTITION).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(opt -> opt.onPresent(offset -> assertThat(offset).isEqualTo(100L)));
        }

        @Test
        void fetch_returnsNone_whenNotCommitted() {
            var result = cursorStore.fetch(GROUP, STREAM, PARTITION).await();

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }
    }

    /// Simple in-memory implementation for testing PgCursorStore delegation.
    static final class InMemoryPgStreamStore implements PgStreamStore {
        private final AtomicReference<String> lastKey = new AtomicReference<>();
        private final AtomicLong lastOffset = new AtomicLong(-1);

        @Override
        public Promise<Unit> storeSegment(String streamName, int partition, long startOffset, long endOffset, byte[] data) {
            return Promise.success(unit());
        }

        @Override
        public Promise<Option<byte[]>> readSegment(String streamName, int partition, long startOffset) {
            return Promise.success(none());
        }

        @Override
        public Promise<Integer> deleteExpired(String streamName, Instant cutoff) {
            return Promise.success(0);
        }

        @Override
        public Promise<Unit> commitCursor(String consumerGroup, String streamName, int partition, long offset) {
            lastKey.set(consumerGroup + "/" + streamName + "/" + partition);
            lastOffset.set(offset);
            return Promise.success(unit());
        }

        @Override
        public Promise<Option<Long>> fetchCursor(String consumerGroup, String streamName, int partition) {
            var key = consumerGroup + "/" + streamName + "/" + partition;
            if (key.equals(lastKey.get())) {
                return Promise.success(some(lastOffset.get()));
            }
            return Promise.success(none());
        }
    }
}
