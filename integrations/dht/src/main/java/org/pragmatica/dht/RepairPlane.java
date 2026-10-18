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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;


/// The node-wide state of the DHT repair plane (A6): the page budget, the peers that speak only the legacy repair
/// protocol, the partitions found diverged and not yet repaired, and the counters the metrics read.
///
/// ## Mixed versions: the messages are versioned, and the peer's version is detected from its traffic
/// Stamp digests and paged pulls travel as NEW message types ([DHTMessage.StampDigestRequest],
/// [DHTMessage.MigrationPageRequest]); the legacy types stay understood. An older node drops a message type it has
/// no codec for (`UnknownTypeTagException`, counted as `quic_unknown_type_tag_drops_total`), so asking it the new
/// question costs one unanswered round, never a broken connection. An older node announces itself: its own
/// anti-entropy asks this node legacy questions ([DHTMessage.DigestRequest], [DHTMessage.MigrationDataRequest]).
/// Such a sender is recorded as legacy, and this node then asks it in the legacy protocol, so repair with it keeps
/// working (at the legacy cost: a value-reading digest and a whole-partition pull).
///
/// The record never latches. Every round, this node also sends each peer it holds as legacy one
/// [DHTMessage.StampDigestRequest] probe: an older node drops it, and a node that is current answers it. Either a
/// probe answer or a new-type message from the peer is CURRENT EVIDENCE: it clears the record, and for one freshness
/// window afterwards a legacy question from that peer does not re-record it. Two current nodes that hold each other as
/// legacy, after a restart under the same identity in either order, therefore both return to the stamp protocol
/// within one round of either side's probe. A peer that really runs the old version produces no current evidence,
/// and its next legacy question after the window records it again.
///
/// ## Freshness: the bound on how long a removed block stays readable (D4)
/// A replica serves a verified single-replica read ([DistributedDHTClient#getContentAddressed]) only while it is
/// FRESH for the key's partition: within the last freshness window (K anti-entropy intervals, default 2) its own
/// rounds SYNCED it with at least max(1, RF − W) co-replicas, a sync being a digest match or a completed pull from
/// that peer. A remove that was acknowledged reached at least W replicas, so at most RF − W − 1 co-replicas other
/// than a stale one lack the tombstone; a sync with RF − W distinct co-replicas therefore includes one that holds
/// it, and that sync delivers it. A replica that missed a remove thus serves the removed block for at most one
/// freshness window after its last sync before the remove, whatever stops it syncing since (a partition, ring
/// disagreement, a failing store). Repair is pull-only, so only the replica's own rounds count.
public final class RepairPlane {
    /// The anti-entropy intervals a replica may go without a sync and still serve verified reads (default K = 2).
    public static final int DEFAULT_FRESHNESS_INTERVALS = 2;

    /// The default freshness window: K intervals of the default anti-entropy period.
    public static final TimeSpan DEFAULT_FRESHNESS_WINDOW = TimeSpan.timeSpan(DEFAULT_FRESHNESS_INTERVALS * DHTAntiEntropy.DEFAULT_ANTI_ENTROPY_INTERVAL.millis()).millis();

    /// How long a verified read waits for one replica before also asking the next (spec A3b: 200 ms).
    public static final TimeSpan DEFAULT_HEDGE_DELAY = TimeSpan.timeSpan(200).millis();

    private final AtomicReference<PageBudget> pageBudget = new AtomicReference<>(PageBudget.DEFAULT);
    private final Set<NodeId> legacyPeers = ConcurrentHashMap.newKeySet();
    /// Partitions whose digest differed from a co-replica's, by partition index, with the peers they differed
    /// from; an entry leaves when a later round agrees with that peer or the pull from it completes.
    private final ConcurrentHashMap<Integer, Set<NodeId>> diverged = new ConcurrentHashMap<>();
    private final AtomicLong pagesSent = new AtomicLong();
    private final AtomicLong pageBytesSent = new AtomicLong();
    private final AtomicLong legacyExchanges = new AtomicLong();
    private final AtomicLong verifiedReadFallbacks = new AtomicLong();
    private final AtomicLong verifiedReadMismatches = new AtomicLong();
    private final AtomicLong verifiedReadQuorumFallbacks = new AtomicLong();
    private final AtomicReference<TimeSpan> freshnessWindow = new AtomicReference<>(DEFAULT_FRESHNESS_WINDOW);
    private final AtomicReference<TimeSpan> hedgeDelay = new AtomicReference<>(DEFAULT_HEDGE_DELAY);

