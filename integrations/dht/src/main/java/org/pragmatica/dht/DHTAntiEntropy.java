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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.storage.KeyStamp;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.utility.IdGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// Periodic anti-entropy process that synchronizes replicas.
/// Computes partition digests and exchanges them with peer nodes
/// to detect and repair inconsistencies.
///
/// It also FILLS partitions this node holds as a replica without yet being authoritative for them (#1777
/// track 2, [CatchUpState]): each such partition runs a [CatchUpRound] against its co-replicas and its
/// surviving previous holders, and becomes serving only once what the sources hold has been stored here.
public final class DHTAntiEntropy {
    private static final Logger log = LoggerFactory.getLogger(DHTAntiEntropy.class);
    /// Default anti-entropy synchronization interval.
    public static final TimeSpan DEFAULT_ANTI_ENTROPY_INTERVAL = TimeSpan.timeSpan(30).seconds();
    /// Cadence of the catch-up tick ([#catchUpNow]): pending partitions are retried this often, so a
    /// node that cannot yet answer authoritatively is filled within about one tick of its sources
    /// answering, rather than within one 30 s anti-entropy period.
    public static final TimeSpan CATCH_UP_INTERVAL = TimeSpan.timeSpan(1).seconds();
    /// A catch-up round that has not decided and completed within this long is abandoned and restarted.
    static final TimeSpan CATCH_UP_ROUND_TIMEOUT = TimeSpan.timeSpan(5).seconds();
    /// A pending partition that has started this many catch-up rounds without completing is reported stuck:
    /// one WARN when it crosses, and [DHTNode#stuckCatchUpPartitions] counts it.
    public static final int STUCK_AFTER_ROUNDS = 10;

    /// Tracks a pending digest comparison: local digest + partition for a remote peer, stamped with
    /// the monotonic time it was registered so an unanswered one can be expired.
    record PendingDigest(NodeId peer, int partitionIndex, byte[] localDigest, long createdAtNanos) {}

    /// A digest request sent for a catch-up round; answered into that round only.
    private record CatchUpDigest(NodeId peer, byte[] localDigest, CatchUpRound round, long createdAtNanos) {}

    /// A pull in flight, remembered so its answer is attributed to its partition — and, for a catch-up
    /// pull, completes that round's wait on `peer` once its entries are stored.
    private record PendingPull(NodeId peer,
                               int partitionIndex,
                               Option<CatchUpRound> round,
                               PullProgress progress,
                               long createdAtNanos) {}

    /// A periodic paged pull, by peer and partition: where its next page starts (A6).
    private record PullKey(NodeId peer, int partitionIndex) {}

    /// How far a paged pull has come (A6, D4): when its FIRST page was requested, on the node's monotonic clock, and
    /// whether every page so far came from a holder authoritative for the partition. A completed pull certifies the
    /// partition only as of `startedAtMillis`: a resumed pull does not read again the keys below its cursor, so the
    /// pages read before an interruption can predate a remove the completion would otherwise vouch for.
    private record PullProgress(long startedAtMillis, boolean authoritative) {
        PullProgress after(DHTMessage.Readiness readiness) {
            return new PullProgress(startedAtMillis, authoritative && readiness.authoritative());
        }
    }

    /// Where an interrupted periodic paged pull resumes, with the progress it carries across rounds (A6).
    private record Resume(byte[] cursor, PullProgress progress) {}

    /// This node's digests of one partition for one exchange (A6): the stamp digest every current peer is asked to
    /// match, and the legacy value digest, computed only when a peer recorded as legacy takes part.
    private record LocalDigests(byte[] stamp, Option<byte[]> legacy) {}

    private final DHTNode node;
    private final DHTNetwork network;
    /// Fixed for the [DHTConfig]-taking factories; the node's live replication for the production one (#1777 track 1).
    private final Supplier<DHTConfig> config;
    private final TimeSpan antiEntropyInterval;
    private final TimeSpan catchUpRoundTimeout;
    /// The monotonic clock catch-up rounds read their ages from: `System.nanoTime` in production, a manual clock in a test.
    private final LongSupplier clock;
    /// Which senders may push a departure batch (`ackRequested`) here: the nodes the leader commanded to
    /// drain, or that the membership has seen depart. Strict by default — no sender.
    private final Predicate<NodeId> departingSenders;

    private final AtomicReference<Option<ScheduledFuture<?>>> scheduledTask = new AtomicReference<>(Option.none());

    private final AtomicReference<Option<ScheduledFuture<?>>> scheduledCatchUp = new AtomicReference<>(Option.none());

    private final AtomicBoolean running = new AtomicBoolean(false);
    /// Pending digest comparisons indexed by correlation ID.
    private final ConcurrentHashMap<String, PendingDigest> pendingDigests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CatchUpDigest> catchUpDigests = new ConcurrentHashMap<>();
    /// Pulls in flight, by correlation id: an answer is attributed to its partition — and, for a catch-up
    /// pull, to its round — only when it comes from the node asked (#1818 round 3, #1777 track 2).
    private final ConcurrentHashMap<String, PendingPull> pendingPulls = new ConcurrentHashMap<>();
    /// The live catch-up round per pending partition index.
    private final ConcurrentHashMap<Integer, CatchUpRound> rounds = new ConcurrentHashMap<>();
    /// The current periodic round per partition, as a vote on tombstone collection (#1777 track 3).
    private final ConcurrentHashMap<Integer, AgreementRound> agreements = new ConcurrentHashMap<>();
    /// The agreement round each periodic digest request answers into, by correlation id.
    private final ConcurrentHashMap<String, AgreementRound> agreementVotes = new ConcurrentHashMap<>();
    private final AtomicLong roundIds = new AtomicLong();
    /// Pulls a holder refused (ring disagreement or an unreadable store) — counted, never silent (#1777).
    private final AtomicLong refusedPulls = new AtomicLong();
    /// Migration batches dropped as unauthentic — counted, never silent.
    private final AtomicLong rejectedResponses = new AtomicLong();
    /// Where an interrupted periodic paged pull resumes (A6): the last key applied from that peer for that
    /// partition, and the pull's progress. Cleared when the pull reaches the partition's last page.
    private final ConcurrentHashMap<PullKey, Resume> resumeAt = new ConcurrentHashMap<>();
    /// Stamp digest probes sent to peers held as legacy, by correlation id: an answer is current evidence ([RepairPlane]).
    private final ConcurrentHashMap<String, PendingProbe> stampProbes = new ConcurrentHashMap<>();

