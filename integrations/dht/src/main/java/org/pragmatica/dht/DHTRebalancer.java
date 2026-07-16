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
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.IdGenerator;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private final DHTConfig config;

    /// Pending graceful-departure pushes awaiting a [DHTMessage.MigrationDataAck], keyed by the
    /// push's correlation id. Populated by [#sendAckedPush], drained by [#onMigrationDataAck];
    /// whatever remains when the budget expires is the at-risk set reported to the observer.
    private final ConcurrentHashMap<String, PendingPush> pendingPushes = new ConcurrentHashMap<>();

    private record PendingPush(List<DHTMessage.KeyValue> entries, Promise<Unit> ackPromise) {}

    private DHTRebalancer(DHTNode node, DHTNetwork network, DHTConfig config) {
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
        return new DHTRebalancer(node, network, config);
    }

    /// Called after a node is removed from the ring.
    /// Scans local storage partition by partition and pushes data to new replica
    /// nodes that need copies to restore the replication factor.
    @Contract
    public void onNodeRemoved(NodeId removedNode) {
        if (config.isFullReplication()) {
            return;
        }

        log.info("Rebalancing after node {} departed", removedNode.id());

        var replicationFactor = config.effectiveReplicationFactor(node.ring().nodeCount());

        for (int p = 0; p < Partition.MAX_PARTITIONS; p++) {
            rebalancePartition(p, replicationFactor);
        }
    }

    /// Graceful-departure push (issue #427, D1/D3/D4). Invoked by the departing node's drain
    /// procedure while it is still reachable: enumerates every locally-held chunk directly from the
    /// storage engine (independent of whether self is still in the ring), and for each pushes only to
    /// the node(s) that NEWLY become responsible post-departure — the delta `newSet \ oldSet`, never
    /// to nodes that were already replicas (storm guard, guards against the reverted `d3e54717e`
    /// class). Each push is ack-requested; the returned promise settles when every ack lands or the
    /// budget expires, at which point the still-unacknowledged keys are reported via `observer` as a
    /// bounded at-risk sample. FULL replication mode is a no-op (every node already holds everything).
    /// The promise ALWAYS succeeds — a departure push is best-effort and must never gate the halt.
    public Promise<Unit> pushOnDeparture(DeparturePushObserver observer) {
        return pushOnDeparture(DEFAULT_DEPARTURE_PUSH_BUDGET, observer);
    }

    /// Budget-explicit variant of [#pushOnDeparture(DeparturePushObserver)].
    public Promise<Unit> pushOnDeparture(TimeSpan budget, DeparturePushObserver observer) {
        if (config.isFullReplication()) {
            return Promise.success(Unit.unit());
        }

        return node.storage()
                   .entries()
                   .flatMap(entries -> dispatchDeparturePush(entries, budget, observer));
    }

    /// Resolve the pending departure push matching an incoming ack's correlation id (issue #427, D2).
    /// A late ack that arrives after the budget expired simply finds no pending entry — harmless.
    @Contract
    public void onMigrationDataAck(DHTMessage.MigrationDataAck ack) {
        Option.option(pendingPushes.remove(ack.requestId()))
              .onPresent(pending -> pending.ackPromise().succeed(Unit.unit()));
    }

    private Promise<Unit> dispatchDeparturePush(List<DHTMessage.KeyValue> entries, TimeSpan budget, DeparturePushObserver observer) {
        var batches = groupByTarget(entries);

        return batches.isEmpty()
               ? Promise.success(Unit.unit())
               : awaitAcks(sendPushes(batches), budget, observer);
    }

    /// Group every locally-held entry under each node that newly becomes responsible for it, so a
    /// target receives one push carrying all of its owed chunks.
    private Map<NodeId, List<DHTMessage.KeyValue>> groupByTarget(List<DHTMessage.KeyValue> entries) {
        var replicationFactor = config.effectiveReplicationFactor(node.ring().nodeCount());

        return entries.stream()
                      .flatMap(entry -> targetPairs(entry, replicationFactor))
                      .collect(Collectors.groupingBy(TargetEntry::target,
                                                     Collectors.mapping(TargetEntry::entry, Collectors.toList())));
    }

    private record TargetEntry(NodeId target, DHTMessage.KeyValue entry) {}

    private Stream<TargetEntry> targetPairs(DHTMessage.KeyValue entry, int replicationFactor) {
        return departureTargets(entry.key(), replicationFactor).stream()
                                                               .map(target -> new TargetEntry(target, entry));
    }

    /// Post-departure delta target set for one key (issue #427, D3). `newSet` is the responsible set
    /// with self excluded (the filtered ring overload — robust whether or not self is still in the
    /// ring). When self is still a ring member (the designed drain ordering), the nodes already
    /// holding the key are the non-self members of the current responsible set, so only the genuine
    /// newcomers (`newSet \ existing`) are targeted. When self has ALREADY been pruned (the ring can
    /// no longer identify the newcomer), fall back to the whole `newSet`: the versioned puts are
    /// idempotent, so re-sending to an existing replica is harmless, and this guarantees no loss
    /// regardless of the prune-vs-drain ordering.
    private List<NodeId> departureTargets(byte[] key, int replicationFactor) {
        var self = node.nodeId();
        var newSet = node.ring().nodesFor(key, replicationFactor, candidate -> !candidate.equals(self));
        var currentSet = node.ring().nodesFor(key, replicationFactor);

        return currentSet.contains(self)
               ? excludeExistingReplicas(newSet, currentSet, self)
               : newSet;
    }

    private static List<NodeId> excludeExistingReplicas(List<NodeId> newSet, List<NodeId> currentSet, NodeId self) {
        var existing = new HashSet<>(currentSet);
        existing.remove(self);

        return newSet.stream()
                     .filter(candidate -> !existing.contains(candidate))
                     .toList();
    }

    private List<Promise<Unit>> sendPushes(Map<NodeId, List<DHTMessage.KeyValue>> batches) {
        return batches.entrySet()
                      .stream()
                      .map(this::sendAckedPush)
                      .toList();
    }

    private Promise<Unit> sendAckedPush(Map.Entry<NodeId, List<DHTMessage.KeyValue>> batch) {
        var correlationId = IdGenerator.generate();
        Promise<Unit> ackPromise = Promise.promise();

        pendingPushes.put(correlationId, new PendingPush(batch.getValue(), ackPromise));
        log.debug("Departure-pushing {} chunk(s) to {} (ack {})", batch.getValue().size(), batch.getKey().id(), correlationId);
        network.send(batch.getKey(),
                     new DHTMessage.MigrationDataResponse(correlationId, node.nodeId(), batch.getValue(), true));

        return ackPromise;
    }

    private Promise<Unit> awaitAcks(List<Promise<Unit>> acks, TimeSpan budget, DeparturePushObserver observer) {
        return Promise.allOf(acks)
                      .timeout(budget)
                      .mapToUnit()
                      .recover(_ -> reportIncomplete(observer));
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
                            .flatMap(pending -> pending.entries().stream())
                            .map(DHTMessage.KeyValue::key)
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
        var partitionKey = ("partition:" + partitionIndex).getBytes(StandardCharsets.UTF_8);
        var replicaNodes = node.ring().nodesFor(partitionKey, replicationFactor);

        if (!replicaNodes.contains(node.nodeId())) {
            return;
        }

        if (!isPrimary(replicaNodes)) {
            return;
        }

        var partition = Partition.at(partitionIndex);
        node.storage()
            .entriesForPartition(node.ring(), partition)
            .onSuccess(entries -> pushToReplicas(partitionIndex, replicaNodes, entries));
    }

    private boolean isPrimary(List<NodeId> replicaNodes) {
        return !replicaNodes.isEmpty() && replicaNodes.getFirst().equals(node.nodeId());
    }

    private void pushToReplicas(int partitionIndex, List<NodeId> replicaNodes, List<DHTMessage.KeyValue> entries) {
        if (entries.isEmpty()) {
            return;
        }

        for (var replica : replicaNodes) {
            if (replica.equals(node.nodeId())) {
                continue;
            }
            sendMigrationData(replica, partitionIndex, entries);
        }
    }

    private void sendMigrationData(NodeId target, int partitionIndex, List<DHTMessage.KeyValue> entries) {
        var correlationId = IdGenerator.generate();

        log.debug("Pushing {} entries for partition {} to {}", entries.size(), partitionIndex, target.id());

        network.send(target, new DHTMessage.MigrationDataResponse(correlationId, node.nodeId(), entries, false));
    }
}
