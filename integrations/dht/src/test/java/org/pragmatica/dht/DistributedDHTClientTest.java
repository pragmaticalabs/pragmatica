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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.DistributedDHTClient.distributedDHTClient;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class DistributedDHTClientTest {
    private static final NodeId LOCAL_NODE = new NodeId("local");
    private static final NodeId REMOTE_NODE_1 = new NodeId("remote-1");
    private static final NodeId REMOTE_NODE_2 = new NodeId("remote-2");

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    class SingleNodeLocalOperations {
        private DistributedDHTClient client;

        @BeforeEach
        void setUp() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.SINGLE_NODE);
            client = distributedDHTClient(node, new NoOpNetwork(), DHTConfig.SINGLE_NODE);
        }

        @Test
        void put_succeeds_singleNode() {
            client.put(key("k1"), value("v1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()));
        }

        @Test
        void get_returnsValue_afterPut() {
            client.put(key("k1"), value("v1"))
                  .await();

            client.get(key("k1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> {
                                 assertThat(opt.isPresent()).isTrue();
                                 opt.onPresent(v -> assertThat(v).isEqualTo(value("v1")));
                             });
        }

        @Test
        void get_returnsEmpty_forMissingKey() {
            client.get(key("missing"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }

        @Test
        void remove_returnsTrue_afterPut() {
            client.put(key("k1"), value("v1"))
                  .await();

            client.remove(key("k1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void remove_returnsFalse_forMissingKey() {
            client.remove(key("missing"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(found -> assertThat(found).isFalse());
        }

        @Test
        void exists_returnsTrue_afterPut() {
            client.put(key("k1"), value("v1"))
                  .await();

            client.exists(key("k1"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(exists -> assertThat(exists).isTrue());
        }

        @Test
        void exists_returnsFalse_forMissingKey() {
            client.exists(key("missing"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(exists -> assertThat(exists).isFalse());
        }

        @Test
        void partitionFor_returnsConsistentPartition() {
            var p1 = client.partitionFor(key("k1"));
            var p2 = client.partitionFor(key("k1"));

            assertThat(p1).isEqualTo(p2);
        }

        @Test
        void get_withStringKey_works() {
            client.put("str-key", value("v1"))
                  .await();

            client.get("str-key")
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
        }
    }

    @Nested
    class RemoteOperationsWithResponses {
        private DistributedDHTClient client;
        private CapturingNetwork network;

        @BeforeEach
        void setUp() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            ring.addNode(REMOTE_NODE_1);
            ring.addNode(REMOTE_NODE_2);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.DEFAULT);
            network = new CapturingNetwork();
            // RF=3, W=2, R=2 — 3 nodes total, so all are targets
            client = distributedDHTClient(node, network, DHTConfig.DEFAULT);
        }

        @Test
        void put_sendsRemoteRequests_toNonLocalNodes() {
            // Start put — will wait for quorum
            var promise = client.put(key("k1"), value("v1"));

            // Network should have captured remote put requests
            var putRequests = network.captured.stream()
                                              .filter(m -> m.message() instanceof DHTMessage.PutRequest)
                                              .toList();
            // At least some requests should be remote (non-local nodes)
            assertThat(putRequests).isNotEmpty();

            // Simulate success responses for remote nodes
            putRequests.forEach(m -> {
                var req = (DHTMessage.PutRequest) m.message();
                client.onPutResponse(new DHTMessage.PutResponse(req.requestId(), m.target(), true, false, false));
            });

            promise.await()
                   .onFailure(c -> fail("Expected success: " + c.message()));
        }

        @Test
        void get_completesWithResponse_afterRemoteReply() {
            // First store locally
            client.node()
                  .putLocal(key("k1"), value("v1"))
                  .await();

            // Start get
            var promise = client.get(key("k1"));

            // Simulate remote get responses
            var getRequests = network.captured.stream()
                                              .filter(m -> m.message() instanceof DHTMessage.GetRequest)
                                              .toList();
            getRequests.forEach(m -> {
                var req = (DHTMessage.GetRequest) m.message();
                client.onGetResponse(new DHTMessage.GetResponse(req.requestId(), m.target(), Option.some(value("v1"))));
            });

            promise.await()
                   .onFailure(c -> fail("Expected success: " + c.message()))
                   .onSuccess(opt -> {
                                  assertThat(opt.isPresent()).isTrue();
                                  opt.onPresent(v -> assertThat(v).isEqualTo(value("v1")));
                              });
        }

        @Test
        void remove_completesWithResponse_afterRemoteReply() {
            // Store locally first
            client.node()
                  .putLocal(key("k1"), value("v1"))
                  .await();

            var promise = client.remove(key("k1"));

            var removeRequests = network.captured.stream()
                                                 .filter(m -> m.message() instanceof DHTMessage.RemoveRequest)
                                                 .toList();
            removeRequests.forEach(m -> {
                var req = (DHTMessage.RemoveRequest) m.message();
                client.onRemoveResponse(new DHTMessage.RemoveResponse(req.requestId(), m.target(), true));
            });

            promise.await()
                   .onFailure(c -> fail("Expected success: " + c.message()))
                   .onSuccess(found -> assertThat(found).isTrue());
        }

        @Test
        void exists_completesWithResponse_afterRemoteReply() {
            client.node()
                  .putLocal(key("k1"), value("v1"))
                  .await();

            var promise = client.exists(key("k1"));

            var existsRequests = network.captured.stream()
                                                 .filter(m -> m.message() instanceof DHTMessage.ExistsRequest)
                                                 .toList();
            existsRequests.forEach(m -> {
                var req = (DHTMessage.ExistsRequest) m.message();
                client.onExistsResponse(new DHTMessage.ExistsResponse(req.requestId(), m.target(), true));
            });

            promise.await()
                   .onFailure(c -> fail("Expected success: " + c.message()))
                   .onSuccess(exists -> assertThat(exists).isTrue());
        }
    }

    @Nested
    class ScopedClient {
        @Test
        void scoped_returnsClientWithDifferentConfig() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.DEFAULT);
            var original = distributedDHTClient(node, new NoOpNetwork(), DHTConfig.DEFAULT);

            var scoped = original.scoped(DHTConfig.SINGLE_NODE);

            assertThat(scoped).isNotSameAs(original);
        }

        @Test
        void scoped_sharesStorageWithOriginal() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.SINGLE_NODE);
            var original = distributedDHTClient(node, new NoOpNetwork(), DHTConfig.SINGLE_NODE);

            // Put via original
            original.put(key("shared"), value("data"))
                    .await();

            // Scope to same single-node config
            var scoped = original.scoped(DHTConfig.SINGLE_NODE);

            // Get via scoped — should see the value (shared storage)
            scoped.get(key("shared"))
                  .await()
                  .onFailure(c -> fail("Expected success: " + c.message()))
                  .onSuccess(opt -> {
                                 assertThat(opt.isPresent()).isTrue();
                                 opt.onPresent(v -> assertThat(v).isEqualTo(value("data")));
                             });
        }

        @Test
        void scoped_usesOwnConfigForReplication() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            ring.addNode(REMOTE_NODE_1);
            ring.addNode(REMOTE_NODE_2);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.DEFAULT);
            var network = new CapturingNetwork();

            // Original: RF=3 (targets 3 nodes)
            var original = distributedDHTClient(node, network, DHTConfig.DEFAULT);
            original.put(key("k1"), value("v1"));
            int originalMessageCount = network.captured.size();

            network.captured.clear();

            // Scoped: RF=1 (targets 1 node — might be local only)
            var scoped = original.scoped(DHTConfig.SINGLE_NODE);
            scoped.put(key("k1"), value("v2"));
            int scopedMessageCount = network.captured.size();

            // Scoped with RF=1 should send fewer remote messages than RF=3
            assertThat(scopedMessageCount).isLessThanOrEqualTo(originalMessageCount);
        }

        @Test
        void scoped_cacheDefault_worksForLocalOps() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.SINGLE_NODE);
            var original = distributedDHTClient(node, new NoOpNetwork(), DHTConfig.DEFAULT);

            // Scope to cache config (RF=1, W=1, R=1)
            var cacheClient = original.scoped(DHTConfig.CACHE_DEFAULT);

            // Should work as single-node local operations
            cacheClient.put(key("cache-key"), value("cached"))
                       .await()
                       .onFailure(c -> fail("Expected success: " + c.message()));

            cacheClient.get(key("cache-key"))
                       .await()
                       .onFailure(c -> fail("Expected success: " + c.message()))
                       .onSuccess(opt -> {
                                      assertThat(opt.isPresent()).isTrue();
                                      opt.onPresent(v -> assertThat(v).isEqualTo(value("cached")));
                                  });
        }
    }

    @Nested
    class TimeoutHandling {
        @Test
        void get_timesOut_whenNoResponse() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            ring.addNode(LOCAL_NODE);
            ring.addNode(REMOTE_NODE_1);
            var node = dhtNode(LOCAL_NODE, storage, ring,
                               new DHTConfig(2, 1, 2, timeSpan(100).millis()));
            var network = new NoOpNetwork();
            // RF=2, R=2 — needs responses from both nodes, but remote won't respond
            var client = distributedDHTClient(node, network,
                                              new DHTConfig(2, 1, 2, timeSpan(100).millis()));

            // Only local responds. Remote never responds. Timeout should fire.
            client.get(key("timeout-key"))
                  .await()
                  .onSuccess(_ -> fail("Expected timeout failure"));
        }
    }

    @Nested
    class EmptyRing {
        @Test
        void get_fails_whenNoNodesInRing() {
            var storage = memoryStorageEngine();
            var ring = ConsistentHashRing.<NodeId>consistentHashRing();
            // No nodes added to ring
            var node = dhtNode(LOCAL_NODE, storage, ring, DHTConfig.SINGLE_NODE);
            var client = distributedDHTClient(node, new NoOpNetwork(), DHTConfig.SINGLE_NODE);

            client.get(key("k1"))
                  .await()
                  .onSuccess(_ -> fail("Expected failure"))
                  .onFailure(c -> assertThat(c.message()).contains("No available nodes"));
        }
    }

    /// A target of an in-flight R-set read departs the ring: the read re-issues to a replacement from the
    /// current ring instead of waiting out the operation timeout. The client is a pure reader (its own
    /// id is not on the ring), so every replica is remote and driven by hand through the capturing network.
    @Nested
    class DepartureReissue {
        private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(10).seconds());

        private ConsistentHashRing<NodeId> ring;
        private CapturingNetwork network;
        private DistributedDHTClient client;

        @BeforeEach
        void setUp() {
            ring = ConsistentHashRing.<NodeId>consistentHashRing();
            // enough spare replicas that only the re-issue bound, never candidate exhaustion, stops a churning read
            for (var i = 1; i <= 12; i++) {
                ring.addNode(new NodeId("replica-" + i));
            }
            var node = dhtNode(LOCAL_NODE, memoryStorageEngine(), ring, CONFIG);
            network = new CapturingNetwork();
            client = distributedDHTClient(node, network, CONFIG);
        }

        private List<CapturedMessage> getRequests() {
            return network.captured.stream()
                                   .filter(m -> m.message() instanceof DHTMessage.GetRequest)
                                   .toList();
        }

        private void reply(CapturedMessage request, Option<byte[]> value) {
            var req = (DHTMessage.GetRequest) request.message();

            client.onGetResponse(new DHTMessage.GetResponse(req.requestId(), request.target(), value));
        }

        @Test
        void get_resolvesWithoutTimeout_whenOneTargetDepartsMidRead() {
            var read = client.get(key("k1"));
            var initial = getRequests();
            assertThat(initial).hasSize(3);

            // one replica answers with the value, one never answers and leaves the ring, one is silent
            reply(initial.getFirst(), Option.some(value("v1")));
            ring.removeNode(initial.get(1).target());

            var reissued = getRequests().subList(3, getRequests().size());
            assertThat(reissued).hasSize(1);
            assertThat(reissued.getFirst().target()).isNotIn(initial.stream().map(CapturedMessage::target).toList());
            reply(reissued.getFirst(), Option.some(value("v1")));

            // the timeout is 10s: resolving inside 2s proves the read did not wait for it
            read.await(timeSpan(2).seconds())
                .onFailure(c -> fail("Expected resolution without waiting for the timeout: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
        }

        @Test
        void get_sendsNoExtraRequests_whenNoTargetDeparts() {
            var read = client.get(key("k1"));
            var initial = getRequests();

            reply(initial.get(0), Option.some(value("v1")));
            // a node outside the R-set leaving mid-read is not a departure of a read target
            var bystander = new HashSet<>(ring.nodes());
            initial.forEach(m -> bystander.remove(m.target()));
            bystander.stream().limit(2).forEach(ring::removeNode);
            reply(initial.get(1), Option.some(value("v1")));

            read.await(timeSpan(2).seconds())
                .onFailure(c -> fail("Expected success: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
            assertThat(getRequests()).hasSize(3);
        }

        @Test
        void get_doesNotReissue_afterReadCompleted() {
            var read = client.get(key("k1"));
            var initial = getRequests();

            // quorum (2 of 3) completes the read; the third target is still owing a reply
            reply(initial.get(0), Option.some(value("v1")));
            reply(initial.get(1), Option.some(value("v1")));
            read.await(timeSpan(2).seconds());
            ring.removeNode(initial.get(2).target());

            assertThat(getRequests()).hasSize(3);
        }

        @Test
        void get_boundsReissues_andFailsFastOncePastTheBound() {
            var read = client.get(key("k1"));
            var departed = new ArrayList<NodeId>();

            // depart 4 of the targets currently owing a reply; only 3 replacements may ever be issued
            for (var i = 0; i < 4; i++) {
                departNextOwingTarget(departed);
            }

            assertThat(getRequests()).hasSize(6);
            assertThat(read.isResolved()).isFalse();

            // a fifth departure fails a second slot: quorum 2 of 3 becomes impossible, no timeout wait
            departNextOwingTarget(departed);

            assertThat(getRequests()).hasSize(6);
            var outcome = read.await(timeSpan(2).seconds());
            outcome.onSuccess(_ -> fail("Expected quorum failure"))
                   .onFailure(c -> assertThat(c).isInstanceOf(DHTError.QuorumNotReached.class));
        }

        private void departNextOwingTarget(List<NodeId> departed) {
            var owing = getRequests().stream()
                                     .map(CapturedMessage::target)
                                     .filter(t -> !departed.contains(t))
                                     .findFirst()
                                     .orElseThrow();

            departed.add(owing);
            ring.removeNode(owing);
        }
    }

    /// Opt-in absent grace (`ReadOptions.absentGrace`): after R empty answers the read waits up to the grace for
    /// the remaining original replica; without the option the read is the plain quorum read (absent at R empties).
    @Nested
    class AbsentGraceWindow {
        private static final DHTConfig CONFIG = new DHTConfig(3, 2, 2, timeSpan(10).seconds());
        private static final ReadOptions GRACE = ReadOptions.absentGrace(timeSpan(400).millis());

        private ConsistentHashRing<NodeId> ring;
        private CapturingNetwork network;
        private DistributedDHTClient client;

        private void useRing(int replicas) {
            ring = ConsistentHashRing.<NodeId>consistentHashRing();
            for (var i = 1; i <= replicas; i++) {
                ring.addNode(new NodeId("replica-" + i));
            }
            var node = dhtNode(LOCAL_NODE, memoryStorageEngine(), ring, CONFIG);
            network = new CapturingNetwork();
            client = distributedDHTClient(node, network, CONFIG);
        }

        private List<CapturedMessage> requests() {
            return network.captured.stream()
                                   .filter(m -> m.message() instanceof DHTMessage.GetRequest)
                                   .toList();
        }

        private void reply(CapturedMessage request, Option<byte[]> value) {
            var req = (DHTMessage.GetRequest) request.message();

            client.onGetResponse(new DHTMessage.GetResponse(req.requestId(), request.target(), value));
        }

        @Test
        void get_isFound_whenValueOnThirdOriginalArrivesWithinGrace() {
            useRing(3);
            var read = client.get(key("k1"), GRACE);
            var initial = requests();

            reply(initial.get(0), Option.none());
            reply(initial.get(1), Option.none());
            assertThat(read.isResolved()).isFalse();
            reply(initial.get(2), Option.some(value("v1")));

            read.await(timeSpan(2).seconds())
                .onFailure(c -> fail("Expected the in-grace value to be found: " + c.message()))
                .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v1")))
                                     .onEmpty(() -> fail("Value discarded: read reported absent")));
        }

        @Test
        void get_isAbsentAtOnce_whenEveryOriginalAnswersEmpty() {
            useRing(3);
            var read = client.get(key("k1"), ReadOptions.absentGrace(timeSpan(5).seconds()));

            requests().forEach(m -> reply(m, Option.none()));

            // the grace is 5s: an answer inside 1s proves no grace wait once every original has answered
            read.await(timeSpan(1).seconds())
                .onFailure(c -> fail("Expected absent: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }

        @Test
        void get_isAbsentAfterGrace_whenThirdOriginalStaysSilent() {
            useRing(3);
            var started = System.nanoTime();
            var read = client.get(key("k1"), GRACE);
            var initial = requests();

            reply(initial.get(0), Option.none());
            reply(initial.get(1), Option.none());

            // bounded by the grace (400ms), not the 10s operation deadline
            read.await(timeSpan(3).seconds())
                .onFailure(c -> fail("Expected absent after the grace: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
            var elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            assertThat(elapsedMillis).isBetween(300L, 2500L);
        }

        @Test
        void get_isFound_whenHolderAnswersAfterAnotherOriginalDepartedAndReplacementWasEmpty() throws InterruptedException {
            useRing(4);
            var read = client.get(key("k1"), GRACE);
            var initial = requests();

            ring.removeNode(initial.get(0).target());
            var replacement = requests().get(3);
            reply(initial.get(1), Option.none());
            // the replacement holds no copy yet: its empty answer is a failed slot, never a vote
            reply(replacement, Option.none());
            // were the replacement's empty answer counted as a vote, B + replacement would be R=2 empties and the
            // grace (400ms) would end the read as absent while the holder C is still owing its reply
            Thread.sleep(800);
            assertThat(read.isResolved()).isFalse();
            reply(initial.get(2), Option.some(value("v1")));

            read.await(timeSpan(2).seconds())
                .onFailure(c -> fail("Expected the holder's value: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
        }

        /// The opt-in is a per-call option on the SAME instance, never a derived client: pendingOps is per instance and
        /// the node routes replies only to the base client, so a derived client's reads would time out on every reply.
        /// Replies here go to the very instance that issued the read; the pin is that the option does not move the read
        /// onto another instance (routing it through `scoped(...)` makes this read never resolve).
        @Test
        void get_withOption_resolvesFromRepliesDeliveredToTheIssuingInstance() {
            useRing(3);
            var read = client.get(key("k1"), GRACE);
            var initial = requests();

            initial.forEach(m -> reply(m, Option.some(value("v1"))));

            read.await(timeSpan(2).seconds())
                .onFailure(c -> fail("Expected the read to resolve through the base client's reply path: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isPresent()).isTrue());
        }

        @Test
        void get_isAbsent_whenValueOnThirdArrivesAfterGrace() throws InterruptedException {
            useRing(3);
            var read = client.get(key("k1"), ReadOptions.absentGrace(timeSpan(150).millis()));
            var initial = requests();

            reply(initial.get(0), Option.none());
            reply(initial.get(1), Option.none());
            Thread.sleep(700);
            reply(initial.get(2), Option.some(value("v1")));

            // documents the bound: a value later than the grace is not waited for
            read.await(timeSpan(2).seconds())
                .onFailure(c -> fail("Expected absent: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }

        @Test
        void get_isAbsentAtTwoEmpties_withoutTheOption() {
            useRing(3);
            var read = client.get(key("k1"));
            var initial = requests();

            reply(initial.get(0), Option.none());
            reply(initial.get(1), Option.none());
            // the value is on the third replica and arrives at once; a caller that did not opt in keeps today's
            // absent, decided at the second empty answer
            reply(initial.get(2), Option.some(value("v1")));

            read.await(timeSpan(1).seconds())
                .onFailure(c -> fail("Expected absent at once: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }

        @Test
        void get_doesNotResurrectRemovedValue_withoutTheOption() {
            useRing(3);
            // remove acked by two replicas; the third keeps the value (the DHT has no tombstones)
            var removal = client.remove(key("k1"));
            var removes = network.captured.stream()
                                          .filter(m -> m.message() instanceof DHTMessage.RemoveRequest)
                                          .toList();
            removes.subList(0, 2).forEach(m -> client.onRemoveResponse(
                new DHTMessage.RemoveResponse(((DHTMessage.RemoveRequest) m.message()).requestId(), m.target(), true)));
            removal.await(timeSpan(2).seconds())
                   .onFailure(c -> fail("Expected the remove to reach quorum: " + c.message()));
            var stale = removes.get(2).target();

            var read = client.get(key("k1"));
            var reads = requests();
            reads.stream().filter(m -> !m.target().equals(stale)).forEach(m -> reply(m, Option.none()));
            reads.stream().filter(m -> m.target().equals(stale)).forEach(m -> reply(m, Option.some(value("stale"))));

            read.await(timeSpan(1).seconds())
                .onFailure(c -> fail("Expected absent: " + c.message()))
                .onSuccess(opt -> assertThat(opt.isEmpty()).isTrue());
        }
    }

    // --- Test infrastructure ---

    /// Captured message: target node and the sent message.
    private record CapturedMessage(NodeId target, ProtocolMessage message) {}

    /// Network stub that captures all sent messages for inspection.
    private static final class CapturingNetwork implements DHTNetwork {
        final CopyOnWriteArrayList<CapturedMessage> captured = new CopyOnWriteArrayList<>();

        @Override
        public void send(NodeId nodeId, ProtocolMessage message) {
            captured.add(new CapturedMessage(nodeId, message));
        }
    }

    /// Minimal no-op network for single-node tests (no remote messaging needed).
    private static final class NoOpNetwork implements DHTNetwork {
        @Override
        public void send(NodeId nodeId, ProtocolMessage message) {}
    }
}
