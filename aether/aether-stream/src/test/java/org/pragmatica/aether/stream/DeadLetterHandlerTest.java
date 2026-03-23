package org.pragmatica.aether.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.DeadLetterHandler.deadLetterHandler;

class DeadLetterHandlerTest {

    private DeadLetterHandler handler;

    @BeforeEach
    void setUp() {
        handler = deadLetterHandler();
    }

    @Nested
    class RecordAndRead {

        @Test
        void read_returnsRecordedEntry_afterRecord() {
            handler.record("orders", 0, 42L, "payload".getBytes(), "processing failed", 3);

            var entries = handler.read("orders", 10);
            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().streamName()).isEqualTo("orders");
            assertThat(entries.getFirst().partition()).isEqualTo(0);
            assertThat(entries.getFirst().offset()).isEqualTo(42L);
            assertThat(entries.getFirst().payload()).isEqualTo("payload".getBytes());
            assertThat(entries.getFirst().errorMessage()).isEqualTo("processing failed");
            assertThat(entries.getFirst().attemptCount()).isEqualTo(3);
            assertThat(entries.getFirst().timestamp()).isGreaterThan(0L);
        }

        @Test
        void read_respectsMaxCount_whenMoreEntriesExist() {
            handler.record("orders", 0, 1L, "a".getBytes(), "err", 1);
            handler.record("orders", 0, 2L, "b".getBytes(), "err", 1);
            handler.record("orders", 0, 3L, "c".getBytes(), "err", 1);

            var entries = handler.read("orders", 2);
            assertThat(entries).hasSize(2);
        }

        @Test
        void read_returnsEmptyList_whenNoEntries() {
            var entries = handler.read("nonexistent", 10);
            assertThat(entries).isEmpty();
        }

        @Test
        void read_isolatesStreams_differentStreamNames() {
            handler.record("orders", 0, 1L, "a".getBytes(), "err", 1);
            handler.record("events", 0, 2L, "b".getBytes(), "err", 1);

            assertThat(handler.read("orders", 10)).hasSize(1);
            assertThat(handler.read("events", 10)).hasSize(1);
        }

        @Test
        void read_returnsAll_whenMaxCountExceedsEntries() {
            handler.record("orders", 0, 1L, "a".getBytes(), "err", 1);

            var entries = handler.read("orders", 100);
            assertThat(entries).hasSize(1);
        }
    }
}