    /// When each co-replica last synced this node for each partition (wall clock), by partition index.
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<NodeId, Long>> syncedAt = new ConcurrentHashMap<>();

    /// When each peer last showed it speaks the current protocol (wall clock).
    private final ConcurrentHashMap<NodeId, Long> currentEvidenceAt = new ConcurrentHashMap<>();

    private RepairPlane() {}

    public static RepairPlane repairPlane() {
        return new RepairPlane();
    }

    /// The byte budget of one repair message.
    public PageBudget pageBudget() {
        return pageBudget.get();
    }

    /// Use `budget` for every repair message sent from now on.
    @Contract
    public void usePageBudget(PageBudget budget) {
        pageBudget.set(budget);
    }

    /// Record `peer` as legacy unconditionally (an older node's own view of its peers, in tests).
    @Contract
    void noteLegacyPeer(NodeId peer) {
        legacyPeers.add(peer);
    }

    /// Clear the legacy record of `peer` without evidence (a restarted node's empty view, in tests).
    @Contract
    void noteCurrentPeer(NodeId peer) {
        legacyPeers.remove(peer);
        currentEvidenceAt.remove(peer);
    }

    /// `peer` asked a legacy repair question at `atMillis`: recorded as legacy unless it showed current evidence within
    /// the freshness window.
    @Contract
    void noteLegacyQuestion(NodeId peer, long atMillis) {
        if (Option.option(currentEvidenceAt.get(peer))
                  .filter(at -> at >= atMillis - freshnessWindow.get()
                                                                .millis())
                  .isEmpty()) {
            legacyPeers.add(peer);
        }
    }

    /// `peer` sent a new-type repair message, or answered a stamp probe, at `atMillis`: it speaks the current protocol.
    @Contract
    void noteCurrentEvidence(NodeId peer, long atMillis) {
        currentEvidenceAt.put(peer, atMillis);
        legacyPeers.remove(peer);
    }

    /// Peers held as legacy now: each is probed with a stamp digest request every round.
    Set<NodeId> legacyPeers() {
        return Set.copyOf(legacyPeers);
    }

    /// The window within which a sync keeps this node fresh, and current evidence outweighs a legacy question.
    public TimeSpan freshnessWindow() {
        return freshnessWindow.get();
    }

    /// Use `window` as the freshness window (the anti-entropy process sets K × its interval).
    @Contract
    public void useFreshnessWindow(TimeSpan window) {
        freshnessWindow.set(window);
    }

    /// How long a verified read waits for one replica before also asking the next.
    public TimeSpan hedgeDelay() {
        return hedgeDelay.get();
    }

    /// Use `delay` as the hedge delay of verified reads from now on.
    @Contract
    public void useHedgeDelay(TimeSpan delay) {
        hedgeDelay.set(delay);
    }

    /// This node's own round synced it with `peer` for the partition at `atMillis`: a digest match or a completed pull.
    @Contract
    void noteSynced(int partitionIndex, NodeId peer, long atMillis) {
        syncedAt.computeIfAbsent(partitionIndex, _ -> new ConcurrentHashMap<>()).put(peer, atMillis);
    }

    /// Whether at least `required` of `coReplicas` synced this node for the partition within the freshness window.
    boolean fresh(int partitionIndex, List<NodeId> coReplicas, int required, long nowMillis) {
        var cutoff = nowMillis - freshnessWindow.get().millis();

        return Option.option(syncedAt.get(partitionIndex))
                     .map(peers -> syncedSince(peers, coReplicas, cutoff))
                     .or(0L) >= required;
    }

