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
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTAntiEntropy.dhtAntiEntropy;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DHTRebalancer.dhtRebalancer;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// Issue #1818, mechanism (b): a replica copy must not be refused by the owner-epoch fence.
///
/// Every node fences its DHT writes against ONE "core" ownership high-water, and that high-water
/// advances whenever `DhtPartitionOwnershipKey("core")` is rewritten (run 7: 02:00:42, owner core-0 to
/// core-2). A key written before the rewrite carries the older epoch for the rest of its life. When
/// migration applies such a key through the fenced write path, a node that does not yet hold it
/// refuses it as a deposed owner's write — so after the rewrite NO node that lacks the key can ever
/// acquire it, by anti-entropy pull or by departure push. In run 7 the joiners 5g4pb and 5skrc never
/// acquired the entity-slice meta key, and it died with its last holder.
///
/// Each test first shows the fence is live on the receiver (a fresh write at the old epoch is refused),
/// then asserts that a COPY of an already-accepted entry is stored.
class DHTMigrationEpochFenceTest {
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, DHTConfig.DEFAULT_TIMEOUT);
    private static final long PRE_REWRITE_TERM = 1L;
    private static final long PRE_REWRITE_COUNTER = 1L;
    private static final long POST_REWRITE_TERM = 2L;
    private static final long POST_REWRITE_COUNTER = 2L;
    private static final byte[] VALUE = "artifact-meta".getBytes(StandardCharsets.UTF_8);

    @Test
    void antiEntropyPull_copiesAPreRewriteKey_toAReplicaWhoseHighWaterHasAdvanced() {
        var cluster = new FencedCluster(List.of("holder", "joiner"));
        var holder = cluster.member("holder");
        var joiner = cluster.member("joiner");
        var key = key("pull");

        cluster.writeAtPreRewriteEpoch(holder, key);
        cluster.advanceEveryHighWaterPastTheKey();

        assertThat(cluster.freshWriteAtPreRewriteEpochAccepted(joiner, key)).as("control: the joiner's fence is live")
                                                                           .isFalse();

        joiner.antiEntropy().synchronizeNow();

        assertThat(cluster.holds(joiner, key)).as("the joiner's pull stored the holder's copy").isTrue();
    }

    /// The control for the pull test: the same exchange with no ownership rewrite. It passes with or
    /// without the fix, so the rewrite is the only variable the pull test's red depends on.
    @Test
    void antiEntropyPull_withoutARewrite_copiesTheKey() {
        var cluster = new FencedCluster(List.of("holder", "joiner"));
        var joiner = cluster.member("joiner");
        var key = key("no-rewrite");

        cluster.writeAtPreRewriteEpoch(cluster.member("holder"), key);
        joiner.antiEntropy().synchronizeNow();

        assertThat(cluster.holds(joiner, key)).as("the pull path itself delivers the copy").isTrue();
    }

    @Test
    void departurePush_copiesAPreRewriteKey_toANewcomerWhoseHighWaterHasAdvanced() {
        var cluster = new FencedCluster(List.of("holder", "a", "b", "newcomer"));
        var holder = cluster.member("holder");
        var newcomer = cluster.member("newcomer");
        var key = cluster.keyWhoseDepartureNewcomerIs(holder.id(), newcomer.id());

        cluster.writeAtPreRewriteEpoch(holder, key);
        cluster.advanceEveryHighWaterPastTheKey();

        assertThat(cluster.freshWriteAtPreRewriteEpochAccepted(newcomer, key)).as("control: the newcomer's fence is live")
                                                                             .isFalse();

        holder.rebalancer().pushOnDeparture(Set.of(), DeparturePushObserver.noop()).await();

        assertThat(cluster.holds(newcomer, key)).as("the departure push stored its copy on the newcomer").isTrue();
    }

    private static byte[] key(String prefix) {
        return ("artifacts/" + prefix + "/meta").getBytes(StandardCharsets.UTF_8);
    }

    private record Member(NodeId id,
                          DHTNode node,
                          DHTRebalancer rebalancer,
                          DHTAntiEntropy antiEntropy,
                          HighWaterGate gate) {}

    /// A node-wide high-water fence, as `HighWaterOwnerEpochGate` binds it in production: one arc for
    /// every key, compared incarnation, term, counter.
    private static final class HighWaterGate implements OwnerEpochGate {
        private final AtomicReference<long[]> highWater = new AtomicReference<>(new long[]{0L, 0L, 0L});

        @Override
        public boolean isStale(byte[] key, long epochIncarnation, long epochTerm, long epochCounter) {
            return compare(highWater.get(), new long[]{epochIncarnation, epochTerm, epochCounter}) > 0;
        }

        @Override
        public void advance(byte[] key, long epochIncarnation, long epochTerm, long epochCounter) {
            highWater.accumulateAndGet(new long[]{epochIncarnation, epochTerm, epochCounter}, HighWaterGate::later);
        }

        private static long[] later(long[] current, long[] presented) {
            return compare(presented, current) > 0
                   ? presented
                   : current;
        }

        private static int compare(long[] left, long[] right) {
            return Arrays.compare(left, right);
        }
    }

    private static final class FencedCluster {
        private final Map<NodeId, Member> members = new LinkedHashMap<>();

        FencedCluster(List<String> names) {
            var ids = names.stream().map(NodeId::new).toList();

            ids.forEach(id -> members.put(id, member(id, ids)));
        }

        private Member member(NodeId id, List<NodeId> ids) {
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();

            ids.forEach(ring::addNode);

            var gate = new HighWaterGate();
            var node = dhtNode(id, memoryStorageEngine(gate), ring, CONFIG);
            DHTNetwork network = this::deliver;

            return new Member(id,
                              node,
                              dhtRebalancer(node, network, CONFIG),
                              dhtAntiEntropy(node, network, CONFIG),
                              gate);
        }

        Member member(String name) {
            return members.get(new NodeId(name));
        }

        void writeAtPreRewriteEpoch(Member holder, byte[] key) {
            holder.node().putLocalVersioned(key, VALUE, 100L, 0L, PRE_REWRITE_TERM, PRE_REWRITE_COUNTER).await();
        }

        /// The `DhtPartitionOwnershipKey("core")` rewrite: every node's high-water moves past the key.
        void advanceEveryHighWaterPastTheKey() {
            members.values().forEach(member -> member.gate().advance(new byte[0], 0L, POST_REWRITE_TERM, POST_REWRITE_COUNTER));
        }

        /// Probes the fence with a FRESH write on a scratch key, so the probe stores nothing under `key`.
        boolean freshWriteAtPreRewriteEpochAccepted(Member member, byte[] key) {
            var probe = (new String(key, StandardCharsets.UTF_8) + "/probe").getBytes(StandardCharsets.UTF_8);

            return member.node()
                         .putLocalVersioned(probe, VALUE, 100L, 0L, PRE_REWRITE_TERM, PRE_REWRITE_COUNTER)
                         .await()
                         .isSuccess();
        }

        boolean holds(Member member, byte[] key) {
            return member.node().getLocal(key).await().or(Option.<byte[]>none()).isPresent();
        }

        byte[] keyWhoseDepartureNewcomerIs(NodeId departing, NodeId newcomer) {
            var ring = members.get(departing).node().ring();
            var rf = CONFIG.effectiveReplicationFactor(members.size());

            for (int i = 0; i < 20_000; i++) {
                var candidate = key("push-" + i);
                var current = ring.nodesFor(candidate, rf);

                if (current.contains(departing) && !current.contains(newcomer)) {
                    return candidate;
                }
            }

            throw new AssertionError("no key whose departure newcomer is " + newcomer.id());
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            Option.option(members.get(target)).onPresent(member -> route(member, message));
        }

        private void route(Member member, ProtocolMessage message) {
            switch (message) {
                case DHTMessage.DigestRequest request ->
                    member.node().handleDigestRequest(request, response -> deliver(request.sender(), response));
                case DHTMessage.DigestResponse response -> member.antiEntropy().onDigestResponse(response);
                case DHTMessage.MigrationDataRequest request ->
                    member.node().handleMigrationDataRequest(request, response -> deliver(request.sender(), response));
                case DHTMessage.MigrationDataResponse response -> member.antiEntropy().onMigrationDataResponse(response);
                case DHTMessage.MigrationDataAck ack -> member.rebalancer().onMigrationDataAck(ack);
                default -> {}
            }
        }
    }
}
