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

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The owner's fence ruling for #1818 (the Dynamo stance), G3c: a deposed owner's write must not survive its
/// own refusal. The deposed owner's high-water lags, so its OWN store accepts the stale-epoch write while
/// the replicas that saw the ownership rewrite refuse it and the put fails quorum. Without a rollback, the
/// deposed owner keeps that accept, and anti-entropy — whose copies bypass the high-water — spreads it to
/// the replicas that refused it (v1820's F1 probe). The coordinator therefore compare-and-deletes its own
/// accept when the put fails on stale-epoch refusals.
class DHTDeposedWriterRollbackTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(2).seconds());
    /// A 20 s operation timeout: the evidence bound (2 s) is clearly distinguishable from "at once" (v1882 F16/F17).
    private static final DHTConfig LONG_CONFIG = new DHTConfig(3, 2, 2, timeSpan(20).seconds());
    private static final long[] OLD_EPOCH = {0L, 1L, 1L};
    private static final long[] NEW_EPOCH = {0L, 2L, 2L};
    private static final NodeId DEPOSED = new NodeId("deposed");
    private static final byte[] KEY = "deposed-owner-write".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE = "deposed".getBytes(StandardCharsets.UTF_8);

    @Test
    void deposedOwnerWrite_refusedByTheOthers_isRolledBackLocally() {
        var cluster = new Cluster();
        var put = cluster.member(DEPOSED).client().put(KEY, VALUE).await();

        assertThat(put.isFailure()).as("control: the replicas that saw the rewrite refused the write").isTrue();
        assertThat(cluster.holds(DEPOSED, KEY)).as("the deposed owner rolled back its own accept").isFalse();
    }

    @Test
    void deposedOwnerWrite_refusedByTheOthers_isNeverSpreadByAntiEntropy() {
        var cluster = new Cluster();

        cluster.member(DEPOSED).client().put(KEY, VALUE).await();
        cluster.others().forEach(member -> member.antiEntropy().synchronizeNow());

        cluster.others().forEach(member -> assertThat(cluster.holds(member.id(), KEY))
            .as("anti-entropy did not spread the refused write to %s", member.id().id())
            .isFalse());
    }

    /// v1882 r11: an owner-epoch fence refusal is evidence, so the deposed write fails on it at once instead of waiting out the
    /// evidence bound (200 ms here, a tenth of the 2 s operation timeout).
    @Test
    void deposedOwnerWrite_failsOnTheFenceEvidence_withoutWaitingOutTheEvidenceBound() {
        var cluster = new Cluster();
        var started = System.nanoTime();
        var put = cluster.member(DEPOSED).client().put(KEY, VALUE).await();
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(put.isFailure()).isTrue();
        assertThat(elapsedMillis).as("well under the 200 ms evidence bound").isLessThan(150L);
    }

    /// G3b: the put is reported INDETERMINATE — a typed, retryable cause that says it may have been applied —
    /// never as a definite failure.
    @Test
    void deposedOwnerWrite_refusedByFences_failsIndeterminate_andRetryable() {
        var cluster = new Cluster();
        var put = cluster.member(DEPOSED).client().put(KEY, VALUE).await();

        assertThat(put.isFailure()).isTrue();
        put.onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.WriteIndeterminate.class)
                                                .isInstanceOf(Cause.Transient.class));
    }

    /// v1882 F17, epoch-fenced twin of probe U: one replica refuses by the owner-epoch fence, the other is DOWN (silent). The
    /// fence refusal is a verdict: the deposed owner fails WriteIndeterminate AT ONCE — typed, not a generic Timeout after the
    /// operation timeout — and never applies its own slot. (The latency pins use the 20 s config: the bound is 2 s.)
    @Test
    void deposedOwnerWrite_oneFencedReplicaPlusOneSilent_failsIndeterminateAtOnce_neverApplied() {
        var cluster = new Cluster(LONG_CONFIG);

        cluster.silent = java.util.Set.of(new NodeId("replica"));
        var started = System.nanoTime();
        var put = cluster.member(DEPOSED).client().put(KEY, VALUE).await(timeSpan(25).seconds());
        var elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        boolean indeterminate = put.fold(cause -> cause instanceof DHTError.WriteIndeterminate, _ -> false);

        assertThat(indeterminate).as("typed WriteIndeterminate, not a Timeout: " + put).isTrue();
        assertThat(elapsedMillis).as("the fence refusal is a verdict; no waiting out the evidence bound").isLessThan(1_000L);
        assertThat(cluster.holds(DEPOSED, KEY)).as("never applied locally").isFalse();
    }

    /// v1882 r12: while the deposed owner's own write is applied and unresolved, it answers another writer's request on the key
    /// with a typed retriable `writePending` refusal, never "superseded"; when its write times out the mark is CLEARED and the key
    /// answers normally again (no leak: it can never refuse forever).
    @Test
    void pendingOwnWrite_refusesAnotherWriterTyped_andIsClearedWhenItTimesOut() throws Exception {
        var cluster = new Cluster();
        var other = cluster.member(new NodeId("replica"));

        cluster.silent = java.util.Set.of(new NodeId("new-owner"), new NodeId("replica"));
        var put = cluster.member(DEPOSED).client().put(KEY, VALUE);
        Thread.sleep(600);

        assertThat(cluster.member(DEPOSED).node().localWritePending(KEY)).as("arming: applied on silence, unresolved").isTrue();
        var during = respondTo(cluster.member(DEPOSED).node(), "newer");

        assertThat(during.writePending()).as("a pending own write refuses, typed").isTrue();
        assertThat(during.superseded()).as("never answered superseded").isFalse();

        put.await(timeSpan(5).seconds());
        var deadline = System.nanoTime() + 2_000_000_000L;

        // the mark is cleared by a completion callback that may run just after the caller's wait returns
        while (cluster.member(DEPOSED).node().localWritePending(KEY) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(cluster.member(DEPOSED).node().localWritePending(KEY)).as("cleared on timeout: no leak").isFalse();
        assertThat(respondTo(cluster.member(DEPOSED).node(), "again").writePending()).as("answers normally afterwards").isFalse();
        assertThat(other).isNotNull();
    }

    private static DHTMessage.PutResponse respondTo(DHTNode node, String id) {
        var response = new AtomicReference<DHTMessage.PutResponse>();

        node.handlePutRequest(new DHTMessage.PutRequest(id,
                                                        new NodeId("another-writer"),
                                                        KEY,
                                                        "v".getBytes(StandardCharsets.UTF_8),
                                                        System.nanoTime() * 1_000L,
                                                        NEW_EPOCH[0],
                                                        NEW_EPOCH[1],
                                                        NEW_EPOCH[2],
                                                        DHTNode.NO_CHANGE),
                              response::set);

        return response.get();
    }

    /// v1882 r9b/r11: the one rollback that remains. No replica answers within the evidence wait (the requests are held), so
    /// the deposed owner applies its write locally per the named limit, over a value its slot already held; the fence refusals
    /// then arrive late and sink the quorum. The rollback must put THAT prior value back exactly, not delete the key.
    @Test
    void deposedOwnerWrite_appliedWithoutEvidence_thenFencedLate_isRestoredToThePrior_notDeleted() throws Exception {
        var cluster = new Cluster();
        var prior = "prior".getBytes(StandardCharsets.UTF_8);

        cluster.member(DEPOSED).node().storage().putVersioned(KEY, prior, 1L, 0L, 1L, 1L).await();
        cluster.holding = true;
        var put = cluster.member(DEPOSED).client().put(KEY, VALUE);
        cluster.holding = false;
        Thread.sleep(600);

        assertThat(cluster.entryValue(DEPOSED)).as("arming: no evidence within the wait, so the write was applied locally").isEqualTo("deposed");

        cluster.deliverHeld();
        var outcome = put.await();

        assertThat(outcome.isFailure()).as("control: the late fences sank the quorum").isTrue();
        assertThat(cluster.entryValue(DEPOSED)).as("the prior value is back, not a hole").isEqualTo("prior");
        assertThat(cluster.entryVersion(DEPOSED)).as("version-identical").isEqualTo(1L);
    }

    /// v1882 r9b: the displaced entry is read in the same step as the write — whatever the key held AT that moment, never
    /// an earlier read — and the exact-stamp restore leaves a newer write alone.
    @Test
    void displacingWrite_returnsWhatItReplaced_andRestoreLeavesANewerWriteAlone() {
        var storage = memoryStorageEngine();

        storage.putVersioned(KEY, "first".getBytes(StandardCharsets.UTF_8), 1L, 0L, 1L, 1L).await();
        var displaced = storage.putVersionedDisplacing(KEY, VALUE, 10L, 0L, 1L, 1L).await().fold(cause -> { throw new AssertionError(cause.message()); }, displacedWrite -> displacedWrite);

        assertThat(displaced.written()).isTrue();
        assertThat(displaced.prior().map(entry -> new String(entry.value(), StandardCharsets.UTF_8)).or("absent")).isEqualTo("first");

        storage.putVersioned(KEY, "newer".getBytes(StandardCharsets.UTF_8), 20L, 0L, 1L, 1L).await();

        assertThat(storage.restoreIfExactly(KEY, 10L, 0L, 1L, 1L, displaced.prior()).await().or(true)).as("superseded: left alone").isFalse();
        assertThat(new String(storage.get(KEY).await().or(Option.none()).or(new byte[0]), StandardCharsets.UTF_8)).isEqualTo("newer");
        assertThat(storage.restoreIfExactly(KEY, 20L, 0L, 1L, 1L, displaced.prior()).await().or(false)).as("control: the exact entry is restored").isTrue();
        assertThat(new String(storage.get(KEY).await().or(Option.none()).or(new byte[0]), StandardCharsets.UTF_8)).isEqualTo("first");
    }

    /// The rollback removes only the writer's OWN entry: one superseded since by a newer write stays.
    @Test
    void rollback_leavesAnEntrySupersededSinceTheWrite() {
        var storage = memoryStorageEngine();

        storage.putVersioned(KEY, VALUE, 10L, 0L, 1L, 1L).await();
        storage.putVersioned(KEY, "newer".getBytes(StandardCharsets.UTF_8), 20L, 0L, 1L, 1L).await();

        assertThat(storage.removeIfExactly(KEY, 10L, 0L, 1L, 1L).await().or(true)).isFalse();
        assertThat(storage.get(KEY).await().or(Option.none()).isPresent()).isTrue();
        assertThat(storage.removeIfExactly(KEY, 20L, 0L, 1L, 1L).await().or(false)).as("control: the exact entry goes")
                                                                                 .isTrue();
    }

    // --- harness: three nodes, RF 3, W 2; the deposed owner's fence lags, the others' have advanced ---

    private record Member(NodeId id, DHTNode node, DHTAntiEntropy antiEntropy, DistributedDHTClient client) {}

    private static final class Gate implements OwnerEpochGate {
        private final AtomicReference<long[]> highWater;

        Gate(long[] seeded) {
            highWater = new AtomicReference<>(seeded);
        }

        @Override
        public boolean isStale(byte[] key, long incarnation, long term, long counter) {
            return Arrays.compare(highWater.get(), new long[]{incarnation, term, counter}) > 0;
        }

        @Override
        public void advance(byte[] key, long incarnation, long term, long counter) {
            highWater.accumulateAndGet(new long[]{incarnation, term, counter}, Gate::later);
        }

        private static long[] later(long[] current, long[] presented) {
            return Arrays.compare(presented, current) > 0
                   ? presented
                   : current;
        }
    }

    /// The deposed owner still stamps its writes with the epoch it believes current.
    private record StaleOwnerEpoch() implements OwnerEpochSource {
        @Override
        public long currentEpochIncarnation() {
            return OLD_EPOCH[0];
        }

        @Override
        public long currentEpochTerm() {
            return OLD_EPOCH[1];
        }

        @Override
        public long currentEpochCounter() {
            return OLD_EPOCH[2];
        }
    }

    private static final class Cluster {
        private final Map<NodeId, Member> members = new LinkedHashMap<>();
        private final List<Map.Entry<NodeId, ProtocolMessage>> held = new java.util.ArrayList<>();
        volatile boolean holding;
        volatile java.util.Set<NodeId> silent = java.util.Set.of();

        private final DHTConfig config;

        Cluster() {
            this(CONFIG);
        }

        Cluster(DHTConfig config) {
            this.config = config;
            var ids = List.of(DEPOSED, new NodeId("new-owner"), new NodeId("replica"));

            ids.forEach(id -> members.put(id, member(id, ids)));
        }

        private Member member(NodeId id, List<NodeId> ids) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            ids.forEach(ring::addNode);

            var gate = new Gate(id.equals(DEPOSED)
                                ? OLD_EPOCH
                                : NEW_EPOCH);
            var node = dhtNode(id, memoryStorageEngine(gate), ring, config);
            DHTNetwork network = this::deliver;

            return new Member(id,
                              node,
                              dhtAntiEntropy(node, network, config),
                              distributedDHTClient(node, network, config, new StaleOwnerEpoch()));
        }

        Member member(NodeId id) {
            return members.get(id);
        }

        List<Member> others() {
            return members.values().stream().filter(member -> !member.id().equals(DEPOSED)).toList();
        }

        boolean holds(NodeId id, byte[] key) {
            return members.get(id).node().getLocal(key).await().or(Option.none()).isPresent();
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            if (message instanceof DHTMessage.PutRequest && silent.contains(target)) {
                return;
            }

            if (holding && message instanceof DHTMessage.PutRequest) {
                held.add(Map.entry(target, message));

                return;
            }

            Option.option(members.get(target)).onPresent(member -> route(member, message));
        }

        void deliverHeld() {
            var due = List.copyOf(held);

            held.clear();
            due.forEach(entry -> deliver(entry.getKey(), entry.getValue()));
        }

        String entryValue(NodeId id) {
            return members.get(id).node().storage().entries().await().or(List.of()).stream()
                          .filter(entry -> Arrays.equals(entry.key(), KEY))
                          .findFirst()
                          .map(entry -> new String(entry.value(), StandardCharsets.UTF_8))
                          .orElse("absent");
        }

        long entryVersion(NodeId id) {
            return members.get(id).node().storage().entries().await().or(List.of()).stream()
                          .filter(entry -> Arrays.equals(entry.key(), KEY))
                          .mapToLong(DHTMessage.KeyValue::version)
                          .findFirst()
                          .orElse(Long.MIN_VALUE);
        }

        private void route(Member member, ProtocolMessage message) {
            switch (message) {
                case DHTMessage.PutRequest request ->
                    member.node().handlePutRequest(request, response -> deliver(request.sender(), response));
                case DHTMessage.PutResponse response -> member.client().onPutResponse(response);
                case DHTMessage.DigestRequest request ->
                    member.node().handleDigestRequest(request, response -> deliver(request.sender(), response));
                case DHTMessage.DigestResponse response -> member.antiEntropy().onDigestResponse(response);
                case DHTMessage.MigrationDataRequest request ->
                    member.node().handleMigrationDataRequest(request, response -> deliver(request.sender(), response));
                case DHTMessage.MigrationDataResponse response -> member.antiEntropy().onMigrationDataResponse(response);
                default -> {}
            }
        }
    }
}
