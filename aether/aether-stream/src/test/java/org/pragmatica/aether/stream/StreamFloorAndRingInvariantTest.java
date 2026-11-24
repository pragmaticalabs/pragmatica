// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.HotWindowDeclaration;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.StreamDeclaration;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.stream.RingGeometry;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #3390 F2 and F3. F2: the materialized floor (`perPartitionFloor x rings`) is one checked product, so a floor a long cannot
/// represent is refused as `STREAM_MEMORY_EXCEEDED` and a refused create or hydrate leaves the node's budget exactly as it was
/// (before, the product wrapped negative, nothing was reserved, and the failed ring build released it into the budget). F3
/// (ruling know e8888553b4): EVERY stream ring holds at least one `max_event_size` event, `system:` and `entity:` included;
/// the commit validator refuses a ring sized below it by name, naming the setting that sizes the ring.
class StreamFloorAndRingInvariantTest {
    private static final long KIB = 1024L;
    private static final long MIB = 1024 * KIB;
    private static final long GIB = 1024 * MIB;
    private static final long HOUR_MS = 3_600_000L;

    @Nested
    class FloorOverflow {
        private static final long FIRST = RingGeometry.firstSegmentBytes(4 * MIB, MIB);
        /// Indexable per ring ([RingGeometry#maxCapacity]), yet two rings' floors overflow a long.
        private static final long COUNT = RingGeometry.maxCapacity(FIRST) / 2 + 1;

        @Test
        void create_floorPastALong_isRefusedAsMemoryExceeded_andLeavesTheBudget() {
            var manager = streamPartitionManager(128 * MIB);

            try {
                var before = manager.availableBytes();

                manager.createStream(withHot(userStream("ovf", 2, MIB), some(4 * MIB), some(COUNT)))
                       .onSuccess(_ -> fail("a floor past a long must be refused"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_MEMORY_EXCEEDED));
                assertThat(manager.availableBytes()).as("a refused create leaves the budget exactly as it was").isEqualTo(before);
                assertThat(manager.createStream(userStream("control", 1, MIB)).isSuccess()).as("a default stream after it")
                                                                                          .isTrue();
            } finally {
                manager.close();
            }
        }

        @Test
        void hydrate_floorPastALong_isRefusedWithNoEntry_andLeavesTheBudget() {
            var committed = withHot(userStream("ovf", 2, MIB), some(4 * MIB), some(COUNT)).withIncarnation(7L);
            var follower = streamPartitionManager(128 * MIB);

            try {
                var before = follower.availableBytes();

                follower.onStreamConfigPut(put(committed));

                assertThat(follower.streamExists("ovf")).as("no entry for a floor past a long").isFalse();
                assertThat(follower.availableBytes()).as("a refused hydrate leaves the budget exactly as it was").isEqualTo(before);
            } finally {
                follower.close();
            }
        }

        @Test
        void retentionCountPastTheOldIndexBound_noLongerSizesTheRing_andLeavesTheBudget() {
            var count = OffHeapRingBuffer.MAX_CAPACITY / 2 + 1;
            var manager = streamPartitionManager(128 * MIB);

            try {
                var before = manager.availableBytes();
                var created = manager.createStream(StreamConfig.streamConfig("ovfr",
                                                                             2,
                                                                             RetentionPolicy.retentionPolicy(count, 256 * MIB, HOUR_MS),
                                                                             "latest",
                                                                             MIB));

                assertThat(manager.availableBytes()).as("available after %s (before %d)", created, before).isBetween(0L, before);
                created.onFailure(_ -> assertThat(manager.availableBytes()).as("refused: budget intact").isEqualTo(before));
            } finally {
                manager.close();
            }
        }
    }

    @Nested
    class EveryRingHoldsOneMaxEvent {
        @Test
        void systemRing_retentionBytesBelowMaxEvent_isRefusedByName() {
            var manager = streamPartitionManager(GIB);

            try {
                manager.createStream(systemStream("system:tiny", 64 * KIB, 128 * KIB))
                       .onSuccess(_ -> fail("a system: ring below one max event must be refused"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.HotWindowBelowMaxEvent("system:tiny",
                                                                                                              "retention max_bytes",
                                                                                                              64 * KIB,
                                                                                                              128 * KIB)))
                       .onFailure(cause -> assertThat(cause.message()).contains("retention max_bytes"));
                assertThat(manager.streamExists("system:tiny")).as("no entry").isFalse();
                assertThat(manager.totalAllocatedBytes()).as("nothing reserved").isZero();
            } finally {
                manager.close();
            }
        }

        @Test
        void systemRing_retentionBytesEqualToMaxEvent_isAccepted_andHoldsTheEvent() {
            var manager = streamPartitionManager(GIB);

            try {
                assertThat(manager.createStream(systemStream("system:exact", 128 * KIB, 128 * KIB)).isSuccess()).isTrue();
                assertThat(manager.publishLocalAtFloor("system:exact", 0, new byte[(int) (128 * KIB)], System.currentTimeMillis(), 0, true)
                                  .isSuccess()).as("one max_event_size event fits the ring")
                                               .isTrue();
            } finally {
                manager.close();
            }
        }

        @Test
        void systemRing_committedBelowMaxEvent_isRefusedAtHydrate_withNoEntry() {
            var follower = streamPartitionManager(GIB);

            try {
                follower.onStreamConfigPut(put(systemStream("system:tiny", 64 * KIB, 128 * KIB).withIncarnation(7L)));

                assertThat(follower.streamExists("system:tiny")).as("no entry for a committed ring below one event").isFalse();
                assertThat(follower.totalAllocatedBytes()).as("nothing reserved").isZero();
            } finally {
                follower.close();
            }
        }

        @Test
        void entityRing_maxEventAboveTheEntityWindow_isRefusedByName() {
            var maxEvent = EntityRingWindow.MAX_BYTES + MIB;
            var manager = streamPartitionManager(GIB);

            try {
                manager.createStream(userStream("entity:big", 1, maxEvent))
                       .onSuccess(_ -> fail("an entity: ring below one max event must be refused"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(new StreamError.HotWindowBelowMaxEvent("entity:big",
                                                                                                              "the entity ring window",
                                                                                                              EntityRingWindow.MAX_BYTES,
                                                                                                              maxEvent)));
                assertThat(manager.streamExists("entity:big")).as("no entry").isFalse();
            } finally {
                manager.close();
            }
        }
    }

    private static StreamConfig userStream(String name, int partitions, long maxEventSize) {
        return StreamConfig.streamConfig(name, partitions, RetentionPolicy.retentionPolicy(), "latest", maxEventSize);
    }

    private static StreamConfig systemStream(String name, long retentionBytes, long maxEventSize) {
        return StreamConfig.streamConfig(name,
                                         1,
                                         RetentionPolicy.retentionPolicy(1_000L, retentionBytes, HOUR_MS),
                                         "earliest",
                                         maxEventSize);
    }

    private static StreamConfig withHot(StreamConfig config, Option<Long> bytes, Option<Long> count) {
        var hot = HotWindowDeclaration.hotWindowDeclaration(bytes, count);

        return config.withDeclaration(StreamDeclaration.streamDeclaration(none(), none(), some(hot), none()));
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> put(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }
}
