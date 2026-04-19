// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import java.util.List;
import java.util.Map;

/// Messages for metrics exchange between nodes.
/// Uses Ping-Pong pattern: leader pings nodes with aggregated cluster metrics,
/// nodes respond with their own metrics.
///
/// Extended in Commit 3 of the ClusterGeneration rollout to carry ephemeral
/// generation snapshots, Rabia-term / epoch fencing, observed lifecycle state,
/// and per-community `CommunityReport` piggybacks.
///
/// `ClusterGenerationSnapshot` lives in `aether/slice` which this module does
/// not depend on. The snapshot is therefore encoded here as an opaque
/// `byte[]` payload — producers serialize via Fory at the boundary, and
/// consumers deserialize on the receiving side. The wire shape is carried as
/// `Option<SnapshotPayload>` so the ping can omit the payload entirely during
/// steady-state heartbeats (§7.5).
///
/// See `aether/docs/specs/cluster-generation-spec.md` §7.
@Codec
public sealed interface MetricsMessage extends ProtocolMessage {
    /// Opaque, self-describing snapshot payload. Contents are a serialized
    /// `ClusterGenerationSnapshot` (core-only knowledge); consumers decode
    /// against their locally-imported `aether/slice` types.
    @Codec record SnapshotPayload(byte[] bytes) {
        public SnapshotPayload {
            if (bytes == null) {bytes = new byte[0];}
        }

        public static SnapshotPayload snapshotPayload(byte[] bytes) {
            return new SnapshotPayload(bytes);
        }
    }

    /// Metrics ping sent by the Rabia leader to core members (Tier 1) or by a
    /// Spokesman core node to its assigned community governors (Tier 2).
    ///
    /// @param sender          ping originator
    /// @param allMetrics      existing per-node metrics map (heartbeat payload)
    /// @param rabiaTerm       current Rabia consensus term — receivers reject stale
    /// @param epochTerm       epoch's `rabiaTerm` part (duplicated for fencing)
    /// @param epochCounter    epoch's `localCounter` part
    /// @param snapshot        `Some(payload)` on epoch change; `None` on heartbeat
    record MetricsPing(NodeId sender,
                       Map<NodeId, Map<String, Double>> allMetrics,
                       long rabiaTerm,
                       long epochTerm,
                       long epochCounter,
                       Option<SnapshotPayload> snapshot) implements MetricsMessage {
        public MetricsPing {
            if (snapshot == null) {snapshot = Option.none();}
        }

        /// Backward-compatible factory for legacy call sites that have no epoch info.
        /// Produces a term/epoch of zero and no snapshot — receivers treat it as a
        /// pre-migration heartbeat.
        public static MetricsPing metricsPing(NodeId sender, Map<NodeId, Map<String, Double>> allMetrics) {
            return new MetricsPing(sender, allMetrics, 0L, 0L, 0L, Option.none());
        }
    }

    /// Metrics pong returned by core members to the leader (Tier 1) or by governors
    /// to their assigned Spokesman core node (Tier 2).
    ///
    /// @param sender              pong originator
    /// @param metrics             existing per-node metrics map
    /// @param observedRabiaTerm   sender's last-accepted Rabia term
    /// @param observedEpochTerm   sender's last-accepted epoch's term
    /// @param observedEpochCounter sender's last-accepted epoch's counter
    /// @param lifecycleState      sender's current `NodeLifecycleState` as a plain string
    ///                            (cluster/ module stays decoupled from aether/slice enums)
    /// @param communityReports    Spokesman core nodes aggregate assigned communities
    ///                            here; empty otherwise
    record MetricsPong(NodeId sender,
                       Map<String, Double> metrics,
                       long observedRabiaTerm,
                       long observedEpochTerm,
                       long observedEpochCounter,
                       String lifecycleState,
                       List<CommunityReport> communityReports) implements MetricsMessage {
        public MetricsPong {
            if (lifecycleState == null) {lifecycleState = "";}
            communityReports = communityReports == null
                              ? List.of()
                              : List.copyOf(communityReports);
        }

        /// Backward-compatible factory for legacy call sites. Emits zero epoch,
        /// empty lifecycle, no community reports.
        public static MetricsPong metricsPong(NodeId sender, Map<String, Double> metrics) {
            return new MetricsPong(sender, metrics, 0L, 0L, 0L, "", List.of());
        }
    }
}
