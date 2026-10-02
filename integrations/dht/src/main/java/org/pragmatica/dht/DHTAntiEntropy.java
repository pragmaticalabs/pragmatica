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

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.utility.IdGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Periodic anti-entropy process that synchronizes replicas.
/// Computes partition digests and exchanges them with peer nodes
/// to detect and repair inconsistencies.
public final class DHTAntiEntropy {
    private static final Logger log = LoggerFactory.getLogger(DHTAntiEntropy.class);
    /// Default anti-entropy synchronization interval.
    public static final TimeSpan DEFAULT_ANTI_ENTROPY_INTERVAL = TimeSpan.timeSpan(30).seconds();

    /// Tracks a pending digest comparison: local digest + partition for a remote peer, stamped with
    /// the monotonic time it was registered so an unanswered one can be expired.
    record PendingDigest(NodeId peer, int partitionIndex, byte[] localDigest, long createdAtNanos) {}

    /// A pull this node sent: only an answer from `peer`, carrying entries of `partitionIndex`, is applied.
    private record PendingPull(NodeId peer, int partitionIndex, long createdAtNanos) {}

    private final DHTNode node;
    private final DHTNetwork network;
    private final DHTConfig config;
    private final TimeSpan antiEntropyInterval;
    /// Which senders may push a departure batch (`ackRequested`) here: the nodes the leader commanded to
    /// drain, or that the membership has seen depart. Strict by default — no sender.
    private final Predicate<NodeId> departingSenders;

    private final AtomicReference<Option<ScheduledFuture<?>>> scheduledTask = new AtomicReference<>(Option.none());

    private final AtomicBoolean running = new AtomicBoolean(false);
    /// Pending digest comparisons indexed by correlation ID.
    private final ConcurrentHashMap<String, PendingDigest> pendingDigests = new ConcurrentHashMap<>();
    /// Pulls in flight, by correlation id.
    private final ConcurrentHashMap<String, PendingPull> pendingPulls = new ConcurrentHashMap<>();
    /// Migration batches dropped as unauthentic — counted, never silent.
    private final AtomicLong rejectedResponses = new AtomicLong();

    private DHTAntiEntropy(DHTNode node,
                           DHTNetwork network,
                           DHTConfig config,
                           TimeSpan antiEntropyInterval,
                           Predicate<NodeId> departingSenders) {
        this.node = node;
        this.network = network;
        this.config = config;
        this.antiEntropyInterval = antiEntropyInterval;
        this.departingSenders = departingSenders;
    }

    /// Create an anti-entropy process for the given node with default interval.
    ///
    /// @param node    local DHT node with storage and ring
    /// @param network cluster network for sending digest requests
    /// @param config  DHT configuration
    public static DHTAntiEntropy dhtAntiEntropy(DHTNode node, DHTNetwork network, DHTConfig config) {
        return new DHTAntiEntropy(node, network, config, DEFAULT_ANTI_ENTROPY_INTERVAL, _ -> false);
    }

    /// Create an anti-entropy process that accepts departure pushes from `departingSenders`.
    ///
    /// @param node             local DHT node with storage and ring
    /// @param network          cluster network for sending digest requests
    /// @param config           DHT configuration
    /// @param departingSenders the nodes a departure push (`ackRequested`) is accepted from
    public static DHTAntiEntropy dhtAntiEntropy(DHTNode node,
                                                DHTNetwork network,
                                                DHTConfig config,
                                                Predicate<NodeId> departingSenders) {
        return new DHTAntiEntropy(node, network, config, DEFAULT_ANTI_ENTROPY_INTERVAL, departingSenders);
    }

    /// Create an anti-entropy process for the given node with configurable interval.
    ///
    /// @param node                local DHT node with storage and ring
    /// @param network             cluster network for sending digest requests
    /// @param config              DHT configuration
    /// @param antiEntropyInterval interval between anti-entropy synchronization rounds
    public static DHTAntiEntropy dhtAntiEntropy(DHTNode node,
                                                DHTNetwork network,
                                                DHTConfig config,
                                                TimeSpan antiEntropyInterval) {
        return new DHTAntiEntropy(node, network, config, antiEntropyInterval, _ -> false);
    }

