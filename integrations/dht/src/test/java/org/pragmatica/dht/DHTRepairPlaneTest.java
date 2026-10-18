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
import org.pragmatica.consensus.net.OutboundMessageLimit;
import org.pragmatica.dht.storage.KeyStamp;
import org.pragmatica.lang.Option;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.RepairCluster.bytes;
import static org.pragmatica.dht.RepairCluster.keysInOnePartition;
import static org.pragmatica.dht.RepairCluster.value;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A6, the DHT repair plane: stamp digests that read no value, migration and departure pushes in pages bounded by
/// a byte budget, the verified single-replica read for content-addressed keys, and repair across a rolling upgrade.
class DHTRepairPlaneTest {
    private static final int MIB = 1024 * 1024;
    private static final DHTConfig PAIR = new DHTConfig(2, 1, 1, timeSpan(5).seconds());
    private static final PageBudget SMALL_BUDGET = PageBudget.pageBudget(512 * 1024).unwrap();

    private static DHTMessage.KeyValue entry(byte[] key, byte[] value, long version) {
        return new DHTMessage.KeyValue(key, value, version, 0L, 0L, 0L);
    }

    private static void store(RepairCluster.Member member, DHTMessage.KeyValue entry) {
        member.engine().putReplica(entry, true).await();
    }

    private static List<KeyStamp> stampsOf(RepairCluster.Member member, byte[] keyInPartition) {
        return PageBudget.sorted(member.engine()
                                       .stampsForPartition(member.node().ring(),
                                                           member.node().ring().partitionFor(keyInPartition))
                                       .await()
                                       .unwrap());
    }

    @Nested
    class StampDigests {
        /// A digest round compares every partition by stamps alone: neither the node asking nor the node answering
        /// reads a value. Red when the digest is computed over values (either side's engine counts a read).
        @Test
        void digestRound_readsNoValue_onEitherSide() {
            var cluster = new RepairCluster(PAIR, "node-a", "node-b");
            var a = cluster.member("node-a");
            var b = cluster.member("node-b");

            for (int i = 0; i < 50; i++) {
                var kv = entry(bytes("k-" + i), value(256, i), 100L + i);

                store(a, kv);
                store(b, kv);
            }

            a.engine().valueReads.set(0);
            b.engine().valueReads.set(0);

            a.antiEntropy().synchronizeNow();

            assertThat(cluster.sentOfType(DHTMessage.StampDigestRequest.class))
                .as("control: the round compared every partition")
                .hasSize(Partition.MAX_PARTITIONS);
            assertThat(a.node().unagreedPartitions(timeSpan(1).hours()))
                .as("control: every partition's digests matched, so the comparison was real")
                .isZero();
            assertThat(cluster.sentOfType(DHTMessage.DigestRequest.class)).as("no legacy digest between current nodes").isEmpty();
            assertThat(cluster.sentOfType(DHTMessage.MigrationPageRequest.class)).as("in sync: nothing pulled").isEmpty();
            assertThat(a.engine().valueReads).as("the asking node read no value").hasValue(0);
            assertThat(b.engine().valueReads).as("the answering node read no value").hasValue(0);
        }

        /// The instrument can fail: the legacy digest reads values, and the counter sees it.
        @Test
        void legacyDigest_readsValues_andTheInstrumentCountsThem() {
            var cluster = new RepairCluster(PAIR, "node-a", "node-b");
            var b = cluster.member("node-b");

            store(b, entry(bytes("k"), value(64, 1), 1L));
            b.engine().valueReads.set(0);
            b.node().handleDigestRequest(new DHTMessage.DigestRequest("legacy", new NodeId("node-a"), 0, 0), _ -> {});

            assertThat(b.engine().valueReads.get()).isPositive();
        }

        @Test
        void stampDigest_differs_whenOnlyTheValueDiffers() {
            var one = KeyStamp.keyStamp(entry(bytes("k"), bytes("v1"), 7L));
            var other = KeyStamp.keyStamp(entry(bytes("k"), bytes("v2"), 7L));

            assertThat(DHTNode.computeStampDigest(List.of(one))).isNotEqualTo(DHTNode.computeStampDigest(List.of(other)));
        }

