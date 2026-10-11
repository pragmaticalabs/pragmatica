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
import org.pragmatica.aether.node.stream.StreamConsumerManager;
import org.pragmatica.aether.node.stream.StreamConsumerManager.ConsumerStatus;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// #1921 (b), topic half: an unknown topic or consumer group is 404 on `TOPICS_GROUPS` and `TOPICS_GROUP_REBUILD`. `TOPICS_GROUPS`
/// answered 200 with an empty list for a topic that does not exist; the rebuild answered 409 ("hosts no projection") for a topic
/// or group that does not exist, which sends the operator hunting for a node that does not matter. Existence is read from what
/// THIS node can see: the committed stream config in its KV view, or the stream materialized in its engine. [unverified: a topic
/// created moments ago on another node may read as unknown here until its config commit applies.]
class ManagementTopicExistenceStatusTest {
    private static final String TOPIC_STREAM = "topic:ns:events:1.0.0";
    private static final String GROUP = "app#onEvent";
    private static final List<String> TOPIC = List.of("ns", "events", "1.0.0");

    @Test
    void topicsGroups_answers404_whenTheTopicIsUnknown() {
        var manager = StreamPartitionManager.streamPartitionManager();

        try {
            var failure = RouteProbe.failureOf(routes(manager, statuses()), ManagementRoute.TOPICS_GROUPS, path("groups"), Map.of());

            assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            manager.close();
        }
    }

    /// Control: a topic whose stream this node materialized is not refused, with no group declared on it.
    @Test
    void topicsGroups_answersTheEmptyList_whenTheTopicExistsAndHasNoGroups() {
        var manager = managerWithTopic();

        try {
            RouteProbe.run(routes(manager, statuses()), ManagementRoute.TOPICS_GROUPS, path("groups"), Map.of())
                      .onFailure(cause -> fail("a known topic must not be refused: " + cause.message()))
                      .onSuccess(value -> assertThat(value).isInstanceOf(TopicRoutes.TopicGroupsResponse.class));
        } finally {
            manager.close();
        }
    }

    @Test
    void topicsGroupRebuild_answers404_whenTheTopicIsUnknown() {
        var manager = StreamPartitionManager.streamPartitionManager();

        try {
            var failure = RouteProbe.failureOf(routes(manager, statuses()), ManagementRoute.TOPICS_GROUP_REBUILD, path("rebuild", GROUP), Map.of());

            assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            manager.close();
        }
    }

    /// The topic carries a DIFFERENT declared group, so an empty status list cannot be what makes this 404.
    @Test
    void topicsGroupRebuild_answers404_whenTheTopicExistsButTheGroupIsUnknown() {
        var manager = managerWithTopic();

        try {
            var failure = RouteProbe.failureOf(routes(manager, statuses(status("other#onEvent"))), ManagementRoute.TOPICS_GROUP_REBUILD, path("rebuild", GROUP), Map.of());

            assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            manager.close();
        }
    }

    /// Control, the boundary of this change: a KNOWN group whose projection this node does not host keeps the deliberate 409 (the
    /// rebuild is LOCAL; the message names the node to POST to). Without it the 404 could have swallowed that refusal.
    @Test
    void topicsGroupRebuild_staysConflict_whenTheGroupIsKnownButNotHostedHere() {
        var manager = managerWithTopic();

        try {
            var failure = RouteProbe.failureOf(routes(manager, statuses(status(GROUP))),
                                               ManagementRoute.TOPICS_GROUP_REBUILD,
                                               path("rebuild", "app%23onEvent"),
                                               Map.of());

            assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.CONFLICT);
        } finally {
            manager.close();
        }
    }

    private static StreamPartitionManager managerWithTopic() {
        var manager = StreamPartitionManager.streamPartitionManager();

        manager.createStream(StreamConfig.streamConfig(TOPIC_STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000), "earliest"))
               .onFailure(cause -> fail(cause.message()));

        return manager;
    }

    private static List<String> path(String literal, String... rest) {
        return Stream.concat(Stream.concat(TOPIC.stream(), Stream.of(literal)), Stream.of(rest)).toList();
    }

    private static StreamConsumerManager statuses(ConsumerStatus... all) {
        var consumers = mock(StreamConsumerManager.class);

        when(consumers.topicGroupStatuses(TOPIC_STREAM)).thenReturn(List.of(all));

        return consumers;
    }

    private static ConsumerStatus status(String group) {
        return new ConsumerStatus(TOPIC_STREAM, "topic", "app", "onEvent", group, false, "Event", true, Option.none(), List.of(), List.of(), List.of(), Option.none());
    }

    @SuppressWarnings("unchecked")
    private static Stream<org.pragmatica.http.routing.Route<?>> routes(StreamPartitionManager manager, StreamConsumerManager consumers) {
        var node = mock(ManageableNode.class);
        var kv = (KVStore<AetherKey, AetherValue>) mock(KVStore.class);

        when(kv.getTyped(any(), any())).thenReturn(Option.none());
        when(node.kvStore()).thenReturn(kv);
        when(node.streamPartitionManager()).thenReturn(manager);
        when(node.streamConsumerManager()).thenReturn(consumers);
        when(node.projectionNodeSupport()).thenReturn(Option.none());
        when(node.self()).thenReturn(NodeId.nodeId("node-1").unwrap());

        return TopicRoutes.topicRoutes(() -> node).routes();
    }
}