    /// Start the periodic anti-entropy process.
    @Contract
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        scheduledTask.set(Option.some(SharedScheduler.scheduleAtFixedRate(this::runAntiEntropy, antiEntropyInterval)));
        log.info("DHT anti-entropy started (interval: {}s)", antiEntropyInterval.millis() / 1000);
    }

    /// Stop the anti-entropy process.
    @Contract
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        scheduledTask.getAndSet(Option.none()).onPresent(task -> task.cancel(false));
        log.info("DHT anti-entropy stopped");
    }

    /// One synchronization round now, outside the periodic schedule: every partition this node is
    /// responsible for has its digest compared with the other responsible peers and the diff
    /// pulled. Idempotent with the scheduled rounds — the same pull, earlier. Issue #420: run by
    /// [DHTTopologyListener] when a node joins the ring, so a joiner holds its partitions before
    /// the first 30s cycle instead of counting toward the replication factor while empty.
    @Contract
    public void synchronizeNow() {
        runAntiEntropy();
    }

    private void runAntiEntropy() {
        if (config.isFullReplication()) {
            return;
        }

        try {
            synchronizePartitions();
        } catch (Exception e) {
            log.error("Anti-entropy cycle failed", e);
        }
    }

    private void synchronizePartitions() {
        expireStalePendingDigests();
        var replicationFactor = config.effectiveReplicationFactor(node.ring().nodeCount());
        var owned = 0;

        for (int p = 0; p < Partition.MAX_PARTITIONS; p++) {
            var nodes = node.ring().nodesFor(Partition.at(p), replicationFactor);

            if (!nodes.contains(node.nodeId())) {
                continue;
            }

            owned++;
            sendDigestRequests(p, nodes);
        }

        log.debug("DHT anti-entropy round: {} partitions owned, digests sent to their replicas", owned);
    }

    private void sendDigestRequests(int partitionIndex, List<NodeId> nodes) {
        var partition = Partition.at(partitionIndex);

        node.storage()
            .entriesForPartition(node.ring(),
                                 partition)
            .onSuccess(entries -> {
                           var digest = computeDigest(entries);

                           sendDigestToPeers(partitionIndex, nodes, digest);
                       });
    }

    private byte[] computeDigest(List<DHTMessage.KeyValue> entries) {
        return DHTNode.computeDigest(entries);
    }

    private void sendDigestToPeers(int partitionIndex, List<NodeId> nodes, byte[] localDigest) {
        for (var peer : nodes) {
            if (peer.equals(node.nodeId())) {
                continue;
            }

            var correlationId = IdGenerator.generate();

            pendingDigests.put(correlationId, new PendingDigest(peer, partitionIndex, localDigest, System.nanoTime()));
            sendLoudly(peer,
                       new DHTMessage.DigestRequest(correlationId, node.nodeId(), partitionIndex, partitionIndex),
                       "digest request",
                       () -> pendingDigests.remove(correlationId));
        }
    }

    /// A refused send is never silent (issue #420): the transport's refusal is logged at WARN and the
    /// round is not retried — the exchange is repeated every interval until a round completes.
    /// `onNotSent` runs on any outcome that did not reach the peer, so a caller that registered
    /// per-send state can drop it again; without that, sustained backpressure — the very condition
    /// that refuses the send — adds one `pendingDigests` entry per owned partition per peer, every
    /// round, with nothing to remove them.
    private void sendLoudly(NodeId peer, ProtocolMessage message, String what, Runnable onNotSent) {
        network.sendOutcome(peer, message)
               .onSuccess(outcome -> {
                              if (!outcome.isSent()) {
                              log.warn("DHT anti-entropy {} to {} not sent ({}); the next round repeats it",
                                       what,
                                       peer.id(),
                                       outcome);
                              onNotSent.run();
                          }
                          })
               .onFailure(cause -> {
                              log.warn("DHT anti-entropy {} to {} failed ({}); the next round repeats it",
                                       what,
                                       peer.id(),
                                       cause.message());
                              onNotSent.run();
                          });
    }

    /// Drop correlations whose response never came. One interval after a digest was sent the peer is
    /// not going to answer that round's question, and the next round asks it again with a fresh
    /// correlation; keeping the old entry only grows the map.
    private void expireStalePendingDigests() {
        var deadline = System.nanoTime() - antiEntropyInterval.nanos();

        pendingDigests.values().removeIf(pending -> pending.createdAtNanos() - deadline <= 0);
        pendingPulls.values().removeIf(pending -> pending.createdAtNanos() - deadline <= 0);
    }

    /// Handle a digest response from a remote peer.
    /// Compares local vs remote digest; if they differ, requests migration data.
    @Contract
    public void onDigestResponse(DHTMessage.DigestResponse response) {
        Option.option(pendingDigests.remove(response.requestId())).onPresent(pending -> handleDigestComparison(pending,
                                                                                                               response));
    }

    private void handleDigestComparison(PendingDigest pending, DHTMessage.DigestResponse response) {
        if (Arrays.equals(pending.localDigest(), response.digest())) {
            log.debug("Partition {} in sync with {}",
                      pending.partitionIndex(),
                      pending.peer().id());

            return;
        }

        if (!isLocalReplicaOf(pending.partitionIndex())) {
            log.debug("Partition {} diverged from {} but is no longer a local replica; not pulling",
                      pending.partitionIndex(),
                      pending.peer().id());

            return;
        }

        log.info("Partition {} diverged from {}, requesting migration data",
                 pending.partitionIndex(),
                 pending.peer().id());
        requestMigrationData(pending.peer(), pending.partitionIndex());
    }

    /// Whether this node is still a replica of the partition, re-evaluated when the digest RESPONSE
    /// lands rather than only when the request went out: a joiner's ring grows one `NodeJoined` at a
    /// time, so the view that justified the digest can already be stale by the time it is answered.
    /// The holder applies the same test against ITS ring ([DHTNode#handleMigrationDataRequest]) — a
    /// replica is acquired only where the two views agree (issue #420).
    private boolean isLocalReplicaOf(int partitionIndex) {
        var replicationFactor = config.effectiveReplicationFactor(node.ring().nodeCount());

        return node.ring()
                   .nodesFor(Partition.at(partitionIndex),
                             replicationFactor)
                   .contains(node.nodeId());
    }

    /// Handle migration data response: merge received entries into local storage, then acknowledge
    /// when the sender requested it (issue #427). The ack is sent only for `ackRequested` responses —
    /// the departing-node push — so the fire-and-forget anti-entropy pull path is unchanged. The ack is
    /// honest (issue #1818): it carries whether every entry was applied, so a batch the receiver failed
    /// to store is nacked and the departing sender counts it as not delivered.
    ///
    /// Only an AUTHENTIC batch is applied (#1818 round 3); anything else is dropped with a WARN and counted:
    ///   - a pull answer must match a pull this node sent — its correlation id, from the node asked, with
    ///     every entry in the partition asked for;
    ///   - an unsolicited departure push (`ackRequested`) must come from a departing node, and is nacked
    ///     otherwise so the pusher keeps the batch at risk;
    ///   - an unsolicited survivor-rebalance push must come from a co-replica, in this node's ring, of
    ///     every entry's partition — the authority `DHTRebalancer` pushes under.
    @Contract
    public void onMigrationDataResponse(DHTMessage.MigrationDataResponse response) {
        Option.option(pendingPulls.get(response.requestId()))
              .onPresent(pull -> onPullAnswer(pull, response))
              .onEmpty(() -> onUnsolicited(response));
    }

    /// Batches dropped as unauthentic since start (#1818 round 3).
    long rejectedResponseCount() {
        return rejectedResponses.get();
    }

    private void onPullAnswer(PendingPull pull, DHTMessage.MigrationDataResponse response) {
        if (!pull.peer().equals(response.sender())) {
            reject(response, "answer to a pull sent to " + pull.peer().id());

            return;
        }

        pendingPulls.remove(response.requestId());

        if (!allInPartition(response.entries(), pull.partitionIndex())) {
            reject(response, "entries outside the requested partition " + pull.partitionIndex());

            return;
        }

        applyAndAcknowledge(response);
    }

    private void onUnsolicited(DHTMessage.MigrationDataResponse response) {
        if (response.ackRequested()) {
            acceptDeparturePush(response);
        } else {
            acceptRebalancePush(response);
        }
    }

    private void acceptDeparturePush(DHTMessage.MigrationDataResponse response) {
        if (departingSenders.test(response.sender())) {
            applyAndAcknowledge(response);

            return;
        }

        reject(response, "departure push from a node that is not departing");
        acknowledge(response, false);
    }

    private void acceptRebalancePush(DHTMessage.MigrationDataResponse response) {
        if (response.entries().stream().allMatch(entry -> coReplicaOfEntry(response.sender(), entry))) {
            applyAndAcknowledge(response);
        } else {
            reject(response, "unsolicited batch from a node that is not a co-replica of its partitions");
        }
    }

    /// Whether both `sender` and this node replicate the entry's partition in this node's ring.
    private boolean coReplicaOfEntry(NodeId sender, DHTMessage.KeyValue entry) {
        var replicationFactor = config.effectiveReplicationFactor(node.ring().nodeCount());
        var replicas = node.ring()
                           .nodesFor(entry.key(), replicationFactor);

        return replicas.contains(sender) && replicas.contains(node.nodeId());
    }

    private boolean allInPartition(List<DHTMessage.KeyValue> entries, int partitionIndex) {
        return entries.stream()
                      .allMatch(entry -> node.ring().partitionFor(entry.key()).value() == partitionIndex);
    }

    private void applyAndAcknowledge(DHTMessage.MigrationDataResponse response) {
        applyMigrationEntries(response).onSuccess(applied -> acknowledge(response, applied));
    }

    private void reject(DHTMessage.MigrationDataResponse response, String reason) {
        rejectedResponses.incrementAndGet();
        log.warn("Rejected {} migrated entries from {} (request {}): {}",
                 response.entries().size(),
                 response.sender().id(),
                 response.requestId(),
                 reason);
    }

    private Promise<Boolean> applyMigrationEntries(DHTMessage.MigrationDataResponse response) {
        if (response.entries().isEmpty()) {
            return Promise.success(true);
        }

        log.info("Received {} entries from {} for repair",
                 response.entries().size(),
                 response.sender().id());

        return node.applyMigrationData(response.entries());
    }

    private void acknowledge(DHTMessage.MigrationDataResponse response, boolean applied) {
        if (!response.ackRequested()) {
            return;
        }

        if (!applied) {
            log.warn("Nacking {} migrated entries from {}: not every entry could be stored",
                     response.entries().size(),
                     response.sender().id());
        }

        network.send(response.sender(),
                     new DHTMessage.MigrationDataAck(response.requestId(), node.nodeId(), applied));
    }

    private void requestMigrationData(NodeId peer, int partitionIndex) {
        var correlationId = IdGenerator.generate();

        pendingPulls.put(correlationId, new PendingPull(peer, partitionIndex, System.nanoTime()));
        sendLoudly(peer,
                   new DHTMessage.MigrationDataRequest(correlationId, node.nodeId(), partitionIndex, partitionIndex),
                   "migration request",
                   () -> pendingPulls.remove(correlationId));
    }

    /// Get the count of pending digest comparisons (for testing).
    int pendingDigestCount() {
        return pendingDigests.size();
    }
}
