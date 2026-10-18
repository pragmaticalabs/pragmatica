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

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

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
