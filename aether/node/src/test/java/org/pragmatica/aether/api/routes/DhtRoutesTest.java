// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.DhtInjectRequest;
import org.pragmatica.aether.api.ManagementApiResponses.DhtInjectResponse;
import org.pragmatica.aether.api.ManagementApiResponses.DhtReplicationMapResponse;
import org.pragmatica.aether.api.ManagementApiResponses.HlcShape;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.dht.storage.MemoryStorageEngine;
import org.pragmatica.lang.Option;

import java.lang.reflect.Proxy;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Covers the two new DHT endpoints introduced by P-NEW-B
/// (explicit-HLC inject, dev-mode-only) and P-NEW-F (replication-map inspect).
/// See `aether/docs/internal/production-readiness-followup-2026-05-21.md`.
class DhtRoutesTest {

    private static final NodeId LOCAL_NODE = new NodeId("node-1");
    private static final NodeId PEER_2 = new NodeId("node-2");
    private static final NodeId PEER_3 = new NodeId("node-3");

    private DHTNode dhtNode;

    @BeforeEach
    void setUp() {
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();
        ring.addNode(LOCAL_NODE);
        ring.addNode(PEER_2);
        ring.addNode(PEER_3);
        var storage = MemoryStorageEngine.memoryStorageEngine();
        dhtNode = DHTNode.dhtNode(LOCAL_NODE, storage, ring, DHTConfig.DEFAULT);
    }

    private DhtRoutes routesWithDevMode(boolean devMode) {
        BooleanSupplier devModeFlag = () -> devMode;
        return DhtRoutes.dhtRoutes(this::nodeProxy, devModeFlag);
    }

    private ManageableNode nodeProxy() {
        return (ManageableNode) Proxy.newProxyInstance(
            ManageableNode.class.getClassLoader(),
            new Class[]{ManageableNode.class},
            (_, method, _1) -> switch (method.getName()) {
                case "self" -> LOCAL_NODE;
                case "dhtNode" -> Option.some(dhtNode);
                case "dhtClient" -> Option.none();
                default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
            }
        );
    }

    @Nested
    class InjectDevModeGate {

