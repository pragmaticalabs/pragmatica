// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1753: a stream's partitions have DIFFERENT owners, and a partition route is answered by the owner of the partition it
/// names. `StreamRoutesNonHolderTest` gives every partition one owner, so a route that asked the owner of partition 0 for
/// every partition would pass there. Here partition 0 is owned by OWNER_0 (which holds 7 events in it) and partition 1 by
/// OWNER_1 (4 events in it); each owner holds an empty ring for the other's partition, so asking the wrong owner is visible
/// in the count.
class StreamRoutesPartitionOwnerTest {
    private static final NodeId OWNER_0 = NodeId.nodeId("owner-0").unwrap();
    private static final NodeId OWNER_1 = NodeId.nodeId("owner-1").unwrap();
    private static final NodeId SERVING = NodeId.nodeId("serving").unwrap();
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final RetentionPolicy RETENTION = RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000);
    private static final int EVENTS_0 = 7;
    private static final int EVENTS_1 = 4;

    private StreamPartitionManager owner0;
    private StreamPartitionManager owner1;
    private StreamPartitionManager serving;
    private String engineKey;

    @BeforeEach
    void setUp() {
        owner0 = StreamPartitionManager.streamPartitionManager();
        owner1 = StreamPartitionManager.streamPartitionManager();
        serving = StreamPartitionManager.streamPartitionManager();
        serving.placementRoleSupplier((_, _) -> Role.NONE);
        engineKey = StreamManager.engineKey(address());
        holds(owner0, 0, EVENTS_0);
        holds(owner1, 1, EVENTS_1);
    }

    @AfterEach
    void tearDown() throws Exception {
        owner0.close();
        owner1.close();
        serving.close();
    }

    @Test
    void partition_zero_isAnsweredByItsOwner() {
        assertThat(partitionDetail("0").eventCount()).isEqualTo(EVENTS_0);
    }

    /// The pin for owner choice: partition 1 is answered by OWNER_1, not by the owner of partition 0.
    @Test
    void partition_one_isAnsweredByItsOwnerNotByTheOwnerOfPartitionZero() {
        var detail = partitionDetail("1");

        assertThat(detail.partition()).isEqualTo(1);
        assertThat(detail.eventCount()).isEqualTo(EVENTS_1);
    }

    @Test
    void consumers_eachPartitionIsItsOwnersCount() {
        var response = (StreamRoutes.StreamConsumersResponse) answer(ManagementRoute.STREAM_CONSUMERS, List.of("consumers"));

        assertThat(response.partitions()).extracting(StreamRoutes.PartitionDetail::eventCount).containsExactly((long) EVENTS_0, (long) EVENTS_1);
    }

    // === helpers ===

    private StreamRoutes.PartitionDetail partitionDetail(String partition) {
        return (StreamRoutes.PartitionDetail) answer(ManagementRoute.STREAM_PARTITION, List.of("partitions", partition));
    }

    private Object answer(ManagementRoute route, List<String> tail) {
        var path = Stream.concat(Stream.of(NAMESPACE, STREAM, VERSION), tail.stream()).toList();

        return RouteProbe.run(routes(), route, path, Map.of())
                         .onFailure(cause -> fail(route + " must be answered by the partition's owner: " + cause.message()))
                         .unwrap();
    }

    private Stream<Route<?>> routes() {
        return StreamRouteFixtures.routes(serving,
                                          forwardingRouter(),
                                          StreamRouteFixtures.storeCommitting(engineKey, config()),
                                          StreamRouteFixtures.registryWith(address(), RETENTION));
    }

    /// `owner` declares both rings and holds `events` events in `partition` only.
    private void holds(StreamPartitionManager owner, int partition, int events) {
        owner.createStream(config()).onFailure(cause -> fail(cause.message()));

        for (var i = 0; i < events; i++) {
            owner.publishLocal(engineKey, partition, new byte[]{(byte) i}, 1_000L + i)
                 .onFailure(cause -> fail(cause.message()));
        }
    }

    private StreamConfig config() {
        return StreamConfig.streamConfig(engineKey, 2, RETENTION, "earliest");
    }

    /// The wire: a ReadForward from SERVING lands on the handler of the node it is addressed to, whose response lands on
    /// SERVING's client. Partition 0 resolves to OWNER_0, every other partition to OWNER_1.
    private StreamReadRouter forwardingRouter() {
        var clientRef = new StreamForwardClient[1];
        StreamForwardTransport ownerToServing = (target, message) -> {
            clientRef[0].onReadForwardResponse((ReadForwardResponse) message);

            return Promise.success(new WriteOutcome.Sent(target));
        };
        var handler0 = StreamForwardHandler.streamForwardHandler(OWNER_0, owner0, ownerToServing);
        var handler1 = StreamForwardHandler.streamForwardHandler(OWNER_1, owner1, ownerToServing);
        StreamForwardTransport servingToOwner = (target, message) -> {
            handlerAt(target, handler0, handler1).onReadForward((ReadForward) message);

            return Promise.success(new WriteOutcome.Sent(target));
        };

        clientRef[0] = StreamForwardClient.streamForwardClient(SERVING, servingToOwner, TimeSpan.timeSpan(5).seconds());

        return StreamReadRouter.streamReadRouter(serving,
                                                 Option.none(),
                                                 Option.some(clientRef[0]),
                                                 SERVING,
                                                 (_, partition) -> Option.some(partition == 0 ? OWNER_0 : OWNER_1),
                                                 StreamReadForwardMetrics.NOOP);
    }

    private static StreamForwardHandler handlerAt(NodeId target, StreamForwardHandler handler0, StreamForwardHandler handler1) {
        return target.equals(OWNER_0) ? handler0 : handler1;
    }

    private static ResourceAddress address() {
        return ResourceAddress.resourceAddress(NAMESPACE, STREAM, VERSION).unwrap();
    }
}