        @Test
        void stampDigest_differs_whenOnlyTheVersionDiffers() {
            var one = KeyStamp.keyStamp(entry(bytes("k"), bytes("v"), 7L));
            var other = KeyStamp.keyStamp(entry(bytes("k"), bytes("v"), 8L));

            assertThat(DHTNode.computeStampDigest(List.of(one))).isNotEqualTo(DHTNode.computeStampDigest(List.of(other)));
        }

        @Test
        void stampDigest_isIndependentOfOrder() {
            var a = KeyStamp.keyStamp(entry(bytes("a"), bytes("1"), 1L));
            var b = KeyStamp.keyStamp(entry(bytes("b"), bytes("2"), 2L));

            assertThat(DHTNode.computeStampDigest(List.of(a, b))).isEqualTo(DHTNode.computeStampDigest(List.of(b, a)));
        }

        /// The memory engine's stamps carry the value's CRC and length kept beside it, and cover tombstones.
        @Test
        void memoryEngineStamps_carryValueCrcAndLength_andTombstones() {
            var engine = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            ring.addNode(new NodeId("n"));
            engine.putVersioned(bytes("live"), bytes("payload"), 5L).await();
            engine.removeVersioned(bytes("gone"), 6L, 0L, 0L, 0L).await();

            var stamps = engine.stamps().await().unwrap();
            var live = stamps.stream().filter(stamp -> Arrays.equals(stamp.key(), bytes("live"))).findFirst().orElseThrow();
            var gone = stamps.stream().filter(stamp -> Arrays.equals(stamp.key(), bytes("gone"))).findFirst().orElseThrow();

            assertThat(live.valueCrc()).isEqualTo(KeyStamp.crcOf(bytes("payload")));
            assertThat(live.valueLength()).isEqualTo(7);
            assertThat(live.version()).isEqualTo(5L);
            assertThat(gone.tombstone()).isTrue();
            assertThat(gone.valueLength()).isZero();
            assertThat(engine.stampsForPartition(ring, ring.partitionFor(bytes("live"))).await().unwrap())
                .anyMatch(stamp -> Arrays.equals(stamp.key(), bytes("live")));
        }
    }

    @Nested
    class PagedMigration {
        /// A 200 MiB partition reaches a co-replica through paged pulls, every page within the 16 MiB default budget
        /// as the wire codec encodes it. Red when the byte budget is dropped and the partition goes in one message.
        @Test
        void twoHundredMiBPartition_migrates_withEveryResponseWithinTheBudget() {
            var cluster = new RepairCluster(PAIR, "holder", "joiner");
            var holder = cluster.member("holder");
            var joiner = cluster.member("joiner");
            var keys = keysInOnePartition(holder.node().ring(), 200, "big");

            for (int i = 0; i < keys.size(); i++) {
                store(holder, entry(keys.get(i), value(MIB, i), 1_000L + i));
            }

            joiner.antiEntropy().synchronizeNow();

            var pages = cluster.sentOfType(DHTMessage.MigrationPageResponse.class);
            var largest = cluster.repairMessageBytes.stream().mapToInt(Integer::intValue).max().orElse(0);

            System.out.printf("A6-200MIB pages=%d largestEncodedBytes=%d budget=%d frame=%d%n",
                              pages.size(), largest, PageBudget.DEFAULT_BYTES, OutboundMessageLimit.MAX_TRANSFER_BYTES);
            assertThat(pages.size()).as("200 MiB cannot fit in fewer than 13 pages of 16 MiB").isGreaterThanOrEqualTo(13);
            assertThat(largest).as("every page, as encoded, is within the budget").isLessThanOrEqualTo((int) PageBudget.DEFAULT_BYTES);
            assertThat(pages.stream().mapToInt(page -> page.entries().size()).sum()).isEqualTo(200);
            assertThat(stampsOf(joiner, keys.getFirst())).as("every key arrived at the holder's stamp and checksum")
                                                         .usingRecursiveFieldByFieldElementComparator()
                                                         .isEqualTo(stampsOf(holder, keys.getFirst()));
            assertThat(joiner.node().getLocal(keys.get(137)).await().unwrap().unwrap())
                .as("and the bytes are the holder's").isEqualTo(value(MIB, 137));
            assertThat(holder.node().repair().pagesSent()).isEqualTo(pages.size());
            assertThat(holder.node().repair().pageBytesSent()).isGreaterThanOrEqualTo(200L * MIB);
            assertThat(joiner.node().repairDeficitPartitions()).as("the divergence is repaired").isZero();
        }

