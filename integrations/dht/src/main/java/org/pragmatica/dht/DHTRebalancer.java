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

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.storage.KeyStamp;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.IdGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Re-replicates data when a node departs to maintain replication factor.
///
/// Two complementary surfaces:
///   - Survivor-side ([#onNodeRemoved]): a remaining node re-pushes partitions it is primary for
///     once a peer leaves the ring — the pre-existing anti-entropy-adjacent path.
///   - Departing-side ([#pushOnDeparture], issue #427): the LEAVING node itself pushes every
///     locally-held chunk that the post-departure replica set would otherwise lose to the node(s)
///     that newly become responsible, ack-gated, before it halts. This closes the scale-down loss
///     mode where a key's only acked holder is pruned and no survivor holds a copy to re-replicate
///     from (survivor-side rebalance pushes from its OWN storage — empty for a key it never held).
///
/// Both surfaces send in PAGES (A6): each push carries at most one [PageBudget] of entries, planned on the
/// engine's [KeyStamp]s, so a partition or a store of any size leaves in messages below the cluster frame, and only
/// one page's values are read at a time. The pages travel as ordinary [DHTMessage.MigrationDataResponse] pushes,
/// which every version already accepts one by one, so paging needs no change on the receiver.
public final class DHTRebalancer {
    private static final Logger log = LoggerFactory.getLogger(DHTRebalancer.class);

    private final DHTNode node;
    private final DHTNetwork network;
    /// Fixed for [#dhtRebalancer(DHTNode, DHTNetwork, DHTConfig)]; the node's live replication for
    /// [#dhtRebalancer(DHTNode, DHTNetwork)] (#1777 track 1).
    private final Supplier<DHTConfig> config;
    /// Departure pages awaiting an applied [DHTMessage.MigrationDataAck], keyed by the page's correlation id. Registered by a
    /// [DeparturePushRun] at plan time, removed by an applied ack, a retarget or the end of the push.
    private final ConcurrentHashMap<String, DeparturePushRun.Page> pendingPushes = new ConcurrentHashMap<>();
    /// Departure pushes in progress, told of every ring change ([#onViewChange]).
    private final Set<DeparturePushRun> activeRuns = ConcurrentHashMap.newKeySet();

    private DHTRebalancer(DHTNode node, DHTNetwork network, Supplier<DHTConfig> config) {
        this.node = node;
        this.network = network;
        this.config = config;
    }

    /// Create a rebalancer for the given DHT node.
    ///
    /// @param node    local DHT node with storage and ring
    /// @param network cluster network for sending migration data
    /// @param config  DHT configuration
    public static DHTRebalancer dhtRebalancer(DHTNode node, DHTNetwork network, DHTConfig config) {
        return new DHTRebalancer(node, network, () -> config);
    }

    /// Create a rebalancer that follows the node's LIVE replication ([DHTNode#config], #1777 track 1).
    public static DHTRebalancer dhtRebalancer(DHTNode node, DHTNetwork network) {
        return new DHTRebalancer(node, network, node::config);
    }

    /// Called after a node is removed from the ring.
    /// Scans local storage partition by partition and pushes data to new replica
    /// nodes that need copies to restore the replication factor.
    @Contract
    public void onNodeRemoved(NodeId removedNode) {
        if (config.get().isFullReplication() || !node.replicationResolved()) {
            return;
        }

        log.info("Rebalancing after node {} departed", removedNode.id());
        var replicationFactor = config.get().effectiveReplicationFactor(node.ring().nodeCount());

        for (int p = 0; p < Partition.MAX_PARTITIONS; p++) {
            rebalancePartition(p, replicationFactor);
        }
    }

    /// Graceful-departure push (issue #427, D1/D3/D4; #2034), paged (A6). Invoked by the departing node's drain procedure while it
    /// is still reachable: enumerates every locally-held chunk directly from the storage engine (independent of whether self is
    /// still in the ring), and for each pushes only to the node(s) that NEWLY become responsible post-departure — the delta
    /// `newSet \ oldSet`, never to nodes that were already replicas (storm guard, guards against the reverted `d3e54717e` class).
    /// FULL replication mode is a no-op (every node already holds everything). The promise ALWAYS succeeds — a departure push must
    /// never gate the halt.
    ///
    /// Each key is owed until a holder of it sits in its deliverer set (#3345, [DeparturePushRun]): owed keys are paged to their
    /// targets, re-planned on a change of the ring, a holder lost and each exit attempt, and the push resolves when nothing is owed
    /// and every holder answered the pre-exit probe, or at the window's deadline with what is still owed.
    ///
    /// @param window   absolute drain-clock deadline, event floor, halt flag and the drain's fence time
    /// @param tuning   the resend cadence and the event wait
    /// @param drainSet nodes named or seen draining, re-read at each re-plan (issue #1818, #3345); self need not be in it
    /// @param observer receives the at-risk, delivered and incomplete events
    ///
    /// @return what was left owed when the push resolved
    public Promise<DeparturePushReport> pushOnDeparture(DepartureWindow window,
                                                        DeparturePushTuning tuning,
                                                        Supplier<Set<NodeId>> drainSet,
                                                        DeparturePushObserver observer) {
        var t0 = System.nanoTime();

        return config.get()
                     .isFullReplication()
               ? Promise.success(DeparturePushReport.COMPLETE)
               : startRun(DeparturePushRun.departurePushRun(node,
                                                            network,
                                                            pendingPushes,
                                                            config.get()
                                                                  .effectiveReplicationFactor(node.ring().nodeCount()),
                                                            drainSet,
                                                            t0,
                                                            window,
                                                            tuning,
                                                            observer));
    }

