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
import org.pragmatica.dht.storage.DhtPrefixAccounting;
import org.pragmatica.dht.storage.OwnerEpochGate;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// v-2509 H5 (#2258): the capacity precedence must not cost the #1818 rollback. A deposed owner's write is accepted by its OWN
/// lagging fence, then one replica fences it LATE (after the local slot was applied) and another refuses it for capacity. The
/// fence verdict says the local accept may spread through anti-entropy (which bypasses the high-water), so the coordinator must
/// still compare-and-delete it, whatever error the caller is handed. At the PR head `refinedPutFailure` hands back `ReplicaFull`,
/// and `afterFailedWrite` rolls back only on `WriteIndeterminate`: the fence-refused accept stays on the deposed owner.
class DHTCapacityFenceRollbackTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(2).seconds());
    private static final long[] OLD_EPOCH = {0L, 1L, 1L};
    private static final long[] NEW_EPOCH = {0L, 2L, 2L};
    private static final NodeId DEPOSED = new NodeId("deposed");
    private static final NodeId NEW_OWNER = new NodeId("new-owner");
    private static final NodeId REPLICA = new NodeId("replica");
    private static final byte[] KEY = "stream-segments/deposed-owner-write".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE = "0123456789".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PRIOR = "prior".getBytes(StandardCharsets.UTF_8);

    /// Control (passes at the head): the same late fence, the third replica silent instead of full: the put ends indeterminate and the
    /// local accept is rolled back.
    @Test
    void control_aLateFenceWithASilentReplica_rollsTheLocalAcceptBack() {
        var cluster = new Cluster(false);

        cluster.silent = REPLICA;

        var outcome = cluster.putWithLateFence();

        assertThat(outcome.failure()).isInstanceOf(DHTError.WriteIndeterminate.class);
        assertThat(cluster.holds(DEPOSED)).as("the fence-refused accept must not stay on the deposed owner").isFalse();
    }

    /// RED at the head: the late fence beside a capacity refusal. Whatever the writer is told, the local accept that a fence refused is
    /// not left to spread.
    @Test
    void aLateFence_besideACapacityRefusal_stillRollsTheLocalAcceptBack() {
        var cluster = new Cluster(true);
        var outcome = cluster.putWithLateFence();

        assertThat(outcome.failure()).as("the writer is told the capacity refusal or the indeterminate verdict")
                                     .isNotNull();
        assertThat(cluster.holds(DEPOSED)).as("a fence-refused local accept must be compare-and-deleted even when the caller sees ReplicaFull (error: %s)",
                                              outcome.failure())
                                          .isFalse();
    }

    /// Fence only, no capacity refusal, EARLY: both replicas fence within the evidence wait, so the writer never applies its own slot
    /// and the put ends indeterminate at once (the pre-#2258 behaviour, unchanged by the capacity precedence).
    @Test
    void fenceOnly_anEarlyFence_skipsTheLocalSlot_andFailsIndeterminate() {
        var cluster = new Cluster(false, true);

        cluster.holdFences = false;
        var failure = cluster.members.get(DEPOSED).client().put(KEY, VALUE).await().<Cause>fold(cause -> cause, _ -> null);

        assertThat(failure).isInstanceOf(DHTError.WriteIndeterminate.class);
        assertThat(cluster.localApplies()).as("a write fenced before the local apply is never applied on the writer, not even transiently")
                                          .isZero();
        assertThat(cluster.holds(DEPOSED)).as("a write fenced before the local apply is never stored on the writer").isFalse();
    }

    /// Fence only, no capacity refusal, LATE: both fence refusals arrive after the writer applied its own slot over a prior value and
    /// sink the quorum. The put fails indeterminate and the writer's slot is put back to the prior value (#1818 restore-prior).
    @Test
    void fenceOnly_aLateFence_failsIndeterminate_andRestoresTheWritersPriorValue() {
        var cluster = new Cluster(false, true);

        cluster.members.get(DEPOSED).node().putLocal(KEY, PRIOR).await();
        var outcome = cluster.putWithLateFence();

        assertThat(outcome.failure()).isInstanceOf(DHTError.WriteIndeterminate.class);
        assertThat(cluster.localApplies()).as("control: the late case did apply the writer's slot").isPositive();
        assertThat(cluster.stored(DEPOSED).or(new byte[0])).as("the writer's slot is back to the value the fenced write displaced")
                                                           .isEqualTo(PRIOR);
    }

    private record Outcome(Cause failure) {}

    private record Member(NodeId id, DHTNode node, DistributedDHTClient client) {}

    private static final class Gate implements OwnerEpochGate {
        private final AtomicReference<long[]> highWater;
        private final AtomicInteger advances = new AtomicInteger();

        Gate(long[] seeded) {
            highWater = new AtomicReference<>(seeded);
        }

        @Override
        public boolean isStale(byte[] key, long incarnation, long term, long counter) {
            return Arrays.compare(highWater.get(), new long[]{incarnation, term, counter}) > 0;
        }

        @Override
        public void advance(byte[] key, long incarnation, long term, long counter) {
            advances.incrementAndGet();
            highWater.accumulateAndGet(new long[]{incarnation, term, counter},
                                       (current, presented) -> Arrays.compare(presented, current) > 0
                                                               ? presented
                                                               : current);
        }
    }

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
        private final List<DHTMessage.PutResponse> heldFence = new CopyOnWriteArrayList<>();
        volatile NodeId silent;
        volatile boolean holdFences = true;
        private final Gate deposedGate = new Gate(OLD_EPOCH);

        Cluster(boolean replicaCapped) {
            this(replicaCapped, false);
        }

        Cluster(boolean replicaCapped, boolean replicaFenced) {
            var ids = List.of(DEPOSED, NEW_OWNER, REPLICA);

            ids.forEach(id -> members.put(id, member(id, ids, replicaCapped, replicaFenced)));
        }

        private Member member(NodeId id, List<NodeId> ids, boolean replicaCapped, boolean replicaFenced) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            ids.forEach(ring::addNode);
            var gate = id.equals(DEPOSED)
                       ? deposedGate
                       : new Gate(NEW_EPOCH);
            var accounting = id.equals(REPLICA) && replicaCapped
                             ? DhtPrefixAccounting.dhtPrefixAccounting(1L)
                             : DhtPrefixAccounting.dhtPrefixAccounting();
            // unless fenced, the replica's gate is a no-op, so only the cap (or silence) refuses there
            var engine = id.equals(REPLICA) && !replicaFenced
                         ? memoryStorageEngine(OwnerEpochGate.noOp(), accounting)
                         : memoryStorageEngine(gate, accounting);
            var node = dhtNode(id, engine, ring, CONFIG);
            DHTNetwork network = this::deliver;

            return new Member(id, node, distributedDHTClient(node, network, CONFIG, new StaleOwnerEpoch()));
        }

        /// How many stamped writes the writer's own store accepted: an applied local slot advances its high-water gate.
        int localApplies() {
            return deposedGate.advances.get();
        }

        boolean holds(NodeId id) {
            return stored(id).isPresent();
        }

        Option<byte[]> stored(NodeId id) {
            return members.get(id).node().getLocal(KEY).await().or(Option.none());
        }

        boolean holdsTheWrite(NodeId id) {
            return stored(id).filter(value -> Arrays.equals(value, VALUE)).isPresent();
        }

        /// The fence refusals reach the writer only after the writer applied its own slot.
        Outcome putWithLateFence() {
            var put = members.get(DEPOSED).client().put(KEY, VALUE);
            var deadline = System.currentTimeMillis() + 1500;

            while (!holdsTheWrite(DEPOSED) && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(holdsTheWrite(DEPOSED)).as("precondition: the lagging fence accepted the write locally before the late refusal")
                                      .isTrue();
            heldFence.forEach(response -> members.get(DEPOSED).client().onPutResponse(response));
            heldFence.clear();

            return new Outcome(put.await().<Cause>fold(cause -> cause, _ -> null));
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            if (target.equals(silent)) {
                return;
            }
            if (message instanceof DHTMessage.PutResponse response && holdFences && target.equals(DEPOSED) && response.fenced()) {
                heldFence.add(response);
                return;
            }
            Option.option(members.get(target)).onPresent(member -> route(member, message));
        }

        private void route(Member member, ProtocolMessage message) {
            switch (message) {
                case DHTMessage.PutRequest request ->
                    member.node().handlePutRequest(request, response -> deliver(request.sender(), response));
                case DHTMessage.PutResponse response -> member.client().onPutResponse(response);
                default -> {}
            }
        }
    }
}