    private static long syncedSince(Map<NodeId, Long> peers, List<NodeId> coReplicas, long cutoff) {
        return coReplicas.stream()
                         .filter(peer -> Option.option(peers.get(peer))
                                               .filter(at -> at >= cutoff)
                                               .isPresent())
                         .count();
    }

    /// Whether `peer` is recorded as speaking only the legacy protocol.
    public boolean isLegacyPeer(NodeId peer) {
        return legacyPeers.contains(peer);
    }

    /// Peers recorded as legacy now (a gauge: non-zero only during a rolling upgrade).
    public int legacyPeerCount() {
        return legacyPeers.size();
    }

    @Contract
    void noteDiverged(int partitionIndex, NodeId peer) {
        diverged.computeIfAbsent(partitionIndex, _ -> ConcurrentHashMap.newKeySet()).add(peer);
    }

    @Contract
    void noteRepaired(int partitionIndex, NodeId peer) {
        diverged.computeIfPresent(partitionIndex, (_, peers) -> withoutPeer(peers, peer));
    }

    @Contract
    void forgetPeer(NodeId peer) {
        diverged.keySet().forEach(index -> noteRepaired(index, peer));
        syncedAt.values().forEach(peers -> peers.remove(peer));
        legacyPeers.remove(peer);
        currentEvidenceAt.remove(peer);
    }

    @Contract
    void forgetPartition(int partitionIndex) {
        diverged.remove(partitionIndex);
        syncedAt.remove(partitionIndex);
    }

    /// `null` drops the entry once its last peer is repaired, per the `computeIfPresent` contract.
    @NullReturn
    private static Set<NodeId> withoutPeer(Set<NodeId> peers, NodeId peer) {
        peers.remove(peer);

        return peers.isEmpty()
               ? null
               : peers;
    }

    /// The repair deficit: partitions this node replicates that are not known to be whole here — still catching up,
    /// or diverged from a co-replica and not yet repaired from it. While non-zero, losing that co-replica may lose
    /// entries this node does not hold yet.
    int deficit(List<Partition> pending) {
        var partitions = new HashSet<>(diverged.keySet());

        pending.forEach(partition -> partitions.add(partition.value()));

        return partitions.size();
    }

    @Contract
    void notePageSent(long bytes) {
        pagesSent.incrementAndGet();
        pageBytesSent.addAndGet(bytes);
    }

    @Contract
    void noteLegacyExchange() {
        legacyExchanges.incrementAndGet();
    }

    @Contract
    void noteVerifiedReadFallback() {
        verifiedReadFallbacks.incrementAndGet();
    }

    @Contract
    void noteVerifiedReadMismatch() {
        verifiedReadMismatches.incrementAndGet();
    }

    @Contract
    void noteVerifiedReadQuorumFallback() {
        verifiedReadQuorumFallbacks.incrementAndGet();
    }

    /// Repair messages carrying entries sent since start: migration pages, rebalance and departure pushes.
    public long pagesSent() {
        return pagesSent.get();
    }

    /// Entry bytes ([PageBudget#transferBytes]) in the repair messages sent since start.
    public long pageBytesSent() {
        return pageBytesSent.get();
    }

    /// Repair exchanges carried out in the legacy protocol since start (rolling upgrade only).
    public long legacyExchanges() {
        return legacyExchanges.get();
    }

    /// Verified single-replica reads that moved past a replica since start: a miss, a failure or a hash mismatch.
    public long verifiedReadFallbacks() {
        return verifiedReadFallbacks.get();
    }

    /// Replica answers to a verified read whose bytes did not hash to the key, since start: corruption evidence.
    public long verifiedReadMismatches() {
        return verifiedReadMismatches.get();
    }

    /// Verified reads that found no verifiable copy on any single replica and fell back to the quorum read.
    public long verifiedReadQuorumFallbacks() {
        return verifiedReadQuorumFallbacks.get();
    }
}
