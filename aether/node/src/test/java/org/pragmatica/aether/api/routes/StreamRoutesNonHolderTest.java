// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
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
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.Route;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1753, #1963: a stream management route gives the same answer whichever node receives it. Two nodes — OWNER holds the
/// rings; SERVING is the delegate that answers, and holds the stream either metadata-only (placement `NONE`, #1753) or not
/// at all (config committed here, apply lagging, #1963). Both know the stream from the catalog (registry entry and committed
/// config). Unknown means the CATALOG has no such stream, never "this node holds no partition".
class StreamRoutesNonHolderTest {
    private static final NodeId OWNER = NodeId.nodeId("owner").unwrap();
    private static final NodeId SERVING = NodeId.nodeId("serving").unwrap();
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final RetentionPolicy RETENTION = RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000);
    private static final int EVENTS = 7;

    /// One row per route whose answer must not depend on the answering node.
    private enum Probe {
        PARTITION(ManagementRoute.STREAM_PARTITION, List.of("partitions", "0")),
        CONSUMERS(ManagementRoute.STREAM_CONSUMERS, List.of("consumers")),
        INFO(ManagementRoute.STREAM_GET, List.of("info")),
        READ(ManagementRoute.STREAM_READ, List.of("read", "0")),
        EVENTS(ManagementRoute.STREAMS_EVENTS, List.of("events"));

        private final ManagementRoute route;
        private final List<String> tail;

        Probe(ManagementRoute route, List<String> tail) {
            this.route = route;
            this.tail = tail;
        }

        List<String> path() {
            return Stream.concat(List.of(NAMESPACE, STREAM, VERSION).stream(), tail.stream()).toList();
        }
    }

    private StreamPartitionManager ownerPartitions;
    private StreamPartitionManager servingPartitions;
    private String engineKey;

    @BeforeEach
    void setUp() {
        ownerPartitions = StreamPartitionManager.streamPartitionManager();
        servingPartitions = StreamPartitionManager.streamPartitionManager();
        servingPartitions.placementRoleSupplier((_, _) -> Role.NONE);
        engineKey = StreamManager.engineKey(address());
    }

    @AfterEach
    void tearDown() throws Exception {
        ownerPartitions.close();
        servingPartitions.close();
    }

    /// #1753: the delegate holds the stream metadata-only, so its own ring answered PARTITION_NOT_LOCAL.
    @Test
    void partition_servingNodeHoldsNoRing_eventCountComesFromTheOwner() {
        ownerHolds(2, EVENTS);
        servingHoldsMetadataOnly(2);

        var detail = (StreamRoutes.PartitionDetail) answer(Probe.PARTITION, forwardingRoutes(true));

        assertThat(detail.eventCount()).isEqualTo(EVENTS);
        assertThat(detail.headOffset()).isEqualTo(EVENTS - 1L);
    }

    /// #1963: the stream exists cluster-wide but is not materialized on the delegate at all.
    @Test
    void partition_streamNotMaterializedOnServingNode_eventCountComesFromTheOwner() {
        ownerHolds(2, EVENTS);

        var detail = (StreamRoutes.PartitionDetail) answer(Probe.PARTITION, forwardingRoutes(true));

        assertThat(detail.eventCount()).isEqualTo(EVENTS);
    }

    @Test
    void consumers_servingNodeHoldsNoRing_everyPartitionIsTheOwnersNotAnEmptyOne() {
        ownerHolds(2, EVENTS);
        servingHoldsMetadataOnly(2);

        var response = (StreamRoutes.StreamConsumersResponse) answer(Probe.CONSUMERS, forwardingRoutes(true));

        assertThat(response.partitions()).hasSize(2);
        assertThat(response.partitions().get(0).eventCount()).isEqualTo(EVENTS);
        assertThat(response.partitions().get(1).eventCount()).isZero();
    }

    @Test
    void consumers_streamNotMaterializedOnServingNode_answersFromTheOwnerNotNotFound() {
        ownerHolds(2, EVENTS);

        var response = (StreamRoutes.StreamConsumersResponse) answer(Probe.CONSUMERS, forwardingRoutes(true));

        assertThat(response.partitions()).hasSize(2);
        assertThat(response.partitions().get(0).eventCount()).isEqualTo(EVENTS);
    }

    @Test
    void info_streamNotMaterializedOnServingNode_totalEventsComesFromTheOwner() {
        ownerHolds(2, EVENTS);

        var info = (StreamRoutes.StreamInfoResponse) answer(Probe.INFO, forwardingRoutes(true));

        assertThat(info.totalEvents()).isEqualTo(EVENTS);
        assertThat(info.partitions()).isEqualTo(2);
    }

    /// `totalBytes` has no forwarded form: a node that holds no ring for the stream holds no bytes for it.
    @Test
    void info_streamNotMaterializedOnServingNode_totalBytesIsZero() {
        ownerHolds(2, EVENTS);

        var info = (StreamRoutes.StreamInfoResponse) answer(Probe.INFO, forwardingRoutes(true));

        assertThat(info.totalBytes()).isZero();
    }

    /// The control: a node that does hold the rings reports its own allocation.
    @Test
    void info_nodeThatHoldsTheRings_reportsItsOwnBytes() {
        ownerHolds(2, EVENTS);

        var info = (StreamRoutes.StreamInfoResponse) answer(Probe.INFO, holderRoutes(true));

        assertThat(info.totalBytes()).isPositive();
    }

    /// The registry alone makes a stream known (a stream registered before its config commit is visible here).
    @Test
    void read_streamKnownOnlyToTheRegistry_isNotUnknown() {
        ownerHolds(2, EVENTS);

        var routes = StreamRouteFixtures.routes(servingPartitions,
                                                forwardingRouter(),
                                                StreamRouteFixtures.emptyStore(),
                                                StreamRouteFixtures.registryWith(address(), RETENTION));
        var read = (StreamApiRoutes.ReadEventsResponse) answer(Probe.READ, routes);

        assertThat(read.events()).hasSize(EVENTS);
    }

    @Test
    void read_streamNotMaterializedOnServingNode_eventsComeFromTheOwner() {
        ownerHolds(2, EVENTS);

        var read = (StreamApiRoutes.ReadEventsResponse) answer(Probe.READ, forwardingRoutes(true));

        assertThat(read.events()).hasSize(EVENTS);
    }

    /// The catalog knows the stream and this node cannot say more: retry (503), never "no such stream".
    @ParameterizedTest
    @EnumSource(Probe.class)
    void catalogKnowsStream_ownerCannotBeAsked_answers503NotNotFound(Probe probe) {
        assertThat(statusOf(probe, isolatedRoutes(true))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /// The owner-asking routes name the stream and partition whose owner did not answer ([ManagementServerError.StreamInfoUnavailable]),
    /// not the bare transport cause: the 503 status alone cannot tell the caller which owner to retry.
    @ParameterizedTest
    @EnumSource(value = Probe.class, names = {"PARTITION", "CONSUMERS", "INFO"})
    void ownerCannotBeAsked_failureNamesTheStreamAndPartitionWhoseOwnerDidNotAnswer(Probe probe) {
        var failure = RouteProbe.failureOf(isolatedRoutes(true), probe.route, probe.path(), Map.of());

        assertThat(failure).isInstanceOfSatisfying(ManagementServerError.StreamInfoUnavailable.class,
                                                   unavailable -> assertThat(unavailable.streamName()).isEqualTo(engineKey));
    }

    /// The same request at the node that DOES hold every partition: the catalog is what decides unknown, so a request for a
    /// stream the catalog lacks is 404 on the holder and on the delegate alike.
    @ParameterizedTest
    @EnumSource(Probe.class)
    void catalogLacksStream_answers404_onTheHolderAndOnANonHolder(Probe probe) {
        var holder = holderRoutes(false);

        assertThat(statusOf(probe, isolatedRoutes(false))).as("non-holder").isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(probe, holder)).as("holder").isEqualTo(HttpStatus.NOT_FOUND);
    }

    /// A partition the stream does not declare is the caller's error at any node, not "this node holds no ring". The three
    /// values are the three edges of `[0, count)`: below it, the first one past it (the count itself), and far past it.
    @ParameterizedTest
    @ValueSource(strings = {"-1", "2", "5"})
    void partition_outsideTheDeclaredRange_answers400_onANonHolder(String partition) {
        assertRefusedAsUndeclared(ManagementRoute.STREAM_PARTITION, "partitions", partition);
    }

    /// The same refusal on the read route: `/read/7` of a 2-partition stream is the caller's error (400), not a 503 that
    /// invites a retry no owner can satisfy.
    @ParameterizedTest
    @ValueSource(strings = {"-1", "2", "7"})
    void read_outsideTheDeclaredRange_answers400_onANonHolder(String partition) {
        assertRefusedAsUndeclared(ManagementRoute.STREAM_READ, "read", partition);
    }

    private void assertRefusedAsUndeclared(ManagementRoute route, String literal, String partition) {
        ownerHolds(2, EVENTS);
        servingHoldsMetadataOnly(2);

        var failure = RouteProbe.failureOf(forwardingRoutes(true), route, List.of(NAMESPACE, STREAM, VERSION, literal, partition), Map.of());

        assertThat(failure).isInstanceOf(ManagementServerError.InvalidPartition.class)
                           .extracting("requested", "partitionCount")
                           .containsExactly(Integer.parseInt(partition), 2);
        assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // === helpers ===

    private Object answer(Probe probe, Stream<Route<?>> routes) {
        return RouteProbe.run(routes, probe.route, probe.path(), Map.of())
                         .onFailure(cause -> fail(probe + " must be answered like the holder would: " + cause.message()))
                         .unwrap();
    }

    private static HttpStatus statusOf(Probe probe, Stream<Route<?>> routes) {
        return RouteProbe.problemStatus(RouteProbe.failureOf(routes, probe.route, probe.path(), Map.of()));
    }

    private void ownerHolds(int partitions, int events) {
        ownerPartitions.createStream(config(partitions)).onFailure(cause -> fail(cause.message()));

        for (var i = 0; i < events; i++) {
            ownerPartitions.publishLocal(engineKey, 0, new byte[]{(byte) i}, 1_000L + i)
                           .onFailure(cause -> fail(cause.message()));
        }
    }

    private void servingHoldsMetadataOnly(int partitions) {
        servingPartitions.createStream(config(partitions)).onFailure(cause -> fail(cause.message()));
    }

    private StreamConfig config(int partitions) {
        return StreamConfig.streamConfig(engineKey, partitions, RETENTION, "earliest");
    }

    /// SERVING routes whose forward path reaches OWNER; `catalogKnows` decides whether the catalog holds the stream.
    private Stream<Route<?>> forwardingRoutes(boolean catalogKnows) {
        return routesOver(servingPartitions, forwardingRouter(), catalogKnows);
    }

    /// SERVING routes with no way to ask anyone: the delegate whose owner cannot be reached.
    private Stream<Route<?>> isolatedRoutes(boolean catalogKnows) {
        return routesOver(servingPartitions, StreamReadRouter.localOnly(servingPartitions), catalogKnows);
    }

    /// A node that holds every ring itself (placement default), asked about a stream.
    private Stream<Route<?>> holderRoutes(boolean catalogKnows) {
        return routesOver(ownerPartitions, StreamReadRouter.localOnly(ownerPartitions), catalogKnows);
    }

    private Stream<Route<?>> routesOver(StreamPartitionManager manager, StreamReadRouter router, boolean catalogKnows) {
        return catalogKnows
               ? StreamRouteFixtures.routes(manager,
                                            router,
                                            StreamRouteFixtures.storeCommitting(engineKey, config(2)),
                                            StreamRouteFixtures.registryWith(address(), RETENTION))
               : StreamRouteFixtures.routes(manager,
                                            router,
                                            StreamRouteFixtures.emptyStore(),
                                            StreamNamespacesService.inMemory());
    }

    /// The transport is the wire: a ReadForward from SERVING lands on the owner's handler, whose response lands on SERVING's
    /// client. Nothing else is between them.
    private StreamReadRouter forwardingRouter() {
        var clientRef = new StreamForwardClient[1];
        StreamForwardTransport ownerToServing = (target, message) -> {
            clientRef[0].onReadForwardResponse((ReadForwardResponse) message);

            return Promise.success(new WriteOutcome.Sent(target));
        };
        var ownerHandler = StreamForwardHandler.streamForwardHandler(OWNER, ownerPartitions, ownerToServing);
        StreamForwardTransport servingToOwner = (target, message) -> {
            ownerHandler.onReadForward((ReadForward) message);

            return Promise.success(new WriteOutcome.Sent(target));
        };

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
}
