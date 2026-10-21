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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
    /// Bounded best-effort budget for the graceful-departure push (issue #427, D4). Sits inside the
    /// drain grace window: acks are milliseconds, so the departing node normally settles far under
    /// this; on overrun the at-risk keys are reported via the [DeparturePushObserver] and the node
    /// halts anyway (never a hard gate on halt).
    public static final TimeSpan DEFAULT_DEPARTURE_PUSH_BUDGET = timeSpan(10).seconds();
    /// Bound on the at-risk key sample carried in a [DeparturePushObserver] report — keeps the
    /// operator-visible payload small on a large-inventory overrun (issue #427, D4).
    private static final int SAMPLE_LIMIT = 16;
    private static final HexFormat HEX = HexFormat.of();

    private final DHTNode node;
    private final DHTNetwork network;
    /// Fixed for [#dhtRebalancer(DHTNode, DHTNetwork, DHTConfig)]; the node's live replication for
    /// [#dhtRebalancer(DHTNode, DHTNetwork)] (#1777 track 1).
    private final Supplier<DHTConfig> config;
    /// Pending graceful-departure pushes awaiting a [DHTMessage.MigrationDataAck], keyed by the
    /// push's correlation id. Populated by [#sendAckedPush], drained by [#onMigrationDataAck];
    /// whatever remains when the budget expires is the at-risk set reported to the observer.
    private final ConcurrentHashMap<String, PendingPush> pendingPushes = new ConcurrentHashMap<>();

    /// A departure page awaiting its ack: the keys it carries (reported at risk while pending) and the ack.
    private record PendingPush(List<byte[]> keys, Promise<Unit> ackPromise) {}

    /// A departure page planned and registered under its correlation id, sent when its turn comes.
    private record PlannedPush(String correlationId, List<KeyStamp> page, Promise<Unit> ackPromise) {}

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

    /// Graceful-departure push (issue #427, D1/D3/D4), paged (A6). Invoked by the departing node's drain
    /// procedure while it is still reachable: enumerates every locally-held chunk directly from the
    /// storage engine (independent of whether self is still in the ring), and for each pushes only to
    /// the node(s) that NEWLY become responsible post-departure — the delta `newSet \ oldSet`, never
    /// to nodes that were already replicas (storm guard, guards against the reverted `d3e54717e`
    /// class). Each push is ack-requested; the returned promise settles when every ack lands or the
    /// budget expires, at which point the still-unacknowledged keys are reported via `observer` as a
    /// bounded at-risk sample. FULL replication mode is a no-op (every node already holds everything).
    /// The promise ALWAYS succeeds — a departure push is best-effort and must never gate the halt.
    ///
    /// Each target's chunks go in pages of at most one [PageBudget], sent one after another: the next page leaves
    /// once the previous one is acked or nacked. Every page is registered as pending before the first is sent, so a
    /// budget that expires mid-push reports the unsent pages at risk together with the unacknowledged ones.
    public Promise<Unit> pushOnDeparture(DeparturePushObserver observer) {
        return pushOnDeparture(DEFAULT_DEPARTURE_PUSH_BUDGET, observer);
    }

    /// Budget-explicit variant of [#pushOnDeparture(DeparturePushObserver)].
    public Promise<Unit> pushOnDeparture(TimeSpan budget, DeparturePushObserver observer) {
        return pushOnDeparture(budget, Set.of(), observer);
    }

    /// Departure push for a node leaving TOGETHER with `coDeparting` (issue #1818): every node in
    /// that set is still in the ring but is about to halt, so it is neither a push target nor a
    /// surviving replica. Without this, a push can land on a co-drainer that halts with the copy, and
    /// two holders leaving together each count the other as a survivor, leaving the slot the other
    /// vacates unstocked. Self need not be in the set.
    public Promise<Unit> pushOnDeparture(Set<NodeId> coDeparting, DeparturePushObserver observer) {
        return pushOnDeparture(DEFAULT_DEPARTURE_PUSH_BUDGET, coDeparting, observer);
    }

    /// Budget-explicit variant of [#pushOnDeparture(Set, DeparturePushObserver)].
    public Promise<Unit> pushOnDeparture(TimeSpan budget, Set<NodeId> coDeparting, DeparturePushObserver observer) {
        if (config.get().isFullReplication()) {
            return Promise.success(Unit.unit());
        }

        var leaving = leavingSet(coDeparting);

        return node.storage()
                   .stamps()
                   .flatMap(stamps -> dispatchDeparturePush(stamps, leaving, budget, observer));
    }

    private Set<NodeId> leavingSet(Set<NodeId> coDeparting) {
        var leaving = new HashSet<>(coDeparting);

        leaving.add(node.nodeId());

        return Set.copyOf(leaving);
    }

    /// Resolve the pending departure push matching an incoming ack's correlation id (issue #427, D2).
    /// A late ack that arrives after the budget expired simply finds no pending entry — harmless. A
    /// nack (issue #1818) settles the wait but leaves the batch pending, so [#reportIncomplete] counts
    /// its chunks at risk exactly as it counts an ack that never came.
    @Contract
    public void onMigrationDataAck(DHTMessage.MigrationDataAck ack) {
        var settled = ack.applied()
                      ? pendingPushes.remove(ack.requestId())
                      : pendingPushes.get(ack.requestId());

        Option.option(settled).onPresent(pending -> pending.ackPromise()
                                                           .succeed(Unit.unit()));
    }

    private Promise<Unit> dispatchDeparturePush(List<KeyStamp> stamps,
                                                Set<NodeId> leaving,
                                                TimeSpan budget,
                                                DeparturePushObserver observer) {
        var batches = groupByTarget(stamps, leaving);

        return batches.isEmpty()
               ? Promise.success(Unit.unit())
               : awaitAcks(sendPushes(batches, leaving), budget, observer);
    }

    /// Group every locally-held entry's stamp under each node that newly becomes responsible for it, so a
    /// target receives the pages carrying all of its owed chunks.
    private Map<NodeId, List<KeyStamp>> groupByTarget(List<KeyStamp> stamps, Set<NodeId> leaving) {
        var replicationFactor = config.get().effectiveReplicationFactor(node.ring().nodeCount());

        return stamps.stream()
                     .flatMap(stamp -> targetPairs(stamp, replicationFactor, leaving))
                     .collect(Collectors.groupingBy(TargetEntry::target,
                                                    Collectors.mapping(TargetEntry::stamp,
                                                                       Collectors.toList())));
    }

    private record TargetEntry(NodeId target, KeyStamp stamp) {}

    private Stream<TargetEntry> targetPairs(KeyStamp stamp, int replicationFactor, Set<NodeId> leaving) {
        return departureTargets(stamp.key(),
                                replicationFactor,
                                leaving).stream()
                               .map(target -> new TargetEntry(target, stamp));
    }

    /// Post-departure delta target set for one key (issue #427, D3). `newSet` is the responsible set
    /// with every `leaving` node excluded — self and each co-departing node (issue #1818) — via the
    /// filtered ring overload, robust whether or not they are still in the ring. When self is still a
    /// ring member (the designed drain ordering), the nodes already holding the key are the members of
    /// the current responsible set that are NOT leaving, so only the genuine newcomers
    /// (`newSet \ existing`) are targeted. When self has ALREADY been pruned (the ring can no longer
    /// identify the newcomer), fall back to the whole `newSet`: the versioned puts are idempotent, so
    /// re-sending to an existing replica is harmless, and this guarantees no loss regardless of the
    /// prune-vs-drain ordering. The same fallback applies when the exclusion exhausts the ring
    /// (`newSet` shorter than the replication factor): no node lies beyond the vacated slots to absorb
    /// the push, so a node wrongly in `leaving` would otherwise remove the only newcomer and leave the
    /// copy resting on survivors that may not hold it.
    private List<NodeId> departureTargets(byte[] key, int replicationFactor, Set<NodeId> leaving) {
        var newSet = node.ring().nodesFor(key, replicationFactor, candidate -> !leaving.contains(candidate));
        var currentSet = node.ring().nodesFor(key, replicationFactor);

        return currentSet.contains(node.nodeId()) && newSet.size() >= replicationFactor
               ? excludeExistingReplicas(newSet, currentSet, leaving)
               : newSet;
    }

    private static List<NodeId> excludeExistingReplicas(List<NodeId> newSet,
                                                        List<NodeId> currentSet,
                                                        Set<NodeId> leaving) {
        var existing = new HashSet<>(currentSet);

        existing.removeAll(leaving);

        return newSet.stream()
                     .filter(candidate -> !existing.contains(candidate))
                     .toList();
    }

    private List<Promise<Unit>> sendPushes(Map<NodeId, List<KeyStamp>> batches, Set<NodeId> leaving) {
        return batches.entrySet()
                      .stream()
                      .map(batch -> pushPagesTo(batch.getKey(),
                                                planPages(batch.getValue()),
                                                leaving))
                      .toList();
    }

    /// Every page of a target is registered as pending before the first leaves (see [#pushOnDeparture]).
    private List<PlannedPush> planPages(List<KeyStamp> stamps) {
        return pageBudget().pages(stamps)
                         .stream()
                         .map(this::registerPage)
                         .toList();
    }

    private PageBudget pageBudget() {
        return node.repair()
                   .pageBudget();
    }

    private PlannedPush registerPage(List<KeyStamp> page) {
        var correlationId = IdGenerator.generate();
        Promise<Unit> ackPromise = Promise.promise();

        pendingPushes.put(correlationId,
                          new PendingPush(page.stream().map(KeyStamp::key).toList(),
                                          ackPromise));

        return new PlannedPush(correlationId, page, ackPromise);
    }

    /// One target's pages, one after another: page `index` leaves once every earlier page is acked or nacked.
    private Promise<Unit> pushPagesTo(NodeId target, List<PlannedPush> pages, Set<NodeId> leaving) {
        return pushPageAt(target, pages, 0, leaving);
    }

    private Promise<Unit> pushPageAt(NodeId target, List<PlannedPush> pages, int index, Set<NodeId> leaving) {
        return index >= pages.size()
               ? Promise.success(Unit.unit())
               : sendPlanned(target, pages.get(index), leaving).flatMap(_ -> pushPageAt(target,
                                                                                        pages,
                                                                                        index + 1,
                                                                                        leaving));
    }

    /// Only this page's values are read, just before it leaves.
    private Promise<Unit> sendPlanned(NodeId target, PlannedPush push, Set<NodeId> leaving) {
        return node.readEntries(push.page())
                   .flatMap(entries -> sendReadPage(target, push, entries, leaving));
    }

    private Promise<Unit> sendReadPage(NodeId target,
                                       PlannedPush push,
                                       List<DHTMessage.KeyValue> entries,
                                       Set<NodeId> leaving) {
        return entries.isEmpty()
               ? Promise.success(settleEmptyPage(push))
               : sendAckedPush(target, push, entries, leaving);
    }

    /// Every key of the page was removed meanwhile: nothing is owed, so nothing is at risk.
    private Unit settleEmptyPage(PlannedPush push) {
        pendingPushes.remove(push.correlationId());

        return Unit.unit();
    }

    /// The push carries `leaving`, the set excluded when choosing its target (issue #1818 L1), so the receiver
    /// checks placement against the same view rather than its own, which may not yet know every co-drainer.
    private Promise<Unit> sendAckedPush(NodeId target,
                                        PlannedPush push,
                                        List<DHTMessage.KeyValue> entries,
                                        Set<NodeId> leaving) {
        log.debug("Departure-pushing a page of {} chunk(s) to {} (ack {})",
                  entries.size(),
                  target.id(),
                  push.correlationId());
        node.repair().notePageSent(PageBudget.transferBytes(entries));
        network.send(target,
                     new DHTMessage.MigrationDataResponse(push.correlationId(),
                                                          node.nodeId(),
                                                          entries,
                                                          true,
                                                          List.copyOf(leaving),
                                                          List.copyOf(node.ring().nodes())));

        return push.ackPromise();
    }

    /// Settles when every batch is acked or nacked, or the budget expires; either way the batches still
    /// pending — nacked or unanswered — are reported. Recovery is FER: an expired budget degrades
    /// forward to that report, because the push must never gate the halt (D4).
    private Promise<Unit> awaitAcks(List<Promise<Unit>> acks, TimeSpan budget, DeparturePushObserver observer) {
        return Promise.allOf(acks)
                      .timeout(budget)
                      .mapToUnit()
                      .recover(_ -> Unit.unit())
                      .map(_ -> reportIncomplete(observer));
    }

    private Unit reportIncomplete(DeparturePushObserver observer) {
        var atRiskKeys = collectAtRiskKeys();

        return atRiskKeys.isEmpty()
               ? Unit.unit()
               : emitIncomplete(observer, atRiskKeys);
    }

    private List<byte[]> collectAtRiskKeys() {
        return pendingPushes.values()
                            .stream()
                            .flatMap(pending -> pending.keys()
                                                       .stream())
                            .toList();
    }

    private Unit emitIncomplete(DeparturePushObserver observer, List<byte[]> atRiskKeys) {
        log.warn("Departure push incomplete: {} chunk(s) unacknowledged within budget", atRiskKeys.size());
        observer.onIncomplete(atRiskKeys.size(), sampleKeys(atRiskKeys));

        return Unit.unit();
    }

    private static List<String> sampleKeys(List<byte[]> keys) {
        return keys.stream()
                   .limit(SAMPLE_LIMIT)
                   .map(HEX::formatHex)
                   .toList();
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
