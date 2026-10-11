// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.forward.ForwardCodecsStream;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.forward.StreamForwardClient.streamForwardClient;
import static org.pragmatica.aether.stream.forward.StreamForwardHandler.streamForwardHandler;


/// #1967: a stream read served by a remote owner answers the status and takes the retry a local read of the owner would. The real
/// `STREAM_READ` and `STREAMS_EVENTS` routes run twice -- over the owner's own router and over a metadata-only node's router that
/// forwards to it through the real `StreamForwardHandler` and `StreamForwardClient`, every message crossing the production codec --
/// and the two outcomes must agree. Before the fix a refusal of the owner arrived as text, so the retention-rolled default read
/// and the expired offset did not get the retry and the 410 a local read gives.
class ForwardedStreamReadRouteTest {
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), ForwardCodecsStream.CODECS);
    private static final NodeId OWNER_ID = new NodeId("owner-node");
    private static final NodeId CALLER_ID = new NodeId("caller-node");
    private static final RetentionPolicy RETENTION = RetentionPolicy.retentionPolicy(5, 1024 * 1024, 600_000);
    private static final List<String> EVENTS_PATH = List.of("ns", "orders", "1.0.0", "events");

    private final ResourceAddress address = ResourceAddress.resourceAddress("ns", "orders", "1.0.0").unwrap();
    private final String engineKey = StreamManager.engineKey(address);
    private StreamPartitionManager owner;
    private StreamPartitionManager caller;
    private StreamNamespacesService namespaces;
    private StreamReadRouter ownerRouter;
    private StreamReadRouter forwardingRouter;
    private StreamForwardHandler handler;

    @BeforeEach
    void startTwoNodes() {
        owner = StreamPartitionManager.streamPartitionManager();
        caller = StreamPartitionManager.streamPartitionManager();
        caller.placementRoleSupplier((_, _) -> Role.NONE);
        namespaces = StreamNamespacesService.inMemory();
        namespaces.registry()
                  .register(StreamRegistryEntry.operator(address, RETENTION, java.time.Instant.now()))
                  .onFailure(cause -> fail(cause.message()));
        StreamForwardClient client = streamForwardClient(CALLER_ID,
                                                         (_, message) -> handler.onReadForward((ReadForward) overTheWire(message)),
                                                         TimeSpan.timeSpan(5).seconds());

        handler = streamForwardHandler(OWNER_ID,
                                       owner,
                                       (_, message) -> client.onReadForwardResponse((ReadForwardResponse) overTheWire(message)),
                                       Long.MAX_VALUE,
                                       StreamReadForwardMetrics.NOOP,
                                       Option.none());
        ownerRouter = StreamReadRouter.localOnly(owner);
        forwardingRouter = StreamReadRouter.streamReadRouter(caller,
                                                             Option.none(),
                                                             Option.some(client),
                                                             CALLER_ID,
                                                             (_, _) -> Option.some(OWNER_ID),
                                                             StreamReadForwardMetrics.NOOP);
    }

    @AfterEach
    void stopTwoNodes() {
        owner.close();
        caller.close();
    }

    /// The #1921 default read: retention rolled the tail past 0, the caller named no offset, and the owner's `CursorExpired` must
    /// reach the route typed so it retries from the earliest retained offset.
    @Test
    void streamsEvents_defaultRead_retriesFromTheEarliestRetainedOffset_whenTheOwnerIsRemote() {
        rolledStreamOnOwner();

        var forwarded = RouteProbe.run(routes(forwardingRouter), ManagementRoute.STREAMS_EVENTS, EVENTS_PATH, Map.of());

        forwarded.onFailure(cause -> fail("a default read through a remote owner must succeed, got: " + cause.message()))
                 .onSuccess(value -> assertThat(value).isInstanceOfSatisfying(StreamApiRoutes.StreamEventsResponse.class,
                                                                              response -> assertThat(response.events()).isNotEmpty()));
        assertThat(outcome(forwardingRouter, ManagementRoute.STREAMS_EVENTS, EVENTS_PATH, Map.of())).isEqualTo(outcome(ownerRouter, ManagementRoute.STREAMS_EVENTS, EVENTS_PATH, Map.of()));
    }

    @Test
    void streamRead_defaultRead_retriesFromTheEarliestRetainedOffset_whenTheOwnerIsRemote() {
        rolledStreamOnOwner();

        RouteProbe.run(routes(forwardingRouter), ManagementRoute.STREAM_READ, readPath(0), Map.of())
                  .onFailure(cause -> fail("a default read through a remote owner must succeed, got: " + cause.message()))
                  .onSuccess(value -> assertThat(value).isInstanceOfSatisfying(StreamApiRoutes.ReadEventsResponse.class,
                                                                               response -> assertThat(response.events()).isNotEmpty()));
    }

    @Test
    void explicitExpiredOffset_answers410_whenTheOwnerIsRemote() {
        rolledStreamOnOwner();

        assertThat(outcome(forwardingRouter, ManagementRoute.STREAMS_EVENTS, EVENTS_PATH, Map.of("fromOffset", List.of("0")))).isEqualTo(HttpStatus.GONE);
        assertThat(outcome(forwardingRouter, ManagementRoute.STREAM_READ, readPath(0), Map.of("from", List.of("0")))).isEqualTo(HttpStatus.GONE);
    }

    /// The caller declares four partitions, the owner two: partition 3 is in range here and out of range there.
    @Test
    void partitionOutOfRangeAtTheOwner_answers400_notThe500OfAnUntypedRemoteFailure() {
        owner.createStream(config(2)).onFailure(cause -> fail(cause.message()));
        caller.onStreamConfigPut(configPut(config(4)));

        assertThat(outcome(forwardingRouter, ManagementRoute.STREAM_READ, readPath(3), Map.of())).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(outcome(forwardingRouter, ManagementRoute.STREAM_READ, readPath(3), Map.of())).isEqualTo(outcome(ownerRouter, ManagementRoute.STREAM_READ, readPath(3), Map.of()));
    }

    @Test
    void streamUnknownToTheOwner_answersWhatALocalReadOfTheOwnerAnswers() {
        caller.onStreamConfigPut(configPut(config(1)));

        assertThat(outcome(forwardingRouter, ManagementRoute.STREAM_READ, readPath(0), Map.of())).isEqualTo(outcome(ownerRouter, ManagementRoute.STREAM_READ, readPath(0), Map.of()));
        assertThat(outcome(forwardingRouter, ManagementRoute.STREAMS_EVENTS, EVENTS_PATH, Map.of())).isEqualTo(outcome(ownerRouter, ManagementRoute.STREAMS_EVENTS, EVENTS_PATH, Map.of()));
    }

    /// "Partition not owned by this node" from the target of a forward is the retryable 503 a local miss is, never a 500 (#1108).
    @Test
    void partitionNotOwnedByTheTarget_answers503() {
        owner.placementRoleSupplier((_, _) -> Role.NONE);
        owner.onStreamConfigPut(configPut(config(1)));
        caller.onStreamConfigPut(configPut(config(1)));

        assertThat(outcome(forwardingRouter, ManagementRoute.STREAM_READ, readPath(0), Map.of())).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(outcome(forwardingRouter, ManagementRoute.STREAMS_EVENTS, EVENTS_PATH, Map.of())).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static List<String> readPath(int partition) {
        return List.of("ns", "orders", "1.0.0", "read", Integer.toString(partition));
    }

    private HttpStatus outcome(StreamReadRouter router, ManagementRoute route, List<String> path, Map<String, List<String>> query) {
        var result = RouteProbe.run(routes(router), route, path, query);

        return result.fold(RouteProbe::problemStatus, _ -> HttpStatus.OK);
    }

    private void rolledStreamOnOwner() {
        owner.createStream(config(1)).onFailure(cause -> fail(cause.message()));
        caller.onStreamConfigPut(configPut(config(1)));

        for (int i = 0; i < 30; i++) {
            owner.publishLocal(engineKey, 0, ("event-" + i).getBytes(UTF_8), System.currentTimeMillis())
                 .onFailure(cause -> fail("publish: " + cause.message()));
        }

        assertThat(owner.partitionInfo(engineKey, 0).unwrap().tailOffset()).as("retention rolled the tail past 0").isGreaterThan(0);
    }

    private StreamConfig config(int partitions) {
        return StreamConfig.streamConfig(engineKey, partitions, RETENTION, "earliest");
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> configPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()), StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }

    private Stream<org.pragmatica.http.routing.Route<?>> routes(StreamReadRouter router) {
        var manager = router == ownerRouter
                      ? owner
                      : caller;
        var node = (ManageableNode) java.lang.reflect.Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                                             new Class[]{ManageableNode.class},
                                                                             (_, method, _) -> switch (method.getName()) {
                                                                                 case "streamPartitionManager" -> manager;
                                                                                 case "streamReadRouter" -> router;
                                                                                 default -> throw new UnsupportedOperationException(method.getName());
                                                                             });

        return StreamApiRoutes.streamApiRoutes(() -> node, namespaces, ConsumerGroupCoordinator.noOp(), null)
                              .routes();
    }

    @SuppressWarnings("unchecked")
    private static <T> T overTheWire(T message) {
        var buffer = Unpooled.buffer();

        CODEC.write(buffer, message);

        return CODEC.read(buffer);
    }
}
