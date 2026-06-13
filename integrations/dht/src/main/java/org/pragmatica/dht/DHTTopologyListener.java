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

package org.pragmatica.dht;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Listens for membership decisions and updates the DHT ring accordingly.
/// Triggers data migration when partitions move between nodes.
///
/// Consumes {@link MembershipDecision} (the cluster-canonical stream) for ring
/// updates so the DHT view matches consensus-committed membership. Also subscribes
/// to {@link TransportObservation.SelfShutdown} for self-cleanup, since self-
/// shutdown is not a cluster decision and never reaches `MembershipDecision`.
public final class DHTTopologyListener {
    private static final Logger log = LoggerFactory.getLogger(DHTTopologyListener.class);

    private final DHTNode node;
    private final Option<DHTRebalancer> rebalancer;

    private DHTTopologyListener(DHTNode node, Option<DHTRebalancer> rebalancer) {
        this.node = node;
        this.rebalancer = rebalancer;
    }

    /// Create a topology listener for the given DHT node without rebalancer.
    ///
    /// @param node the local DHT node whose ring will be updated
    public static DHTTopologyListener dhtTopologyListener(DHTNode node) {
        return new DHTTopologyListener(node, Option.none());
    }

    /// Create a topology listener for the given DHT node with rebalancer.
    ///
    /// @param node       the local DHT node whose ring will be updated
    /// @param rebalancer rebalancer to trigger re-replication on node departure
    public static DHTTopologyListener dhtTopologyListener(DHTNode node, DHTRebalancer rebalancer) {
        return new DHTTopologyListener(node, Option.some(rebalancer));
    }

    /// Handle a node-joined decision by adding the node to the consistent hash ring.
    @Contract
    public void onNodeJoined(MembershipDecision.NodeJoined event) {
        var addedNodeId = event.nodeId();
        log.info("DHT: Node added {}, updating ring", addedNodeId.id());
        node.ring()
            .addNode(addedNodeId);
    }

    /// Handle a node-removed decision by removing the node from the consistent hash ring
    /// and triggering rebalance to restore replication factor.
    @Contract
    public void onNodeRemoved(MembershipDecision.NodeRemoved event) {
        removeFromRing(event.nodeId());
    }

    /// Handle a node-decommissioned decision identically to node-removed for ring purposes.
    @Contract
    public void onNodeDecommissioned(MembershipDecision.NodeDecommissioned event) {
        removeFromRing(event.nodeId());
    }

    /// Prune a node from the ring at the moment it ENTERS the membership `Departing` state
    /// (seed-500 part 2). A drained node has been commanded to halt and will `Runtime.halt` within
    /// the grace window; pruning it from the ring here — at the deliberate drain-command edge,
    /// seconds AHEAD of the SWIM-driven `MembershipDecision.NodeRemoved` DEAD-edge — re-replicates
    /// the keys it owned to LIVE replicas in time for an in-flight write's bounded `ArtifactStore`
    /// retry to reach quorum on live owners (without it the retried write hammers the still-stale
    /// ring and 500s). Reuses the SAME ring-prune + rebalance ([`#removeFromRing`]) as the DEAD
    /// edge, and is strictly idempotent with it (the ring's `removeNode` is a no-op for an
    /// already-absent node), so the node is pruned once per drain regardless of which edge fires
    /// first.
    ///
    /// SAFETY: invoked ONLY off the membership FSM's fresh-edge-into-`Departing` callback, reached
    /// solely via the deliberate-departure transitions (drain command / graceful leave /
    /// sustained-absence down-hysteresis). The transient transport-disconnect edge that caused the
    /// reverted rebalance storm never reaches `Departing` — see the not-wired note below — so this
    /// seam cannot reintroduce the per-flap storm.
    @Contract
    public void onNodeDeparting(NodeId departingNodeId) {
        log.info("DHT: Node {} entering DEPARTING, pruning ring ahead of halt", departingNodeId.id());
        removeFromRing(departingNodeId);
    }

    /// Re-add a node to the ring when it RECOVERS out of the membership `Departing` state
    /// (seed-500 part 2, symmetry fix) — the symmetric counterpart to [`#onNodeDeparting`]. A node
    /// pruned on its DEPARTING edge that then refutes the drain (a strictly-newer-incarnation
    /// recovery, DEPARTING→MEMBER) emits NO `MembershipDecision.NodeJoined` (it was already JOINED),
    /// so the normal [`#onNodeJoined`] ring RE-ADD never runs and the node would be left missing
    /// from the ring → under-replication. This re-adds it via the SAME `ConsistentHashRing.addNode`
    /// seam [`#onNodeJoined`] uses, which is idempotent (a no-op for a node already in the ring), so
    /// it is harmless even if a `NodeJoined` also fires.
    @Contract
    public void onNodeRecovered(NodeId recoveredNodeId) {
        log.info("DHT: Node {} recovered from DEPARTING, re-adding to ring", recoveredNodeId.id());
        node.ring()
            .addNode(recoveredNodeId);
    }

    /// Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown
    /// is not a cluster decision. Mirrors `onNodeRemoved`; today the only `SelfShutdown`
    /// emitter is QuicClusterNetwork on local-process SHUTDOWN, which is a no-op for the
    /// local DHT (process is stopping). Required for symmetry with prior NodeDown handling.
    @Contract
    public void onSelfShutdown(TransportObservation.SelfShutdown event) {
        var downNodeId = event.nodeId();
        log.info("DHT: Self shutdown {}, updating ring", downNodeId.id());
        node.ring()
            .removeNode(downNodeId);
        rebalancer.onPresent(r -> r.onNodeRemoved(downNodeId));
    }

    /// Transport-disconnect ring-prune is intentionally NOT exposed.
    ///
    /// Earlier work (reverted) pruned the ring on every `TransportObservation.PeerDisconnected`,
    /// which caused a rebalance storm under sustained write pressure (e.g. 16-chunk 1MB
    /// artifact deploy fan-out): each prune triggered re-replication that saturated QUIC,
    /// which in turn caused `writeIfWritable` watermark drops, which manifested as
    /// indefinite hangs on `Promise.allOf` of chunk DHT puts. The consensus-driven path
    /// (`MembershipDecision.NodeRemoved` → `onNodeRemoved`) IS the correct ring-prune
    /// trigger: it fires only after the cluster has agreed the node is gone, not on every
    /// transient QUIC flap. Genuinely-dead peers reach `NodeRemoved` via SWIM failure
    /// detection + reconciler decision within seconds; transient flaps reconnect and
    /// preserve ring locality.

    private void removeFromRing(NodeId removedNodeId) {
        log.info("DHT: Node removed {}, updating ring", removedNodeId.id());
        node.ring()
            .removeNode(removedNodeId);
        rebalancer.onPresent(r -> r.onNodeRemoved(removedNodeId));
    }
}
