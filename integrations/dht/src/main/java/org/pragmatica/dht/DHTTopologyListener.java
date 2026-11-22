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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Option<DHTAntiEntropy> antiEntropy;
    /// Nodes seen entering DEPARTING and not since recovered or rejoined — the membership half of "who may
    /// send a departure push" (#1818 round 3). Kept past the node's removal: its push can land after the
    /// ring has pruned it.
    private final Set<NodeId> departing = ConcurrentHashMap.newKeySet();
    /// Nodes THIS listener took off THIS ring on their departing edge, and only those (#3415): the recovery edge
    /// restores exactly this set. A node that was not on the ring when it went DEPARTING (a worker, which is never a
    /// ring member) is not recorded, so its recovery leaves the ring unchanged. An entry ends at the recovery, at a
    /// join (which re-adds the node itself) or at a removal decision (which supersedes the prune).
    private final Set<NodeId> pruned = ConcurrentHashMap.newKeySet();

    private DHTTopologyListener(DHTNode node, Option<DHTRebalancer> rebalancer, Option<DHTAntiEntropy> antiEntropy) {
        this.node = node;
        this.rebalancer = rebalancer;
        this.antiEntropy = antiEntropy;
    }

    /// Create a topology listener for the given DHT node without rebalancer.
    ///
    /// @param node the local DHT node whose ring will be updated
    public static DHTTopologyListener dhtTopologyListener(DHTNode node) {
        return new DHTTopologyListener(node, Option.none(), Option.none());
    }

    /// Create a topology listener for the given DHT node with rebalancer.
    ///
    /// @param node       the local DHT node whose ring will be updated
    /// @param rebalancer rebalancer to trigger re-replication on node departure
    public static DHTTopologyListener dhtTopologyListener(DHTNode node, DHTRebalancer rebalancer) {
        return new DHTTopologyListener(node, Option.some(rebalancer), Option.none());
    }

    /// Create a topology listener with rebalancer and anti-entropy: a join triggers an immediate
    /// anti-entropy round (issue #420).
    ///
    /// @param node        the local DHT node whose ring will be updated
    /// @param rebalancer  rebalancer to trigger re-replication on node departure
    /// @param antiEntropy anti-entropy process to run once when a node joins the ring
    public static DHTTopologyListener dhtTopologyListener(DHTNode node,
                                                          DHTRebalancer rebalancer,
                                                          DHTAntiEntropy antiEntropy) {
        return new DHTTopologyListener(node, Option.some(rebalancer), Option.some(antiEntropy));
    }

    /// Handle a node-joined decision by adding the node to the consistent hash ring, then run one
    /// anti-entropy round: the ring now counts the joiner toward the replication factor of every
    /// partition it gained, while the joiner holds none of them until it pulls. The round runs on
    /// every node (the joiner's own `NodeJoined` included), and is idempotent with the periodic
    /// cycle — nodes that gained nothing pull nothing (issue #420).
    @Contract
    public void onNodeJoined(MembershipDecision.NodeJoined event) {
        var addedNodeId = event.nodeId();

        departing.remove(addedNodeId);
        pruned.remove(addedNodeId);
        log.info("DHT: Node added {}, updating ring", addedNodeId.id());
        node.changeRing(ring -> ring.addNode(addedNodeId));
        antiEntropy.onPresent(DHTAntiEntropy::synchronizeNow);
    }

    /// Handle a node-removed decision by removing the node from the consistent hash ring
    /// and triggering rebalance to restore replication factor.
    /// ```tla DHTDepartureDrain:Removed
    /// \* A halted drainer reaches `o` as NodeRemoved: `o` prunes it from its ring. The departing set keeps it.
    /// \* The code also drops the drainer from the recorded-prune set (`pruned.remove`, #3415), so a later recovery restores nothing;
    /// \* the model reaches the same state because Recover is enabled only for stale entries. NodeDecommissioned does the same.
    /// Removed(o, x) ==
    ///     /\ phase[o] # "halted"
    ///     /\ x \in drainers /\ phase[x] = "halted"
    ///     /\ x \notin pruned[o]
    ///     /\ pruned' = [pruned EXCEPT ![o] = @ \cup {x}]
    ///     /\ UNCHANGED <<drainers, held0, stale, drainSet, known, flapped, store, phase, leaving, rfAt, pages, claimed, strayNack,
    ///                deliverers, acked, delivered>>
    /// ```
    @Contract
    public void onNodeRemoved(MembershipDecision.NodeRemoved event) {
        pruned.remove(event.nodeId());
        removeFromRing(event.nodeId());
        discardIfSelfRemovedWhileRunning(event.nodeId());
    }

    /// Handle a node-decommissioned decision identically to node-removed for ring purposes.
    @Contract
    public void onNodeDecommissioned(MembershipDecision.NodeDecommissioned event) {
        pruned.remove(event.nodeId());
        removeFromRing(event.nodeId());
        discardIfSelfRemovedWhileRunning(event.nodeId());
    }

    /// A committed removal of THIS node while it is not departing — it was cut off (a pause, a partition) rather
    /// than drained — leaves it holding a store no tombstone issued since has reached (#1777 track 3, owner ruling
    /// 2026-10-03). The node drops that store, so it rejoins empty, as a restarted node does. A DEPARTING node keeps
    /// its store: its departure push hands that store off before it halts, and it prunes itself from its own ring
    /// on the DEPARTING edge (seed-500), so "self left the ring" alone cannot be the trigger.
    private void discardIfSelfRemovedWhileRunning(NodeId removedNodeId) {
        if (removedNodeId.equals(node.nodeId()) && !departing.contains(removedNodeId)) {
            node.discardStoreAfterSelfRemoval();
        }
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
    /// The prune is recorded only when the node was on this ring at that moment (#3415), checked under the same
    /// ring change that removes it, so [`#onNodeRecovered`] restores what this edge took off and nothing else.
    ///
    /// SAFETY: invoked ONLY off the membership FSM's fresh-edge-into-`Departing` callback, reached
    /// solely via the deliberate-departure transitions (drain command / graceful leave /
    /// sustained-absence down-hysteresis). The transient transport-disconnect edge that caused the
    /// reverted rebalance storm never reaches `Departing` — see the not-wired note below — so this
    /// seam cannot reintroduce the per-flap storm.
    /// ```tla DHTDepartureDrain:DepartingEdge
    /// \* `o` sees `x` enter DEPARTING (a drainer once its drain began, or a stale entry flapping once): departing add, ring prune.
    /// \* `o` may be `x` itself: nothing in the listener skips self. Which stale flaps happen is the Flap switch.
    /// DepartingEdge(o, x) ==
    ///     /\ phase[o] # "halted"
    ///     /\ x \notin pruned[o]
    ///     /\ \/ x \in drainers /\ phase[x] \in {"draining", "pushing"}
    ///        \/ x \in stale /\ x \notin flapped[o] /\ (Flap = "self" \/ (Flap = "peer" /\ o # x))
    ///     /\ pruned' = [pruned EXCEPT ![o] = @ \cup {x}]
    ///     /\ flapped' = IF x \in stale THEN [flapped EXCEPT ![o] = @ \cup {x}] ELSE flapped
    ///     /\ UNCHANGED <<drainers, held0, stale, drainSet, known, store, phase, leaving, rfAt, pages, claimed, strayNack,
    ///                deliverers, acked, delivered>>
    /// ```
    @Contract
    public void onNodeDeparting(NodeId departingNodeId) {
        log.info("DHT: Node {} entering DEPARTING, pruning ring ahead of halt", departingNodeId.id());
        departing.add(departingNodeId);
        node.changeRing(ring -> pruneRecorded(ring, departingNodeId));
        rebalanceAfterRemoval(departingNodeId);
    }

    /// Take `departingNodeId` off `ring` and record it in [`#pruned`] iff it was a member of `ring`.
    private void pruneRecorded(ConsistentHashRing<NodeId> ring, NodeId departingNodeId) {
        if (ring.nodes().contains(departingNodeId)) {
            pruned.add(departingNodeId);
        }

        ring.removeNode(departingNodeId);
    }

    /// Re-add a node to the ring when it RECOVERS out of the membership `Departing` state
    /// (seed-500 part 2, symmetry fix) — the symmetric counterpart to [`#onNodeDeparting`]. A node
    /// pruned on its DEPARTING edge that then refutes the drain (a strictly-newer-incarnation
    /// recovery, DEPARTING→MEMBER) emits NO `MembershipDecision.NodeJoined` (it was already JOINED),
    /// so the normal [`#onNodeJoined`] ring RE-ADD never runs and the node would be left missing
    /// from the ring → under-replication. This re-adds it via the SAME `ConsistentHashRing.addNode`
    /// seam [`#onNodeJoined`] uses, which is idempotent (a no-op for a node already in the ring), so
    /// it is harmless even if a `NodeJoined` also fires.
    ///
    /// It restores ONLY a node this listener pruned on its departing edge ([`#pruned`], #3415). A node that was never on
    /// this ring (a worker: a refuted worker drain recovers DEPARTING→MEMBER on every core) or that was not pruned here
    /// leaves the ring unchanged. A core member that reaches MEMBER for the first time through DEPARTING is not lost by
    /// this: that first MEMBER entry emits its JOINED edge, so [`#onNodeJoined`] adds it.
    /// ```tla DHTDepartureDrain:Recover
    /// \* A flapped stale entry recovers on `o`: departing remove, ring re-add. The guard `x \in pruned[o]` is the code's
    /// \* `pruned.remove` returning true (the DEPARTING edge recorded the prune, #3415); the model's `pruned[o]` is ring absence,
    /// \* which for a stale entry (never a drainer, so never removed by NodeRemoved) is exactly the recorded set. Not recorded:
    /// \* the else branch leaves the ring unchanged, which is the action being disabled.
    /// Recover(o, x) ==
    ///     /\ phase[o] # "halted"
    ///     /\ x \in stale /\ x \in pruned[o]
    ///     /\ pruned' = [pruned EXCEPT ![o] = @ \ {x}]
    ///     /\ UNCHANGED <<drainers, held0, stale, drainSet, known, flapped, store, phase, leaving, rfAt, pages, claimed, strayNack,
    ///                deliverers, acked, delivered>>
    /// ```
    @Contract
    public void onNodeRecovered(NodeId recoveredNodeId) {
        departing.remove(recoveredNodeId);
        if (pruned.remove(recoveredNodeId)) {
            restoreToRing(recoveredNodeId);
        } else {
            log.info("DHT: Node {} recovered from DEPARTING, not pruned from this ring, ring unchanged",
                     recoveredNodeId.id());
        }
    }

    private void restoreToRing(NodeId recoveredNodeId) {
        log.info("DHT: Node {} recovered from DEPARTING, re-adding to ring", recoveredNodeId.id());
        node.changeRing(ring -> ring.addNode(recoveredNodeId));
        // Same shape as a join: the re-added node counts toward RF again; a round settles what it
        // missed while pruned (issue #420).
        antiEntropy.onPresent(DHTAntiEntropy::synchronizeNow);
    }

    /// Whether `nodeId` was seen entering DEPARTING and has not since recovered or rejoined.
    public boolean isDeparting(NodeId nodeId) {
        return departing.contains(nodeId);
    }

    /// Self-shutdown cleanup hook: kept on TransportObservation stream because self-shutdown
    /// is not a cluster decision. Mirrors `onNodeRemoved`; today the only `SelfShutdown`
    /// emitter is QuicClusterNetwork on local-process SHUTDOWN, which is a no-op for the
    /// local DHT (process is stopping). Required for symmetry with prior NodeDown handling.
    @Contract
    public void onSelfShutdown(TransportObservation.SelfShutdown event) {
        var downNodeId = event.nodeId();

        log.info("DHT: Self shutdown {}, updating ring", downNodeId.id());
        node.ring().removeNode(downNodeId);
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
    ///
    /// A removal can make this node a replica of partitions it never held (#1777 track 2): those become
    /// catching up, and one anti-entropy round starts filling them at once rather than at the next tick.
    private void removeFromRing(NodeId removedNodeId) {
        log.info("DHT: Node removed {}, updating ring", removedNodeId.id());
        node.changeRing(ring -> ring.removeNode(removedNodeId));
        rebalanceAfterRemoval(removedNodeId);
    }

    private void rebalanceAfterRemoval(NodeId removedNodeId) {
        rebalancer.onPresent(r -> r.onNodeRemoved(removedNodeId));
        antiEntropy.onPresent(DHTAntiEntropy::catchUpNow);
    }
}