    private record PendingProbe(NodeId peer, long createdAtNanos) {}

    private DHTAntiEntropy(DHTNode node,
                           DHTNetwork network,
                           Supplier<DHTConfig> config,
                           TimeSpan antiEntropyInterval,
                           TimeSpan catchUpRoundTimeout,
                           Predicate<NodeId> departingSenders,
                           LongSupplier clock) {
        this.node = node;
        this.network = network;
        this.config = config;
        this.antiEntropyInterval = antiEntropyInterval;
        this.catchUpRoundTimeout = catchUpRoundTimeout;
        this.departingSenders = departingSenders;
        this.clock = clock;
        node.ring().onNodeRemoved(this::dropRoundsAsking);
        node.ring().onNodeRemoved(this::forgetRepairPeer);
        node.repair()
            .useFreshnessWindow(TimeSpan.timeSpan(RepairPlane.DEFAULT_FRESHNESS_INTERVALS * antiEntropyInterval.millis()).millis());
    }

    /// Create an anti-entropy process for the given node with default interval.
    ///
    /// @param node    local DHT node with storage and ring
    /// @param network cluster network for sending digest requests
    /// @param config  DHT configuration
    public static DHTAntiEntropy dhtAntiEntropy(DHTNode node, DHTNetwork network, DHTConfig config) {
        return new DHTAntiEntropy(node,
                                  network,
                                  () -> config,
                                  DEFAULT_ANTI_ENTROPY_INTERVAL,
                                  CATCH_UP_ROUND_TIMEOUT,
                                  _ -> false,
                                  System::nanoTime);
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
        return new DHTAntiEntropy(node,
                                  network,
                                  () -> config,
                                  DEFAULT_ANTI_ENTROPY_INTERVAL,
                                  CATCH_UP_ROUND_TIMEOUT,
                                  departingSenders,
                                  System::nanoTime);
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
        return new DHTAntiEntropy(node,
                                  network,
                                  () -> config,
                                  antiEntropyInterval,
                                  CATCH_UP_ROUND_TIMEOUT,
                                  _ -> false,
                                  System::nanoTime);
    }

    /// Create an anti-entropy process that follows the node's LIVE replication ([DHTNode#config], #1777 track 1)
    /// and accepts departure pushes from `departingSenders`. Rounds wait until the node has resolved the
    /// cluster's committed replication: before that it cannot tell which partitions it replicates.
    public static DHTAntiEntropy dhtAntiEntropy(DHTNode node, DHTNetwork network, Predicate<NodeId> departingSenders) {
        return new DHTAntiEntropy(node,
                                  network,
                                  node::config,
                                  DEFAULT_ANTI_ENTROPY_INTERVAL,
                                  CATCH_UP_ROUND_TIMEOUT,
                                  departingSenders,
                                  System::nanoTime);
    }

    /// Test seam: an anti-entropy process whose catch-up rounds time out after `catchUpRoundTimeout`.
    static DHTAntiEntropy dhtAntiEntropy(DHTNode node,
                                         DHTNetwork network,
                                         DHTConfig config,
                                         TimeSpan antiEntropyInterval,
                                         TimeSpan catchUpRoundTimeout) {
        return new DHTAntiEntropy(node,
                                  network,
                                  () -> config,
                                  antiEntropyInterval,
                                  catchUpRoundTimeout,
                                  _ -> false,
                                  System::nanoTime);
    }

    /// Test seam: catch-up rounds read their ages from `clock` instead of `System.nanoTime`, so a test advances time exactly
    /// and a stall between two ticks can neither be missed nor invented.
    static DHTAntiEntropy dhtAntiEntropy(DHTNode node,
                                         DHTNetwork network,
                                         DHTConfig config,
                                         TimeSpan antiEntropyInterval,
                                         TimeSpan catchUpRoundTimeout,
                                         LongSupplier clock) {
        return new DHTAntiEntropy(node,
                                  network,
                                  () -> config,
                                  antiEntropyInterval,
                                  catchUpRoundTimeout,
                                  _ -> false,
                                  clock);
    }

