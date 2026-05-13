/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.consensus.topology;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.messaging.Message;

import java.util.List;

/// Cluster-canonical decisions about membership, projected from the consensus-committed
/// {@code MembershipView} snapshot.
///
/// Epistemically distinct from {@link TransportObservation}: a `MembershipDecision` is a *global*
/// fact ("the cluster has agreed peer X has joined") whereas a `TransportObservation` is a *local*
/// fact ("I observed peer X's QUIC channel come up"). Conflating the two has caused subtle bugs
/// in the past — code reacting to transport flaps as if they were cluster decisions, and vice versa.
///
/// Properties of this stream:
/// - **Global.** Reflects cluster-wide consensus, not any single node's observation.
/// - **Authoritative.** Once a `MembershipDecision` fires, the cluster has agreed via consensus on
///   this fact. Subscribers can rely on it for canonical reactions (workload reassignment,
///   capacity planning, routing-table updates).
/// - **Eventually consistent.** Slower than transport observations because consensus must commit
///   before the projection updates. Subscribers that need fast local reactions during cluster
///   bootstrap (before consensus exists) should consume {@link TransportObservation} instead.
/// - **Idempotent at projection.** The diff is computed from the prior committed state, so
///   duplicate emissions for the same decision do not occur.
///
/// Producer: `TopologyObserver.publishMembershipDeltas` is the *exclusive* emitter. Single-source-
/// of-truth for membership decisions is part of this contract.
///
/// Note on lifecycle: `NodeJoined` and `NodeRemoved` reflect membership *view* transitions
/// (whether the peer is in the cluster's `coreMemberIds` set). `NodeDecommissioned` is a
/// specialised decision for the canonical "this node is permanently leaving" state, projected
/// from the lifecycle KV-Store entry rather than the membership view diff. `NodeJoining`,
/// `NodeDraining`, and `NodeFailedDrain` are RC1 lifecycle-projection variants emitted by the
/// parallel lifecycle-projection walker in `publishMembershipDeltas` — they correspond to the
/// per-node `NodeLifecycleState` transitions JOINING / DRAINING / FAILED_DRAIN respectively.
/// Subscribers that distinguish between transient view changes and durable lifecycle decisions
/// can react to the appropriate variant.
///
/// RC1 Step 2 added two cross-cutting metadata fields to every variant:
/// - `logIndex` — Rabia commit index of the underlying KV snapshot used by `TopologyObserver`.
///   Sentinel `-1L` indicates cold-replay (no committed index reconstructible at the emission
///   site). Subscribers that read `logIndex` for dedup MUST guard against `-1L`.
/// - `stampedAt` — HLC timestamp captured at emission time (from `HlcClock.now()`). Carries
///   causal ordering across nodes. `HlcTimestamp.ZERO` is the cold-replay / unwired sentinel.
///
/// Consumers: any code that needs to react to canonical cluster membership changes — notably
/// `ClusterDeploymentManager` (workload reassignment), `ClusterTopologyManager` (capacity
/// anchoring), `LoadBalancerManager` (target table), `HttpForwarder` (routing cleanup),
/// `SliceInvoker`, `TaskAssignmentCoordinator`, `ClusterSyncCollector`,
/// `DeploymentMetricsCollector`, `ControlLoop`, `AppHttpServer`, `DHTTopologyListener`.
public sealed interface MembershipDecision extends Message.Local {
    /// Sentinel `logIndex` for cold-replay or unwired emission sites. Subscribers reading
    /// `logIndex` for dedup MUST guard against this value.
    long UNKNOWN_LOG_INDEX = -1L;

    /// The node whose membership status changed.
    NodeId nodeId();

    /// Cluster-canonical view of core members after this decision was committed.
    List<NodeId> topology();

    /// Rabia commit index of the underlying KV snapshot for dedup / ordering. `-1L` indicates
    /// cold-replay where no committed index is reconstructible — subscribers MUST guard.
    long logIndex();

    /// HLC timestamp captured at emission time. `HlcTimestamp.ZERO` indicates cold-replay /
    /// unwired sentinel.
    HlcTimestamp stampedAt();

    /// The cluster has agreed (via consensus on the membership snapshot) that this node
    /// is a core member.
    record NodeJoined(NodeId nodeId, List<NodeId> topology, long logIndex, HlcTimestamp stampedAt) implements MembershipDecision {}

    /// The cluster has agreed (via consensus on the membership snapshot) that this node
    /// is no longer a core member. This is a *view* transition; for the durable lifecycle
    /// decommission decision, see {@link NodeDecommissioned}.
    record NodeRemoved(NodeId nodeId, List<NodeId> topology, long logIndex, HlcTimestamp stampedAt) implements MembershipDecision {}

    /// The cluster has agreed (via consensus on the lifecycle KV entry) that this node is
    /// permanently decommissioned and will not be re-admitted under the single-writer
    /// DECOMMISSIONED rule. Distinct from {@link NodeRemoved} because decommission is a
    /// durable, lifecycle-level decision rather than a transient view change.
    record NodeDecommissioned(NodeId nodeId, List<NodeId> topology, long logIndex, HlcTimestamp stampedAt) implements MembershipDecision {}

