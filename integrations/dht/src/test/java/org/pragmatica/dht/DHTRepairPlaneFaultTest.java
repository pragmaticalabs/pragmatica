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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.storage.KeyStamp;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.RepairCluster.bytes;
import static org.pragmatica.dht.RepairCluster.keysInOnePartition;
import static org.pragmatica.dht.RepairCluster.value;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A6 fix round: the hedged verified read (D2), legacy negotiation in either order (D1), the expired-tombstone filter
/// of the stamp digest (D3), the hard bound on how long a removed block stays readable under faults (D4), and exists
/// agreeing with get (D5).
class DHTRepairPlaneFaultTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final long INTERVAL = DHTAntiEntropy.DEFAULT_ANTI_ENTROPY_INTERVAL.millis();
    private static final long WINDOW = RepairPlane.DEFAULT_FRESHNESS_INTERVALS * INTERVAL;

    private static byte[] sha256(byte[] value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value);
    }

    private static byte[] contentKey(byte[] sha) {
        return bytes("content-blocks/" + HEX.formatHex(sha));
    }

    private static void syncAll(RepairCluster cluster) {
        cluster.members.values().forEach(member -> member.antiEntropy().synchronizeNow());
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /// A content block stored on every replica, every replica synced, then removed on `a` and `b` only: `c` missed the
    /// remove and still holds the bytes. The reader is `a`, which asks `a`, `b`, then `c`.
    private record Removed(RepairCluster cluster, byte[] block, byte[] sha, byte[] key) {
        RepairCluster.Member member(String id) {
            return cluster.member(id);
        }

        boolean servedTo(String reader) {
            return member(reader).client().getContentAddressed(key, sha).await().unwrap().isPresent();
        }

        boolean existsFor(String reader) {
            return member(reader).client().existsContentAddressed(key, sha).await().unwrap();
        }

        /// Whether `id` still holds the removed block live: the freshness gate, not repair, then decides what it serves.
        boolean liveOn(String id) {
            return member(id).engine().getEntry(key).await().unwrap().filter(kv -> !kv.tombstone()).isPresent();
        }
    }

    private static Removed removedOnTwoOfThree() throws Exception {
        var cluster = new RepairCluster(new DHTConfig(3, 2, 2, timeSpan(2).seconds()), "a", "b", "c");
        var block = value(4096, 21);
        var sha = sha256(block);
        var key = contentKey(sha);
        var a = cluster.member("a");

        assertThat(a.client().put(key, block).await().isSuccess()).isTrue();
        syncAll(cluster);

        var stored = a.engine().getEntry(key).await().unwrap().unwrap();

        for (var id : List.of("a", "b")) {
            cluster.member(id).engine().removeVersioned(key, stored.version() + 1, stored.epochIncarnation(),
                                                        stored.epochTerm(), stored.epochCounter()).await();
        }

        return new Removed(cluster, block, sha, key);
    }

    /// Like [#removedOnTwoOfThree], on four nodes, with the key placed away from the reader `r`: the stale replica can
    /// be cut off from its co-replicas while the reader still reaches it both ways.
    private record RemovedAway(Removed removed, String stale, List<String> holders) {}

    private static RemovedAway removedAwayFromTheReader() throws Exception {
        var cluster = new RepairCluster(new DHTConfig(3, 2, 2, timeSpan(2).seconds()), "a", "b", "c", "r");
        var reader = cluster.member("r");

        for (int seed = 0; ; seed++) {
            var block = value(4096, 500 + seed);
            var sha = sha256(block);
            var key = contentKey(sha);
            var replicas = reader.node().ring().nodesFor(reader.node().ring().partitionFor(key), 3);

            if (replicas.contains(reader.id())) {
                continue;
            }

            assertThat(reader.client().put(key, block).await().isSuccess()).isTrue();
            syncAll(cluster);

            var holders = List.of(replicas.get(0).id(), replicas.get(1).id());
            var stored = cluster.member(holders.getFirst()).engine().getEntry(key).await().unwrap().unwrap();

            for (var id : holders) {
                cluster.member(id).engine().removeVersioned(key, stored.version() + 1, stored.epochIncarnation(),
                                                            stored.epochTerm(), stored.epochCounter()).await();
            }

            return new RemovedAway(new Removed(cluster, block, sha, key), replicas.get(2).id(), holders);
        }
    }

    @Nested
    class HedgedVerifiedRead {
        private record Placed(RepairCluster cluster, RepairCluster.Member reader, List<NodeId> replicas, byte[] block,
                              byte[] sha, byte[] key) {}

        /// A reader outside the key's replica set, so every candidate is remote.
        private static Placed placedAwayFromTheReader(DHTConfig config) throws Exception {
            var cluster = new RepairCluster(config, "a", "b", "c", "d");
            var reader = cluster.member("a");

            for (int seed = 0; ; seed++) {
                var block = value(1024, 300 + seed);
                var sha = sha256(block);
                var key = contentKey(sha);
                var replicas = reader.node().ring().nodesFor(reader.node().ring().partitionFor(key), 3);

                if (!replicas.contains(reader.id())) {
                    assertThat(reader.client().put(key, block).await().isSuccess()).isTrue();
                    syncAll(cluster);

                    return new Placed(cluster, reader, replicas, block, sha, key);
                }
            }
        }

        /// P6: the first replica is silent. The read asks the next one after the hedge delay and completes near it,
        /// not after a per-replica share of the timeout. Red when the hedge is removed (the read then waits for the
        /// silent replica until the verified phase ends, 1.5 s here).
        @Test
        void oneSilentReplica_readCompletesNearTheHedgeDelay() throws Exception {
            var placed = placedAwayFromTheReader(new DHTConfig(3, 2, 2, timeSpan(3).seconds()));

            placed.cluster().unreachable.add(placed.replicas().getFirst());

            var start = System.nanoTime();
            var read = placed.reader().client().getContentAddressed(placed.key(), placed.sha()).await().unwrap();
            var elapsed = millisSince(start);

            System.out.printf("A6-D2 silentFirstReplica elapsedMs=%d hedgeMs=%d%n", elapsed,
                              RepairPlane.DEFAULT_HEDGE_DELAY.millis());
            assertThat(read.unwrap()).isEqualTo(placed.block());
            assertThat(elapsed).as("the hedge, not the silent replica, decides the latency")
                               .isGreaterThanOrEqualTo(RepairPlane.DEFAULT_HEDGE_DELAY.millis() - 20)
                               .isLessThan(RepairPlane.DEFAULT_HEDGE_DELAY.millis() + 600);
        }

        /// P6b: the local copy is corrupt and both remote replicas are silent. The whole read — verified phase and
        /// quorum fallback — ends within one operation timeout. Red when the quorum fallback is given its own full
        /// timeout instead of what remains (about 1.5x the timeout, 3 s here).
        @Test
        void worstCase_isBoundedByOneOperationTimeout() throws Exception {
            var cluster = new RepairCluster(new DHTConfig(3, 2, 2, timeSpan(2).seconds()), "a", "b", "c");
            var a = cluster.member("a");
            var block = value(1024, 77);
            var sha = sha256(block);
            var key = contentKey(sha);

            assertThat(a.client().put(key, block).await().isSuccess()).isTrue();
            syncAll(cluster);
            a.engine().corrupt(key);
            cluster.unreachable.add(new NodeId("b"));
            cluster.unreachable.add(new NodeId("c"));

            var start = System.nanoTime();
            var read = a.client().getContentAddressed(key, sha).await();
            var elapsed = millisSince(start);

            System.out.printf("A6-D2 worstCase elapsedMs=%d operationTimeoutMs=2000 result=%s%n", elapsed, read);
            boolean neverCorrupt = read.fold(_ -> true, value -> value.isEmpty());

            assertThat(neverCorrupt).as("never the corrupt bytes").isTrue();
            assertThat(elapsed).as("bounded by one operation timeout").isLessThan(2000 + 250);
        }

        /// The verified phase may use half the operation timeout, and the quorum fallback the rest. Every replica stays
        /// silent to verified reads but answers the quorum read, so the read ends near half the timeout. Red when the
        /// verified phase is given the whole timeout: the read then ends near the full timeout.
        @Test
        void silentVerifiedPhase_endsAtHalfTheTimeout_andTheQuorumAnswers() throws Exception {
            var placed = placedAwayFromTheReader(new DHTConfig(3, 2, 2, timeSpan(2).seconds()));

            placed.cluster().droppedTypes.add(DHTMessage.VerifiedReadRequest.class);

            var start = System.nanoTime();
            var read = placed.reader().client().getContentAddressed(placed.key(), placed.sha()).await().unwrap();
            var elapsed = millisSince(start);

            System.out.printf("A6-C03 silentVerifiedPhase elapsedMs=%d operationTimeoutMs=2000%n", elapsed);
            assertThat(read.unwrap()).as("the quorum read answers").isEqualTo(placed.block());
            assertThat(elapsed).as("the verified phase ends at half the timeout")
                               .isGreaterThanOrEqualTo(1000 - 20)
                               .isLessThan(1500);
        }

        /// A replica that answers with a miss moves the read to the next one at once, not after the hedge delay. The
        /// hedge delay is set far beyond the timeout, so only the miss can move the read on. Red when a miss does not
        /// launch the next replica: the read then waits for the hedge.
        @Test
        void aMiss_movesTheReadToTheNextReplicaAtOnce() throws Exception {
            var placed = placedAwayFromTheReader(new DHTConfig(3, 2, 2, timeSpan(3).seconds()));

            placed.reader().node().repair().useHedgeDelay(timeSpan(10).seconds());
            placed.cluster().member(placed.replicas().getFirst().id()).engine().corrupt(placed.key());

            var start = System.nanoTime();
            var read = placed.reader().client().getContentAddressed(placed.key(), placed.sha()).await().unwrap();
            var elapsed = millisSince(start);

            System.out.printf("A6-C07 missMovesOn elapsedMs=%d hedgeMs=10000%n", elapsed);
            assertThat(read.unwrap()).isEqualTo(placed.block());
            assertThat(placed.reader().node().repair().verifiedReadQuorumFallbacks()).as("a replica, not the quorum, answered")
                                                                                    .isZero();
            assertThat(elapsed).as("the miss, not the hedge delay, moved the read on").isLessThan(500);
        }

        /// A replica recorded as legacy cannot answer a verified read, so it is never asked one; the next replica is.
        /// Red when legacy replicas are asked.
        @Test
        void legacyReplica_isNeverAskedAVerifiedRead() throws Exception {
            var placed = placedAwayFromTheReader(new DHTConfig(3, 2, 2, timeSpan(2).seconds()));
            var legacy = placed.replicas().getFirst();

            placed.reader().node().repair().noteLegacyPeer(legacy);

            var read = placed.reader().client().getContentAddressed(placed.key(), placed.sha()).await().unwrap();

            assertThat(read.unwrap()).isEqualTo(placed.block());
            assertThat(verifiedReadsSentTo(placed.cluster(), legacy)).as("the legacy replica is skipped").isZero();
            assertThat(verifiedReadsSentTo(placed.cluster(), placed.replicas().get(1))).as("control: the next one is asked")
                                                                                      .isPositive();
        }

        private static long verifiedReadsSentTo(RepairCluster cluster, NodeId target) {
            return cluster.sent.stream()
                               .filter(entry -> entry.getKey().equals(target))
                               .filter(entry -> entry.getValue() instanceof DHTMessage.VerifiedReadRequest)
                               .count();
        }
    }

    @Nested
    class LegacyNegotiation {
        /// P1 (D1): the old peer restarts upgraded under the same identity, and the NEW node's legacy question reaches
        /// it first. Without the probe the two current nodes held each other as legacy for good; with it both return
        /// to the stamp protocol. Red when the probe is removed.
        @Test
        void upgradedPeer_askedInLegacyFirst_stillReturnsToTheStampProtocol() {
            var cluster = new RepairCluster(new DHTConfig(2, 1, 1, timeSpan(5).seconds()), "new", "old");
            var fresh = cluster.member("new");
            var old = cluster.member("old");

            keysInOnePartition(old.node().ring(), 5, "neg").forEach(key -> old.engine()
                                                                              .putReplica(new DHTMessage.KeyValue(key, value(256, 1), 3L, 0L, 0L, 0L), true)
                                                                              .await());
            cluster.legacyNodes.add(old.id());
            old.node().repair().noteLegacyPeer(fresh.id());
            old.antiEntropy().synchronizeNow();
            assertThat(fresh.node().repair().isLegacyPeer(old.id())).as("control: the old node announced itself").isTrue();

            // the old node restarts upgraded, same identity, with an empty view of its peers
            cluster.legacyNodes.remove(old.id());
            old.node().repair().noteCurrentPeer(fresh.id());

            // the new node's round runs first: it still asks the upgraded node in the legacy protocol
            fresh.antiEntropy().synchronizeNow();

            for (int round = 0; round < 3; round++) {
                old.antiEntropy().synchronizeNow();
                fresh.antiEntropy().synchronizeNow();
            }

            var stampsBefore = cluster.sentOfType(DHTMessage.StampDigestRequest.class).size();

            fresh.antiEntropy().synchronizeNow();
            old.antiEntropy().synchronizeNow();

            var stampsInLastRound = cluster.sentOfType(DHTMessage.StampDigestRequest.class).size() - stampsBefore;

            assertThat(fresh.node().repair().isLegacyPeer(old.id())).isFalse();
            assertThat(old.node().repair().isLegacyPeer(fresh.id())).isFalse();
            assertThat(stampsInLastRound).as("both sides ask stamp digests for every partition")
                                         .isEqualTo(2 * Partition.MAX_PARTITIONS);
        }

        /// A peer that showed current evidence is not recorded as legacy by a legacy question within the freshness
        /// window, whichever legacy question it is; past the window it is. Red when a legacy question always records the
        /// peer, and when the evidence is ignored.
        @Test
        void currentEvidence_outweighsLegacyQuestions_forOneFreshnessWindow() {
            var cluster = new RepairCluster(new DHTConfig(2, 1, 1, timeSpan(5).seconds()), "n", "p");
            var n = cluster.member("n").node();
            var p = new NodeId("p");

            n.handleStampDigestRequest(new DHTMessage.StampDigestRequest("s", p, 0), _ -> {});
            n.handleDigestRequest(new DHTMessage.DigestRequest("d", p, 0, 0), _ -> {});
            assertThat(n.repair().isLegacyPeer(p)).as("a legacy digest question inside the window").isFalse();
            n.handleMigrationDataRequest(new DHTMessage.MigrationDataRequest("m", p, 0, 0), _ -> {});
            assertThat(n.repair().isLegacyPeer(p)).as("a legacy pull inside the window").isFalse();

            cluster.advance(WINDOW + 1);
            n.handleDigestRequest(new DHTMessage.DigestRequest("d2", p, 0, 0), _ -> {});
            assertThat(n.repair().isLegacyPeer(p)).as("control: past the window a legacy question records the peer").isTrue();
        }

        /// A probe a legacy peer never answers is expired with the round's other unanswered questions, so a peer that
        /// stays legacy holds one probe at most. Red when probes never expire.
        @Test
        void unansweredProbes_areExpiredByTheFollowingRound() throws Exception {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            var self = new NodeId("node-0");
            var legacy = new NodeId("node-1");

            ring.addNode(self);
            ring.addNode(legacy);

            var config = new DHTConfig(2, 1, 1, timeSpan(5).seconds());
            var node = DHTNode.dhtNode(self, memoryStorageEngine(), ring, config);
            var antiEntropy = DHTAntiEntropy.dhtAntiEntropy(node, (_, _) -> {}, config, timeSpan(1).millis());

            node.repair().noteLegacyPeer(legacy);
            antiEntropy.synchronizeNow();
            assertThat(antiEntropy.pendingProbeCount()).as("control: the round probed the legacy peer").isEqualTo(1);

            Thread.sleep(20L);
            antiEntropy.synchronizeNow();
            assertThat(antiEntropy.pendingProbeCount()).as("the second round holds its own probe, not the first's")
                                                       .isEqualTo(1);
        }
    }

    /// A sync is forgotten with the peer that left the ring, and with the partition this node no longer replicates: a
    /// departed peer's syncs cannot keep a replica fresh. Red when either keeps the syncs.
    @Nested
    class ForgettingSyncs {
        private static final NodeId PEER = new NodeId("peer");

        @Test
        void forgetPeer_dropsItsSyncs() {
            var plane = RepairPlane.repairPlane();

            plane.noteSynced(7, PEER, 1_000L);
            assertThat(plane.fresh(7, List.of(PEER), 1, 1_000L)).as("control: synced").isTrue();
            plane.forgetPeer(PEER);
            assertThat(plane.fresh(7, List.of(PEER), 1, 1_000L)).isFalse();
        }

        @Test
        void forgetPartition_dropsItsSyncs() {
            var plane = RepairPlane.repairPlane();

            plane.noteSynced(7, PEER, 1_000L);
            plane.noteSynced(8, PEER, 1_000L);
            plane.forgetPartition(7);
            assertThat(plane.fresh(7, List.of(PEER), 1, 1_000L)).isFalse();
            assertThat(plane.fresh(8, List.of(PEER), 1, 1_000L)).as("control: only that partition").isTrue();
        }
    }

    @Nested
    class ExpiredTombstoneDigest {
        /// D3: a tombstone older than the retention is left out of the stamp digest, so a replica that has collected it
        /// and one that has not agree. Red when the filter is dropped (M11).
        @Test
        void expiredTombstone_isLeftOutOfTheStampDigest_whileAnUnexpiredOneCounts() {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            ring.addNode(new NodeId("n"));

            var node = DHTNode.dhtNode(new NodeId("n"), memoryStorageEngine(), ring, DHTConfig.SINGLE_NODE);
            var now = System.currentTimeMillis();
            var expired = KeyStamp.keyStamp(new DHTMessage.KeyValue(bytes("gone"), new byte[0],
                                                                    HlcTimestamp.pack(now - 2 * 3_600_000L, 0), 0L, 0L, 0L, true));
            var fresh = KeyStamp.keyStamp(new DHTMessage.KeyValue(bytes("gone"), new byte[0],
                                                                  HlcTimestamp.pack(now, 0), 0L, 0L, 0L, true));
            var live = KeyStamp.keyStamp(new DHTMessage.KeyValue(bytes("kept"), bytes("v"), HlcTimestamp.pack(now, 0), 0L, 0L, 0L));

            assertThat(node.stampDigestOf(List.of(live, expired))).as("collected or not, the digests agree")
                                                                  .isEqualTo(node.stampDigestOf(List.of(live)));
            assertThat(node.stampDigestOf(List.of(live, fresh))).as("control: an unexpired tombstone counts")
                                                                .isNotEqualTo(node.stampDigestOf(List.of(live)));
        }
    }

    /// D4: a replica that missed a remove serves the removed block only while it is fresh, so the window is at most one
    /// freshness window (K = 2 intervals, 60 s at defaults) after its last sync, under any fault that stops it syncing.
    /// Each fault test asserts the block is still served just inside the window and no longer just past it. Red when the
    /// freshness gate is removed: the faulted replica keeps serving.
    @Nested
    class RemovedBlockWindow {
        @Test
        void noFault_theStaleReplicasOwnRound_endsTheWindow() throws Exception {
            var removed = removedOnTwoOfThree();

            assertThat(removed.servedTo("a")).as("control: the window exists").isTrue();
            removed.member("c").antiEntropy().synchronizeNow();
            assertThat(removed.servedTo("a")).as("one round of the stale replica ends it").isFalse();
        }

        @Test
        void asymmetricPartition_staleReplicaCannotReachItsPeers_windowEndsWithTheFreshnessWindow() throws Exception {
            var away = removedAwayFromTheReader();
            var removed = away.removed();

            away.holders().forEach(holder -> removed.cluster().cut(away.stale(), holder));
            assertThat(removed.servedTo("r")).as("control: the reader still reaches the stale replica").isTrue();
            stepThroughWindow(removed, "r");
        }

        @Test
        void ringDisagreement_holdersRefuseTheStaleReplica_windowEndsWithTheFreshnessWindow() throws Exception {
            var removed = removedOnTwoOfThree();
            var c = new NodeId("c");

            removed.member("a").node().ring().removeNode(c);
            removed.member("b").node().ring().removeNode(c);
            // the reader that still routes to the stale replica is the stale replica itself
            assertThat(removed.servedTo("c")).as("control: the window exists").isTrue();
            stepThroughWindow(removed, "c");
            assertThat(removed.member("c").node().repairDeficitPartitions())
                .as("the refused pulls leave the divergence recorded").isPositive();
        }

        @Test
        void staleReplicaFailsToApply_windowEndsWithTheFreshnessWindow() throws Exception {
            var removed = removedOnTwoOfThree();

            removed.member("c").engine().failReplicaWrites.set(true);
            assertBoundedWindow(removed);
        }

        /// The case the verifier left untraced: the stale replica's partition is catching up. A catching-up partition
        /// is never fresh, so it serves no verified read at all, whatever it holds.
        @Test
        void staleReplicaCatchingUp_servesNoVerifiedRead() throws Exception {
            var removed = removedOnTwoOfThree();

            assertThat(removed.servedTo("a")).as("control: the window exists").isTrue();
            removed.member("c").node().beginCatchUp();
            assertThat(removed.servedTo("a")).isFalse();
        }

        private void assertBoundedWindow(Removed removed) {
            assertThat(removed.servedTo("a")).as("control: the window exists").isTrue();
            stepThroughWindow(removed, "a");
        }

        /// Rounds run every interval on every node; the stale replica's never sync it. Served just inside one freshness
        /// window after the last sync, no longer just past it.
        private void stepThroughWindow(Removed removed, String reader) {
            for (long elapsed = INTERVAL; elapsed < WINDOW; elapsed += INTERVAL) {
                removed.cluster().advance(INTERVAL);
                syncAll(removed.cluster());
            }

            removed.cluster().advance(INTERVAL - 1_000);
            assertThat(removed.servedTo(reader)).as("served just inside the freshness window").isTrue();
            assertThat(removed.existsFor(reader)).as("exists agrees with get inside the window").isTrue();
            removed.cluster().advance(1_001);
            System.out.printf("A6-D4 windowMs=%d servedAfter=%s%n", WINDOW, removed.servedTo(reader));
            assertThat(removed.servedTo(reader)).as("not served past the freshness window").isFalse();
            assertThat(removed.existsFor(reader)).as("exists agrees with get past the window").isFalse();
        }
    }

    /// Round 2 (H1, H2): the window stays closed once closed. Freshness is an interval on the replica's MONOTONIC clock,
    /// and a pull certifies the partition only as of the time its first page was asked.
    @Nested
    class WindowStaysClosed {
        /// H2: the stale replica is isolated after the remove, its window closes, then its wall clock steps back an hour.
        /// Red when freshness is measured on the wall clock: the step re-opens the window for the hour.
        @Test
        void wallClockStepsBack_theClosedWindowStaysClosed() throws Exception {
            var away = removedAwayFromTheReader();
            var removed = away.removed();
            var cluster = removed.cluster();

            away.holders().forEach(holder -> cluster.cut(away.stale(), holder));
            assertThat(removed.servedTo("r")).as("control: the window exists").isTrue();
            roundsEveryInterval(cluster, WINDOW / INTERVAL);
            cluster.advance(1_000);
            assertThat(removed.servedTo("r")).as("control: the window closed").isFalse();

            cluster.stepWallClock(-3_600_000L);

            var servedAfterStep = 0;

            for (int round = 0; round < 20; round++) {
                servedAfterStep += removed.servedTo("r") ? 1 : 0;
                roundsEveryInterval(cluster, 1);
            }

            System.out.printf("A6-H2 wallClockStepBack servedAfterStep=%d of 20%n", servedAfterStep);
            assertThat(removed.liveOn(away.stale())).as("control: the stale copy is still live, so the gate decides")
                                                    .isTrue();
            assertThat(servedAfterStep).as("served at none of the checks over 10 min after the step").isZero();
        }

        /// H1: the stale replica's paged pull from a holder is interrupted after its first page, before the remove; the
        /// removed key sorts below the cursor. The stale replica is then isolated, its window closes, and at 600 s its link
        /// to that holder heals: the pull resumes past the removed key and completes. Red when a completed pull is stamped
        /// with its completion time: the completion then certifies the partition and re-opens the window.
        @Test
        void resumedPull_completedAfterTheRemove_doesNotReopenTheWindow() throws Exception {
            var away = removedBeforeAnInterruptedPull();
            var removed = away.removed();
            var cluster = removed.cluster();
            var stale = cluster.member(away.stale());
            var holder = away.holders().getFirst();

            cluster.cut(away.stale(), holder);
            assertThat(removed.servedTo("r")).as("control: the window exists").isTrue();
            roundsEveryInterval(cluster, 3);
            assertThat(removed.servedTo("r")).as("control: the window closed by 90 s").isFalse();
            roundsEveryInterval(cluster, 600 / 30 - 3);

            cluster.cutLinks.remove(away.stale() + ">" + holder);

            var before = cluster.sent.size();

            stale.antiEntropy().synchronizeNow();

            var resumedAt = firstPageRequestFrom(cluster, before, stale.id());

            assertThat(resumedAt.flatMap(DHTMessage.MigrationPageRequest::after)
                                .map(cursor -> Arrays.compareUnsigned(cursor, removed.key()) > 0)
                                .or(false)).as("control: the heal round resumes past the removed key").isTrue();
            assertThat(fillersHeld(stale, away.fillers())).as("control: the resumed pull completed")
                                                           .isEqualTo(away.fillers().size());
            assertThat(removed.liveOn(away.stale())).as("control: the stale copy is still live, so the gate decides")
                                                    .isTrue();
            assertThat(removed.servedTo("r")).as("the completed pull does not re-open the window").isFalse();
            assertThat(removed.existsFor("r")).as("exists agrees with get").isFalse();
        }

        private static Option<DHTMessage.MigrationPageRequest> firstPageRequestFrom(RepairCluster cluster, int from, NodeId sender) {
            return Option.from(cluster.sent.stream()
                                           .skip(from)
                                           .map(Map.Entry::getValue)
                                           .filter(DHTMessage.MigrationPageRequest.class::isInstance)
                                           .map(DHTMessage.MigrationPageRequest.class::cast)
                                           .filter(request -> request.sender().equals(sender))
                                           .findFirst());
        }

        private static long fillersHeld(RepairCluster.Member member, List<byte[]> fillers) {
            return fillers.stream()
                          .filter(key -> member.engine().getEntry(key).await().unwrap().isPresent())
                          .count();
        }
    }

    private record Interrupted(RemovedAway away, List<byte[]> fillers) {
        Removed removed() {
            return away.removed();
        }

        String stale() {
            return away.stale();
        }

        List<String> holders() {
            return away.holders();
        }
    }

    /// [#removedAwayFromTheReader], except the stale replica's pull from the first holder was interrupted after one page
    /// just before the remove. Six 100 KiB fillers sorting after the block's key make the pull span several pages of the
    /// minimum budget, and the stale replica reaches only that holder from the start.
    private static Interrupted removedBeforeAnInterruptedPull() throws Exception {
        var cluster = new RepairCluster(new DHTConfig(3, 2, 2, timeSpan(2).seconds()), "a", "b", "c", "r");
        var reader = cluster.member("r");
        var budget = PageBudget.pageBudget(PageBudget.MIN_BYTES).unwrap();

        cluster.members.values().forEach(member -> member.node().repair().usePageBudget(budget));

        for (int seed = 0; ; seed++) {
            var block = value(4096, 4242 + seed);
            var sha = sha256(block);
            var key = contentKey(sha);
            var partition = reader.node().ring().partitionFor(key);
            var replicas = reader.node().ring().nodesFor(partition, 3);

            if (replicas.contains(reader.id())) {
                continue;
            }

            assertThat(reader.client().put(key, block).await().isSuccess()).isTrue();
            syncAll(cluster);

            var holders = List.of(replicas.get(0).id(), replicas.get(1).id());
            var stale = replicas.get(2).id();
            var fillers = fillersIn(reader.node().ring(), partition);

            for (var holder : holders) {
                fillers.forEach(filler -> cluster.member(holder).engine()
                                                 .putReplica(new DHTMessage.KeyValue(filler, value(100 * 1024, 5), 10L, 0L, 0L, 0L), true)
                                                 .await());
            }

            cluster.cut(stale, holders.get(1));
            cluster.pageRequestsDelivered.set(1);
            cluster.member(stale).antiEntropy().synchronizeNow();
            cluster.pageRequestsDelivered.set(-1);

            var removed = new Removed(cluster, block, sha, key);
            var stored = cluster.member(holders.getFirst()).engine().getEntry(key).await().unwrap().unwrap();

            assertThat(Arrays.compareUnsigned(key, fillers.getFirst())).as("control: the key sorts below the fillers").isNegative();
            assertThat(cluster.member(stale).engine().getEntry(fillers.getLast()).await().unwrap().isEmpty())
                .as("control: the pull was interrupted").isTrue();

            for (var holder : holders) {
                cluster.member(holder).engine().removeVersioned(key, stored.version() + 1, stored.epochIncarnation(),
                                                                stored.epochTerm(), stored.epochCounter()).await();
            }

            return new Interrupted(new RemovedAway(removed, stale, holders), fillers);
        }
    }

    private static List<byte[]> fillersIn(ConsistentHashRing<NodeId> ring, Partition partition) {
        var fillers = new ArrayList<byte[]>();

        for (int i = 0; fillers.size() < 6; i++) {
            var key = bytes("zz-filler-" + i);

            if (ring.partitionFor(key).equals(partition)) {
                fillers.add(key);
            }
        }

        return List.copyOf(fillers);
    }

    private static void roundsEveryInterval(RepairCluster cluster, long rounds) {
        for (long round = 0; round < rounds; round++) {
            cluster.advance(INTERVAL);
            syncAll(cluster);
        }
    }

    /// Round 2 (H3, D4): what counts as a sync. Three nodes, RF = 3; the replica `c` reaches only `b`, and its previous
    /// syncs have expired, so its next round alone decides whether it is fresh.
    @Nested
    class WhatCountsAsASync {
        /// `key` is the key `b` may hold alone; `fresh` asks about its partition.
        private record Isolated(RepairCluster cluster, RepairCluster.Member c, RepairCluster.Member b, byte[] key) {
            boolean fresh() {
                return c.node().verifiedReadServable(c.node().ring().partitionFor(key));
            }
        }

        private static Isolated reachingOnlyB(DHTConfig config) {
            var cluster = new RepairCluster(config, "a", "b", "c");
            var c = cluster.member("c");

            syncAll(cluster);
            cluster.cut("c", "a");
            cluster.advance(WINDOW + 1);

            return new Isolated(cluster, c, cluster.member("b"), keysInOnePartition(c.node().ring(), 1, "h3").getFirst());
        }

        /// The authoritative arm and the catching-up arm differ only in `b`'s readiness. Red when a digest match with a
        /// peer still catching up counts as a sync.
        @Test
        void digestMatch_countsOnlyWithAnAuthoritativePeer() {
            var serving = reachingOnlyB(new DHTConfig(3, 2, 2, timeSpan(2).seconds()));
            var catchingUp = reachingOnlyB(new DHTConfig(3, 2, 2, timeSpan(2).seconds()));

            assertThat(serving.fresh()).as("control: the earlier syncs expired").isFalse();
            catchingUp.b().node().beginCatchUp();
            serving.c().antiEntropy().synchronizeNow();
            catchingUp.c().antiEntropy().synchronizeNow();

            assertThat(serving.fresh()).as("control: a digest match with a serving peer syncs").isTrue();
            assertThat(catchingUp.fresh()).as("a digest match with a peer catching up does not").isFalse();
        }

        /// `b` holds an entry `c` lacks, so `c`'s round pulls it. Red when a completed pull from a holder catching up counts
        /// as a sync (H3), and in the serving arm when a completed paged pull never syncs.
        @Test
        void completedPull_syncsOnlyFromAnAuthoritativeHolder() {
            var serving = reachingOnlyB(new DHTConfig(3, 2, 2, timeSpan(2).seconds()));
            var catchingUp = reachingOnlyB(new DHTConfig(3, 2, 2, timeSpan(2).seconds()));

            catchingUp.b().node().beginCatchUp();

            var key = divergeOnB(serving);

            divergeOnB(catchingUp);
            serving.c().antiEntropy().synchronizeNow();
            catchingUp.c().antiEntropy().synchronizeNow();

            assertThat(serving.c().engine().getEntry(key).await().unwrap().isPresent()).as("control: the pull completed").isTrue();
            assertThat(catchingUp.c().engine().getEntry(key).await().unwrap().isPresent()).as("control: the pull completed").isTrue();
            assertThat(serving.cluster().sentOfType(DHTMessage.MigrationPageRequest.class)).as("control: paged, not legacy")
                                                                                           .isNotEmpty();
            assertThat(serving.fresh()).as("a completed pull from a serving holder syncs").isTrue();
            assertThat(catchingUp.fresh()).as("a completed pull from a holder catching up does not").isFalse();
        }

        /// A pull spans rounds: its first page came from `b` while `b` was catching up, then the pull was interrupted, `b`
        /// caught up, and the next round resumed and completed it from a serving `b`. The keys of that first page are no
        /// evidence, so the completed pull is no sync. Red when a pull's authority is judged by its later pages only.
        @Test
        void resumedPull_withAPageFromAHolderCatchingUp_isNoSync() {
            var isolated = reachingOnlyB(new DHTConfig(3, 2, 2, timeSpan(2).seconds()));
            var cluster = isolated.cluster();
            var budget = PageBudget.pageBudget(PageBudget.MIN_BYTES).unwrap();
            var keys = keysInOnePartition(isolated.b().node().ring(), 4, "h3");
            var partition = isolated.b().node().ring().partitionFor(isolated.key());

            cluster.members.values().forEach(member -> member.node().repair().usePageBudget(budget));
            keys.forEach(key -> isolated.b().engine()
                                        .putReplica(new DHTMessage.KeyValue(key, value(100 * 1024, 9), 3L, 0L, 0L, 0L), true)
                                        .await());
            isolated.b().node().beginCatchUp();
            cluster.pageRequestsDelivered.set(1);
            isolated.c().antiEntropy().synchronizeNow();
            cluster.pageRequestsDelivered.set(-1);
            assertThat(isolated.c().engine().getEntry(keys.getLast()).await().unwrap().isEmpty())
                .as("control: the pull was interrupted").isTrue();

            isolated.b().node().markServing(partition);
            isolated.c().antiEntropy().synchronizeNow();

            assertThat(isolated.c().engine().getEntry(keys.getLast()).await().unwrap().isPresent())
                .as("control: the resumed pull completed").isTrue();
            assertThat(isolated.fresh()).isFalse();
        }

        /// A legacy whole-partition pull carries no readiness, so it repairs but never syncs. Red when its completion
        /// counts as a sync.
        @Test
        void completedLegacyPull_repairsButIsNoSync() {
            var isolated = reachingOnlyB(new DHTConfig(3, 2, 2, timeSpan(2).seconds()));

            isolated.cluster().legacyNodes.add(isolated.b().id());
            isolated.c().node().repair().noteLegacyPeer(isolated.b().id());

            var key = divergeOnB(isolated);

            isolated.c().antiEntropy().synchronizeNow();

            assertThat(isolated.c().engine().getEntry(key).await().unwrap().isPresent()).as("control: the legacy pull completed")
                                                                                         .isTrue();
            assertThat(isolated.cluster().sentOfType(DHTMessage.MigrationDataRequest.class)).as("control: legacy, not paged")
                                                                                            .isNotEmpty();
            assertThat(isolated.fresh()).isFalse();
        }

        /// With W = 1 a remove may have reached one replica only, so a fresh replica needs RF − W = 2 co-replica syncs. Red
        /// when one sync is always enough.
        @Test
        void writeQuorumOne_needsSyncsWithTwoCoReplicas() {
            var isolated = reachingOnlyB(new DHTConfig(3, 1, 3, timeSpan(2).seconds()));

            isolated.c().antiEntropy().synchronizeNow();
            assertThat(isolated.fresh()).as("one of the two syncs required").isFalse();

            isolated.cluster().cutLinks.clear();
            isolated.c().antiEntropy().synchronizeNow();
            assertThat(isolated.fresh()).as("control: two syncs suffice").isTrue();
        }

        private static byte[] divergeOnB(Isolated isolated) {
            isolated.b().engine().putReplica(new DHTMessage.KeyValue(isolated.key(), value(256, 9), 3L, 0L, 0L, 0L), true).await();

            return isolated.key();
        }
    }

    @Nested
    class ExistsAgreesWithGet {
        /// D5: inside the window exists answers what get answers; once every copy is corrupt both say absent.
        @Test
        void exists_followsTheVerifiedPath_likeGet() throws Exception {
            var removed = removedOnTwoOfThree();

            assertThat(removed.servedTo("a")).isTrue();
            assertThat(removed.existsFor("a")).as("exists agrees with get while the stale replica is fresh").isTrue();
            assertThat(removed.member("a").client().exists(removed.key()).await().unwrap())
                .as("control: the quorum exists disagrees").isFalse();

            removed.cluster().members.values().forEach(member -> member.engine().corrupt(removed.key()));

            assertThat(removed.servedTo("a")).isFalse();
            assertThat(removed.existsFor("a")).as("no verifiable copy: exists says absent too").isFalse();
        }
    }
}
