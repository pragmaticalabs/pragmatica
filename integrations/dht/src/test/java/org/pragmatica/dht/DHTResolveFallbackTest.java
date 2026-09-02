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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// In-JVM coverage for resolve-time alternate-target fallback + read-repair (RFC/#428, C2 — staged
/// arm B). Runs a small multi-`DistributedDHTClient` cluster over a shared synchronous in-process
/// routing fabric (get/put requests fan to the target node and route the response back to the
/// sender's client), so an end-to-end `get` misses its R-set, probes the bounded ring beyond it,
/// finds a stranded copy, and read-repairs it back onto the R-set — with the [ResolveFallbackObserver]
/// surfacing every fallback outcome.
class DHTResolveFallbackTest {
    /// Local mirror of `DistributedDHTClient.DEFAULT_FALLBACK_PROBE_LIMIT` (private there): the
    /// bounded ring-probe cap the observer's `probed` argument must respect.
    private static final int FALLBACK_PROBE_LIMIT = 8;
    private static final TimeSpan OP_TIMEOUT = timeSpan(2).seconds();
    private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, OP_TIMEOUT);
    private static final DHTConfig FULL = new DHTConfig(DHTConfig.FULL_REPLICATION, 1, 1, OP_TIMEOUT);

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void get_rSetMiss_strandedCopyOnNonReplica_fallbackHitAndReadRepairs() {
        var observer = new RecordingResolveFallbackObserver();
        var fabric = fabric(CONFIG, observer, 5);
        var probeKey = key("stranded-key");
        var rSet = fabric.rSetFor(probeKey);
        var holder = fabric.outsiderFor(probeKey);

        // The only copy lives on a node OUTSIDE the R-set — the exact stranded-copy loss window.
        fabric.seedOnly(holder, probeKey, value("payload"));

        fabric.client(rSet.getFirst())
              .get(probeKey)
              .await()
              .onFailure(cause -> Assertions.fail(cause.message()))
              .onSuccess(opt -> {
                             assertThat(opt.isPresent()).isTrue();
                             opt.onPresent(v -> assertThat(v).isEqualTo(value("payload")));
                         });

        // Read-repair re-homed the stranded copy onto every current R-set member.
        rSet.forEach(member -> assertThat(fabric.localGet(member, probeKey).isPresent())
                                   .as("R-set member " + member.id() + " holds the read-repaired copy")
                                   .isTrue());
        assertThat(observer.resolvedCount()).isEqualTo(1);
        assertThat(observer.unresolvedCount()).isZero();
    }

    @Test
    void get_allNodesMiss_returnsNoneAndObservesUnresolved() {
        var observer = new RecordingResolveFallbackObserver();
        var fabric = fabric(CONFIG, observer, 5);
        var absentKey = key("absent-key");
        var rSet = fabric.rSetFor(absentKey);

        fabric.client(rSet.getFirst())
              .get(absentKey)
              .await()
              .onFailure(cause -> Assertions.fail(cause.message()))
              .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());

        assertThat(observer.unresolvedCount()).isEqualTo(1);
        assertThat(observer.resolvedCount()).isZero();
        assertThat(observer.lastProbed()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void get_fallbackBounded_probesAtMostLimit() {
        var observer = new RecordingResolveFallbackObserver();
        // 12 nodes, RF=3 -> 9 non-R-set candidates, more than the probe bound of 8.
        var fabric = fabric(CONFIG, observer, 12);
        var boundedKey = key("bounded-key");
        var rSet = fabric.rSetFor(boundedKey);

        fabric.client(rSet.getFirst())
              .get(boundedKey)
              .await()
              .onFailure(cause -> Assertions.fail(cause.message()))
              .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());

        assertThat(observer.unresolvedCount()).isEqualTo(1);
        assertThat(observer.lastProbed()).isLessThanOrEqualTo(FALLBACK_PROBE_LIMIT);
        assertThat(observer.lastProbed()).isEqualTo(FALLBACK_PROBE_LIMIT);
    }

    @Test
    void get_rSetHit_noFallback() {
        var observer = new RecordingResolveFallbackObserver();
        var fabric = fabric(CONFIG, observer, 5);
        var presentKey = key("present-key");
        var rSet = fabric.rSetFor(presentKey);
        var coordinator = fabric.client(rSet.getFirst());

        // Normal quorum write puts the value on the full R-set.
        coordinator.put(presentKey, value("payload")).await().onFailure(cause -> Assertions.fail(cause.message()));

        coordinator.get(presentKey)
                   .await()
                   .onFailure(cause -> Assertions.fail(cause.message()))
                   .onSuccess(opt -> {
                                  assertThat(opt.isPresent()).isTrue();
                                  opt.onPresent(v -> assertThat(v).isEqualTo(value("payload")));
                              });

        assertThat(observer.resolvedCount()).isZero();
        assertThat(observer.unresolvedCount()).isZero();
    }

    @Test
    void get_fullReplication_noFallbackTargets_noOp() {
        var observer = new RecordingResolveFallbackObserver();
        var fabric = fabric(FULL, observer, 5);
        var fullKey = key("full-key");

        fabric.anyClient()
              .get(fullKey)
              .await()
              .onFailure(cause -> Assertions.fail(cause.message()))
              .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());

        // FULL replication: R-set spans every node, so there is no fallback candidate — a natural no-op.
        assertThat(observer.resolvedCount()).isZero();
        assertThat(observer.unresolvedCount()).isZero();
    }

    private DhtFabric fabric(DHTConfig config, ResolveFallbackObserver observer, int nodeCount) {
        var fabric = new DhtFabric(config, observer);
        for (int i = 0; i < nodeCount; i++) {
            fabric.add(new NodeId("node-" + i));
        }
        return fabric;
    }

    // --- In-process multi-node routing fabric ---

    private record Member(NodeId id,
                          DHTNode node,
                          DistributedDHTClient client,
                          ConsistentHashRing<NodeId> ring) {}

    private static final class DhtFabric {
        private final Map<NodeId, Member> members = new LinkedHashMap<>();
        private final DHTConfig config;
        private final ResolveFallbackObserver observer;

        DhtFabric(DHTConfig config, ResolveFallbackObserver observer) {
            this.config = config;
            this.observer = observer;
        }

        void add(NodeId id) {
            members.values().forEach(existing -> existing.ring().addNode(id));
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            members.keySet().forEach(ring::addNode);
            ring.addNode(id);
            var node = dhtNode(id, memoryStorageEngine(), ring, config);
            DHTNetwork network = this::deliver;
            var client = distributedDHTClient(node, network, config).withResolveFallbackObserver(observer);
            members.put(id, new Member(id, node, client, ring));
        }

        DistributedDHTClient client(NodeId id) {
            return members.get(id).client();
        }

        DistributedDHTClient anyClient() {
            return members.values().iterator().next().client();
        }

        List<NodeId> rSetFor(byte[] key) {
            return anyRing().nodesFor(key, config.effectiveReplicationFactor(members.size()));
        }

        NodeId outsiderFor(byte[] key) {
            var rSet = new HashSet<>(rSetFor(key));

            return members.keySet()
                          .stream()
                          .filter(id -> !rSet.contains(id))
                          .findFirst()
                          .orElseThrow(() -> new AssertionError("no non-R-set node for key"));
        }

        void seedOnly(NodeId holder, byte[] key, byte[] value) {
            members.get(holder).node().putLocal(key, value).await();
        }

        Option<byte[]> localGet(NodeId id, byte[] key) {
            return members.get(id).node().getLocal(key).await().or(Option.<byte[]>none());
        }

        private ConsistentHashRing<NodeId> anyRing() {
            return members.values().iterator().next().ring();
        }

        private void deliver(NodeId target, ProtocolMessage message) {
            var targetMember = members.get(target);

            if (targetMember == null) {
                return;  // target has departed — message dropped, as on a real halted node
            }
            route(targetMember, message);
        }

        private void route(Member target, ProtocolMessage message) {
            switch (message) {
                case DHTMessage.GetRequest req -> target.node().handleGetRequest(req, resp -> replyGet(req.sender(), resp));
                case DHTMessage.PutRequest req -> target.node().handlePutRequest(req, resp -> replyPut(req.sender(), resp));
                default -> { }
            }
        }

        private void replyGet(NodeId sender, DHTMessage.GetResponse response) {
            var senderMember = members.get(sender);

            if (senderMember != null) {
                senderMember.client().onGetResponse(response);
            }
        }

        private void replyPut(NodeId sender, DHTMessage.PutResponse response) {
            var senderMember = members.get(sender);

            if (senderMember != null) {
                senderMember.client().onPutResponse(response);
            }
        }
    }

    /// Records resolve-time fallback outcomes for assertions.
    private static final class RecordingResolveFallbackObserver implements ResolveFallbackObserver {
        private final AtomicInteger resolvedCount = new AtomicInteger();
        private final AtomicInteger unresolvedCount = new AtomicInteger();
        private final AtomicInteger lastProbed = new AtomicInteger();

        @Override
        public void onResolvedViaFallback(String keyHex, int probed) {
            resolvedCount.incrementAndGet();
            lastProbed.set(probed);
        }

        @Override
        public void onUnresolvedAfterFallback(String keyHex, int probed) {
            unresolvedCount.incrementAndGet();
            lastProbed.set(probed);
        }

        int resolvedCount() {
            return resolvedCount.get();
        }

        int unresolvedCount() {
            return unresolvedCount.get();
        }

        int lastProbed() {
            return lastProbed.get();
        }
    }
}