    /// Start the periodic anti-entropy process.
    @Contract
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        scheduledTask.set(Option.some(SharedScheduler.scheduleAtFixedRate(this::runAntiEntropy, antiEntropyInterval)));
        scheduledCatchUp.set(Option.some(SharedScheduler.scheduleAtFixedRate(this::catchUpNow, CATCH_UP_INTERVAL)));
        log.info("DHT anti-entropy started (interval: {}s)", antiEntropyInterval.millis() / 1000);
    }

    /// Stop the anti-entropy process.
    @Contract
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        scheduledTask.getAndSet(Option.none()).onPresent(task -> task.cancel(false));
        scheduledCatchUp.getAndSet(Option.none()).onPresent(task -> task.cancel(false));
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

    /// One catch-up tick (#1777 track 2): every partition this node owns but is not yet authoritative
    /// for gets a [CatchUpRound], unless one is already in flight and younger than
    /// [#CATCH_UP_ROUND_TIMEOUT]. Cheap when nothing is pending. Run every [#CATCH_UP_INTERVAL].
    @Contract
    public void catchUpNow() {
        if (config.get().isFullReplication() || !node.replicationResolved()) {
            return;
        }

        var replicationFactor = config.get().effectiveReplicationFactor(node.ring().nodeCount());

        node.pendingPartitions().forEach(partition -> catchUpIfOwned(partition, replicationFactor));
    }

    /// Pulls a holder refused since start — ring disagreement or an unreadable store (#1777).
    long refusedPullCount() {
        return refusedPulls.get();
    }

    private void runAntiEntropy() {
        if (config.get().isFullReplication() || !node.replicationResolved()) {
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
        purgeStrays();
        probeLegacyPeers();
        var replicationFactor = config.get().effectiveReplicationFactor(node.ring().nodeCount());
        var owned = 0;

        for (int p = 0; p < Partition.MAX_PARTITIONS; p++) {
            var nodes = node.ring().nodesFor(Partition.at(p), replicationFactor);

            if (!nodes.contains(node.nodeId())) {
                node.repair().forgetPartition(p);
                continue;
            }

            owned++;
            synchronizeOwned(Partition.at(p), nodes);
        }

        log.debug("DHT anti-entropy round: {} partitions owned, digests sent to their replicas", owned);
    }

    /// An owned partition this node is authoritative for is compared with its co-replicas; one it is
    /// still catching up on runs a catch-up round instead.
    private void synchronizeOwned(Partition partition, List<NodeId> nodes) {
        if (node.readiness(partition).authoritative()) {
            sendDigestRequests(partition.value(), nodes);
        } else {
            startCatchUpRound(partition, nodes);
        }
    }

    private void catchUpIfOwned(Partition partition, int replicationFactor) {
        var nodes = node.ring().nodesFor(partition, replicationFactor);

        if (nodes.contains(node.nodeId())) {
            startCatchUpRound(partition, nodes);
        }
    }

    private void startCatchUpRound(Partition partition, List<NodeId> coReplicas) {
        // A round begun for an earlier pending spell (the partition was lost and regained since) is not in
        // flight for this one: it is replaced, and its late pulls complete nothing (see #isCurrent).
        var inFlight = Option.option(rounds.get(partition.value())).filter(this::isCurrent);

        if (inFlight.filter(round -> !round.olderThan(catchUpRoundTimeout.nanos())).isPresent()) {
            return;
        }
        // A round that has decided keeps its pulls for one more round timeout (#1777, K5): a decision taken on
        // the answers in hand happens AFTER the round timed out, and replacing it on the next tick would drop
        // a pull slower than a tick — exactly the backpressured lane the early decision exists for.
        if (inFlight.filter(round -> round.decidedWithin(catchUpRoundTimeout.nanos())).isPresent()) {
            return;
        }

        if (inFlight.filter(this::decidedOnAnswersInHand).isPresent()) {
            return;
        }

        var candidates = ringSources(partition, coReplicas);
        var sources = liveOnly(candidates);

        if (sources.isEmpty()) {
            completeWhenAlone(partition, candidates);

            return;
        }

        var round = CatchUpRound.catchUpRound(roundIds.incrementAndGet(),
                                              node.catchUpGeneration(partition),
                                              partition,
                                              sources,
                                              anchors(partition, coReplicas),
                                              clock);

        rounds.put(partition.value(), round);
        warnIfStuck(partition, node.noteCatchUpRound(partition), sources);
        // A local store that cannot be read sends no digest; the round never decides, expires after
        // CATCH_UP_ROUND_TIMEOUT and is restarted by the next tick — FER: the catch-up is delayed, never
        // completed on a read that did not happen.
        localDigests(partition, sources).onSuccess(digests -> sendCatchUpDigests(round, digests));
    }

    /// The current co-replicas plus every recorded previous holder still in the ring — the nodes that may
    /// hold this partition's data (#1777, ruling C1). Self is never a source, and neither is a node the
    /// transport does not consider live: a dead source never answers, and a round waits for every source,
    /// so it would never decide (the same liveness view the read path filters its targets by).
    /// A round that timed out with a source still silent decides on the answers in hand when a serving source
    /// answered (#1777, H2): a live-but-silent node — SUSPECT, a backpressured lane — must not hold the
    /// partition catching up while an authoritative answer is in. Pulls from every answered source whose
    /// digest differs; the silent one's data is not waited for.
    private boolean decidedOnAnswersInHand(CatchUpRound round) {
        if (!round.decideOnAnswersInHand()) {
            return false;
        }

        log.info("Catch-up of partition {}: deciding on the answers in hand; {} of {} sources stayed silent",
                 round.partition().value(),
                 round.silentCount(),
                 round.sources().size());
        decideCatchUp(round);

        return true;
    }

    private void warnIfStuck(Partition partition, int rounds, Set<NodeId> sources) {
        if (rounds == STUCK_AFTER_ROUNDS) {
            log.warn("Partition {} is still catching up after {} rounds: no serving source among {} has answered",
                     partition.value(),
                     rounds,
                     sources.stream().map(NodeId::id).toList());
        }
    }

    private Set<NodeId> ringSources(Partition partition, List<NodeId> coReplicas) {
        var members = node.ring().nodes();
        var sources = new HashSet<>(coReplicas);

        node.previousHolders(partition).stream().filter(members::contains).forEach(sources::add);
        sources.remove(node.nodeId());

        return Set.copyOf(sources);
    }

    private Set<NodeId> anchors(Partition partition, List<NodeId> coReplicas) {
        var anchors = new HashSet<>(coReplicas);

        anchors.addAll(node.recordedPreviousHolders(partition));

        return Set.copyOf(anchors);
    }

    private Set<NodeId> liveOnly(Set<NodeId> candidates) {
        var sources = new HashSet<>(candidates);

        retainLive(sources);

        return Set.copyOf(sources);
    }

    /// No live source: complete only when the ring holds no other node at all (a single-node DHT). When
    /// sources exist but none is live — a liveness view that has not yet learned its peers, or every
    /// holder departing — nothing has been heard, so the partition stays catching up; a dead source
    /// stops blocking once it leaves the ring, and the next tick retries.
    private void completeWhenAlone(Partition partition, Set<NodeId> candidates) {
        if (candidates.isEmpty()) {
            completeCatchUp(partition, true);
        } else {
            log.debug("Catch-up of partition {} waits: none of its {} sources is live",
                      partition.value(),
                      candidates.size());
        }
    }

    /// An empty live set means the adapter has no liveness view (non-cluster paths): every source stays.
    private void retainLive(Set<NodeId> sources) {
        var live = network.livePeers();

        if (!live.isEmpty()) {
            sources.retainAll(live);
        }
    }

    private void sendCatchUpDigests(CatchUpRound round, LocalDigests digests) {
        round.sources().forEach(source -> sendCatchUpDigest(round, source, digests));
    }

    private void sendCatchUpDigest(CatchUpRound round, NodeId source, LocalDigests digests) {
        var correlationId = IdGenerator.generate();
        var partitionIndex = round.partition().value();

        catchUpDigests.put(correlationId,
                           new CatchUpDigest(source, digestFor(source, digests), round, System.nanoTime()));
        sendLoudly(source,
                   digestRequest(correlationId, source, partitionIndex),
                   "catch-up digest request",
                   () -> catchUpDigests.remove(correlationId));
    }

    private void onCatchUpDigest(CatchUpDigest pending, DHTMessage.DigestResponse response) {
        var matches = Arrays.equals(pending.localDigest(), response.digest());

        if (isCurrent(pending.round()) && pending.round().answer(pending.peer(), response.readiness(), matches)) {
            decideCatchUp(pending.round());
        }
    }

    /// Every source has answered: pull from the authoritative ones (or, with none, from all) whose digest
    /// differs, or complete at once when nothing differs. An UNKNOWN answer abandons the round.
    private void decideCatchUp(CatchUpRound round) {
        if (round.anyUnknown()) {
            log.info("Catch-up of partition {} abandoned: a source could not report its state; retrying",
                     round.partition().value());
            rounds.remove(round.partition().value(),
                          round);

            return;
        }

        var targets = round.pullTargets();

        if (targets.isEmpty()) {
            completeCatchUp(round.partition(), round.anchorless());
            rounds.remove(round.partition().value(),
                          round);

            return;
        }

        targets.forEach(target -> requestCatchUpPull(round, target));
    }

    private void requestCatchUpPull(CatchUpRound round, NodeId source) {
        sendPull(source,
                 round.partition().value(),
                 Option.some(round),
                 "catch-up pull");
    }

    private void onCatchUpPull(PendingPull pull, CatchUpRound round, DHTMessage.MigrationDataResponse response) {
        // Readback runs once the apply settled. As at round start, a failed apply or an unreadable store leaves
        // the round undecided until it expires and restarts.
        applyMigrationEntries(response).flatMap(_ -> node.storage()
                                                         .stampsForPartition(node.ring(),
                                                                             round.partition()))
                             .onSuccess(local -> verifyCatchUpPull(pull.peer(),
                                                                   round,
                                                                   response.entries(),
                                                                   local));
    }

    /// Completion is proven by READBACK, not by the apply call: every pulled entry must now be stored
    /// here at a version at least as new. A copy the store refused leaves the partition catching up.
    private void verifyCatchUpPull(NodeId peer,
                                   CatchUpRound round,
                                   List<DHTMessage.KeyValue> pulled,
                                   List<KeyStamp> local) {
        if (!allStored(pulled, local)) {
            log.warn("Catch-up of partition {}: entries pulled from {} were not stored; retrying",
                     round.partition().value(),
                     peer.id());
            rounds.remove(round.partition().value(),
                          round);

            return;
        }

        if (isCurrent(round) && round.pullStored(peer)) {
            completeCatchUp(round.partition(), round.anchorless());
            rounds.remove(round.partition().value(),
                          round);
        }
    }

    /// The readback compares against the local STAMPS (A6): proving a copy stored needs its stamp, not its value.
    private boolean allStored(List<DHTMessage.KeyValue> pulled, List<KeyStamp> local) {
        var stored = local.stream()
                          .collect(Collectors.toMap(DHTAntiEntropy::keyOf,
                                                    stamp -> stamp,
                                                    (left, right) -> left));

        return pulled.stream()
                     .allMatch(entry -> isStored(stored, entry));
    }

    /// Stored means the local entry is at least as new as the pulled one under the store's own ordering — owner
    /// epoch first, then HLC version (#1818). A copy kept out by a newer-epoch entry with a lower HLC version is
    /// superseded, not refused; comparing versions alone would leave the partition catching up forever. An EXPIRED
    /// tombstone with no local entry is satisfied too (#1777 track 3): it is deliberately never re-created.
    private boolean isStored(Map<String, KeyStamp> stored, DHTMessage.KeyValue entry) {
        return Option.option(stored.get(keyOf(entry)))
                     .map(local -> local.compareOrder(entry) >= 0)
                     .or(() -> node.expiredTombstone(entry));
    }

    private static String keyOf(DHTMessage.KeyValue entry) {
        return Arrays.toString(entry.key());
    }

    private static String keyOf(KeyStamp stamp) {
        return Arrays.toString(stamp.key());
    }

    /// A round whose sources include a node the ring has since pruned is dropped (#1777 Q2): it would keep
    /// waiting on that node, or deciding without it, on a source set frozen before the prune. The removal's own
    /// catch-up tick then starts a fresh round from the current ring. A dropped round is no longer current, so
    /// its late answers complete nothing.
    private void dropRoundsAsking(NodeId removed) {
        rounds.values().removeIf(round -> asks(round, removed));
    }

    /// A node that left the ring takes its divergence, its legacy record and its resume cursors with it (A6).
    private void forgetRepairPeer(NodeId removed) {
        node.repair().forgetPeer(removed);
        resumeAt.keySet().removeIf(key -> key.peer()
                                             .equals(removed));
    }

    private static boolean asks(CatchUpRound round, NodeId node) {
        return round.sources()
                    .contains(node);
    }

    private boolean isCurrent(CatchUpRound round) {
        return rounds.get(round.partition().value()) == round && round.generation() == node.catchUpGeneration(round.partition());
    }

    private void completeCatchUp(Partition partition, boolean anchorless) {
        node.markServing(partition);
        if (anchorless) {
            log.warn("Partition {} is now served without an authoritative source: every live co-replica and "
                    + "previous holder was itself catching up, so its absent answers are best-effort",
                     partition.value());
        } else {
            log.debug("Partition {} caught up", partition.value());
        }
    }

    /// A refused pull is counted and logged at INFO with its partition (#1777): a holder whose ring
    /// disagrees refuses every round, and a DEBUG line would make that look healthy. A refused catch-up
    /// pull also abandons its round, so the next tick retries rather than waiting on it.
    private void onRefusedPull(PendingPull pull) {
        refusedPulls.incrementAndGet();
        log.info("Migration pull of partition {} refused by {}: not a replica in its ring, or its store was unreadable",
                 pull.partitionIndex(),
                 pull.peer().id());
        pull.round().onPresent(round -> rounds.remove(round.partition().value(),
                                                      round));
    }

    /// A pull from a peer recorded as legacy is the whole-partition [DHTMessage.MigrationDataRequest] it understands;
    /// any other pull is paged (A6). A periodic paged pull resumes where an interrupted one stopped; a catch-up pull
    /// always starts at the first key, since its completion is proven page by page within one round. A resumed pull
    /// keeps the progress of the pull it resumes, so its completion is stamped with the time ITS first page was asked.
    private void sendPull(NodeId peer, int partitionIndex, Option<CatchUpRound> round, String what) {
        if (node.repair().isLegacyPeer(peer)) {
            sendLegacyPull(peer, partitionIndex, round, what);
        } else {
            sendResumedPageRequest(peer, partitionIndex, round, resumeFrom(peer, partitionIndex, round), what);
        }
    }

    private void sendResumedPageRequest(NodeId peer,
                                        int partitionIndex,
                                        Option<CatchUpRound> round,
                                        Option<Resume> resume,
                                        String what) {
        sendPageRequest(peer,
                        partitionIndex,
                        round,
                        resume.map(Resume::cursor),
                        resume.map(Resume::progress).or(this::pullStartingNow),
                        what);
    }

    private PullProgress pullStartingNow() {
        return new PullProgress(node.monotonicMillis(), true);
    }

    private void sendLegacyPull(NodeId peer, int partitionIndex, Option<CatchUpRound> round, String what) {
        var correlationId = IdGenerator.generate();

        node.repair().noteLegacyExchange();
        pendingPulls.put(correlationId,
                         new PendingPull(peer, partitionIndex, round, pullStartingNow(), System.nanoTime()));
        sendLoudly(peer,
                   new DHTMessage.MigrationDataRequest(correlationId, node.nodeId(), partitionIndex, partitionIndex),
                   what,
                   () -> pendingPulls.remove(correlationId));
    }

    private Option<Resume> resumeFrom(NodeId peer, int partitionIndex, Option<CatchUpRound> round) {
        return round.isPresent()
               ? none()
               : option(resumeAt.get(new PullKey(peer, partitionIndex)));
    }

    private void sendPageRequest(NodeId peer,
                                 int partitionIndex,
                                 Option<CatchUpRound> round,
                                 Option<byte[]> after,
                                 PullProgress progress,
                                 String what) {
        var correlationId = IdGenerator.generate();

        pendingPulls.put(correlationId, new PendingPull(peer, partitionIndex, round, progress, System.nanoTime()));
        sendLoudly(peer,
                   new DHTMessage.MigrationPageRequest(correlationId,
                                                       node.nodeId(),
                                                       partitionIndex,
                                                       after,
                                                       node.repair().pageBudget().bytes()),
                   what,
                   () -> pendingPulls.remove(correlationId));
    }

    private void sendDigestRequests(int partitionIndex, List<NodeId> nodes) {
        var partition = Partition.at(partitionIndex);
        var startedAt = node.nowMillis();

        localDigests(partition, coReplicas(nodes)).onSuccess(digests -> startAgreementRound(partition,
                                                                                            startedAt,
                                                                                            nodes,
                                                                                            digests));
    }

    private void startAgreementRound(Partition partition, long startedAt, List<NodeId> nodes, LocalDigests digests) {
        var round = AgreementRound.agreementRound(partition, startedAt, coReplicas(nodes));

        agreements.put(partition.value(), round);
        sendDigestToPeers(partition.value(), nodes, digests, round);
        if (round.agreed()) {
            onAgreed(round);
        }
    }

    /// This node's digests of `partition` for an exchange with `peers` (A6): the stamp digest, which reads no value,
    /// and — only when one of `peers` is recorded as legacy — the legacy value digest that peer computes.
    private Promise<LocalDigests> localDigests(Partition partition, Set<NodeId> peers) {
        return Promise.all(node.storage().stampsForPartition(node.ring(),
                                                             partition).map(node::stampDigestOf),
                           legacyDigest(partition, peers))
                      .map(LocalDigests::new);
    }

    private Promise<Option<byte[]>> legacyDigest(Partition partition, Set<NodeId> peers) {
        return peers.stream()
                    .anyMatch(node.repair()::isLegacyPeer)
               ? valueDigest(partition).map(Option::some)
               : Promise.success(none());
    }

    /// The legacy digest, over values: read only for an exchange with a legacy peer.
    private Promise<byte[]> valueDigest(Partition partition) {
        return node.storage()
                   .entriesForPartition(node.ring(),
                                        partition)
                   .map(this::computeDigest);
    }

    /// The digest `peer` is compared against: the legacy one for a peer recorded as legacy, else the stamp digest.
    private byte[] digestFor(NodeId peer, LocalDigests digests) {
        return node.repair()
                   .isLegacyPeer(peer)
               ? digests.legacy()
                        .or(digests.stamp())
               : digests.stamp();
    }

    /// The digest question `peer` understands: the legacy [DHTMessage.DigestRequest] for a peer recorded as legacy,
    /// else the [DHTMessage.StampDigestRequest] (A6).
    private ProtocolMessage digestRequest(String correlationId, NodeId peer, int partitionIndex) {
        return node.repair()
                   .isLegacyPeer(peer)
               ? legacyDigestRequest(correlationId, partitionIndex)
               : new DHTMessage.StampDigestRequest(correlationId, node.nodeId(), partitionIndex);
    }

    private ProtocolMessage legacyDigestRequest(String correlationId, int partitionIndex) {
        node.repair().noteLegacyExchange();

        return new DHTMessage.DigestRequest(correlationId, node.nodeId(), partitionIndex, partitionIndex);
    }

    private Set<NodeId> coReplicas(List<NodeId> nodes) {
        return nodes.stream()
                    .filter(peer -> !peer.equals(node.nodeId()))
                    .collect(Collectors.toSet());
    }

    /// The digest every exchange compares: the node's own, which leaves out tombstones expired by their stamp
    /// (#1777 track 3), so catch-up and periodic rounds agree with what a source computes.
    private byte[] computeDigest(List<DHTMessage.KeyValue> entries) {
        return node.digestOf(entries);
    }

    private void sendDigestToPeers(int partitionIndex, List<NodeId> nodes, LocalDigests digests, AgreementRound round) {
        for (var peer : nodes) {
            if (peer.equals(node.nodeId())) {
                continue;
            }

            var correlationId = IdGenerator.generate();

            pendingDigests.put(correlationId,
                               new PendingDigest(peer, partitionIndex, digestFor(peer, digests), System.nanoTime()));
            agreementVotes.put(correlationId, round);
            sendLoudly(peer,
                       digestRequest(correlationId, peer, partitionIndex),
                       "digest request",
                       () -> forgetDigest(correlationId));
        }
    }

    private void forgetDigest(String correlationId) {
        pendingDigests.remove(correlationId);
        agreementVotes.remove(correlationId);
    }

    /// A periodic round in which every co-replica agreed (#1777 track 3). Tombstones expired when the round's
    /// digest was taken are then collected — unless a holder left the partition's replica set within the
    /// retention: its stray copy may outlive the stray horizon only by the margin, so the tombstone waits for it.
    private void onAgreed(AgreementRound round) {
        var partition = round.partition();
        var now = node.nowMillis();

        node.noteAgreement(partition, round.startedAtMillis());
        // #1777 R1b: strays are kept while a replication change is unsettled (see purgeStrays), so a tombstone they could
        // outlive waits until the change has settled here for a margin — the time every other replica needs to observe
        // the same committed settle and drop its strays first
        if (!node.replicationSettledSince(now - margin().millis())) {
            log.debug("Partition {} agreed, but a replication change is unsettled or settled within the margin; tombstones wait",
                      partition.value());

            return;
        }

        if (!node.holderSetStableSince(partition,
                                       now - node.tombstoneRetention().millis())) {
            log.debug("Partition {} agreed, but a holder left its replica set within the tombstone retention; "
                     + "tombstones wait",
                      partition.value());

            return;
        }

        node.collectTombstones(partition,
                               node.expiryCutoffMillis(round.startedAtMillis()))
            .onSuccess(collected -> logCollected(partition, collected));
    }

    private static void logCollected(Partition partition, int collected) {
        if (collected > 0) {
            log.debug("Partition {}: collected {} expired tombstones every co-replica agreed on",
                      partition.value(),
                      collected);
        }
    }

    /// Drop the copies of every partition this node stopped replicating more than the stray horizon ago (#1777
    /// track 3). The horizon is the tombstone retention less a margin of two anti-entropy periods and an
    /// operation timeout, so a stray copy is always gone before a tombstone that supersedes it may be collected.
    private void purgeStrays() {
        // #1777 R1b: while a replication change is unsettled, a stray copy may hold the only copy of a write a slower node
        // acked at the old quorum; the writers-switched catch-up pulls from it, so it stays until the change settles
        if (node.replicationChangeSettling()) {
            return;
        }

        var horizon = Math.max(0L,
                               node.tombstoneRetention().millis() - margin().millis());

        node.strayPartitionsSince(node.nowMillis() - horizon).forEach(node::dropStray);
    }

    /// Two anti-entropy periods and an operation timeout: the slack between a stray copy's purge and the earliest
    /// collection of a tombstone it could outlive — view lag between nodes and copies in flight (#1777 track 3).
    TimeSpan margin() {
        return TimeSpan.timeSpan(2 * antiEntropyInterval.millis() + config.get().operationTimeout().millis()).millis();
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
        agreementVotes.keySet().retainAll(pendingDigests.keySet());
        catchUpDigests.values().removeIf(pending -> pending.createdAtNanos() - deadline <= 0);
        pendingPulls.values().removeIf(pending -> pending.createdAtNanos() - deadline <= 0);
        stampProbes.values().removeIf(pending -> pending.createdAtNanos() - deadline <= 0);
    }

    /// One stamp digest probe per peer held as legacy, every round (D1): an older node drops it, a current one answers,
    /// and the answer clears the record. The legacy record therefore never latches between two current nodes.
    private void probeLegacyPeers() {
        node.repair().legacyPeers().stream().filter(node.ring().nodes()::contains).forEach(this::sendStampProbe);
    }

    private void sendStampProbe(NodeId peer) {
        var correlationId = IdGenerator.generate();

        stampProbes.put(correlationId, new PendingProbe(peer, System.nanoTime()));
        sendLoudly(peer,
                   new DHTMessage.StampDigestRequest(correlationId, node.nodeId(), 0),
                   "stamp protocol probe",
                   () -> stampProbes.remove(correlationId));
    }

    /// Handle a digest response from a remote peer.
    /// Compares local vs remote digest; if they differ, requests migration data.
    @Contract
    public void onDigestResponse(DHTMessage.DigestResponse response) {
        node.noteHeardFrom(response.sender());
        Option.option(stampProbes.remove(response.requestId())).onPresent(probe -> node.repair()
                                                                                       .noteCurrentEvidence(probe.peer(),
                                                                                                            node.monotonicMillis()));
        Option.option(pendingDigests.remove(response.requestId())).onPresent(pending -> voteAndCompare(pending, response));
        Option.option(catchUpDigests.remove(response.requestId())).onPresent(pending -> onCatchUpDigest(pending,
                                                                                                        response));
    }

    private void voteAndCompare(PendingDigest pending, DHTMessage.DigestResponse response) {
        voteAgreement(pending, response);
        handleDigestComparison(pending, response);
    }

    private void voteAgreement(PendingDigest pending, DHTMessage.DigestResponse response) {
        var matches = Arrays.equals(pending.localDigest(), response.digest());

        Option.option(agreementVotes.remove(response.requestId()))
              .filter(round -> agreements.get(pending.partitionIndex()) == round)
              .filter(round -> round.answer(pending.peer(),
                                            response.readiness(),
                                            matches))
              .onPresent(this::onAgreed);
    }

    private void handleDigestComparison(PendingDigest pending, DHTMessage.DigestResponse response) {
        if (Arrays.equals(pending.localDigest(), response.digest())) {
            log.debug("Partition {} in sync with {}",
                      pending.partitionIndex(),
                      pending.peer().id());
            node.repair().noteRepaired(pending.partitionIndex(), pending.peer());
            noteSyncedIfAuthoritative(pending, response);
            resumeAt.remove(new PullKey(pending.peer(), pending.partitionIndex()));

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
        node.repair().noteDiverged(pending.partitionIndex(), pending.peer());
        sendPull(pending.peer(), pending.partitionIndex(), Option.none(), "migration request");
    }

    /// Whether this node is still a replica of the partition, re-evaluated when the digest RESPONSE
    /// lands rather than only when the request went out: a joiner's ring grows one `NodeJoined` at a
    /// time, so the view that justified the digest can already be stale by the time it is answered.
    /// The holder applies the same test against ITS ring ([DHTNode#handleMigrationDataRequest]) — a
    /// replica is acquired only where the two views agree (issue #420).
    private boolean isLocalReplicaOf(int partitionIndex) {
        var replicationFactor = config.get().effectiveReplicationFactor(node.ring().nodeCount());

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
    ///
    /// An authentic pull answer is then attributed to its partition: a refusal is counted, and a catch-up
    /// pull's entries complete its round once stored (#1777 track 2).
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
            reject(response,
                   "answer to a pull sent to " + pull.peer().id());

            return;
        }

        pendingPulls.remove(response.requestId());
        if (response.refused()) {
            onRefusedPull(pull);

            return;
        }

        if (!allInPartition(response.entries(), pull.partitionIndex())) {
            reject(response, "entries outside the requested partition " + pull.partitionIndex());

            return;
        }

        pull.round()
            .filter(this::isCurrent)
            .onPresent(round -> onCatchUpPull(pull, round, response))
            .onEmpty(() -> applyPulled(pull, response));
    }

    /// A pull answer is never ack-requested; once every entry is applied, the divergence from that peer is repaired.
    private void applyPulled(PendingPull pull, DHTMessage.MigrationDataResponse response) {
        applyMigrationEntries(response).onSuccess(applied -> notePulled(pull, applied));
    }

    /// A completed LEGACY pull repairs the divergence but is not a sync (D4): its answer, a [DHTMessage.MigrationDataResponse]
    /// whose shape an older node fixes, carries no readiness, so it cannot show the holder was authoritative, which a
    /// sync requires of a paged pull and a digest match alike. A legacy peer still syncs this node by a digest match:
    /// its digest answer carries readiness.
    @Contract
    private void notePulled(PendingPull pull, boolean applied) {
        if (applied) {
            node.repair().noteRepaired(pull.partitionIndex(), pull.peer());
        }
    }

    /// A digest match with a SERVING peer syncs this node with it (D4): this node held what the peer held when it
    /// answered. A peer still catching up is no evidence of what the partition holds. The sync is stamped when the
    /// answer arrives, so it vouches for the peer's state at most one exchange earlier than its stamp; an unanswered
    /// digest is expired after one interval.
    @Contract
    private void noteSyncedIfAuthoritative(PendingDigest pending, DHTMessage.DigestResponse response) {
        if (response.readiness().authoritative()) {
            node.repair().noteSynced(pending.partitionIndex(), pending.peer(), node.monotonicMillis());
        }
    }

    /// Handle one page of a paged pull (A6). Only the answer to a page request this node sent is applied — its
    /// correlation id, from the node asked, with every entry in the partition asked for — exactly the authenticity
    /// rules of a [DHTMessage.MigrationDataResponse] pull answer; anything else is dropped with a WARN and counted.
    /// A catch-up page is proven stored by readback before the next page is asked for, and the round completes only
    /// on the last page; a periodic page asks for the next one once applied, until the last.
    @Contract
    public void onMigrationPageResponse(DHTMessage.MigrationPageResponse response) {
        Option.option(pendingPulls.get(response.requestId()))
              .onPresent(pull -> onPageAnswer(pull, response))
              .onEmpty(() -> rejectPage(response, "answer to no page request this node sent"));
    }

    private void onPageAnswer(PendingPull pull, DHTMessage.MigrationPageResponse response) {
        if (!pull.peer().equals(response.sender())) {
            rejectPage(response,
                       "answer to a page request sent to " + pull.peer().id());

            return;
        }

        pendingPulls.remove(response.requestId());
        node.repair().noteCurrentEvidence(response.sender(), node.monotonicMillis());
        if (response.refused()) {
            onRefusedPull(pull);

            return;
        }

        if (!allInPartition(response.entries(), pull.partitionIndex())) {
            rejectPage(response, "entries outside the requested partition " + pull.partitionIndex());

            return;
        }

        routePage(pull, response);
    }

    /// A page of a catch-up round still current continues that round; a page of a round since replaced is applied
    /// and its pull ends there (the replacing round pulls afresh); a periodic page continues its pull.
    private void routePage(PendingPull pull, DHTMessage.MigrationPageResponse response) {
        if (pull.round().isEmpty()) {
            onRepairPage(pull, response);

            return;
        }

        pull.round()
            .filter(this::isCurrent)
            .onPresent(round -> onCatchUpPage(pull, round, response))
            .onEmpty(() -> applyStalePage(pull, response));
    }

    /// The entries of a replaced round's page are still copies worth keeping; nothing waits on them.
    private void applyStalePage(PendingPull pull, DHTMessage.MigrationPageResponse response) {
        applyPage(response).onSuccess(_ -> log.debug("Applied a page of partition {} from {} for a replaced catch-up "
                                                    + "round; its pull ends here",
                                                     pull.partitionIndex(),
                                                     pull.peer().id()));
    }

    private Promise<Boolean> applyPage(DHTMessage.MigrationPageResponse response) {
        if (response.entries().isEmpty()) {
            return Promise.success(true);
        }

        log.debug("Received a page of {} entries from {} for repair",
                  response.entries().size(),
                  response.sender().id());

        return node.applyMigrationData(response.entries());
    }

    private void onRepairPage(PendingPull pull, DHTMessage.MigrationPageResponse response) {
        applyPage(response).onSuccess(applied -> continueRepairPull(pull, response, applied));
    }

    /// A page that could not be stored ends the pull; the divergence stays recorded and the next round retries from
    /// the resume cursor (FER: repair is delayed, never reported complete on a failed store).
    @Contract
    private void continueRepairPull(PendingPull pull, DHTMessage.MigrationPageResponse response, boolean applied) {
        var key = new PullKey(pull.peer(), pull.partitionIndex());
        var progress = pull.progress().after(response.readiness());

        if (!applied) {
            log.warn("Repair pull of partition {} from {}: a page could not be stored; the next round retries",
                     pull.partitionIndex(),
                     pull.peer().id());

            return;
        }

        response.next()
                .onPresent(cursor -> resumeAt.put(key,
                                                  new Resume(cursor, progress)))
                .onPresent(cursor -> sendPageRequest(pull.peer(),
                                                     pull.partitionIndex(),
                                                     none(),
                                                     some(cursor),
                                                     progress,
                                                     "migration page request"))
                .onEmpty(() -> finishRepairPull(key, progress));
    }

    /// A completed pull repairs the divergence from its holder, and syncs this node with it (D4) only if every page came
    /// from the holder while it was authoritative, as of the time the pull's first page was asked.
    @Contract
    private void finishRepairPull(PullKey key, PullProgress progress) {
        resumeAt.remove(key);
        node.repair().noteRepaired(key.partitionIndex(), key.peer());
        if (progress.authoritative()) {
            node.repair().noteSynced(key.partitionIndex(), key.peer(), progress.startedAtMillis());
        }
    }

    private void onCatchUpPage(PendingPull pull, CatchUpRound round, DHTMessage.MigrationPageResponse response) {
        // As for a whole-partition catch-up pull: a failed apply or an unreadable store leaves the round undecided
        // until it expires and restarts.
        applyPage(response).flatMap(_ -> node.storage()
                                             .stampsForPartition(node.ring(),
                                                                 round.partition()))
                 .onSuccess(local -> verifyCatchUpPage(pull, round, response, local));
    }

    /// A page is proven stored by readback before the round goes on; only the readback of the LAST page completes
    /// the round's wait on that source.
    private void verifyCatchUpPage(PendingPull pull,
                                   CatchUpRound round,
                                   DHTMessage.MigrationPageResponse response,
                                   List<KeyStamp> local) {
        if (!allStored(response.entries(), local)) {
            log.warn("Catch-up of partition {}: entries paged from {} were not stored; retrying",
                     round.partition().value(),
                     pull.peer().id());
            rounds.remove(round.partition().value(),
                          round);

            return;
        }

        response.next()
                .filter(_ -> isCurrent(round))
                .onPresent(cursor -> sendPageRequest(pull.peer(),
                                                     pull.partitionIndex(),
                                                     some(round),
                                                     some(cursor),
                                                     pull.progress(),
                                                     "catch-up page request"))
                .onEmpty(() -> completeIfLastPage(pull, round, response));
    }

    private void completeIfLastPage(PendingPull pull, CatchUpRound round, DHTMessage.MigrationPageResponse response) {
        if (response.next().isEmpty() && isCurrent(round) && round.pullStored(pull.peer())) {
            completeCatchUp(round.partition(), round.anchorless());
            rounds.remove(round.partition().value(),
                          round);
        }
    }

    private void rejectPage(DHTMessage.MigrationPageResponse response, String reason) {
        rejectedResponses.incrementAndGet();
        log.warn("Rejected a page of {} migrated entries from {} (request {}): {}",
                 response.entries().size(),
                 response.sender().id(),
                 response.requestId(),
                 reason);
    }

    private void onUnsolicited(DHTMessage.MigrationDataResponse response) {
        if (response.ackRequested()) {
            acceptDeparturePush(response);
        } else {
            acceptRebalancePush(response);
        }
    }

    /// A departure push is accepted from a node that is departing — named by the leader's drain set or seen
    /// entering DEPARTING — or from a CURRENT replica, in this node's ring, of every entry's partition. The
    /// second covers a node that drains itself on quorum loss: no signal reaches its peers in time, and it
    /// halts with its in-memory store, so a copy it holds last would otherwise be lost. A current replica
    /// already holds those partitions legitimately, so its push carries no authority it lacks.
    private void acceptDeparturePush(DHTMessage.MigrationDataResponse response) {
        if (departingSenders.test(response.sender()) || response.entries()
                                                                .stream()
                                                                .allMatch(entry -> replicaOfEntry(response.sender(),
                                                                                                  entry))) {
            applyPlaced(response);

            return;
        }

        reject(response, "departure push from a node that is not departing");
        acknowledge(response, false);
    }

    /// An accepted departure push is applied only for partitions this node replicates — in the ring as it is
    /// (pre-departure) or in the post-departure view: without the pusher's leaving set and every node this node
    /// knows to be departing — #1818 J2, L1.
    /// A copy anywhere else is a stray the #428 fallback read can later serve, so those entries are dropped,
    /// counted, and the batch is nacked: the pusher keeps them at risk rather than believing them delivered.
    private void applyPlaced(DHTMessage.MigrationDataResponse response) {
        var placed = response.entries().stream().filter(entry -> replicaHereAroundDeparture(response, entry)).toList();

        if (placed.size() == response.entries().size()) {
            applyAndAcknowledge(response);

            return;
        }

        reject(response,
               (response.entries().size() - placed.size()) + " entries for partitions this node does not replicate");
        applyMigrationEntries(withEntries(response, placed)).onSuccess(_ -> acknowledge(response, false));
    }

    /// The post-departure view keeps only the nodes the pusher's ring held (its carried `view`) and excludes
    /// the pusher, the pusher's carried leaving set, and this node's own departing set (#1818 L1, N1).
    /// Why that is sound: the pusher chose this node as within RF of ITS ring without ITS leaving set. This
    /// node's view is a subset of that ring: it drops every joiner the pusher had not heard of, and it
    /// excludes a superset of the leaving set. Removing nodes from a ring only moves the remaining nodes
    /// EARLIER in each walk, so this node can only move earlier, and it stays within RF. Its own knowledge can
    /// therefore never turn a legitimate newcomer into a stray. Two shapes did exactly that before: a co-drainer
    /// this node had not yet heard of (v1820 r4), and a joiner known here but not to the pusher, i.e. joins
    /// concurrent with drains as in a fleet replacement (v1820 r5). Nothing retries the nack, so each lost the copy.
    /// A member the pusher knows and this node does not is simply absent here, which is another removal.
    private boolean replicaHereAroundDeparture(DHTMessage.MigrationDataResponse response, DHTMessage.KeyValue entry) {
        var replicationFactor = config.get().effectiveReplicationFactor(node.ring().nodeCount());
        var pusherLeaving = Set.copyOf(response.leaving());
        var pusherView = Set.copyOf(response.view());

        return replicaOfEntry(node.nodeId(), entry) || node.ring()
                                                           .nodesFor(entry.key(),
                                                                     replicationFactor,
                                                                     candidate -> inPusherView(candidate, pusherView) && !leftInDepartureView(candidate,
                                                                                                                                              response.sender(),
                                                                                                                                              pusherLeaving))
                                                           .contains(node.nodeId());
    }

    /// A node the pusher's ring did not hold — a joiner it had not yet heard of — is outside the view it chose
    /// this receiver in, and is dropped from it the same way a leaving node is (v1820 r5): removing nodes only
    /// moves this receiver earlier, so the pusher's choice stays within RF. An empty view is the receiver's ring.
    private static boolean inPusherView(NodeId candidate, Set<NodeId> pusherView) {
        return pusherView.isEmpty() || pusherView.contains(candidate);
    }

    private boolean leftInDepartureView(NodeId candidate, NodeId pusher, Set<NodeId> pusherLeaving) {
        return candidate.equals(pusher) || pusherLeaving.contains(candidate) || departingSenders.test(candidate);
    }

    private static DHTMessage.MigrationDataResponse withEntries(DHTMessage.MigrationDataResponse response,
                                                                List<DHTMessage.KeyValue> entries) {
        return new DHTMessage.MigrationDataResponse(response.requestId(),
                                                    response.sender(),
                                                    entries,
                                                    response.ackRequested(),
                                                    response.leaving(),
                                                    response.view());
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
        return replicaOfEntry(sender, entry) && replicaOfEntry(node.nodeId(), entry);
    }

    private boolean replicaOfEntry(NodeId candidate, DHTMessage.KeyValue entry) {
        var replicationFactor = config.get().effectiveReplicationFactor(node.ring().nodeCount());

        return node.ring()
                   .nodesFor(entry.key(),
                             replicationFactor)
                   .contains(candidate);
    }

    private boolean allInPartition(List<DHTMessage.KeyValue> entries, int partitionIndex) {
        return entries.stream()
                      .allMatch(entry -> node.ring()
                                             .partitionFor(entry.key())
                                             .value() == partitionIndex);
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

    /// Get the count of pending digest comparisons (for testing).
    int pendingDigestCount() {
        return pendingDigests.size();
    }

    /// Get the count of unanswered stamp protocol probes (for testing).
    int pendingProbeCount() {
        return stampProbes.size();
    }
}
