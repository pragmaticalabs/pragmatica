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

import java.util.HashSet;
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
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.IdGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


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
    /// Legacy budget of the budget-only departure-push overloads (issue #427, D4): the whole resend window of
    /// [#pushOnDeparture(TimeSpan, DeparturePushObserver)] callers that pass it. The no-budget overloads use
    /// [DeparturePushTuning#DEFAULT] instead (#2034).
    public static final TimeSpan DEFAULT_DEPARTURE_PUSH_BUDGET = timeSpan(10).seconds();

    private final DHTNode node;
    private final DHTNetwork network;
    /// Fixed for [#dhtRebalancer(DHTNode, DHTNetwork, DHTConfig)]; the node's live replication for
    /// [#dhtRebalancer(DHTNode, DHTNetwork)] (#1777 track 1).
    private final Supplier<DHTConfig> config;
    /// Departure pages awaiting an applied [DHTMessage.MigrationDataAck], keyed by the page's correlation id. Registered by a
    /// [DeparturePushRun] at plan time, removed by an applied ack, a retarget or the end of the push.
    private final ConcurrentHashMap<String, DeparturePushRun.Page> pendingPushes = new ConcurrentHashMap<>();

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
    /// Each page is resent to its target until an applied ack arrives, within a bounded window (#2034): a page whose keys still
    /// have a surviving replica stops at `baseBudget`; a page carrying a key no surviving replica holds (a sole copy) continues for
    /// `soleExtra` more. A target is replaced only when it leaves the ring. Each target's pages travel in two chains, sole and
    /// replicated, each one page after another. See [DeparturePushRun] for the classification and the events.
    ///
    /// @param window      absolute drain-clock deadline, event floor and halt flag
    /// @param tuning      the windows and resend cadence
    /// @param coDeparting nodes leaving together with this one (issue #1818); self need not be in the set
    /// @param observer    receives the at-risk, delivered and incomplete events
    ///
    /// @return what was left unconfirmed when the push resolved
    public Promise<DeparturePushReport> pushOnDeparture(DepartureWindow window,
                                                        DeparturePushTuning tuning,
                                                        Set<NodeId> coDeparting,
                                                        DeparturePushObserver observer) {
        var t0 = System.nanoTime();

        return config.get()
                     .isFullReplication()
               ? Promise.success(DeparturePushReport.COMPLETE)
               : DeparturePushRun.departurePushRun(node,
                                                   network,
                                                   pendingPushes,
                                                   config.get().effectiveReplicationFactor(node.ring().nodeCount()),
                                                   leavingSet(coDeparting),
                                                   t0,
                                                   window,
                                                   tuning,
                                                   observer)
                                 .run();
    }

    /// Legacy form of [#pushOnDeparture(DepartureWindow, DeparturePushTuning, Set, DeparturePushObserver)] with
    /// [DeparturePushTuning#DEFAULT], no co-departing nodes, a deadline of base + sole extra + event wait from now, never halted.
    public Promise<Unit> pushOnDeparture(DeparturePushObserver observer) {
        return pushOnDeparture(Set.of(), observer);
    }

    /// Legacy budget-explicit form: `budget` is the whole resend window (no sole extra), an attempt waits `min(1 s, budget / 4)`,
    /// and the push resolves by `budget` plus the event wait. With an observer whose events settle at once the event wait is zero.
    public Promise<Unit> pushOnDeparture(TimeSpan budget, DeparturePushObserver observer) {
        return pushOnDeparture(budget, Set.of(), observer);
    }

    /// Legacy form for a node leaving TOGETHER with `coDeparting` (issue #1818): every node in that set is still in the ring but is
    /// about to halt, so it is neither a push target nor a surviving replica. Without this, a push can land on a co-drainer that
    /// halts with the copy, and two holders leaving together each count the other as a survivor, leaving the slot the other vacates
    /// unstocked. Self need not be in the set. Uses [DeparturePushTuning#DEFAULT].
    public Promise<Unit> pushOnDeparture(Set<NodeId> coDeparting, DeparturePushObserver observer) {
        var tuning = DeparturePushTuning.DEFAULT;

        return pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + tuning.baseBudget()
                                                                                         .nanos() + tuning.soleExtra()
                                                                                                          .nanos() + tuning.eventDeliveryWait()
                                                                                                                           .nanos()),
                               tuning,
                               coDeparting,
                               observer).mapToUnit();
    }

    /// Legacy budget-explicit form of [#pushOnDeparture(Set, DeparturePushObserver)]; see
    /// [#pushOnDeparture(TimeSpan, DeparturePushObserver)].
    public Promise<Unit> pushOnDeparture(TimeSpan budget, Set<NodeId> coDeparting, DeparturePushObserver observer) {
        var tuning = DeparturePushTuning.forBudget(budget);

        return pushOnDeparture(DepartureWindow.departureWindow(System.nanoTime() + budget.nanos() + tuning.eventDeliveryWait()
                                                                                                          .nanos()),
                               tuning,
                               coDeparting,
                               observer).mapToUnit();
    }

    private Set<NodeId> leavingSet(Set<NodeId> coDeparting) {
        var leaving = new HashSet<>(coDeparting);

        leaving.add(node.nodeId());

        return Set.copyOf(leaving);
    }

    /// Settle the departure page matching an incoming ack's correlation id (issue #427, D2; #2034). An applied ack removes the page
    /// and delivers its keys; a nack (issue #1818) leaves it pending and settles only the current attempt, so the page is resent
    /// after a backoff. An ack for a page no longer registered (the push ended, or the page was retargeted) is a no-op.
    @Contract
    public void onMigrationDataAck(DHTMessage.MigrationDataAck ack) {
        var settled = ack.applied()
                      ? pendingPushes.remove(ack.requestId())
                      : pendingPushes.get(ack.requestId());

        Option.option(settled).onPresent(page -> page.settle(ack.applied()));
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
