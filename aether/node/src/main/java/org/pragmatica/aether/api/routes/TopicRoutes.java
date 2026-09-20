// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.projection.ProjectionHandle;
import org.pragmatica.aether.node.projection.ProjectionNodeSupport;
import org.pragmatica.aether.node.projection.ProjectionRegistry.Registration;
import org.pragmatica.aether.node.stream.StreamConsumerManager.ConsumerStatus;
import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionAssignment;
import org.pragmatica.aether.node.stream.StreamConsumerManager.PartitionCursor;
import org.pragmatica.aether.resource.projection.ProjectionStore.PartitionReplay;
import org.pragmatica.aether.resource.projection.ProjectionStore.ReplayStatus;
import org.pragmatica.aether.resource.projection.ProjectionStore.RewindToken;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.stream.topic.DurableTopicNames;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.PathParameter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Durable-topic operator surface (#1333, durable-pubsub-spec §9): the groups over a topic and the
/// rebuild of the projection behind one of them. Both LOCAL (CTO ruling 5): the projection column is
/// what THIS node hosts, and a rebuild must run where the projection's slice is — the groups route names
/// that node per partition (`consumerNode`), which is how an operator finds where to POST.
///
/// **groups** — per group (`artifactBase#method`), per partition: the assignee and the owner as this node
/// computes them (identical on every node), the CLUSTER-committed cursor and its rewind epoch from KV
/// (cluster truth, readable everywhere), the LIVE cursor and last commit failure where this node holds
/// the partition, and — when this node hosts the group's projection — its REBUILDING/LIVE state and, while
/// rebuilding, the next replay offset and captured head. `replayState` is `UNKNOWN` on a node that hosts
/// no projection for the group: the store's status lives in the slice, and this node cannot see it.
///
/// **rebuild** — resolves the group to a projection attached on this node and drives
/// `Projection.rebuild`: capture bounds, reset the store to a new generation, rewind the group's cursor
/// under a fenced epoch. Answers the new generation, the rewind token and the captured range. `409` when
/// no projection for the group is attached here, when this node consumes none of the group's partitions
/// (the response names the consuming node per partition), or when the group's projection cannot be
/// attributed (two durable subscribers on the topic, method not named). A refused capture or an uncommitted rewind (`NodeReplayCursor`) surfaces as the cause it was.
public final class TopicRoutes implements RouteSource {
    private static final String UNKNOWN = "UNKNOWN";
    private static final String LIVE = "LIVE";
    private static final String REBUILDING = "REBUILDING";

    private final Supplier<ManageableNode> nodeSupplier;

    private TopicRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static TopicRoutes topicRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new TopicRoutes(nodeSupplier);
    }

    record TopicGroupsResponse(String topic,
                               String topicStream,
                               String node,
                               long cursorReportFailures,
                               List<TopicGroupDetail> groups) {}

    record TopicGroupDetail(String consumerGroup,
                            String artifact,
                            String method,
                            boolean sliceDeployedLocally,
                            String projection,
                            List<TopicGroupPartition> partitions,
                            List<Integer> unassignedPartitions,
                            String diagnostic) {}

    /// `committedCursor`/`committedEpoch` are the KV checkpoint (absent → `committedCursor` empty, epoch
    /// `""`); `liveCursor`/`liveEpoch`/`lastCursorCommitFailure` are this node's consumer, present only when
    /// `heldHere`. `replayState` is LIVE, REBUILDING or UNKNOWN; the two offsets are present while REBUILDING.
    record TopicGroupPartition(int partition,
                               String consumerNode,
                               String ownerNode,
                               Option<Long> committedCursor,
                               String committedEpoch,
                               boolean heldHere,
                               Option<Long> liveCursor,
                               String liveEpoch,
                               String lastCursorCommitFailure,
                               String replayState,
                               Option<Long> nextReplayOffset,
                               Option<Long> replayThroughOffset) {}

    record RebuildResponse(String topicStream,
                           String consumerGroup,
                           String projection,
                           long generation,
                           RewindTokenView token,
                           Map<Integer, PartitionReplayView> partitions,
                           String state) {}

    record RewindTokenView(long generation, long rewind) {}

    record PartitionReplayView(long nextOffset, long throughOffset) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<TopicGroupsResponse> route(ManagementRoute.TOPICS_GROUPS)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.spacer("groups"))
                                         .to(this::groups)
                                         .asJson(),
                         ManagementRoutes.<RebuildResponse> route(ManagementRoute.TOPICS_GROUP_REBUILD)
                                         .withPath(PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.aString(),
                                                   PathParameter.spacer("rebuild"),
                                                   PathParameter.aString())
                                         .to(this::rebuild)
                                         .asJson());
    }

    private Promise<TopicGroupsResponse> groups(String namespace, String topic, String version, String groupsLiteral) {
        return ResourceAddress.resourceAddress(namespace, topic, version)
                              .async()
                              .flatMap(address -> groupsOf(address.asString()));
    }

    private Promise<TopicGroupsResponse> groupsOf(String topicAddress) {
        var node = nodeSupplier.get();
        var topicStream = DurableTopicNames.topicStream(topicAddress);
        var statuses = node.streamConsumerManager().topicGroupStatuses(topicStream);
        var details = statuses.stream().map(status -> groupDetail(node, topicStream, status)).toList();

        return Promise.allOf(details)
                      .flatMap(results -> Result.allOf(results).async())
                      .map(groups -> new TopicGroupsResponse(topicAddress,
                                                             topicStream,
                                                             node.self().id(),
                                                             node.projectionNodeSupport()
                                                                 .map(ProjectionNodeSupport::cursorReportFailures)
                                                                 .or(0L),
                                                             groups));
    }

    private Promise<TopicGroupDetail> groupDetail(ManageableNode node, String topicStream, ConsumerStatus status) {
        var handle = hostedProjection(node, topicStream, status.consumerGroup());

        return handle.map(hosted -> hosted.replayStatus()
                                          .map(Option::some))
                     .or(() -> Promise.success(Option.<ReplayStatus> none()))
                     .map(replay -> groupDetail(node, topicStream, status, handle, replay));
    }

    private static TopicGroupDetail groupDetail(ManageableNode node,
                                                String topicStream,
                                                ConsumerStatus status,
                                                Option<ProjectionHandle> handle,
                                                Option<ReplayStatus> replay) {
        var held = status.assignedPartitions()
                         .stream()
                         .collect(Collectors.toMap(PartitionCursor::partition, cursor -> cursor));
        var partitions = status.partitionAssignments()
                               .stream()
                               .sorted(Comparator.comparingInt(PartitionAssignment::partition))
                               .map(assignment -> partitionRow(node,
                                                               topicStream,
                                                               status.consumerGroup(),
                                                               assignment,
                                                               Option.option(held.get(assignment.partition())),
                                                               replay))
                               .toList();

        return new TopicGroupDetail(status.consumerGroup(),
                                    status.artifact(),
                                    status.methodName(),
                                    status.sliceDeployedLocally(),
                                    handle.map(ProjectionHandle::projectionName).or(""),
                                    partitions,
                                    status.unassignedPartitions(),
                                    status.diagnostic().or(""));
    }

    private static TopicGroupPartition partitionRow(ManageableNode node,
                                                    String topicStream,
                                                    String group,
                                                    PartitionAssignment assignment,
                                                    Option<PartitionCursor> held,
                                                    Option<ReplayStatus> replay) {
        var committed = node.kvStore()
                            .getTyped(StreamCursorCheckpointKey.streamCursorCheckpointKey(topicStream,
                                                                                          assignment.partition(),
                                                                                          group),
                                      StreamCursorCheckpointValue.class);
        var partitionReplay = replay.flatMap(status -> Option.option(status.rebuilding().get(assignment.partition())));

        return new TopicGroupPartition(assignment.partition(),
                                       assignment.consumerNode().map(NodeId::id).or(""),
                                       assignment.ownerNode().map(NodeId::id).or(""),
                                       committed.map(StreamCursorCheckpointValue::committedOffset),
                                       committed.map(value -> value.rewindEpoch()
                                                                   .toString()).or(""),
                                       held.isPresent(),
                                       held.map(PartitionCursor::cursor),
                                       held.map(cursor -> cursor.rewindEpoch()
                                                                .toString()).or(""),
                                       held.flatMap(PartitionCursor::lastCursorCommitFailure).or(""),
                                       replayState(replay, partitionReplay),
                                       partitionReplay.map(PartitionReplay::nextOffset),
                                       partitionReplay.map(PartitionReplay::throughOffset));
    }

    private static String replayState(Option<ReplayStatus> replay, Option<PartitionReplay> partitionReplay) {
        return replay.map(_ -> partitionReplay.isPresent()
                               ? REBUILDING
                               : LIVE)
                     .or(UNKNOWN);
    }

    /// The projection attached on this node whose resolved group is `group`, if any.
    private static Option<ProjectionHandle> hostedProjection(ManageableNode node, String topicStream, String group) {
        return node.projectionNodeSupport()
                   .flatMap(support -> support.registry()
                                              .registrations(topicStream)
                                              .stream()
                                              .filter(registration -> support.registry()
                                                                             .groupIdOf(registration)
                                                                             .option()
                                                                             .filter(group::equals)
                                                                             .isPresent())
                                              .findFirst()
                                              .map(Registration::handle)
                                              .map(Option::some)
                                              .orElseGet(Option::none));
    }

    /// `group` is `artifactBase#method`; the `#` must travel percent-encoded (`%23`) or a client drops it
    /// as a fragment, and the router hands the segment over undecoded — so it is decoded here. The CLI's
    /// `RouteAssembler.encodeSegment` encodes it; a plain group id without `%` decodes to itself.
    private Promise<RebuildResponse> rebuild(String namespace,
                                             String topic,
                                             String version,
                                             String rebuildLiteral,
                                             String group) {
        return ResourceAddress.resourceAddress(namespace, topic, version)
                              .async()
                              .flatMap(address -> rebuildGroup(DurableTopicNames.topicStream(address.asString()),
                                                               URLDecoder.decode(group, StandardCharsets.UTF_8)));
    }

    /// Two conditions, both LOCAL: the projection must be attached here AND this node must consume at
    /// least one of the group's partitions. The second is what makes a per-node projection store (the
    /// in-process backing) coherent — a rebuild on a slice-hosting node that consumes nothing would reset
    /// THAT node's never-written store and rewind the consuming node's cursor into a store that is not
    /// rebuilding, so the replays would dedupe into nothing. `[design intent — unverified: a shared
    /// ProjectionStore backing could relax the second condition; none exists]`
    private Promise<RebuildResponse> rebuildGroup(String topicStream, String group) {
        var node = nodeSupplier.get();

        return hostedProjection(node, topicStream, group).filter(_ -> consumesHere(node, topicStream, group))
                               .map(handle -> runRebuild(topicStream, group, handle))
                               .or(() -> notHostedHere(node, topicStream, group).promise());
    }

    private static boolean consumesHere(ManageableNode node, String topicStream, String group) {
        return node.streamConsumerManager()
                   .topicGroupStatuses(topicStream)
                   .stream()
                   .filter(status -> status.consumerGroup()
                                           .equals(group))
                   .anyMatch(status -> !status.assignedPartitions()
                                              .isEmpty());
    }

    private static Promise<RebuildResponse> runRebuild(String topicStream, String group, ProjectionHandle handle) {
        return handle.rebuild()
                     .flatMap(_ -> handle.replayStatus())
                     .map(status -> new RebuildResponse(topicStream,
                                                        group,
                                                        handle.projectionName(),
                                                        status.generation(),
                                                        status.currentRewind()
                                                              .map(TopicRoutes::tokenView)
                                                              .or(new RewindTokenView(0L, 0L)),
                                                        replayViews(status),
                                                        status.isLive()
                                                        ? LIVE
                                                        : REBUILDING));
    }

    private static RewindTokenView tokenView(RewindToken token) {
        return new RewindTokenView(token.generation(), token.rewind());
    }

    private static Map<Integer, PartitionReplayView> replayViews(ReplayStatus status) {
        return status.rebuilding()
                     .entrySet()
                     .stream()
                     .collect(Collectors.toMap(Map.Entry::getKey,
                                               entry -> new PartitionReplayView(entry.getValue().nextOffset(),
                                                                                entry.getValue().throughOffset())));
    }

    /// 409: the group's projection is not attached on this node — either the slice is not here, or the
    /// slice has two durable subscribers on the topic and named neither. The message names the consuming
    /// node per partition, which is where the POST belongs.
    private static Cause notHostedHere(ManageableNode node, String topicStream, String group) {
        var hosting = node.streamConsumerManager()
                          .topicGroupStatuses(topicStream)
                          .stream()
                          .filter(status -> status.consumerGroup()
                                                  .equals(group))
                          .flatMap(status -> status.partitionAssignments()
                                                   .stream())
                          .map(assignment -> assignment.partition()
                                            + "=" + assignment.consumerNode()
                                                              .map(NodeId::id)
                                                              .or("<unassigned>"))
                          .toList();
        var attributionProblem = node.projectionNodeSupport()
                                     .map(support -> support.registry()
                                                            .registrations(topicStream)
                                                            .stream()
                                                            .map(registration -> support.registry()
                                                                                        .groupIdOf(registration))
                                                            .filter(Result::isFailure)
                                                            .map(result -> result.fold(Cause::message, _ -> ""))
                                                            .findFirst()
                                                            .orElse(""))
                                     .or("");

        return HttpError.httpError(HttpStatus.CONFLICT,
                                   Causes.cause("Node " + node.self()
                                                              .id()
                                               + " hosts no projection for consumer group '" + group
                                               + "' on " + topicStream
                                               + " that it also consumes"
                                               + "; the rebuild route is LOCAL — POST it to the node consuming the group's"
                                               + " partitions (" + String.join(", ", hosting)
                                               + ")" + (attributionProblem.isEmpty()
                                                        ? ""
                                                        : ". A projection on this topic exists here but cannot be attributed: " + attributionProblem)));
    }
}
