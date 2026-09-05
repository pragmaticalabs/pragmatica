// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.DeclarativeConsumerAssignment;
import org.pragmatica.aether.api.ManagementApiResponses.DeclarativeConsumerDetail;
import org.pragmatica.aether.api.ManagementApiResponses.DeclarativeConsumerPartition;
import org.pragmatica.aether.api.ManagementApiResponses.DeclarativeConsumersResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ReplicaStateDetail;
import org.pragmatica.aether.api.ManagementApiResponses.StreamHydrationDetail;
import org.pragmatica.aether.api.ManagementApiResponses.StreamHydrationResponse;
import org.pragmatica.aether.api.ManagementApiResponses.StreamReplicasResponse;
import org.pragmatica.aether.node.stream.StreamConsumerManager.ConsumerStatus;
import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionAssignment;
import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionCursor;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamCreateOutcome;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamPartitionManager.HydrationSnapshot;
import org.pragmatica.aether.stream.StreamPartitionManager.PartitionInfo;
import org.pragmatica.aether.stream.StreamPartitionManager.StreamHydration;
import org.pragmatica.aether.stream.StreamPartitionManager.StreamInfo;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.StreamReadRouter.ReplicaSetView;
import org.pragmatica.aether.stream.StreamReadRouter.ReplicaView;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator.ConsumerInfo;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


public final class StreamRoutes implements RouteSource {
    private static final Cause MISSING_STREAM_NAME = Causes.cause("Missing stream name");

    private static final Cause SYSTEM_STREAM_NAME_FORBIDDEN = Causes.cause("Cannot create a stream using a reserved system stream name");

    private static final int DEFAULT_PARTITIONS = 4;

    private final Supplier<ManageableNode> nodeSupplier;
    private final ConsumerGroupCoordinator coordinator;
    private final ConsumerGroupRegistry registry;

    private StreamRoutes(Supplier<ManageableNode> nodeSupplier,
                         ConsumerGroupCoordinator coordinator,
                         ConsumerGroupRegistry registry) {
        this.nodeSupplier = nodeSupplier;
        this.coordinator = coordinator;
        this.registry = registry;
    }

    public static StreamRoutes streamRoutes(Supplier<ManageableNode> nodeSupplier,
                                            ConsumerGroupCoordinator coordinator,
                                            ConsumerGroupRegistry registry) {
        return new StreamRoutes(nodeSupplier, coordinator, registry);
    }

    record StreamInfoResponse(String name,
                              int partitions,
                              long totalEvents,
                              long totalBytes,
                              List<PartitionDetail> partitionDetails) {}

    record PartitionDetail(int partition, long headOffset, long tailOffset, long eventCount) {
        static PartitionDetail fromPartitionInfo(PartitionInfo info) {
            return new PartitionDetail(info.partition(), info.headOffset(), info.tailOffset(), info.eventCount());
        }
    }

    record StreamCreateRequest(String name, Integer partitions) {}

    record StreamCreateResponse(String name, int partitions, String status) {}

    record StreamConsumersResponse(String name, List<PartitionInfo> partitions) {}

    record JoinGroupRequest(String groupId, String streamName, int partitionCount, String consumerId) {}

    record LeaveGroupRequest(String groupId, String streamName, String consumerId) {}

    record GroupStatusResponse(String groupId, Map<String, List<ConsumerInfo>> streams) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<StreamCreateResponse> route(ManagementRoute.STREAM_CREATE)
                                         .withBody(StreamCreateRequest.class)
                                         .toResult(this::createStream)
                                         .asJson(),