    private Promise<DeparturePushReport> startRun(DeparturePushRun run) {
        activeRuns.add(run);

        return run.run()
                  .onResultRun(() -> activeRuns.remove(run));
    }

    /// The ring changed (#3345): every running departure push re-plans. Called by [DHTTopologyListener] after each ring change.
    @Contract
    public void onViewChange() {
        activeRuns.forEach(DeparturePushRun::onViewChange);
    }

    /// Route an ack to the departure page matching its correlation id (issue #427, D2; #2034, #3345). The page stays registered
    /// until its target's removal edge or the end of its push, so a late ack within the target's tenure still counts; an ack for
    /// a page no longer registered is a no-op.
    @Contract
    public void onMigrationDataAck(DHTMessage.MigrationDataAck ack) {
        Option.option(pendingPushes.get(ack.requestId())).onPresent(page -> page.onAck(ack));
    }

    private PageBudget pageBudget() {
        return node.repair()
                   .pageBudget();
    }

    private void rebalancePartition(int partitionIndex, int replicationFactor) {
        var partition = Partition.at(partitionIndex);
        var replicaNodes = node.ring().nodesFor(partition, replicationFactor);

        if (!replicaNodes.contains(node.nodeId())) {
            return;
        }

        if (!isPrimary(replicaNodes)) {
            return;
        }

        node.storage()
            .stampsForPartition(node.ring(),
                                partition)
            .onSuccess(stamps -> pushToReplicas(partitionIndex, replicaNodes, stamps));
    }

    private boolean isPrimary(List<NodeId> replicaNodes) {
        return ! replicaNodes.isEmpty() && replicaNodes.getFirst()
                                                       .equals(node.nodeId());
    }

    /// The partition leaves in pages (A6), read one page at a time, each pushed to every other replica before the
    /// next page is read. A page that cannot be read ends this push; the next anti-entropy round finds the
    /// divergence and pulls (FER: re-replication is delayed, not lost).
    private void pushToReplicas(int partitionIndex, List<NodeId> replicaNodes, List<KeyStamp> stamps) {
        if (stamps.isEmpty()) {
            return;
        }

        var targets = replicaNodes.stream().filter(replica -> !replica.equals(node.nodeId())).toList();

        pushPartitionPages(partitionIndex, targets, pageBudget().pages(stamps), 0).onFailure(cause -> log.warn("Rebalance push of partition {} stopped: {}; anti-entropy "
                                                                                                              + "repairs the rest",
                                                                                                               partitionIndex,
                                                                                                               cause.message()));
    }

    private Promise<Unit> pushPartitionPages(int partitionIndex,
                                             List<NodeId> targets,
                                             List<List<KeyStamp>> pages,
                                             int index) {
        return index >= pages.size()
               ? Promise.success(Unit.unit())
               : node.readEntries(pages.get(index))
                     .map(entries -> pushPage(partitionIndex, targets, entries))
                     .flatMap(_ -> pushPartitionPages(partitionIndex, targets, pages, index + 1));
    }

    private Unit pushPage(int partitionIndex, List<NodeId> targets, List<DHTMessage.KeyValue> entries) {
        if (!entries.isEmpty()) {
            targets.forEach(target -> sendMigrationData(target, partitionIndex, entries));
        }

        return Unit.unit();
    }

    @Contract
    private void sendMigrationData(NodeId target, int partitionIndex, List<DHTMessage.KeyValue> entries) {
        var correlationId = IdGenerator.generate();

        log.debug("Pushing a page of {} entries for partition {} to {}", entries.size(), partitionIndex, target.id());
        node.repair().notePageSent(PageBudget.transferBytes(entries));
        network.send(target,
                     new DHTMessage.MigrationDataResponse(correlationId, node.nodeId(), entries, false, false));
    }
}