        /// A page lost in flight leaves the pull's cursor behind; the next round resumes there instead of at the
        /// first key, and completes the partition.
        @Test
        void interruptedPull_resumesAtTheLastAppliedCursor_andCompletes() {
            var cluster = new RepairCluster(PAIR, "holder", "joiner");
            var holder = cluster.member("holder");
            var joiner = cluster.member("joiner");
            var keys = keysInOnePartition(holder.node().ring(), 20, "resume");

            holder.node().repair().usePageBudget(SMALL_BUDGET);
            joiner.node().repair().usePageBudget(SMALL_BUDGET);
            keys.forEach(key -> store(holder, entry(key, value(100 * 1024, key.length), 10L)));
            cluster.pageRequestsDelivered.set(2);

            joiner.antiEntropy().synchronizeNow();

            assertThat(stampsOf(joiner, keys.getFirst()).size()).as("control: the lost page stopped the pull short")
                                                                .isBetween(1, keys.size() - 1);
            assertThat(joiner.node().repairDeficitPartitions()).as("and the divergence stays recorded").isEqualTo(1);

            var appliedSoFar = stampsOf(joiner, keys.getFirst());
            var requestsBefore = cluster.sentOfType(DHTMessage.MigrationPageRequest.class).size();

            joiner.antiEntropy().synchronizeNow();

            var resumed = cluster.sentOfType(DHTMessage.MigrationPageRequest.class).get(requestsBefore);

            assertThat(resumed.after().map(cursor -> Arrays.equals(cursor, appliedSoFar.getLast().key())).or(false))
                .as("the next round resumes after the last key applied").isTrue();
            assertThat(stampsOf(joiner, keys.getFirst())).hasSize(keys.size());
            assertThat(joiner.node().repairDeficitPartitions()).isZero();
        }
    }

    @Nested
    class PageRequests {
        /// Pages walk the partition in key order without gaps or repeats, and the last page carries no cursor.
        @Test
        void pages_coverThePartitionInOrder_andEndWithoutACursor() {
            var cluster = new RepairCluster(PAIR, "holder", "asker");
            var holder = cluster.member("holder");
            var keys = keysInOnePartition(holder.node().ring(), 10, "walk");

            holder.node().repair().usePageBudget(PageBudget.pageBudget(PageBudget.MIN_BYTES).unwrap());
            keys.forEach(key -> store(holder, entry(key, value(100 * 1024, 3), 1L)));

            var partition = holder.node().ring().partitionFor(keys.getFirst()).value();
            var seen = new ArrayList<byte[]>();
            Option<byte[]> cursor = Option.none();
            var pages = 0;

            do {
                var page = askPage(holder, partition, cursor, PageBudget.DEFAULT_BYTES);

                page.entries().forEach(kv -> seen.add(kv.key()));
                cursor = page.next();
                pages++;
            } while (cursor.isPresent() && pages < 100);

            assertThat(pages).as("a small budget splits the partition").isGreaterThan(1);
            assertThat(RepairCluster.keySet(seen)).isEqualTo(RepairCluster.keySet(keys));
            assertThat(seen).hasSize(keys.size());
            assertThat(seen).isSortedAccordingTo(PageBudget.keyOrder());
        }

        /// The page respects the smaller of the requester's budget and the holder's own.
        @Test
        void page_staysWithinTheHoldersBudget_whenTheRequesterAsksForMore() {
            var cluster = new RepairCluster(PAIR, "holder", "asker");
            var holder = cluster.member("holder");
            var keys = keysInOnePartition(holder.node().ring(), 8, "cap");

            holder.node().repair().usePageBudget(SMALL_BUDGET);
            keys.forEach(key -> store(holder, entry(key, value(100 * 1024, 4), 1L)));

            var page = askPage(holder, holder.node().ring().partitionFor(keys.getFirst()).value(), Option.none(), PageBudget.DEFAULT_BYTES);

            assertThat(PageBudget.transferBytes(page.entries())).isLessThanOrEqualTo(SMALL_BUDGET.entryBytes());
            assertThat(page.next().isPresent()).isTrue();
        }