        // #742 fold: STREAM_GET/STREAM_PARTITION/STREAM_REPLICAS/STREAM_READ/STREAM_PUBLISH/
        // STREAM_DELETE's flat-name legacy registrations moved to StreamApiRoutes' catalog-form
        // equivalents (STREAM_GET, STREAM_PARTITION, STREAM_REPLICAS, STREAM_READ, STREAMS_PUBLISH,
        // STREAMS_DELETE) — same underlying operations, canonicalized (namespace, stream, version)
        // addressing. See #742's closing comment for why this file remains: STREAM_CREATE/
        // STREAM_CONSUMERS/CONSUMER_GROUP_STATUS have no catalog home and CONSUMER_GROUP_JOIN/LEAVE
        // are excluded from this fold (capability mismatch against their nearest catalog candidates,
        // tracked separately).
        // #490: LOCAL variant — same handler, but the route target makes the RECEIVING
        // node answer from its own registry (see ManagementRoute.STREAM_REPLICAS_LOCAL).
        ManagementRoutes.<StreamReplicasResponse> route(ManagementRoute.STREAM_REPLICAS_LOCAL)
                        .withPath(PathParameter.aString(),
                                  PathParameter.aInteger())
                        .toResult(this::replicaDetails)
                        .asJson(),
                         ManagementRoutes.<StreamHydrationResponse> route(ManagementRoute.STREAM_HYDRATION).toJson(this::streamHydration),
                         ManagementRoutes.<DeclarativeConsumersResponse> route(ManagementRoute.STREAM_DECLARATIVE_CONSUMERS).toJson(this::declarativeConsumers),
                         ManagementRoutes.<StreamConsumersResponse> route(ManagementRoute.STREAM_CONSUMERS)
                                         .withPath(PathParameter.aString())
                                         .toResult(this::streamConsumers)
                                         .asJson(),
                         ManagementRoutes.<GroupStatusResponse> route(ManagementRoute.CONSUMER_GROUP_JOIN)
                                         .withBody(JoinGroupRequest.class)
                                         .toResult(this::joinGroup)
                                         .asJson(),
                         ManagementRoutes.<GroupStatusResponse> route(ManagementRoute.CONSUMER_GROUP_LEAVE)
                                         .withBody(LeaveGroupRequest.class)
                                         .toResult(this::leaveGroup)
                                         .asJson(),
                         ManagementRoutes.<GroupStatusResponse> route(ManagementRoute.CONSUMER_GROUP_STATUS)
                                         .withPath(PathParameter.aString())
                                         .toResult(this::groupStatus)
                                         .asJson());
    }

    /// #260/#261/#333 replica-state observability handler. Assembles the partition's replica-set view
    /// off the router's `ReplicaRegistry` + HRW owner resolver (a Leaf — pure read of already-computed
    /// state). Always succeeds: an unknown stream/partition yields an empty replica set with
    /// `servedByOwner=false`, which is itself the operator-meaningful answer.
    private Result<StreamReplicasResponse> replicaDetails(String name, Integer partition) {
        return Result.success(toReplicasResponse(streamReadRouter().replicaSnapshot(name, partition)));
    }

    /// Package-visible: reused by [StreamApiRoutes]'s catalog-scoped `STREAM_REPLICAS` handler so the
    /// view-to-DTO mapping has one definition, not a parallel copy per route surface.
    static StreamReplicasResponse toReplicasResponse(ReplicaSetView view) {
        return new StreamReplicasResponse(view.streamName(),
                                          view.partition(),
                                          view.ownerNodeId().or(""),
                                          view.servedByOwner(),
                                          view.ownerHeadOffset(),
                                          view.earliestRetainedOffset(),
                                          view.replicas().stream().map(StreamRoutes::toReplicaStateDetail).toList());
    }

    private static ReplicaStateDetail toReplicaStateDetail(ReplicaView replica) {
        return new ReplicaStateDetail(replica.nodeId(), replica.state(), replica.confirmedOffset(), replica.hrwOwner());
    }

    /// #265 increment 0 per-node hydration snapshot handler (a Leaf — pure read of already-computed
    /// state off the local `StreamPartitionManager`). Always succeeds; an empty cluster yields an empty
    /// `streams` list with the node's live budget totals, itself the operator-meaningful answer.
    private StreamHydrationResponse streamHydration() {
        return toHydrationResponse(streamManager().hydrationSnapshot());
    }

    /// #488: what this node has actually attached. Pure snapshot read off the consumer manager — no
    /// hot-path cost. An empty `consumers` list means no slice in the cluster declares a `[streams.X]`
    /// consumer, which is itself the honest answer; it never fabricates rows.
    private DeclarativeConsumersResponse declarativeConsumers() {
        var statuses = nodeSupplier.get().streamConsumerManager().statuses();

        return new DeclarativeConsumersResponse(nodeSupplier.get().streamConsumerManager().activeSubscriptionCount(),
                                                nodeSupplier.get().streamConsumerManager().cursorCommitFailureCount(),
                                                statuses.stream().map(StreamRoutes::toConsumerDetail).toList());
    }

    private static DeclarativeConsumerDetail toConsumerDetail(ConsumerStatus status) {
        return new DeclarativeConsumerDetail(status.streamName(),
                                             status.configSection(),
                                             status.artifact(),
                                             status.methodName(),
                                             status.consumerGroup(),
                                             status.batchMode(),
                                             status.eventType(),
                                             status.sliceDeployedLocally(),
                                             status.eventTypePublishable(),
                                             status.assignedPartitions()
                                                   .stream()
                                                   .map(StreamRoutes::toConsumerPartition)
                                                   .toList(),
                                             status.unassignedPartitions(),
                                             status.partitionAssignments()
                                                   .stream()
                                                   .map(StreamRoutes::toConsumerAssignment)
                                                   .toList(),
                                             status.diagnostic().or(""));
    }

    private static DeclarativeConsumerAssignment toConsumerAssignment(PartitionAssignment assignment) {
        return new DeclarativeConsumerAssignment(assignment.partition(),
                                                 assignment.consumerNode().map(NodeId::id),
                                                 assignment.ownerNode().map(NodeId::id));
    }

    private static DeclarativeConsumerPartition toConsumerPartition(PartitionCursor cursor) {
        return new DeclarativeConsumerPartition(cursor.partition(),
                                                cursor.cursor(),
                                                cursor.stalled(),
                                                cursor.lastCursorCommitFailure().or(""));
    }

    private static StreamHydrationResponse toHydrationResponse(HydrationSnapshot snapshot) {
        return new StreamHydrationResponse(snapshot.totalAllocatedBytes(),
                                           snapshot.maxTotalBytes(),
                                           snapshot.overBudget(),
                                           snapshot.deferredPartitions(),
                                           snapshot.perStreamCeiling(),
                                           snapshot.clusterAggregateGuard(),
                                           snapshot.currentAggregatePartitionSlots(),
                                           snapshot.aggregateHeadroom(),
                                           snapshot.configOverCeilingStreams(),
                                           snapshot.releaseCandidates(),
                                           snapshot.releasedPartitionsSinceBoot(),
                                           snapshot.materializeQueueDepth(),
                                           snapshot.streams().stream().map(StreamRoutes::toHydrationDetail).toList());
    }

    private static StreamHydrationDetail toHydrationDetail(StreamHydration stream) {
        var roles = stream.roleCounts();

        return new StreamHydrationDetail(stream.name(),
                                         stream.partitionsDeclared(),
                                         stream.ringsMaterialized(),
                                         stream.partitionsDeferred(),
                                         stream.floorBytesAllocated(),
                                         stream.overCeiling(),
                                         roleCount(roles, Role.OWNER),
                                         roleCount(roles, Role.REPLICA),
                                         roleCount(roles, Role.NONE));
    }

    private static int roleCount(Map<Role, Long> roles, Role role) {
        return roles.getOrDefault(role, 0L)
                    .intValue();
    }

    /// Package-visible for direct unit coverage of the system-stream-name guard in
    /// [#createFreshStream] — the entry point a real `POST /streams` request also goes through.
    Result<StreamCreateResponse> createStream(StreamCreateRequest request) {
        return Option.option(request.name())
                     .toResult(MISSING_STREAM_NAME)
                     .flatMap(name -> createStreamWithConfig(name, request));
    }

    private Result<StreamCreateResponse> createStreamWithConfig(String name, StreamCreateRequest request) {
        var partitions = Option.option(request.partitions()).or(DEFAULT_PARTITIONS);

        return streamManager().streamInfo(name)
                            .map(existing -> Result.success(new StreamCreateResponse(name,
                                                                                     existing.partitions(),
                                                                                     "exists")))
                            .or(() -> createFreshStream(name, partitions));
    }

    /// [ManagementServer]'s HTTP-path write-gate is pre-auth and reuses the dispatch path's route
    /// canonicalization (condition 1) — a parallel body parser inside the gate would violate that.
    /// `STREAM_CREATE`'s target name is body-carried, not path-carried, so the gate structurally
    /// cannot see it; this method is the sole call site that ever mints a stream
    /// ([StreamManager#createStream]), so the guard sits here, first, unconditionally, with no
    /// branch that reaches the mint below it — the handler-level equivalent of the gate's pre-auth
    /// placement, honest about running post-auth since body-carried identity leaves no earlier hook.
    /// This closes the narrow window `createStreamWithConfig`'s idempotent "already exists" check
    /// does not: a create racing ahead of [SystemStreamBootstrap]'s registration at cluster startup
    /// would otherwise find `streamManager().streamInfo(name)` empty and mint a caller-controlled
    /// config under a reserved name.
    private Result<StreamCreateResponse> createFreshStream(String name, int partitions) {
        if (SystemStreams.isForbiddenEngineKey(name)) {
            return Result.failure(SYSTEM_STREAM_NAME_FORBIDDEN);
        }

        var config = StreamConfig.streamConfig(name, partitions, MANAGEMENT_API_RETENTION, "latest");

        return streamManager().createStream(config)
                            .map(_ -> new StreamCreateResponse(name, partitions, "created"));
    }

    private static final RetentionPolicy MANAGEMENT_API_RETENTION = RetentionPolicy.retentionPolicy(10_000,
                                                                                                    4 * 1024 * 1024L,
                                                                                                    60 * 60 * 1000L);

    /// Publish auto-create guard. A stream ALREADY materialized locally carries its own committed
    /// `StreamConfig` — an app/blueprint-declared stream keeps its `replicas` / `minSyncReplicas`
    /// durability knobs — so the management publish path must leave it INTACT rather than fabricate and
    /// commit a `replicas=1/min-sync=0` MANAGEMENT DEFAULT over it (which would disarm the sync barrier
    /// — `minSyncReplicas` read as 0 — and collapse the replica set to RF=1). `streamInfo` is a LOCAL
    /// read of the manager's already-materialized state — NO consensus round-trip on the publish hot
    /// path. When it MISSES (a first publish racing ahead of local materialization of an app config
    /// committed at slice activation), the absent branch still prefers the committed config from applied
    /// KV state before falling back to the management default, so the RF is preserved across the race.
    /// Package-visible for direct unit coverage.
    Result<Unit> ensureStreamExists(String name) {
        return streamManager().streamInfo(name)
                            .map(_ -> Result.unitResult())
                            .or(() -> materializeAbsentStream(name));
    }

    /// Absent-locally branch. The app/blueprint stream's committed `StreamConfig` (with its
    /// `replicas` / `minSyncReplicas` durability knobs) lands in applied KV state at slice activation but
    /// may not yet be in the manager's local materialized map when a first publish races in. Prefer that
    /// committed config so the auto-create preserves RF; fall back to the management default only for a
    /// genuinely management-only stream that has no committed entry.
    private Result<Unit> materializeAbsentStream(String name) {
        var config = nodeSupplier.get()
                                 .kvStore()
                                 .getTyped(StreamConfigKey.streamConfigKey(name),
                                           StreamConfigValue.class)
                                 .map(StreamConfigValue::config)
                                 .or(() -> StreamConfig.streamConfig(name,
                                                                     DEFAULT_PARTITIONS,
                                                                     MANAGEMENT_API_RETENTION,
                                                                     "latest"));

        return StreamCreateOutcome.tolerateAlreadyExists(streamManager().createStream(config));
    }

    private Result<StreamConsumersResponse> streamConsumers(String name) {
        return streamManager().allPartitionInfo(name)
                            .map(partitions -> new StreamConsumersResponse(name, partitions));
    }

    private Result<GroupStatusResponse> joinGroup(JoinGroupRequest request) {
        return coordinator.joinGroup(request.groupId(),
                                     request.streamName(),
                                     request.partitionCount(),
                                     request.consumerId(),
                                     nodeSupplier.get().self())
                          .map(_ -> new GroupStatusResponse(request.groupId(),
                                                            coordinator.groupStatus(request.groupId())));
    }

    private Result<GroupStatusResponse> leaveGroup(LeaveGroupRequest request) {
        return coordinator.leaveGroup(request.groupId(),
                                      request.streamName(),
                                      request.consumerId())
                          .map(_ -> new GroupStatusResponse(request.groupId(),
                                                            coordinator.groupStatus(request.groupId())));
    }

    private Result<GroupStatusResponse> groupStatus(String groupId) {
        return Result.success(new GroupStatusResponse(groupId, coordinator.groupStatus(groupId)));
    }

    private StreamPartitionManager streamManager() {
        return nodeSupplier.get()
                           .streamPartitionManager();
    }

    private StreamReadRouter streamReadRouter() {
        return nodeSupplier.get()
                           .streamReadRouter();
    }
}
