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

        Cluster() {
            var ids = List.of(DEPOSED, new NodeId("new-owner"), new NodeId("replica"));

            ids.forEach(id -> members.put(id, member(id, ids)));
        }

        private Member member(NodeId id, List<NodeId> ids) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            ids.forEach(ring::addNode);

            var gate = new Gate(id.equals(DEPOSED)
                                ? OLD_EPOCH
                                : NEW_EPOCH);
            var node = dhtNode(id, memoryStorageEngine(gate), ring, CONFIG);
            DHTNetwork network = this::deliver;

            return new Member(id,
                              node,
                              dhtAntiEntropy(node, network, CONFIG),
                              distributedDHTClient(node, network, CONFIG, new StaleOwnerEpoch()));
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
            Option.option(members.get(target)).onPresent(member -> route(member, message));
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