        @Test
        void pageRequest_fromANodeThatIsNotAReplica_isRefused() {
            var cluster = new RepairCluster(new DHTConfig(1, 1, 1, timeSpan(5).seconds()), "holder", "other");
            var holder = cluster.member("holder");
            var key = findKeyReplicatedOnlyBy(holder, new NodeId("holder"));

            store(holder, entry(key, bytes("v"), 1L));

            var answer = new AtomicReference<DHTMessage.MigrationPageResponse>();

            holder.node().handleMigrationPageRequest(new DHTMessage.MigrationPageRequest("r", new NodeId("other"),
                                                                                         holder.node().ring().partitionFor(key).value(),
                                                                                         Option.none(), PageBudget.DEFAULT_BYTES),
                                                     answer::set);

            assertThat(answer.get().refused()).isTrue();
            assertThat(answer.get().entries()).isEmpty();
        }

        private static byte[] findKeyReplicatedOnlyBy(RepairCluster.Member member, NodeId owner) {
            for (int i = 0; ; i++) {
                var key = bytes("only-" + i);

                if (member.node().ring().nodesFor(key, 1).equals(List.of(owner))) {
                    return key;
                }
            }
        }

        private static DHTMessage.MigrationPageResponse askPage(RepairCluster.Member holder, int partition,
                                                                Option<byte[]> after, long pageBytes) {
            var answer = new AtomicReference<DHTMessage.MigrationPageResponse>();

            holder.node().handleMigrationPageRequest(new DHTMessage.MigrationPageRequest("r", new NodeId("asker"), partition, after, pageBytes),
                                                     answer::set);

            return answer.get();
        }
    }

    @Nested
    class DeparturePush {
        /// A departing node's large partition reaches its newcomer in pages within the budget, every page acked, so
        /// the push completes with nothing reported at risk. Red when the byte budget is dropped.
        @Test
        void largePartition_departurePush_completes_inPagesWithinTheBudget() {
            var cluster = new RepairCluster(PAIR, "leaver", "r1", "r2");
            var leaver = cluster.member("leaver");
            var keys = keysLeaverReplicates(leaver, 96);

            for (int i = 0; i < keys.size(); i++) {
                store(leaver, entry(keys.get(i), value(MIB, 500 + i), 1L));
            }

            var newcomer = newcomerOf(cluster, leaver, keys.getFirst());
            var reported = new AtomicInteger(-1);

            cluster.departing.add(leaver.id());
            leaver.rebalancer().pushOnDeparture(timeSpan(60).seconds(), (atRisk, _) -> reported.set(atRisk)).await();

            var pushes = cluster.sentOfType(DHTMessage.MigrationDataResponse.class);
            var largest = cluster.repairMessageBytes.stream().mapToInt(Integer::intValue).max().orElse(0);

            System.out.printf("A6-DEPARTURE pages=%d largestEncodedBytes=%d budget=%d%n", pushes.size(), largest, PageBudget.DEFAULT_BYTES);
            assertThat(pushes.size()).as("96 MiB cannot fit in fewer than 6 pages").isGreaterThanOrEqualTo(6);
            assertThat(largest).isLessThanOrEqualTo((int) PageBudget.DEFAULT_BYTES);
            assertThat(reported).as("every page was acked: nothing at risk").hasValue(-1);
            assertThat(stampsOf(newcomer, keys.getFirst())).hasSize(keys.size());
            assertThat(newcomer.node().getLocal(keys.get(41)).await().unwrap().unwrap()).isEqualTo(value(MIB, 541));
        }

        /// Pages leave one after another, each once the previous is acked: with no ack coming back only the first
        /// page leaves, and when the budget expires every key — sent and unsent — is reported at risk.
        @Test
        void unackedPush_reportsEveryPlannedKeyAtRisk_andSendsOnlyTheFirstPage() {
            var cluster = new RepairCluster(PAIR, "leaver", "r1", "r2");
            var leaver = cluster.member("leaver");
            var keys = keysLeaverReplicates(leaver, 40);

            leaver.node().repair().usePageBudget(SMALL_BUDGET);
            keys.forEach(key -> store(leaver, entry(key, value(100 * 1024, 9), 1L)));

            var newcomer = newcomerOf(cluster, leaver, keys.getFirst());
            var reported = new AtomicInteger(-1);

            cluster.unreachable.add(newcomer.id());
            leaver.rebalancer().pushOnDeparture(timeSpan(300).millis(), (atRisk, _) -> reported.set(atRisk)).await();

            assertThat(cluster.sentOfType(DHTMessage.MigrationDataResponse.class)).as("the chain waits for the first ack").hasSize(1);
            assertThat(reported).as("unsent pages are at risk too").hasValue(keys.size());
        }

