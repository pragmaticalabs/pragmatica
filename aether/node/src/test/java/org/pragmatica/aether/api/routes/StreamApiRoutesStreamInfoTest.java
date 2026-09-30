// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1478: `/info` (STREAM_GET) is delegate-routed, so the node that answers usually holds no ring, and
/// summing its OWN rings reported `totalEvents: 0` for a stream whose owner held every event. Two nodes
/// here — OWNER holds the rings and serves through the real `StreamForwardHandler`; SERVING holds the
/// stream metadata-only (placement `NONE`) and asks through the real `StreamForwardClient`. The registry
/// metadata's `partitionCount` was likewise a constant (4) rather than the stream's declared count.
class StreamApiRoutesStreamInfoTest {
    private static final NodeId OWNER = NodeId.nodeId("owner").unwrap();
    private static final NodeId SERVING = NodeId.nodeId("serving").unwrap();
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final RetentionPolicy RETENTION = RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000);

    private StreamPartitionManager ownerPartitions;
    private StreamPartitionManager servingPartitions;
    private StreamApiRoutes routes;
    private String engineKey;

    @BeforeEach
    void setUp() {
        ownerPartitions = StreamPartitionManager.streamPartitionManager();
        servingPartitions = StreamPartitionManager.streamPartitionManager();
        servingPartitions.placementRoleSupplier((_, _) -> Role.NONE);
        engineKey = StreamManager.engineKey(address());
        routes = StreamApiRoutes.streamApiRoutes(() -> nodeWith(servingPartitions, forwardingRouter(), emptyStore()),
                                                 StreamNamespacesService.inMemory(),
                                                 ConsumerGroupCoordinator.noOp(),
                                                 ConsumerGroupRegistry.consumerGroupRegistry());
    }

    @AfterEach
    void tearDown() throws Exception {
        ownerPartitions.close();
        servingPartitions.close();
    }

    @Test
    void streamInfo_servingNodeHoldsNoRing_totalEventsComesFromTheOwner() {
        declare(2);
        publish(0, 20);

        assertThat(servingPartitions.streamInfo(engineKey).unwrap().totalEvents()).as("control: the serving node's own rings hold nothing, so the pre-fix sum was 0")
                  .isZero();

        routes.streamInfo(NAMESPACE, STREAM, VERSION, "info")
              .await()
              .onFailure(cause -> fail("info must be answered from the owner: " + cause.message()))
              .onSuccess(info -> {
                  assertThat(info.totalEvents()).isEqualTo(20L);
                  assertThat(info.partitions()).isEqualTo(2);
                  assertThat(info.partitionDetails()).extracting(StreamRoutes.PartitionDetail::eventCount)
                            .containsExactly(20L, 0L);
                  assertThat(info.partitionDetails().get(0).headOffset()).isEqualTo(19L);
                  assertThat(info.partitionDetails().get(0).tailOffset()).isZero();
              });
    }

    @Test
    void streamInfo_severalPartitions_totalIsTheSumOfTheOwnersCounts() {
        declare(2);
        publish(0, 5);
        publish(1, 7);

        routes.streamInfo(NAMESPACE, STREAM, VERSION, "info")
              .await()
              .onFailure(cause -> fail("info must be answered from the owner: " + cause.message()))
              .onSuccess(info -> assertThat(info.totalEvents()).isEqualTo(12L));
    }

    @Test
    void streamInfo_ownerHoldsNothing_reportsAMeasuredZero() {
        declare(1);

        routes.streamInfo(NAMESPACE, STREAM, VERSION, "info")
              .await()
              .onFailure(cause -> fail("an empty owner ring is a real answer: " + cause.message()))
              .onSuccess(info -> {
                  assertThat(info.totalEvents()).isZero();
                  assertThat(info.partitionDetails()).hasSize(1);
              });
    }

    @Test
    void streamInfo_ownerCannotBeAsked_failsInsteadOfReportingZero() {
        declare(1);
        publish(0, 3);

        var isolated = StreamApiRoutes.streamApiRoutes(() -> nodeWith(servingPartitions,
                                                                      StreamReadRouter.localOnly(servingPartitions),
                                                                      emptyStore()),
                                                       StreamNamespacesService.inMemory(),
                                                       ConsumerGroupCoordinator.noOp(),
                                                       ConsumerGroupRegistry.consumerGroupRegistry());

        isolated.streamInfo(NAMESPACE, STREAM, VERSION, "info")
                .await()
                .onSuccess(info -> fail("an unreachable owner must not render as totalEvents=" + info.totalEvents()));
    }

    @Test
    void streamMetadata_partitionCount_isTheCommittedConfigsNotAConstant() {
        var store = emptyStore();
        var namespaces = StreamNamespacesService.inMemory();
        var metadataRoutes = routesWith(StreamPartitionManager.streamPartitionManager(), store, namespaces);

        commit(store, 1);
        register(namespaces);

        metadataRoutes.streamMetadata(NAMESPACE, STREAM, VERSION)
                      .onFailure(cause -> fail("metadata must resolve: " + cause.message()))
                      .onSuccess(metadata -> assertThat(metadata.partitionCount()).as("declared partitions=1, never the constant 4")
                                                                               .isEqualTo(1));
    }

    @Test
    void streamMetadata_noCommittedConfig_usesTheLocallyDeclaredCount() {
        declare(2);
        var namespaces = StreamNamespacesService.inMemory();
        var metadataRoutes = routesWith(servingPartitions, emptyStore(), namespaces);

        register(namespaces);

        metadataRoutes.streamMetadata(NAMESPACE, STREAM, VERSION)
                      .onFailure(cause -> fail("metadata must resolve: " + cause.message()))
                      .onSuccess(metadata -> assertThat(metadata.partitionCount()).isEqualTo(2));
    }

    @Test
    void streamMetadata_countUnknownAnywhere_isRefusedNotFabricated() {
        var namespaces = StreamNamespacesService.inMemory();
        var metadataRoutes = routesWith(StreamPartitionManager.streamPartitionManager(), emptyStore(), namespaces);

        register(namespaces);

        metadataRoutes.streamMetadata(NAMESPACE, STREAM, VERSION)
                      .onSuccess(metadata -> fail("no source knows the count, yet metadata claimed " + metadata.partitionCount()))
                      .onFailure(cause -> assertThat(cause).isInstanceOf(ManagementServerError.StreamUnavailable.class));
    }

    // === helpers ===

    private void declare(int partitions) {
        var config = StreamConfig.streamConfig(engineKey, partitions, RETENTION, "earliest");

        ownerPartitions.createStream(config).onFailure(cause -> fail(cause.message()));
        servingPartitions.createStream(config).onFailure(cause -> fail(cause.message()));
    }

    private void publish(int partition, int events) {
        for (var i = 0; i < events; i++) {
            ownerPartitions.publishLocal(engineKey, partition, new byte[]{(byte) i}, 1_000L + i)
                           .onFailure(cause -> fail(cause.message()));
        }
    }

    /// The transport is the wire: a ReadForward from SERVING lands on the owner's handler, whose response
    /// lands on SERVING's client. Nothing else is between them.
    private StreamReadRouter forwardingRouter() {
        var clientRef = new StreamForwardClient[1];
        StreamForwardTransport ownerToServing = (_, message) -> clientRef[0].onReadForwardResponse((ReadForwardResponse) message);
        var ownerHandler = StreamForwardHandler.streamForwardHandler(OWNER, ownerPartitions, ownerToServing);
        StreamForwardTransport servingToOwner = (_, message) -> ownerHandler.onReadForward((ReadForward) message);

        clientRef[0] = StreamForwardClient.streamForwardClient(SERVING, servingToOwner, TimeSpan.timeSpan(5).seconds());

        return StreamReadRouter.streamReadRouter(servingPartitions,
                                                 Option.none(),
                                                 Option.some(clientRef[0]),
                                                 SERVING,
                                                 (_, _) -> Option.some(OWNER),
                                                 StreamReadForwardMetrics.NOOP);
    }

    private static ResourceAddress address() {
        return ResourceAddress.resourceAddress(NAMESPACE, STREAM, VERSION).unwrap();
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private void commit(KVStore<AetherKey, AetherValue> store, int partitions) {
        var config = StreamConfig.streamConfig(engineKey, partitions, RETENTION, "earliest");

        store.process(store.createBatch(List.of(new Put<>(StreamConfigKey.streamConfigKey(engineKey),
                                                                    StreamConfigValue.streamConfigValue(config)))));
    }

    private StreamApiRoutes routesWith(StreamPartitionManager manager,
                                       KVStore<AetherKey, AetherValue> store,
                                       StreamNamespacesService namespaces) {
        return StreamApiRoutes.streamApiRoutes(() -> nodeWith(manager, StreamReadRouter.localOnly(manager), store),
                                               namespaces,
                                               ConsumerGroupCoordinator.noOp(),
                                               ConsumerGroupRegistry.consumerGroupRegistry());
    }

    private static void register(StreamNamespacesService namespaces) {
        namespaces.registry()
                  .register(StreamRegistryEntry.operator(address(), RETENTION, Instant.now()))
                  .onFailure(cause -> fail("registration must succeed: " + cause.message()));
    }

    private static ManageableNode nodeWith(StreamPartitionManager manager,
                                           StreamReadRouter readRouter,
                                           KVStore<AetherKey, AetherValue> store) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager, readRouter, store));
    }

    private static Object stubbed(String method,
                                  StreamPartitionManager manager,
                                  StreamReadRouter readRouter,
                                  KVStore<AetherKey, AetherValue> store) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            case "streamReadRouter" -> readRouter;
            case "kvStore" -> store;
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
