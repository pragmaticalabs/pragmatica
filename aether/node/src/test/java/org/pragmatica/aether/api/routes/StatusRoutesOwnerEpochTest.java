// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import io.netty.buffer.ByteBuf;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.ComponentHealth;
import org.pragmatica.aether.deployment.membership.ntt.QuorumLossSnapshot;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.lifecycle.NodeLifecycle;
import org.pragmatica.aether.node.lifecycle.NodeState;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.api.ManagementApiResponses.ReadinessResponse;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.lang.Option;

import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;
import static org.assertj.core.api.Assertions.assertThat;

/// #2535: a node answers `/health/ready` UP only once the DHT owner epoch is applied. A DHT put is stamped with the
/// applied epoch, ZERO until the committed ownership record is applied here, and replicas that applied the record refuse
/// a ZERO-stamped put. Measured on Ember (3 cores, put + readiness polled from t=0): one ready=UP sample with the record
/// not applied and the put refused "1 refused by owner-epoch fences".
class StatusRoutesOwnerEpochTest {
    private static final NodeId PROXY_SELF = new NodeId("proxy-self");

    @Test
    void ownerEpochHealth_recordNotApplied_isDown() {
        var health = StatusRoutes.ownerEpochHealth(true, Option::none);

        assertThat(health.name()).isEqualTo("owner-epoch");
        assertThat(health.status()).isEqualTo("DOWN");
    }

    @Test
    void ownerEpochHealth_zeroEpoch_isDown() {
        assertThat(StatusRoutes.ownerEpochHealth(true, () -> Option.some(Epoch.ZERO)).status()).isEqualTo("DOWN");
    }

    @Test
    void ownerEpochHealth_nonZeroEpoch_isUp() {
        assertThat(StatusRoutes.ownerEpochHealth(true, () -> Option.some(Epoch.epoch(1, 1, 1))).status()).isEqualTo("UP");
    }

    @Test
    void ownerEpochHealth_noDht_isUp_andNeverReadsTheStore() {
        var read = new AtomicBoolean();
        var health = StatusRoutes.ownerEpochHealth(false, () -> {
            read.set(true);
            return Option.none();
        });

        assertThat(health.status()).isEqualTo("UP");
        assertThat(read).as("no DHT, nothing to fence: the store is not consulted").isFalse();
    }

    /// The wiring: the component is on `/health/ready`, and the overall status and `ready` agree with the lifecycle when
    /// the epoch is not in the way (a node with no DHT).
    @Test
    void buildReadinessResponse_carriesOwnerEpochComponent_andActiveNoDhtNodeIsUp() {
        var lifecycle = NodeLifecycle.nodeLifecycle();

        lifecycle.subsystemsReady();
        lifecycle.signalReady();

        var routes = StatusRoutes.statusRoutes(() -> stubNode(lifecycle), StatusRoutesOwnerEpochTest::stubAppHttpServer);
        var readiness = routes.buildReadinessResponse();

        assertThat(lifecycle.currentState()).isEqualTo(NodeState.ACTIVE);
        assertThat(readiness.components().stream().map(ComponentHealth::name)).contains("owner-epoch");
        assertThat(readiness.status()).isEqualTo("UP");
        assertThat(readiness.ready()).isTrue();
    }

    /// The gate itself: an ACTIVE node WITH a DHT whose ownership record is not applied is not ready, and the same node is
    /// ready once the record (non-ZERO epoch) is applied. Red when the readiness status ignores the owner-epoch component.
    @Test
    void buildReadinessResponse_activeDhtNode_isDownUntilOwnershipRecordApplied() {
        var before = readiness(ownershipStore(Option.none()));

        assertThat(before.status()).isEqualTo("DOWN");
        assertThat(before.ready()).isFalse();
        assertThat(componentStatus(before, "owner-epoch")).isEqualTo("DOWN");

        var after = readiness(ownershipStore(Option.some(Epoch.epoch(1, 1, 1))));

        assertThat(after.status()).isEqualTo("UP");
        assertThat(after.ready()).isTrue();
        assertThat(componentStatus(after, "owner-epoch")).isEqualTo("UP");
    }

    private static ReadinessResponse readiness(KVStore<AetherKey, AetherValue> kv) {
        var lifecycle = NodeLifecycle.nodeLifecycle();

        lifecycle.subsystemsReady();
        lifecycle.signalReady();

        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(PROXY_SELF);

        var dht = DHTNode.dhtNode(PROXY_SELF, memoryStorageEngine(), ring, DHTConfig.SINGLE_NODE);

        return StatusRoutes.statusRoutes(() -> stubNode(lifecycle, Option.some(dht), kv), StatusRoutesOwnerEpochTest::stubAppHttpServer)
                           .buildReadinessResponse();
    }

    private static String componentStatus(ReadinessResponse response, String name) {
        return response.components().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow().status();
    }

    private static KVStore<AetherKey, AetherValue> ownershipStore(Option<Epoch> applied) {
        return new KVStore<>(MessageRouter.mutable(), noopSerializer(), noopDeserializer()) {
            @SuppressWarnings("unchecked")
            @Override
            public synchronized <VV> Option<VV> getTyped(StructuredKey key, Class<VV> valueClass) {
                return key instanceof DhtPartitionOwnershipKey
                       ? (Option<VV>) applied.map(StatusRoutesOwnerEpochTest::ownershipValue)
                       : Option.none();
            }
        };
    }

    private static DhtPartitionOwnershipValue ownershipValue(Epoch epoch) {
        return DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(PROXY_SELF, "c", epoch, 1L, HlcTimestamp.ZERO);
    }

    private static Serializer noopSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer noopDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    private static ManageableNode stubNode(NodeLifecycle lifecycle) {
        return stubNode(lifecycle, Option.none(), null);
    }

    private static ManageableNode stubNode(NodeLifecycle lifecycle, Option<DHTNode> dht, KVStore<AetherKey, AetherValue> kv) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "self" -> PROXY_SELF;
                                                           case "nodeLifecycle" -> lifecycle;
                                                           case "isReady" -> true;
                                                           case "quorumLossSnapshot" -> Option.some(new QuorumLossSnapshot(1, 1, false, false));
                                                           case "storageSetups" -> Map.of();
                                                           case "dhtNode" -> dht;
                                                           case "kvStore" -> kv;
                                                           default -> throw new UnsupportedOperationException("Not stubbed in test proxy: "
                                                                                                              + method.getName());
                                                       });
    }

    private static AppHttpServer stubAppHttpServer() {
        return (AppHttpServer) Proxy.newProxyInstance(AppHttpServer.class.getClassLoader(),
                                                      new Class[]{AppHttpServer.class},
                                                      (_, method, _) -> switch (method.getName()) {
                                                          case "isRouteReady" -> true;
                                                          default -> throw new UnsupportedOperationException("Not stubbed in test proxy: "
                                                                                                             + method.getName());
                                                      });
    }
}
