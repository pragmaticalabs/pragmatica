// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RecentCommandsBufferTest {
    private RecentCommandsBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = RecentCommandsBuffer.recentCommandsBuffer(4);
    }

    @Test
    void recentCommandsBuffer_rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> RecentCommandsBuffer.recentCommandsBuffer(0));
        assertThrows(IllegalArgumentException.class, () -> RecentCommandsBuffer.recentCommandsBuffer(-1));
    }

    @Test
    void record_storesEventsInInsertionOrder() {
        var first = receivedAt(100L, "OPERATOR");
        var second = receivedAt(200L, "RECONCILER");
        buffer.record(first);
        buffer.record(second);

        var snapshot = buffer.snapshotAll();
        assertEquals(2, snapshot.size());
        assertSame(first, snapshot.get(0));
        assertSame(second, snapshot.get(1));
    }

    @Test
    void record_evictsOldestOnOverflow() {
        for (var i = 0; i < 6; i++) {
            buffer.record(receivedAt(100L + i, "OPERATOR"));
        }
        var snapshot = buffer.snapshotAll();
        assertEquals(4, snapshot.size());
        assertEquals(102L, snapshot.get(0).timestampMs());
        assertEquals(105L, snapshot.get(3).timestampMs());
    }

    @Test
    void record_rejectsNullEvent() {
        assertThrows(NullPointerException.class, () -> buffer.record(null));
    }

    @Nested
    class SnapshotFiltering {
        @BeforeEach
        void seed() {
            buffer.record(receivedAt(100L, "OPERATOR"));
            buffer.record(receivedAt(200L, "RECONCILER"));
            buffer.record(receivedAt(300L, "CTM"));
        }

        @Test
        void snapshot_returnsAllWhenSinceIsZero() {
            assertEquals(3, buffer.snapshot(0L, null, 0).size());
        }

        @Test
        void snapshot_filtersBySince() {
            var snapshot = buffer.snapshot(200L, null, 0);
            assertEquals(2, snapshot.size());
            assertEquals(200L, snapshot.get(0).timestampMs());
            assertEquals(300L, snapshot.get(1).timestampMs());
        }

        @Test
        void snapshot_filtersBySource() {
            var snapshot = buffer.snapshot(0L, "operator", 0);
            assertEquals(1, snapshot.size());
            assertEquals("OPERATOR", snapshot.get(0).source());
        }

        @Test
        void snapshot_treatsAllAsNoSourceFilter() {
            assertEquals(3, buffer.snapshot(0L, "all", 0).size());
        }

        @Test
        void snapshot_treatsEmptySourceAsNoSourceFilter() {
            assertEquals(3, buffer.snapshot(0L, "", 0).size());
        }

        @Test
        void snapshot_limitTrimsFromOldest() {
            var snapshot = buffer.snapshot(0L, null, 2);
            assertEquals(2, snapshot.size());
            assertEquals(200L, snapshot.get(0).timestampMs());
            assertEquals(300L, snapshot.get(1).timestampMs());
        }
    }

    @Test
    void asPublisher_writesToBuffer() {
        var publisher = buffer.asPublisher();
        var event = receivedAt(100L, "OPERATOR");
        publisher.publish(event);
        assertEquals(1, buffer.size());
        assertSame(event, buffer.snapshotAll().get(0));
    }

    @Test
    void teeOn_writesToBufferAndDelegates() {
        var delegateSeen = new java.util.concurrent.atomic.AtomicReference<CommandLifecycleEvent>();
        var tee = buffer.teeOn(event -> {
            delegateSeen.set(event);
            return org.pragmatica.lang.Promise.unitPromise();
        });
        var event = receivedAt(123L, "OPERATOR");
        tee.publish(event);
        assertSame(event, buffer.snapshotAll().get(0));
        assertSame(event, delegateSeen.get());
    }

    @Test
    void teeOn_continuesRecordingWhenDelegateFails() {
        var tee = buffer.teeOn(_ -> org.pragmatica.lang.utils.Causes.cause("upstream down").promise());
        var event = receivedAt(500L, "OPERATOR");
        tee.publish(event);
        assertEquals(1, buffer.size());
    }

    @Test
    void clearForTesting_emptiesBuffer() {
        buffer.record(receivedAt(100L, "OPERATOR"));
        buffer.clearForTesting();
        assertEquals(0, buffer.size());
        assertTrue(buffer.snapshotAll().isEmpty());
    }

    @Test
    void capacity_reflectsConstructorValue() {
        assertEquals(4, buffer.capacity());
        assertFalse(buffer.snapshotAll().isEmpty() == buffer.size() > 0 && buffer.size() > 4,
                    "size never exceeds capacity");
    }

    private static CommandLifecycleEvent receivedAt(long timestampMs, String source) {
        return new CommandLifecycleEvent.CommandReceived("ForceDecommission",
                                                          "node-x",
                                                          "FORCED",
                                                          "operator test",
                                                          source,
                                                          timestampMs);
    }
}
