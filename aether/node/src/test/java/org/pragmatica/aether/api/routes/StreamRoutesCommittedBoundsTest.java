// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;
import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateAck.replicateAck;

/// #2087: every bound a consumer or an operator reads over the management API is the COMMITTED span. The owner holds two records,
/// neither acknowledged by the peers its confirmation factor requires; the partition-detail route and the consumers route must
/// report an empty partition (`head -1`, count 0), not the appended head (`head 1`, count 2) -- the probe that found the defect.
/// When the peers acknowledge the first record only, the span is exactly that one record.
class StreamRoutesCommittedBoundsTest {
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final List<String> ADDRESS = List.of(NAMESPACE, STREAM, VERSION);
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId PEER = NodeId.nodeId("peer").unwrap();
    private static final NodeId PEER2 = NodeId.nodeId("peer2").unwrap();

    @Test
    void partitionAndConsumersRoutes_reportTheCommittedSpan_notTheAppendedHead() {
        var engineKey = StreamManager.engineKey(ResourceAddress.resourceAddress(NAMESPACE, STREAM, VERSION).unwrap());
        ReplicaRegistry registry = replicaRegistry();

        registry.registerReplica(engineKey, 0, SELF);
        registry.registerReplica(engineKey, 0, PEER);
        registry.registerReplica(engineKey, 0, PEER2);
        var replication = replicationManager(SELF, registry);
        var manager = streamPartitionManager(Long.MAX_VALUE, EvictionListener.NOOP, replication);

        try {
            manager.createStream(StreamConfig.streamConfig(engineKey,
                                                           1,
                                                           RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000),
                                                           "earliest",
                                                           1_048_576L,
                                                           ConsistencyMode.EVENTUAL,
                                                           3,
                                                           3,
                                                           StreamCompression.NONE,
                                                           Option.none()))
                   .onFailure(cause -> fail(cause.message()));
            manager.publishLocal(engineKey, 0, "a".getBytes(), 1L).onFailure(cause -> fail(cause.message()));
            manager.publishLocal(engineKey, 0, "b".getBytes(), 2L).onFailure(cause -> fail(cause.message()));

            assertThat(manager.appendedHead(engineKey, 0)).as("control: both records are appended").isEqualTo(1L);
            assertSpan(manager, -1L, 0L);

            replication.handleAck(replicateAck(PEER, engineKey, 0, 0L));
            replication.handleAck(replicateAck(PEER2, engineKey, 0, 0L));

            assertSpan(manager, 0L, 1L);
        } finally {
            manager.close();
        }
    }

    private static void assertSpan(StreamPartitionManager manager, long expectedHead, long expectedCount) {
        var apiRoutes = StreamApiRoutes.streamApiRoutes(() -> node(manager),
                                                        StreamNamespacesService.inMemory(),
                                                        ConsumerGroupCoordinator.noOp(),
                                                        ConsumerGroupRegistry.consumerGroupRegistry());

        RouteProbe.run(apiRoutes.routes(), ManagementRoute.STREAM_PARTITION, path("partitions", "0"), Map.of())
                  .onFailure(cause -> fail("partition detail must answer: " + cause.message()))
                  .onSuccess(value -> assertThat(value).isInstanceOfSatisfying(StreamApiRoutes.PartitionDetail.class, detail -> {
                      assertThat(detail.headOffset()).as("partition detail head").isEqualTo(expectedHead);
                      assertThat(detail.eventCount()).as("partition detail count").isEqualTo(expectedCount);
                  }));

        var streamRoutes = StreamRoutes.streamRoutes(() -> node(manager), ConsumerGroupCoordinator.noOp(), null).routes();

        RouteProbe.run(streamRoutes, ManagementRoute.STREAM_CONSUMERS, path("consumers"), Map.of())
                  .onFailure(cause -> fail("consumers must answer: " + cause.message()))
                  .onSuccess(value -> assertThat(value).isInstanceOfSatisfying(StreamRoutes.StreamConsumersResponse.class, response -> {
                      var partition = response.partitions().getFirst();

                      assertThat(partition.headOffset()).as("consumers head").isEqualTo(expectedHead);
                      assertThat(partition.eventCount()).as("consumers count").isEqualTo(expectedCount);
                  }));
    }

    private static List<String> path(String... rest) {
        return java.util.stream.Stream.concat(ADDRESS.stream(), java.util.stream.Stream.of(rest)).toList();
    }

    private static ManageableNode node(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> {
                                                           if (method.getName().equals("streamPartitionManager")) {
                                                               return manager;
                                                           }
                                                           throw new UnsupportedOperationException(method.getName());
                                                       });
    }
}
