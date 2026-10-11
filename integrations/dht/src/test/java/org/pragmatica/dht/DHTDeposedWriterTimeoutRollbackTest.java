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
class DHTDeposedWriterTimeoutRollbackTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(2).seconds());
    private static final long[] OLD_EPOCH = {0L, 1L, 1L};
    private static final long[] NEW_EPOCH = {0L, 2L, 2L};
    private static final NodeId DEPOSED = new NodeId("deposed");
    private static final byte[] KEY = "deposed-owner-write".getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALUE = "deposed".getBytes(StandardCharsets.UTF_8);

    /// v1820 r3: one refusing replica is slow (its reply never arrives): the put ends on the operation TIMEOUT,
    /// not on the collector's indeterminate verdict — is the deposed owner's own accept still rolled back?
    @Test
    void q1_deposedWrite_withOneReplicaSilent_isStillRolledBack_andNeverSpread() {
        var cluster = new Cluster();
        cluster.silent.add(new NodeId("replica"));
        var put = cluster.member(DEPOSED).client().put(KEY, VALUE).await();
        
        cluster.silent.clear();
        cluster.others().forEach(member -> member.antiEntropy().synchronizeNow());
        assertThat(put.isFailure()).isTrue();
        assertThat(cluster.holds(DEPOSED, KEY)).as("the refused write must not stay on the deposed owner").isFalse();
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

        final java.util.Set<NodeId> silent = new java.util.HashSet<>();

        private void deliver(NodeId target, ProtocolMessage message) {
            if (silent.contains(target)) {
                return;
            }
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
