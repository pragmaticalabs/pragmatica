// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.StreamType;
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
/// `CommunityReport` piggybacks. The metrics ping channel is metrics-only; cluster
/// membership/generation state is derived live from the per-node membership FSM, not
/// carried on this channel.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §7.
@Codec
public sealed interface ClusterSyncMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.METRICS;
    }

    /// Cluster-sync ping sent by the Rabia leader to core members (Tier 1) or
    /// by a Spokesman core node to its assigned community governors (Tier 2).
    ///
    /// @param sender                 ping originator
    /// @param allMetrics             existing per-node metrics map (heartbeat payload)
    /// @param rabiaTerm              current Rabia consensus term — receivers reject stale
    /// @param epochTerm              epoch's `rabiaTerm` part (duplicated for fencing)
    /// @param epochCounter           epoch's `localCounter` part
    /// @param drainNodes             GLOBAL set of nodes the leader is commanding to DRAIN. One
    ///                               uniform ping is BROADCAST to every QUIC-connected peer; each
    ///                               receiver self-checks membership (`drainNodes.contains(self)`)
    ///                               and triggers its local (CAS-guarded, idempotent)
    ///                               `DrainProcedure` when present. Mirrors `evictionHints`: a
    ///                               global advisory set rather than a per-peer command. `null`/legacy
    ///                               pings default to the empty set.
    /// @param readinessView          leader-authoritative `NodeId → lifecycle-state` view (the values
    ///                               are the plain `NodeReportedState` names — "READY"/"SYNCING"/
    ///                               "DRAINING" — to keep this module decoupled from the aether-metrics
    ///                               enum, exactly as the pong's `lifecycleState` field already does).
    ///                               Populated only on the LEADER's broadcast ping; followers cache it
    ///                               (leader+term-gated, TTL) so per-node readiness is queryable from
    ///                               any node and not just the leader. `null`/legacy pings and
    ///                               non-leader pings default to the empty map.
    /// @param dispatchedNodes        GLOBAL set of node ids the leader currently tracks as IN-FLIGHT
    ///                               provisioning (dispatched-but-not-yet-joined replacements). The
    ///                               leader broadcasts its in-flight set so followers retain it STICKILY
    ///                               (term-fenced, NOT TTL). On leader change the new leader SEEDS its
    ///                               in-flight provisioning set from this retained set instead of
    ///                               starting empty — preventing re-dispatch (over-provisioning) of
    ///                               replacements already provisioned by the prior leader. Mirrors
    ///                               `drainNodes` / `evictionHints`: a global advisory set. `null`/legacy
    ///                               pings and non-leader pings default to the empty set.
    record ClusterSyncPing(NodeId sender,
                           Map<NodeId, Map<String, Double>> allMetrics,
                           long rabiaTerm,
                           long epochTerm,
                           long epochCounter,
                           Set<NodeId> evictionHints,
                           Set<NodeId> drainNodes,
                           Map<NodeId, String> readinessView,
                           Set<NodeId> dispatchedNodes) implements ClusterSyncMessage {
        public ClusterSyncPing {
            evictionHints = evictionHints == null
                           ? Set.of()
                           : Set.copyOf(evictionHints);
            drainNodes = drainNodes == null
                        ? Set.of()
                        : Set.copyOf(drainNodes);
            readinessView = readinessView == null
                           ? Map.of()
                           : Map.copyOf(readinessView);
            dispatchedNodes = dispatchedNodes == null
                             ? Set.of()
                             : Set.copyOf(dispatchedNodes);
        }

        /// Backward-compatible constructor for call sites that pre-date the `dispatchedNodes`
        /// extension. Defaults the dispatched set to the empty set.
        public ClusterSyncPing(NodeId sender,
                               Map<NodeId, Map<String, Double>> allMetrics,
                               long rabiaTerm,
                               long epochTerm,
                               long epochCounter,
                               Set<NodeId> evictionHints,
                               Set<NodeId> drainNodes,
                               Map<NodeId, String> readinessView) {
            this(sender, allMetrics, rabiaTerm, epochTerm, epochCounter, evictionHints, drainNodes, readinessView, Set.of());
        }

        /// Backward-compatible constructor for call sites that pre-date the `readinessView`
        /// extension. Defaults the view to the empty map and dispatched set to the empty set.
        public ClusterSyncPing(NodeId sender,
                               Map<NodeId, Map<String, Double>> allMetrics,
                               long rabiaTerm,
                               long epochTerm,
                               long epochCounter,
                               Set<NodeId> evictionHints,
                               Set<NodeId> drainNodes) {
            this(sender, allMetrics, rabiaTerm, epochTerm, epochCounter, evictionHints, drainNodes, Map.of(), Set.of());
        }

        /// Backward-compatible factory for legacy call sites that have no epoch info.
        /// Produces a term/epoch of zero — receivers treat it as a pre-migration heartbeat.
        public static ClusterSyncPing clusterSyncPing(NodeId sender, Map<NodeId, Map<String, Double>> allMetrics) {
            return new ClusterSyncPing(sender, allMetrics, 0L, 0L, 0L, Set.of(), Set.of(), Map.of(), Set.of());
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
    /// @param lifecycleState      sender's current `NodeReportedState` as a plain string
    ///                            (cluster/ module stays decoupled from aether enums)
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
    /// @param incarnation         per-incarnation discriminator (changes on process restart, distinct
    ///                            from `NodeId`) so the leader rejects a stale prior-incarnation pong
    ///                            and never misattributes a fast-restart `DRAINING → SYNCING` flip to
    ///                            one continuous life. `0L` for pre-migration peers. See
    ///                            membership-architecture-v2-spec §7.5.3 / I14.
    record ClusterSyncPong(NodeId sender,
                           Map<String, Double> metrics,
                           long observedRabiaTerm,
                           long observedEpochTerm,
                           long observedEpochCounter,
                           String lifecycleState,
                           List<CommunityReport> communityReports,
                           List<PeerHealthObservation> peerHealth,
                           List<PeerConnectivityObservation> peerConnectivity,
                           Option<NodeId> readyCandidate,
                           long incarnation) implements ClusterSyncMessage {
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
        /// Defaults the candidate field to `Option.none()` and incarnation to `0L`.
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
                 lifecycleState, communityReports, peerHealth, peerConnectivity, Option.none(), 0L);
        }

        /// Backward-compatible 10-arg constructor for call sites that pre-date the
        /// `incarnation` extension (membership-architecture-v2-spec §7.5.3). Defaults
        /// incarnation to `0L` so existing deserialization / call sites do not break.
        public ClusterSyncPong(NodeId sender,
                               Map<String, Double> metrics,
                               long observedRabiaTerm,
                               long observedEpochTerm,
                               long observedEpochCounter,
                               String lifecycleState,
                               List<CommunityReport> communityReports,
                               List<PeerHealthObservation> peerHealth,
                               List<PeerConnectivityObservation> peerConnectivity,
                               Option<NodeId> readyCandidate) {
            this(sender, metrics, observedRabiaTerm, observedEpochTerm, observedEpochCounter,
                 lifecycleState, communityReports, peerHealth, peerConnectivity, readyCandidate, 0L);
        }

        /// Backward-compatible factory for legacy call sites. Emits zero epoch,
        /// empty lifecycle, no community reports, no peer observations, zero incarnation.
        public static ClusterSyncPong clusterSyncPong(NodeId sender, Map<String, Double> metrics) {
            return new ClusterSyncPong(sender, metrics, 0L, 0L, 0L, "", List.of(), List.of(), List.of(), Option.none(), 0L);
        }
    }
}