    /// RC1 lifecycle-projection variant: the node's `NodeLifecycleValue.state` has transitioned
    /// into `JOINING` — provisional admission, not yet fully ON_DUTY. Subscribers that previously
    /// listened to `onNodeLifecyclePut` for the JOINING state consume this variant instead.
    record NodeJoining(NodeId nodeId, List<NodeId> topology, long logIndex, HlcTimestamp stampedAt) implements MembershipDecision {}

    /// RC1 lifecycle-projection variant: the node's `NodeLifecycleValue.state` has transitioned
    /// into `DRAINING` — scheduled departure, slices being evicted. Subscribers that previously
    /// listened to `onNodeLifecyclePut` for the DRAINING state consume this variant instead.
    record NodeDraining(NodeId nodeId, List<NodeId> topology, long logIndex, HlcTimestamp stampedAt) implements MembershipDecision {}

    /// RC1 lifecycle-projection variant: the node's `NodeLifecycleValue.state` has transitioned
    /// into `FAILED_DRAIN` — drain timed out or aborted, operator intervention may be required.
    record NodeFailedDrain(NodeId nodeId, List<NodeId> topology, long logIndex, HlcTimestamp stampedAt) implements MembershipDecision {}

    /// RC1 lifecycle-projection variant: the node's `NodeLifecycleValue.state` has transitioned
    /// into `SHUTTING_DOWN`. Emitted by `TopologyObserver`'s lifecycle walker so node-local
    /// subscribers (`NodeDeploymentManager`) can react to operator-initiated remote shutdown
    /// after the dual-channel KV-put listener was retired. Distinct from
    /// `TransportObservation.SelfShutdown` (which is the local-transport notification raised
    /// when the JVM begins shutting down its own process).
    record NodeShuttingDown(NodeId nodeId, List<NodeId> topology, long logIndex, HlcTimestamp stampedAt) implements MembershipDecision {}

    static NodeJoined nodeJoined(NodeId nodeId, List<NodeId> view) {
        return new NodeJoined(nodeId, view, UNKNOWN_LOG_INDEX, HlcTimestamp.ZERO);
    }

    static NodeJoined nodeJoined(NodeId nodeId, List<NodeId> view, long logIndex, HlcTimestamp stampedAt) {
        return new NodeJoined(nodeId, view, logIndex, stampedAt);
    }

    static NodeRemoved nodeRemoved(NodeId nodeId, List<NodeId> view) {
        return new NodeRemoved(nodeId, view, UNKNOWN_LOG_INDEX, HlcTimestamp.ZERO);
    }

    static NodeRemoved nodeRemoved(NodeId nodeId, List<NodeId> view, long logIndex, HlcTimestamp stampedAt) {
        return new NodeRemoved(nodeId, view, logIndex, stampedAt);
    }

    static NodeDecommissioned nodeDecommissioned(NodeId nodeId, List<NodeId> view) {
        return new NodeDecommissioned(nodeId, view, UNKNOWN_LOG_INDEX, HlcTimestamp.ZERO);
    }

    static NodeDecommissioned nodeDecommissioned(NodeId nodeId, List<NodeId> view, long logIndex, HlcTimestamp stampedAt) {
        return new NodeDecommissioned(nodeId, view, logIndex, stampedAt);
    }

    static NodeJoining nodeJoining(NodeId nodeId, List<NodeId> view) {
        return new NodeJoining(nodeId, view, UNKNOWN_LOG_INDEX, HlcTimestamp.ZERO);
    }

    static NodeJoining nodeJoining(NodeId nodeId, List<NodeId> view, long logIndex, HlcTimestamp stampedAt) {
        return new NodeJoining(nodeId, view, logIndex, stampedAt);
    }

    static NodeDraining nodeDraining(NodeId nodeId, List<NodeId> view) {
        return new NodeDraining(nodeId, view, UNKNOWN_LOG_INDEX, HlcTimestamp.ZERO);
    }

    static NodeDraining nodeDraining(NodeId nodeId, List<NodeId> view, long logIndex, HlcTimestamp stampedAt) {
        return new NodeDraining(nodeId, view, logIndex, stampedAt);
    }

    static NodeFailedDrain nodeFailedDrain(NodeId nodeId, List<NodeId> view) {
        return new NodeFailedDrain(nodeId, view, UNKNOWN_LOG_INDEX, HlcTimestamp.ZERO);
    }

    static NodeFailedDrain nodeFailedDrain(NodeId nodeId, List<NodeId> view, long logIndex, HlcTimestamp stampedAt) {
        return new NodeFailedDrain(nodeId, view, logIndex, stampedAt);
    }

    static NodeShuttingDown nodeShuttingDown(NodeId nodeId, List<NodeId> view) {
        return new NodeShuttingDown(nodeId, view, UNKNOWN_LOG_INDEX, HlcTimestamp.ZERO);
    }

    static NodeShuttingDown nodeShuttingDown(NodeId nodeId, List<NodeId> view, long logIndex, HlcTimestamp stampedAt) {
        return new NodeShuttingDown(nodeId, view, logIndex, stampedAt);
    }
}
