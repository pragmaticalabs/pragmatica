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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.NullReturn;


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
/// working (at the legacy cost: a value-reading digest and a whole-partition pull). Any new-type message from the
/// peer clears the record, which is what happens when it restarts upgraded. A node that is new never asks a legacy
/// question of a peer it has not recorded as legacy, so two new nodes never fall back.
public final class RepairPlane {
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

    /// `peer` asked a legacy repair question: it speaks only the legacy protocol.
    @Contract
    void noteLegacyPeer(NodeId peer) {
        legacyPeers.add(peer);
    }

    /// `peer` sent a new-type repair message: it speaks the current protocol.
    @Contract
    void noteCurrentPeer(NodeId peer) {
        legacyPeers.remove(peer);
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
        legacyPeers.remove(peer);
    }

    @Contract
    void forgetPartition(int partitionIndex) {
        diverged.remove(partitionIndex);
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