        private static List<byte[]> keysLeaverReplicates(RepairCluster.Member leaver, int count) {
            for (int attempt = 0; ; attempt++) {
                var keys = keysInOnePartition(leaver.node().ring(), count, "leave-" + attempt);

                if (leaver.node().ring().nodesFor(keys.getFirst(), 2).contains(leaver.id())) {
                    return keys;
                }
            }
        }

        private static RepairCluster.Member newcomerOf(RepairCluster cluster, RepairCluster.Member leaver, byte[] key) {
            var current = leaver.node().ring().nodesFor(key, 2);

            return cluster.members.values().stream()
                                  .filter(member -> !current.contains(member.id()))
                                  .findFirst()
                                  .orElseThrow();
        }
    }

    @Nested
    class VerifiedRead {
        private static final DHTConfig TRIPLE = new DHTConfig(3, 2, 2, timeSpan(2).seconds());
        private static final HexFormat HEX = HexFormat.of();

        private static byte[] sha256(byte[] value) throws Exception {
            return MessageDigest.getInstance("SHA-256").digest(value);
        }

        private static byte[] contentKey(byte[] sha) {
            return bytes("content-blocks/" + HEX.formatHex(sha));
        }

        private record Stored(RepairCluster cluster, RepairCluster.Member reader, byte[] block, byte[] sha, byte[] key) {}

        private static Stored stored() throws Exception {
            var cluster = new RepairCluster(TRIPLE, "a", "b", "c");
            var reader = cluster.member("a");
            var block = value(4096, 7);
            var sha = sha256(block);
            var key = contentKey(sha);

            assertThat(reader.client().put(key, block).await().isSuccess()).isTrue();
            // a replica serves a verified read only once its own round has synced it (D4)
            cluster.members.values().forEach(member -> member.antiEntropy().synchronizeNow());

            return new Stored(cluster, reader, block, sha, key);
        }

        private static int getRequests(RepairCluster cluster) {
            return cluster.sentOfType(DHTMessage.GetRequest.class).size();
        }

        private static int remoteReads(RepairCluster cluster) {
            return cluster.sentOfType(DHTMessage.VerifiedReadRequest.class).size();
        }

        /// One corrupt replica — here the reader's own, which it tries first — is detected by the hash check, the
        /// read moves to the next replica, returns the right bytes and counts the fallback. Red when the hash check is
        /// skipped (the corrupt bytes come back) or the fallback is removed (nothing comes back).
        @Test
        void oneCorruptReplica_returnsTheCorrectBytes_andCountsTheFallback() throws Exception {
            var stored = stored();
            var before = remoteReads(stored.cluster());

            stored.reader().engine().corrupt(stored.key());

            var read = stored.reader().client().getContentAddressed(stored.key(), stored.sha()).await().unwrap();
            var repair = stored.reader().node().repair();

            assertThat(read.unwrap()).isEqualTo(stored.block());
            assertThat(repair.verifiedReadFallbacks()).isEqualTo(1);
            assertThat(repair.verifiedReadMismatches()).isEqualTo(1);
            assertThat(repair.verifiedReadQuorumFallbacks()).isZero();
            assertThat(remoteReads(stored.cluster()) - before).as("one remote replica was asked, not a quorum").isEqualTo(1);
        }

        @Test
        void healthyReplicas_areReadFromOneReplicaOnly() throws Exception {
            var stored = stored();
            var before = remoteReads(stored.cluster());

            var read = stored.reader().client().getContentAddressed(stored.key(), stored.sha()).await().unwrap();

            assertThat(read.unwrap()).isEqualTo(stored.block());
            assertThat(remoteReads(stored.cluster()) - before).as("the local replica answered").isZero();
            assertThat(stored.reader().node().repair().verifiedReadFallbacks()).isZero();
        }

