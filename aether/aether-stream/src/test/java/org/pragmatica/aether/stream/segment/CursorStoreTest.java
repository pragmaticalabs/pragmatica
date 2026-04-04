package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.CursorStore.cursorStore;

class CursorStoreTest {

    private static final String STREAM = "test-stream";
    private static final String GROUP = "my-group";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    private StorageInstance storage;
    private CursorStore store;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        store = cursorStore(storage);
    }

    @Nested
    class CommitAndFetch {

        @Test
        void commit_fetch_returnsCommittedOffset() {
            store.commit(GROUP, STREAM, PARTITION, 42L).await();

            var result = store.fetch(GROUP, STREAM, PARTITION);

            assertThat(result.isPresent()).isTrue();
            result.onPresent(offset -> assertThat(offset).isEqualTo(42L));
        }

        @Test
        void commit_overwritesPreviousOffset() {
            store.commit(GROUP, STREAM, PARTITION, 10L).await();
            store.commit(GROUP, STREAM, PARTITION, 100L).await();

            var result = store.fetch(GROUP, STREAM, PARTITION);

            assertThat(result.isPresent()).isTrue();
            result.onPresent(offset -> assertThat(offset).isEqualTo(100L));
        }

        @Test
        void fetch_returnsNone_whenNotCommitted() {
            var result = store.fetch(GROUP, STREAM, PARTITION);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void commit_isolatesByConsumerGroup() {
            store.commit("group-a", STREAM, PARTITION, 10L).await();
            store.commit("group-b", STREAM, PARTITION, 20L).await();

            var resultA = store.fetch("group-a", STREAM, PARTITION);
            var resultB = store.fetch("group-b", STREAM, PARTITION);

            resultA.onPresent(offset -> assertThat(offset).isEqualTo(10L));
            resultB.onPresent(offset -> assertThat(offset).isEqualTo(20L));
        }

        @Test
        void commit_isolatesByPartition() {
            store.commit(GROUP, STREAM, 0, 10L).await();
            store.commit(GROUP, STREAM, 1, 20L).await();

            var result0 = store.fetch(GROUP, STREAM, 0);
            var result1 = store.fetch(GROUP, STREAM, 1);

            result0.onPresent(offset -> assertThat(offset).isEqualTo(10L));
            result1.onPresent(offset -> assertThat(offset).isEqualTo(20L));
        }

        @Test
        void commit_isolatesByStream() {
            store.commit(GROUP, "stream-a", PARTITION, 10L).await();
            store.commit(GROUP, "stream-b", PARTITION, 20L).await();

            var resultA = store.fetch(GROUP, "stream-a", PARTITION);
            var resultB = store.fetch(GROUP, "stream-b", PARTITION);

            resultA.onPresent(offset -> assertThat(offset).isEqualTo(10L));
            resultB.onPresent(offset -> assertThat(offset).isEqualTo(20L));
        }
    }

    @Nested
    class Encoding {

        @Test
        void encodeOffset_decodeOffset_roundTrip() {
            var encoded = CursorStore.encodeOffset(Long.MAX_VALUE);
            var decoded = CursorStore.decodeOffset(encoded);

            assertThat(decoded).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        void encodeOffset_decodeOffset_zero() {
            var encoded = CursorStore.encodeOffset(0L);
            var decoded = CursorStore.decodeOffset(encoded);

            assertThat(decoded).isEqualTo(0L);
        }

        @Test
        void buildRefName_formatsCorrectly() {
            var refName = CursorStore.buildRefName("my-group", "orders", 3);

            assertThat(refName).isEqualTo("cursors/my-group/orders/3");
        }
    }
}
