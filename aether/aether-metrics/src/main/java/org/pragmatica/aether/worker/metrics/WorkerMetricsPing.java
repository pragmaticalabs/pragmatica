// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.aether.slice.generation.CommunityGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;


/// Ping from governor to followers requesting metrics.
///
/// Extended in Commit 3 to carry community term/epoch, the governor's last-
/// observed core epoch, and an optional `CommunityGenerationSnapshot`.
/// The snapshot is `Some(...)` on community epoch advance and `None` on
/// steady-state heartbeat.
///
/// @param sender              governor node ID
/// @param timestampMs         when the ping was sent (wall clock)
/// @param communityTerm       strictly monotonic community-scope term
/// @param communityEpoch      current community epoch `(communityTerm, counter)`
/// @param observedCoreEpoch   governor's most-recently-seen core epoch
/// @param snapshot            optional community generation snapshot
@Codec public record WorkerMetricsPing(NodeId sender,
                                       long timestampMs,
                                       long communityTerm,
                                       Epoch communityEpoch,
                                       Epoch observedCoreEpoch,
                                       Option<CommunityGenerationSnapshot> snapshot) implements Message.Wired {
    public WorkerMetricsPing {
        if (communityEpoch == null) {communityEpoch = Epoch.ZERO;}
        if (observedCoreEpoch == null) {observedCoreEpoch = Epoch.ZERO;}
        if (snapshot == null) {snapshot = Option.none();}
    }

    public static WorkerMetricsPing workerMetricsPing(NodeId sender,
                                                      long timestampMs,
                                                      long communityTerm,
                                                      Epoch communityEpoch,
                                                      Epoch observedCoreEpoch,
                                                      Option<CommunityGenerationSnapshot> snapshot) {
        return new WorkerMetricsPing(sender, timestampMs, communityTerm, communityEpoch, observedCoreEpoch, snapshot);
    }

    public static WorkerMetricsPing workerMetricsPing(NodeId sender, long timestampMs) {
        return new WorkerMetricsPing(sender, timestampMs, 0L, Epoch.ZERO, Epoch.ZERO, Option.none());
    }

    public static WorkerMetricsPing workerMetricsPing(NodeId sender) {
        return new WorkerMetricsPing(sender, System.currentTimeMillis(), 0L, Epoch.ZERO, Epoch.ZERO, Option.none());
    }
}