        /// Ordinary keys keep the quorum read: every remote replica is asked. Red when the single-replica path is
        /// applied to ordinary reads.
        @Test
        void ordinaryGet_stillReadsTheQuorum() throws Exception {
            var stored = stored();
            var before = getRequests(stored.cluster());

            var read = stored.reader().client().get(stored.key()).await().unwrap();

            assertThat(read.unwrap()).isEqualTo(stored.block());
            assertThat(getRequests(stored.cluster()) - before).as("both remote replicas were asked").isEqualTo(2);
            assertThat(stored.reader().node().repair().verifiedReadFallbacks()).isZero();
        }

        /// No replica holds a verifiable copy: the quorum read decides, and a corrupt copy is never returned.
        @Test
        void everyReplicaCorrupt_returnsNothing_neverTheCorruptBytes() throws Exception {
            var stored = stored();

            stored.cluster().members.values().forEach(member -> member.engine().corrupt(stored.key()));

            var read = stored.reader().client().getContentAddressed(stored.key(), stored.sha()).await().unwrap();
            var repair = stored.reader().node().repair();

            assertThat(read.isEmpty()).isTrue();
            assertThat(repair.verifiedReadQuorumFallbacks()).isEqualTo(1);
            assertThat(repair.verifiedReadMismatches()).isGreaterThanOrEqualTo(3);
        }

        @Test
        void absentKey_fallsBackToTheQuorum_andIsAbsent() throws Exception {
            var stored = stored();
            var missing = value(100, 99);
            var sha = sha256(missing);

            var read = stored.reader().client().getContentAddressed(contentKey(sha), sha).await().unwrap();

            assertThat(read.isEmpty()).isTrue();
            assertThat(stored.reader().node().repair().verifiedReadQuorumFallbacks()).isEqualTo(1);
        }
    }

    @Nested
    class MixedVersions {
        /// A rolling upgrade: a new node and an old one (which drops the A6 message types and speaks only the legacy
        /// protocol, simulated by its wire and its legacy record of every peer). The new node's first question goes
        /// unanswered; the old node's own round announces it; from then on the new node repairs with it in the legacy
        /// protocol, in both directions. Once the old node restarts upgraded, the record clears.
        @Test
        void newNode_repairsWithAnOldPeer_inTheLegacyProtocol_untilItUpgrades() {
            var cluster = new RepairCluster(PAIR, "new", "old");
            var fresh = cluster.member("new");
            var old = cluster.member("old");
            var keys = keysInOnePartition(old.node().ring(), 20, "mixed");

            cluster.legacyNodes.add(old.id());
            old.node().repair().noteLegacyPeer(fresh.id());
            keys.forEach(key -> store(old, entry(key, value(512, key.length), 3L)));

            fresh.antiEntropy().synchronizeNow();

            assertThat(cluster.droppedUnknownTypes.get()).as("the old node dropped the stamp digest requests").isPositive();
            assertThat(fresh.node().repair().isLegacyPeer(old.id())).isFalse();
            assertThat(fresh.engine().size()).as("nothing repaired yet").isZero();

            old.antiEntropy().synchronizeNow();

            assertThat(fresh.node().repair().isLegacyPeer(old.id())).as("the old node announced itself").isTrue();

            fresh.antiEntropy().synchronizeNow();

            assertThat(stampsOf(fresh, keys.getFirst())).as("the new node repaired from the old one").hasSize(keys.size());
            assertThat(fresh.node().repair().legacyExchanges()).isPositive();
            assertThat(cluster.sentOfType(DHTMessage.MigrationDataRequest.class).stream()
                              .anyMatch(request -> request.sender().equals(fresh.id())))
                .as("in the legacy whole-partition pull").isTrue();

            var extra = keysInOnePartition(fresh.node().ring(), 1, "fresh-only").getFirst();

            store(fresh, entry(extra, bytes("from-new"), 4L));
            old.antiEntropy().synchronizeNow();

            assertThat(old.node().getLocal(extra).await().unwrap().isPresent()).as("and the old node repaired from the new one").isTrue();

            cluster.legacyNodes.remove(old.id());
            old.node().repair().noteCurrentPeer(fresh.id());
            old.antiEntropy().synchronizeNow();

            assertThat(fresh.node().repair().isLegacyPeer(old.id())).as("an upgraded peer is current again").isFalse();
        }
    }

