// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #3334 ST 2.5, the manager's side of an online `[retention]` or `[hot]` change on a stream of the same life, partition count
/// and durability: what this node ENFORCES and what retention READS after the committed change is applied (T-157, T-158), and
/// that an existing ring is never resized in place (T-160). The enforcer's own passes are `DeclaredRetentionEnforcerTest`.
class RetentionOnlineChangeTest {
    private static final String STREAM = "orders";
    private static final long GIB = 1L << 30;
    private static final long COUNT = 1_000L;

    private final AtomicReference<Role> role = new AtomicReference<>(Role.OWNER);
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.placementRoleSupplier((_, partition) -> partition == 0
                                                        ? Role.OWNER
                                                        : role.get());
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// T-157, enforcement arm: `max_bytes` 1 GiB -> 2 GiB at equal partitions and durability. The enforced config and the
    /// policy retention reads both carry the new bound. Today's `adopts` (stronger durability only) keeps the old bound
    /// enforced.
    @Test
    void maxBytesGrowing_isTheEnforcedBound_andTheBoundRetentionReads() {
        manager.onStreamConfigPut(committedPut(config(1, bytes(GIB))));
        manager.onStreamConfigPut(committedPut(config(1, bytes(2 * GIB))));

        assertThat(enforcedBytes()).as("the enforced bound").isEqualTo(2 * GIB);
        assertThat(readBytes()).as("the bound retention reads").isEqualTo(2 * GIB);
    }

    /// T-158, enforcement arm: `max_bytes` halved. A shrink takes effect on the enforced config at once, not at the next ring
    /// or stream creation, so each holder's next pass reclaims to the new share.
    @Test
    void maxBytesHalved_isTheEnforcedBoundAtOnce_notAtTheNextRingCreation() {
        manager.onStreamConfigPut(committedPut(config(1, bytes(GIB))));
        manager.onStreamConfigPut(committedPut(config(1, bytes(GIB / 2))));

        assertThat(enforcedBytes()).as("the enforced bound").isEqualTo(GIB / 2);
        assertThat(readBytes()).as("the bound retention reads").isEqualTo(GIB / 2);
    }

    /// T-160, "never in place" half: after a `[hot]` change is adopted the ring this node already holds is the same ring,
    /// with the capacity it was created with; the new window is for a ring created later. Capacity is read off the ring's
    /// control region, which is sized by it.
    @Test
    void hotChange_neverResizesTheExistingRing() {
        role.set(Role.NONE);
        manager.onStreamConfigPut(committedPut(hot(16_384L)));
        var ring = manager.partitionBuffer(STREAM, 0).unwrap();
        var controlBefore = ring.controlBytes();

        manager.onStreamConfigPut(committedPut(hot(32_768L)));

        assertThat(manager.enforcedConfig(STREAM).unwrap().declaration().unwrap().hot().unwrap().maxCount().unwrap()).as("control: the change was adopted").isEqualTo(32_768L);
        assertThat(manager.partitionBuffer(STREAM, 0).unwrap()).as("the same ring, not a rebuilt one").isSameAs(ring);
        assertThat(ring.controlBytes()).as("the same capacity").isEqualTo(controlBefore);
    }

    /// T-160, a later ring: after a promotion creates the ring of another copy, that ring has the new 32 768 entries. The
    /// ring window is the declared `[hot]` window (`StreamPartitionManager.ringWindow`), never the retention count.
    @Test
    void hotChange_ringCreatedLater_isSizedByTheHotWindow() {
        var later = ringCreatedAfterHotChange();

        assertThat(later.controlBytes()).isEqualTo(OffHeapRingBuffer.capBytes(32_768L, 0L));
    }

    private OffHeapRingBuffer ringCreatedAfterHotChange() {
        role.set(Role.NONE);
        manager.onStreamConfigPut(committedPut(hot(16_384L)));
        manager.onStreamConfigPut(committedPut(hot(32_768L)));
        assertThat(manager.partitionBuffer(STREAM, 1).isPresent()).as("control: the second copy's ring does not exist yet").isFalse();

        role.set(Role.OWNER);

        return manager.materializePartition(STREAM, 1).unwrap();
    }

    private long enforcedBytes() {
        return manager.enforcedConfig(STREAM).unwrap().retention().maxBytes();
    }

    private long readBytes() {
        return manager.committedRetention(STREAM).unwrap().policy().maxBytes();
    }

    private static RetentionPolicy bytes(long maxBytes) {
        return RetentionPolicy.retentionPolicy(COUNT, maxBytes, 3_600_000L).withDeclared(RetentionDimensions.BYTES);
    }

    private static StreamConfig config(int partitions, RetentionPolicy retention) {
        return StreamConfig.streamConfig(STREAM, partitions, retention, "earliest");
    }

    private static StreamConfig hot(long count) {
        return HotWindowFixture.declaredHot(config(2, bytes(GIB)), count, GIB);
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> committedPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }
}
