// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamAccess.PartitionInfo;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamAccess.StreamMetadata;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class StreamAccessTest {

    @Nested
    class StreamEventTests {

        @Test
        void recordFields_areAccessible() {
            var event = new StreamEvent<>(42L, 1000L, 3, "payload");

            assertThat(event.offset()).isEqualTo(42L);
            assertThat(event.timestamp()).isEqualTo(1000L);
            assertThat(event.partition()).isEqualTo(3);
            assertThat(event.payload()).isEqualTo("payload");
        }
    }

    @Nested
    class StreamMetadataTests {

        @Test
        void recordFields_areAccessible() {
            var partitions = List.of(new PartitionInfo(0, 0L, 100L, 100L));
            var metadata = new StreamMetadata("test-stream", 1, partitions);

            assertThat(metadata.streamName()).isEqualTo("test-stream");
            assertThat(metadata.partitionCount()).isEqualTo(1);
            assertThat(metadata.partitions()).hasSize(1);
        }
    }

    @Nested
    class PartitionInfoTests {

        @Test
        void recordFields_areAccessible() {
            var info = new PartitionInfo(2, 10L, 500L, 490L);

            assertThat(info.partition()).isEqualTo(2);
            assertThat(info.headOffset()).isEqualTo(10L);
            assertThat(info.tailOffset()).isEqualTo(500L);
            assertThat(info.eventCount()).isEqualTo(490L);
        }
    }

    @Nested
    class InterfaceTests {

        @Test
        void isInterface() {
            assertThat(StreamAccess.class.isInterface()).isTrue();
        }

        @Test
        void declaresExpectedMethods() {
            var methodNames = java.util.Arrays.stream(StreamAccess.class.getMethods())
                .map(java.lang.reflect.Method::getName)
                .toList();

            assertThat(methodNames).contains("publish", "fetch", "commit", "committedOffset", "fetchFromCommitted", "metadata");
        }
    }

    @Nested
    class FetchFromCommittedTests {

        @Test
        void fetchFromCommitted_readsFromCommittedOffset_whenPresent() {
            var access = new RecordingAccess(Option.some(7L));

            access.fetchFromCommitted("group-1", 0, 10).await();

            assertThat(access.requestedOffset()).isEqualTo(7L);
        }

        @Test
        void fetchFromCommitted_readsFromZero_whenAbsent() {
            var access = new RecordingAccess(Option.none());

            access.fetchFromCommitted("group-1", 0, 10).await();

            assertThat(access.requestedOffset()).isEqualTo(0L);
        }
    }

    /// Minimal `StreamAccess` double that scripts the committed cursor and records the offset the
    /// inherited default `fetchFromCommitted` forwards to `fetch(partition, fromOffset, maxEvents)`.
    static final class RecordingAccess implements StreamAccess<String> {
        private final Option<Long> committed;
        private final AtomicLong requestedOffset = new AtomicLong(-1L);

        RecordingAccess(Option<Long> committed) {
            this.committed = committed;
        }

        long requestedOffset() {
            return requestedOffset.get();
        }

        @Override
        public Promise<Long> publish(String event) {
            return Promise.success(0L);
        }

        @Override
        public Promise<List<StreamEvent<String>>> fetch(long fromOffset, int maxEvents) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<List<StreamEvent<String>>> fetch(int partition, long fromOffset, int maxEvents) {
            requestedOffset.set(fromOffset);
            return Promise.success(List.of());
        }

        @Override
        public Promise<Unit> commit(String consumerGroup, int partition, long offset) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<Long>> committedOffset(String consumerGroup, int partition) {
            return Promise.success(committed);
        }

        @Override
        public Promise<StreamMetadata> metadata() {
            return Promise.success(new StreamMetadata("test", 1, List.of()));
        }
    }
}