    @Nested
    class MixedVersionCatchUp {
        /// A booting new node catches up from an old peer it has recorded as legacy: the catch-up digest and pull go
        /// in the legacy protocol, completion is proven by a stamp readback, and the partition becomes serving.
        @Test
        void bootingNewNode_catchesUpFromALegacyPeer_inTheLegacyProtocol() {
            var cluster = new RepairCluster(PAIR, "new", "old");
            var fresh = cluster.member("new");
            var old = cluster.member("old");
            var keys = keysInOnePartition(old.node().ring(), 10, "boot");
            var partition = fresh.node().ring().partitionFor(keys.getFirst());

            cluster.legacyNodes.add(old.id());
            old.node().repair().noteLegacyPeer(fresh.id());
            keys.forEach(key -> store(old, entry(key, value(256, 1), 2L)));
            fresh.node().handleDigestRequest(new DHTMessage.DigestRequest("legacy-hello", old.id(), 0, 0), _ -> {});
            fresh.node().beginCatchUp();

            assertThat(fresh.node().readiness(partition)).as("control: the partition starts catching up")
                                                         .isEqualTo(DHTMessage.Readiness.CATCHING_UP);

            fresh.antiEntropy().catchUpNow();

            assertThat(stampsOf(fresh, keys.getFirst())).hasSize(keys.size());
            assertThat(fresh.node().readiness(partition)).isEqualTo(DHTMessage.Readiness.SERVING);
            assertThat(cluster.sentOfType(DHTMessage.MigrationPageRequest.class)).as("no page request to a legacy peer").isEmpty();
        }
    }

    @Nested
    class Wire {
        @Test
        void newMessages_roundTrip_throughTheWireCodec() {
            var digest = RepairCluster.roundTrip(new DHTMessage.StampDigestRequest("d", new NodeId("n"), 17));
            var first = RepairCluster.roundTrip(new DHTMessage.MigrationPageRequest("p", new NodeId("n"), 17, Option.none(), 4096L));
            var next = RepairCluster.roundTrip(new DHTMessage.MigrationPageRequest("p", new NodeId("n"), 17, Option.some(bytes("k")), 4096L));
            var page = RepairCluster.roundTrip(new DHTMessage.MigrationPageResponse("p", new NodeId("n"),
                                                                                   List.of(entry(bytes("k"), bytes("v"), 3L)),
                                                                                   false, Option.some(bytes("k"))));

            assertThat(digest.partition()).isEqualTo(17);
            assertThat(first.after().isEmpty()).isTrue();
            assertThat(next.after().unwrap()).isEqualTo(bytes("k"));
            assertThat(next.pageBytes()).isEqualTo(4096L);
            assertThat(page.entries().getFirst().value()).isEqualTo(bytes("v"));
            assertThat(page.next().unwrap()).isEqualTo(bytes("k"));
        }
    }

    @Nested
    class Budget {
        @Test
        void pageBudget_isValidated_belowTheFrame() {
            assertThat(PageBudget.pageBudget(PageBudget.MIN_BYTES - 1).isFailure()).isTrue();
            assertThat(PageBudget.pageBudget(OutboundMessageLimit.MAX_TRANSFER_BYTES).isFailure()).isTrue();
            assertThat(PageBudget.pageBudget(PageBudget.DEFAULT_BYTES).isSuccess()).isTrue();
            assertThat(PageBudget.MAX_BYTES).isLessThan(OutboundMessageLimit.MAX_TRANSFER_BYTES);
        }

        @Test
        void pages_stayWithinTheBudget_andAnOversizedEntryTravelsAlone() {
            var stamps = List.of(KeyStamp.keyStamp(entry(bytes("a"), new byte[200 * 1024], 1L)),
                                 KeyStamp.keyStamp(entry(bytes("b"), new byte[200 * 1024], 1L)),
                                 KeyStamp.keyStamp(entry(bytes("c"), new byte[900 * 1024], 1L)),
                                 KeyStamp.keyStamp(entry(bytes("d"), new byte[10], 1L)));
            var pages = SMALL_BUDGET.pages(stamps);

            assertThat(pages).hasSize(3);
            assertThat(pages.get(0)).hasSize(2);
            assertThat(pages.get(1)).as("an entry above the budget is alone in its page").hasSize(1);
            assertThat(pages.stream().filter(page -> page.size() > 1)
                            .allMatch(page -> page.stream().mapToLong(KeyStamp::transferBytes).sum() <= SMALL_BUDGET.entryBytes()))
                .isTrue();
        }
    }
}
