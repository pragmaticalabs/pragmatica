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
/// from the lifecycle KV-Store entry rather than the membership view diff. Subscribers that
/// distinguish between transient view changes and durable lifecycle decommissions can react to
/// the appropriate variant.
///
/// Consumers: any code that needs to react to canonical cluster membership changes — notably
/// `ClusterDeploymentManager` (workload reassignment), `ClusterTopologyManager` (capacity
/// anchoring), `LoadBalancerManager` (target table), `HttpForwarder` (routing cleanup),
/// `SliceInvoker`, `TaskAssignmentCoordinator`, `ClusterSyncCollector`,
/// `DeploymentMetricsCollector`, `ControlLoop`, `AppHttpServer`, `DHTTopologyListener`.
public sealed interface MembershipDecision extends Message.Local {
    /// The node whose membership status changed.
    NodeId nodeId();

    /// Cluster-canonical view of core members after this decision was committed.
    List<NodeId> topology();

    /// The cluster has agreed (via consensus on the membership snapshot) that this node
    /// is a core member.
    record NodeJoined(NodeId nodeId, List<NodeId> topology) implements MembershipDecision {}

    /// The cluster has agreed (via consensus on the membership snapshot) that this node
    /// is no longer a core member. This is a *view* transition; for the durable lifecycle
    /// decommission decision, see {@link NodeDecommissioned}.
    record NodeRemoved(NodeId nodeId, List<NodeId> topology) implements MembershipDecision {}

    /// The cluster has agreed (via consensus on the lifecycle KV entry) that this node is
    /// permanently decommissioned and will not be re-admitted under the single-writer
    /// DECOMMISSIONED rule. Distinct from {@link NodeRemoved} because decommission is a
    /// durable, lifecycle-level decision rather than a transient view change.
    record NodeDecommissioned(NodeId nodeId, List<NodeId> topology) implements MembershipDecision {}

    static NodeJoined nodeJoined(NodeId nodeId, List<NodeId> view) {
        return new NodeJoined(nodeId, view);
    }

    static NodeRemoved nodeRemoved(NodeId nodeId, List<NodeId> view) {
        return new NodeRemoved(nodeId, view);
    }

    static NodeDecommissioned nodeDecommissioned(NodeId nodeId, List<NodeId> view) {
        return new NodeDecommissioned(nodeId, view);
    }
}
