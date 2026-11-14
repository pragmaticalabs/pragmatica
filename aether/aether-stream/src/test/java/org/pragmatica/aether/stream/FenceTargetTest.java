// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.FenceTarget.NoFence;
import org.pragmatica.aether.stream.FenceTarget.Probe;
import org.pragmatica.aether.stream.FenceTarget.Unknown;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #2981 step 3 (design §2.3, §8, model action `GateProbe`): the fence target is the owner of the newest STARTED epoch older
/// than the record's own, read from `epochStarts` alone.
class FenceTargetTest {
    private static final NodeId SELF = NodeId.nodeId("node-3").unwrap();
    private static final NodeId OLDER = NodeId.nodeId("node-1").unwrap();
    private static final NodeId NEWEST = NodeId.nodeId("node-2").unwrap();
    private static final NodeId OUTSIDE = NodeId.nodeId("node-9").unwrap();

    static StreamPartitionOwnershipValue ownedBy(NodeId owner, long counter, List<NodeId> isr, List<EpochStart> starts) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner,
                                                                           epoch(counter),
                                                                           counter,
                                                                           HlcTimestamp.ZERO,
                                                                           isr,
                                                                           1L,
                                                                           List.of(),
                                                                           starts);
    }

    static Epoch epoch(long counter) {
        return Epoch.epoch(1L, 1L, counter);
    }

    static EpochStart start(long counter, Option<NodeId> owner) {
        return EpochStart.epochStart(epoch(counter), counter * 10, owner);
    }

    @Test
    void fenceTarget_isTheOwnerOfTheNewestStartedOlderEpoch() {
        var record = ownedBy(SELF,
                             5L,
                             List.of(SELF, OLDER, NEWEST),
                             List.of(start(1L, Option.some(OLDER)), start(3L, Option.some(NEWEST))));

        assertThat(FenceTarget.fenceTarget(record, SELF)).isEqualTo(Probe.probe(NEWEST));
    }

    @Test
    void fenceTarget_ignoresTheRecordsOwnEpochStart_onAReGate() {
        var record = ownedBy(SELF,
                             5L,
                             List.of(SELF, NEWEST),
                             List.of(start(3L, Option.some(NEWEST)), start(5L, Option.some(SELF))));

        assertThat(FenceTarget.fenceTarget(record, SELF)).as("the record's own epoch is not older than itself")
                                                          .isEqualTo(Probe.probe(NEWEST));
    }

    @Test
    void fenceTarget_isProbedEvenOutsideTheLivePlacementSet() {
        var record = ownedBy(SELF, 5L, List.of(SELF, NEWEST), List.of(start(3L, Option.some(OUTSIDE))));

        assertThat(FenceTarget.fenceTarget(record, SELF)).as("L comes from epochStarts, never from the placement or the ISR")
                                                          .isEqualTo(Probe.probe(OUTSIDE));
    }

    @Test
    void fenceTarget_missingOwner_isUnknown_soTheFenceWaitsTheFullDeadline() {
        var record = ownedBy(SELF,
                             5L,
                             List.of(SELF),
                             List.of(start(1L, Option.some(OLDER)), start(3L, Option.none())));

        assertThat(FenceTarget.fenceTarget(record, SELF)).isEqualTo(Unknown.unknown());
    }

    @Test
    void fenceTarget_ownOlderEpoch_needsNoFence() {
        var record = ownedBy(SELF, 5L, List.of(SELF), List.of(start(3L, Option.some(SELF))));

        assertThat(FenceTarget.fenceTarget(record, SELF)).isEqualTo(NoFence.noFence());
    }

    @Test
    void fenceTarget_noStartedOlderEpoch_needsNoFence() {
        var record = ownedBy(SELF, 5L, List.of(SELF), List.of(start(5L, Option.some(SELF))));

        assertThat(FenceTarget.fenceTarget(record, SELF)).isEqualTo(NoFence.noFence());
        assertThat(FenceTarget.fenceTarget(ownedBy(SELF, 5L, List.of(SELF), List.of()), SELF)).isEqualTo(NoFence.noFence());
    }
}