        @Test
        void inject_rejected_whenDevModeDisabled() {
            var routes = routesWithDevMode(false);

            var result = routes.handleInjectForTest(new DhtInjectRequest("k1", "v1", new HlcShape(100L, 0)))
                                .onSuccess(_ -> fail("Inject must be rejected when AETHER_INSECURE_DEV_MODE is not set"))
                                .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().contains("AETHER_INSECURE_DEV_MODE"),
                                                  "Failure must mention the gate env var: " + cause.message()));
        }
    }

    @Nested
    class InjectValidation {

        @Test
        void inject_missingKey_returnsFailure() {
            var routes = routesWithDevMode(true);

            var result = routes.handleInjectForTest(new DhtInjectRequest("", "v1", new HlcShape(100L, 0)))
                                .onSuccess(_ -> fail("Blank key must be rejected"))
                                .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("key"),
                                                  "Failure must mention missing key: " + cause.message()));
        }

        @Test
        void inject_missingValue_returnsFailure() {
            var routes = routesWithDevMode(true);

            var result = routes.handleInjectForTest(new DhtInjectRequest("k1", null, new HlcShape(100L, 0)))
                                .onSuccess(_ -> fail("Null value must be rejected"))
                                .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("value"),
                                                  "Failure must mention missing value: " + cause.message()));
        }

        @Test
        void inject_missingHlc_returnsFailure() {
            var routes = routesWithDevMode(true);

            var result = routes.handleInjectForTest(new DhtInjectRequest("k1", "v1", null))
                                .onSuccess(_ -> fail("Null hlc must be rejected"))
                                .await();

            assertTrue(result.isFailure());
            result.onFailure(cause -> assertTrue(cause.message().toLowerCase().contains("hlc"),
                                                  "Failure must mention missing hlc: " + cause.message()));
        }
    }

    @Nested
    class InjectHappyPath {

        @Test
        void inject_writesWithCommittedHlcAtLeastRequested() {
            var routes = routesWithDevMode(true);

            var response = routes.handleInjectForTest(new DhtInjectRequest("k1", "v1", new HlcShape(100L, 5)))
                                  .onFailure(cause -> fail("Inject must succeed: " + cause.message()))
                                  .await()
                                  .or((DhtInjectResponse) null);

            assertNotNull(response);
            assertEquals("k1", response.key());
            assertTrue(response.written(), "Fresh write must be accepted by storage");
            // Committed HLC must be >= requested (HLC merge rule advances clock).
            assertNotNull(response.committedHlc());
            assertTrue(response.committedHlc().physical() >= 100L,
                        "Committed physical must be >= requested physical");
        }

        @Test
        void inject_routesIncludesInjectRoute() {
            var routes = routesWithDevMode(true);

            var names = routes.routes().map(r -> r.name()).toList();

            assertThat(names).contains("DHT_INJECT");
        }
    }

    @Nested
    class ReplicationMap {

        @Test
        void replicationMap_emptyStorage_emptyEntries() {
            var routes = routesWithDevMode(false);

            var response = routes.handleReplicationMapForTest(Option.none(), Option.none())
                                  .onFailure(cause -> fail("Replication-map must succeed: " + cause.message()))
                                  .await()
                                  .or((DhtReplicationMapResponse) null);

            assertNotNull(response);
            assertEquals(0, response.totalKeys());
            assertEquals(0, response.returned());
            assertTrue(response.entries().isEmpty());
            assertEquals(3, response.replicationFactor(),
                          "Effective replication factor must reflect DHTConfig.DEFAULT (3) for 3-node ring");
        }

        @Test
        void replicationMap_afterInject_listsKeysWithNodes() {
            var routes = routesWithDevMode(true);

            routes.handleInjectForTest(new DhtInjectRequest("alpha", "v1", new HlcShape(100L, 0)))
                  .onFailure(cause -> fail("Inject must succeed: " + cause.message()))
                  .await();
            routes.handleInjectForTest(new DhtInjectRequest("beta", "v2", new HlcShape(101L, 0)))
                  .onFailure(cause -> fail("Inject must succeed: " + cause.message()))
                  .await();

            var response = routes.handleReplicationMapForTest(Option.none(), Option.none())
                                  .onFailure(cause -> fail("Replication-map must succeed: " + cause.message()))
                                  .await()
                                  .or((DhtReplicationMapResponse) null);

            assertNotNull(response);
            assertEquals(2, response.totalKeys());
            assertEquals(2, response.returned());
            assertEquals(2, response.entries().size());
            for (var entry : response.entries()) {
                assertNotNull(entry.key());
                assertThat(entry.nodes()).isNotEmpty();
                assertThat(entry.nodes()).hasSizeLessThanOrEqualTo(3);
            }
        }

        @Test
        void replicationMap_prefixFilter_restrictsEntries() {
            var routes = routesWithDevMode(true);

            routes.handleInjectForTest(new DhtInjectRequest("user:1", "v1", new HlcShape(100L, 0))).await();
            routes.handleInjectForTest(new DhtInjectRequest("user:2", "v2", new HlcShape(101L, 0))).await();
            routes.handleInjectForTest(new DhtInjectRequest("order:1", "v3", new HlcShape(102L, 0))).await();

            var response = routes.handleReplicationMapForTest(Option.none(), Option.some("user:"))
                                  .onFailure(cause -> fail("Replication-map must succeed: " + cause.message()))
                                  .await()
                                  .or((DhtReplicationMapResponse) null);

            assertNotNull(response);
            assertEquals(2, response.totalKeys(),
                          "Prefix filter must report total of matched keys, not full-storage count");
            for (var entry : response.entries()) {
                assertTrue(entry.key().startsWith("user:"),
                            "All returned keys must match the supplied prefix: " + entry.key());
            }
        }

        @Test
        void replicationMap_limit_caps_returned_entries() {
            var routes = routesWithDevMode(true);

            for (int i = 0; i < 5; i++) {
                routes.handleInjectForTest(new DhtInjectRequest("k" + i, "v" + i, new HlcShape(100L + i, 0))).await();
            }

            var response = routes.handleReplicationMapForTest(Option.some("2"), Option.none())
                                  .onFailure(cause -> fail("Replication-map must succeed: " + cause.message()))
                                  .await()
                                  .or((DhtReplicationMapResponse) null);

            assertNotNull(response);
            assertEquals(5, response.totalKeys());
            assertEquals(2, response.returned(),
                          "limit=2 must cap entries to 2 even though totalKeys is 5");
            assertEquals(2, response.entries().size());
        }

        @Test
        void replicationMap_routesIncludesReplicationMapRoute() {
            var routes = routesWithDevMode(false);

            var names = routes.routes().map(r -> r.name()).toList();

            assertThat(names).contains("DHT_REPLICATION_MAP");
        }
    }
}
