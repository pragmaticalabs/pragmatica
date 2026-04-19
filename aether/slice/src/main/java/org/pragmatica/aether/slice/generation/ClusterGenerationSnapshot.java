// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.serialization.Codec;

import java.util.Map;


/// Ephemeral, leader-projected view of the cluster at a specific generation epoch.
///
/// Not persisted. Distributed via pings. Consumed by all nodes as a single coherent view.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §6.
@Codec public record ClusterGenerationSnapshot(Epoch epoch,
                                               HlcTimestamp committedAt,
                                               GenerationReason reason,
                                               int desiredCoreSize,
                                               Map<NodeId, CoreMember> coreMembers,
                                               Map<String, CommunitySummary> communities,
                                               Map<String, PartitionOwner> partitions,
                                               ClusterMode derivedMode,
                                               ClusterQuiescence quiescence,
                                               String quiescenceDetail) {
    public ClusterGenerationSnapshot {
        coreMembers = Map.copyOf(coreMembers);
        communities = Map.copyOf(communities);
        partitions = Map.copyOf(partitions);
    }

    public long rabiaTerm() {
        return epoch.rabiaTerm();
    }

    public static ClusterGenerationSnapshot clusterGenerationSnapshot(Epoch epoch,
                                                                      HlcTimestamp committedAt,
                                                                      GenerationReason reason,
                                                                      int desiredCoreSize,
                                                                      Map<NodeId, CoreMember> coreMembers,
                                                                      Map<String, CommunitySummary> communities,
                                                                      Map<String, PartitionOwner> partitions,
                                                                      ClusterMode derivedMode,
                                                                      ClusterQuiescence quiescence,
                                                                      String quiescenceDetail) {
        return new ClusterGenerationSnapshot(epoch,
                                             committedAt,
                                             reason,
                                             desiredCoreSize,
                                             coreMembers,
                                             communities,
                                             partitions,
                                             derivedMode,
                                             quiescence,
                                             quiescenceDetail);
    }

    public static ClusterGenerationSnapshot empty(long rabiaTerm) {
        return new ClusterGenerationSnapshot(Epoch.epoch(rabiaTerm, 0L),
                                             HlcTimestamp.ZERO,
                                             GenerationReason.LEADER_ELECTED,
                                             0,
                                             Map.of(),
                                             Map.of(),
                                             Map.of(),
                                             ClusterMode.CORE_ONLY,
                                             ClusterQuiescence.QUIESCED,
                                             "");
    }

    public ClusterGenerationSnapshot withBumpedCounter(GenerationReason newReason) {
        return new ClusterGenerationSnapshot(epoch.nextCounter(),
                                             committedAt,
                                             newReason,
                                             desiredCoreSize,
                                             coreMembers,
                                             communities,
                                             partitions,
                                             derivedMode,
                                             quiescence,
                                             quiescenceDetail);
    }

    public ClusterGenerationSnapshot withCommittedAt(HlcTimestamp newCommittedAt) {
        return new ClusterGenerationSnapshot(epoch,
                                             newCommittedAt,
                                             reason,
                                             desiredCoreSize,
                                             coreMembers,
                                             communities,
                                             partitions,
                                             derivedMode,
                                             quiescence,
                                             quiescenceDetail);
    }

    public ClusterGenerationSnapshot withCoreMembers(Map<NodeId, CoreMember> newCoreMembers) {
        return new ClusterGenerationSnapshot(epoch,
                                             committedAt,
                                             reason,
                                             desiredCoreSize,
                                             newCoreMembers,
                                             communities,
                                             partitions,
                                             derivedMode,
                                             quiescence,
                                             quiescenceDetail);
    }

    public ClusterGenerationSnapshot withDesiredCoreSize(int newDesiredCoreSize) {
        return new ClusterGenerationSnapshot(epoch,
                                             committedAt,
                                             reason,
                                             newDesiredCoreSize,
                                             coreMembers,
                                             communities,
                                             partitions,
                                             derivedMode,
                                             quiescence,
                                             quiescenceDetail);
    }
}
