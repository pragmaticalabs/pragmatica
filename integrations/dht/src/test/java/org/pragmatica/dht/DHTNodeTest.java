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
import org.pragmatica.dht.DHTMessage.GetRequest;
import org.pragmatica.dht.DHTMessage.GetResponse;
import org.pragmatica.dht.DHTMessage.PutRequest;
import org.pragmatica.dht.DHTMessage.PutResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.dht.ConsistentHashRing.consistentHashRing;
import static org.pragmatica.dht.DHTNode.dhtNode;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

class DHTNodeTest {
    private static final NodeId NODE_ID = new NodeId("node-1");

    private DHTNode node;

    @BeforeEach
    void setUp() {
        var storage = memoryStorageEngine();
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.addNode(NODE_ID);
        node = dhtNode(NODE_ID, storage, ring, DHTConfig.SINGLE_NODE);
    }

    private static byte[] key(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    class LocalOperations {
        @Test
        void getLocal_nonExistentKey_returnsEmpty() {
            node.getLocal(key("missing"))
                .await()
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(opt -> assertThat(opt.isPresent()).isFalse());
        }

        @Test
        void putLocal_newKey_storesValue() {
            node.putLocal(key("k1"), value("v1")).await();

            node.getLocal(key("k1"))
                .await()
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v1")))
                                     .onEmpty(() -> fail("Expected value to be present")));
        }

        @Test
        void putLocal_existingKey_overwritesValue() {
            node.putLocal(key("k1"), value("v1")).await();
            node.putLocal(key("k1"), value("v2")).await();

            node.getLocal(key("k1"))
                .await()
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v2"))));
        }

        @Test
        void removeLocal_existingKey_removesAndReturnsTrue() {
            node.putLocal(key("k1"), value("v1")).await();

            node.removeLocal(key("k1"))
                .await()
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(removed -> assertThat(removed).isTrue());

            node.getLocal(key("k1"))
                .await()
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(opt -> assertThat(opt.isPresent()).isFalse());
        }

        @Test
        void removeLocal_nonExistentKey_returnsFalse() {
            node.removeLocal(key("missing"))
                .await()
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(removed -> assertThat(removed).isFalse());
        }

        @Test
        void existsLocal_existingKey_returnsTrue() {
            node.putLocal(key("k1"), value("v1")).await();

            node.existsLocal(key("k1"))
                .await()
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(exists -> assertThat(exists).isTrue());
        }

        @Test
        void existsLocal_nonExistentKey_returnsFalse() {
            node.existsLocal(key("missing"))
                .await()
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(exists -> assertThat(exists).isFalse());
        }

        @Test
        void localSize_afterPuts_reflectsCount() {
            node.putLocal(key("k1"), value("v1")).await();
            node.putLocal(key("k2"), value("v2")).await();
            node.putLocal(key("k3"), value("v3")).await();

            assertThat(node.localSize()).isEqualTo(3);
        }
    }

    @Nested
    class MessageHandling {
        @Test
        void handleGetRequest_existingKey_sendsResponseWithValue() {
            node.putLocal(key("k1"), value("v1")).await();

            var captured = new AtomicReference<GetResponse>();
            var request = new GetRequest("req-1", NODE_ID, key("k1"));

            node.handleGetRequest(request, captured::set);

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().requestId()).isEqualTo("req-1");
            assertThat(captured.get().sender()).isEqualTo(NODE_ID);
            captured.get()
                    .value()
                    .onPresent(v -> assertThat(v).isEqualTo(value("v1")))
                    .onEmpty(() -> fail("Expected value to be present"));
        }

        @Test
        void handlePutRequest_validData_storesAndSendsSuccessResponse() {
            var captured = new AtomicReference<PutResponse>();
            var request = new PutRequest("req-2", NODE_ID, key("k1"), value("v1"));

            node.handlePutRequest(request, captured::set);

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().requestId()).isEqualTo("req-2");
            assertThat(captured.get().success()).isTrue();

            node.getLocal(key("k1"))
                .await()
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(opt -> opt.onPresent(v -> assertThat(v).isEqualTo(value("v1"))));
        }
    }

    @Nested
    class RingResponsibility {
        @Test
        void isResponsibleFor_singleNodeRing_alwaysTrue() {
            assertThat(node.isResponsibleFor(key("any-key"))).isTrue();
            assertThat(node.isResponsibleFor(key("another-key"))).isTrue();
            assertThat(node.isResponsibleFor(key("third-key"))).isTrue();
        }

        @Test
        void isPrimaryFor_singleNodeRing_alwaysTrue() {
            assertThat(node.isPrimaryFor(key("any-key"))).isTrue();
            assertThat(node.isPrimaryFor(key("another-key"))).isTrue();
            assertThat(node.isPrimaryFor(key("third-key"))).isTrue();
        }
    }
}
