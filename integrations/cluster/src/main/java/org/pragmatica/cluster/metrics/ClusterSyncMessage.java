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
import java.util.Set;

/// Tier 1 cluster-sync wire protocol — the ping/pong chain carrying lifecycle,
/// metrics, and peer observations between the Rabia leader and core members.
///
/// Uses a Ping-Pong pattern: the leader pings every core member with aggregated
/// metrics; each node responds with a pong containing its own metrics, lifecycle
/// state, and observed epoch.
///
/// Carries Rabia-term / epoch fencing, observed lifecycle state, and per-community
/// `CommunityReport` piggybacks. Cluster-generation snapshots are delivered via the
/// KV-Store (`GenerationSnapshotKey`); the metrics ping channel is metrics-only.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §7.
@Codec
public sealed interface ClusterSyncMessage extends ProtocolMessage {
    /// Cluster-sync ping sent by the Rabia leader to core members (Tier 1) or
    /// by a Spokesman core node to its assigned community governors (Tier 2).
    ///
    /// @param sender                 ping originator
    /// @param allMetrics             existing per-node metrics map (heartbeat payload)
    /// @param rabiaTerm              current Rabia consensus term — receivers reject stale
    /// @param epochTerm              epoch's `rabiaTerm` part (duplicated for fencing)
    /// @param epochCounter           epoch's `localCounter` part
    /// @param aggregatedReachability leader-derived cluster-canonical reachability snapshot;
    ///                               `Option.none()` during cold-start window and pre-extension
    ///                               peers. Followers cache for warm-takeover; `/api/status`
    ///                               reads to eliminate per-reader QUIC-view variance. See
    ///                               `aether/docs/specs/reachability-aggregator-spec.md`.
    record ClusterSyncPing(NodeId sender,
                           Map<NodeId, Map<String, Double>> allMetrics,
                           long rabiaTerm,
                           long epochTerm,
                           long epochCounter,
                           Option<AggregatedReachabilitySnapshot> aggregatedReachability,
                           Set<NodeId> evictionHints) implements ClusterSyncMessage {
        public ClusterSyncPing {
            aggregatedReachability = aggregatedReachability == null
                                    ? Option.none()
                                    : aggregatedReachability;
            evictionHints = evictionHints == null
                           ? Set.of()
                           : Set.copyOf(evictionHints);
        }

        /// Backward-compatible 6-arg constructor for call sites that pre-date the
        /// `evictionHints` extension (RC1 S01 fix). Defaults the hint set to empty.
        public ClusterSyncPing(NodeId sender,
                               Map<NodeId, Map<String, Double>> allMetrics,
                               long rabiaTerm,
                               long epochTerm,
                               long epochCounter,
                               Option<AggregatedReachabilitySnapshot> aggregatedReachability) {
            this(sender, allMetrics, rabiaTerm, epochTerm, epochCounter, aggregatedReachability, Set.of());
        }

        /// Backward-compatible factory for legacy call sites that have no epoch info.
        /// Produces a term/epoch of zero — receivers treat it as a pre-migration heartbeat.
        public static ClusterSyncPing clusterSyncPing(NodeId sender, Map<NodeId, Map<String, Double>> allMetrics) {
            return new ClusterSyncPing(sender, allMetrics, 0L, 0L, 0L, Option.none(), Set.of());
        }
    }

    /// Cluster-sync pong returned by core members to the leader (Tier 1) or by
    /// governors to their assigned Spokesman core node (Tier 2).
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
    /// @param peerHealth          per-peer SWIM health observations collected by the sender
    ///                            since its last pong; empty on leaders and during steady state
    /// @param peerConnectivity    per-peer QUIC connectivity observations collected by the
    ///                            sender since its last pong; empty on leaders and during steady state
    /// @param readyCandidate      set by a non-leader node that has just received and applied a full
    ///                            KV-sync snapshot from the leader. Leader-side handler emits
    ///                            `LifecycleCommand.ForceOnDuty` for the candidate so the lifecycle
    ///                            transition `JOINING → ON_DUTY` is recorded through the audit-routed
    ///                            command path. `Option.none()` on the leader's own outgoing pongs
    ///                            and during steady state. See cluster-convergence-reconciler-spec
    ///                            §SYNCING.
    record ClusterSyncPong(NodeId sender,
                           Map<String, Double> metrics,
                           long observedRabiaTerm,
                           long observedEpochTerm,
                           long observedEpochCounter,
                           String lifecycleState,
                           List<CommunityReport> communityReports,
                           List<PeerHealthObservation> peerHealth,
                           List<PeerConnectivityObservation> peerConnectivity,
                           Option<NodeId> readyCandidate) implements ClusterSyncMessage {
        public ClusterSyncPong {
            if (lifecycleState == null) {lifecycleState = "";}
            communityReports = communityReports == null
                              ? List.of()
                              : List.copyOf(communityReports);
            peerHealth = peerHealth == null
                        ? List.of()
                        : List.copyOf(peerHealth);
            peerConnectivity = peerConnectivity == null
                              ? List.of()
                              : List.copyOf(peerConnectivity);
            readyCandidate = readyCandidate == null
                            ? Option.none()
                            : readyCandidate;
        }

        /// Backward-compatible 9-arg constructor for call sites that pre-date the
        /// `readyCandidate` extension (Phase 2 PR-B of cluster-convergence-reconciler).
        /// Defaults the candidate field to `Option.none()`.
        public ClusterSyncPong(NodeId sender,
                               Map<String, Double> metrics,
                               long observedRabiaTerm,
                               long observedEpochTerm,
                               long observedEpochCounter,
                               String lifecycleState,
                               List<CommunityReport> communityReports,
                               List<PeerHealthObservation> peerHealth,
                               List<PeerConnectivityObservation> peerConnectivity) {
            this(sender, metrics, observedRabiaTerm, observedEpochTerm, observedEpochCounter,
                 lifecycleState, communityReports, peerHealth, peerConnectivity, Option.none());
        }

        /// Backward-compatible factory for legacy call sites. Emits zero epoch,
        /// empty lifecycle, no community reports, no peer observations.
        public static ClusterSyncPong clusterSyncPong(NodeId sender, Map<String, Double> metrics) {
            return new ClusterSyncPong(sender, metrics, 0L, 0L, 0L, "", List.of(), List.of(), List.of(), Option.none());
        }
    }
}
