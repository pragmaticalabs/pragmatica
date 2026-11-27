// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.projection.ProjectionNodeSupport;
import org.pragmatica.aether.node.projection.ProjectionRegistry;
import org.pragmatica.aether.node.projection.ProjectionRegistry.Registration;
import org.pragmatica.aether.node.stream.StreamConsumerManager;
import org.pragmatica.aether.node.stream.StreamConsumerManager.ConsumerStatus;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpError;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #3794: the rebuild's deliberate 409 ("hosts no projection") appends why a projection on the topic could not be attributed to a
/// group. That reason is a failure's client text, never its `message()`, which for a caught throwable is the printed stack
/// trace. Mutation: folding the registry failure with `Cause::message` turns this red.
class ManagementTopicRebuildConflictNoTraceTest {
    private static final String SENTINEL = "SENTINEL-3794-attribution";
    private static final String TOPIC_STREAM = "topic:ns:events:1.0.0";
    private static final String GROUP = "app#onEvent";

    @Test
    void rebuildConflict_whenAttributionFailsWithAThrowable_carriesTheClientText_notTheTrace() {
        var manager = StreamPartitionManager.streamPartitionManager();

        try {
            var cause = Causes.fromThrowable(new IllegalStateException(SENTINEL));
            var failure = RouteProbe.failureOf(routes(manager, cause),
                                               ManagementRoute.TOPICS_GROUP_REBUILD,
                                               List.of("ns", "events", "1.0.0", "rebuild", GROUP),
                                               Map.of());
            var status = new AtomicReference<HttpStatus>();
            var body = new AtomicReference<String>();

            ProblemResponses.writeProblem(new ResponseWriter() {
                @Override
                public void write(HttpStatus responseStatus, byte[] bytes, ContentType contentType) {
                    status.set(responseStatus);
                    body.set(new String(bytes, StandardCharsets.UTF_8));
                }

                @Override
                public ResponseWriter header(String name, String value) {
                    return this;
                }
            }, failure, "/x", "req_1");

            assertThat(cause.message()).as("control: the cause's own message carries the sentinel").contains(SENTINEL);
            assertThat(status.get()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(body.get()).contains("cannot be attributed: " + HttpError.clientMessage(cause))
                                  .doesNotContain(SENTINEL)
                                  .doesNotContain("IllegalStateException")
                                  .doesNotContain("\\tat ")
                                  .doesNotContain("\tat ")
                                  .doesNotContain("at java");
        } finally {
            manager.close();
        }
    }

    @SuppressWarnings("unchecked")
    private static Stream<Route<?>> routes(StreamPartitionManager manager, Cause attributionFailure) {
        manager.createStream(StreamConfig.streamConfig(TOPIC_STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000), "earliest"))
               .onFailure(cause -> fail(cause.message()));

        var consumers = mock(StreamConsumerManager.class);

        when(consumers.topicGroupStatuses(TOPIC_STREAM)).thenReturn(List.of(status()));

        var registry = mock(ProjectionRegistry.class);

        when(registry.registrations(TOPIC_STREAM)).thenReturn(List.of(new Registration(null, TOPIC_STREAM, Option.none(), null)));
        when(registry.groupIdOf(any(Registration.class))).thenReturn(attributionFailure.<String> result());

        var support = mock(ProjectionNodeSupport.class);

        when(support.registry()).thenReturn(registry);

        var node = mock(ManageableNode.class);
        var kv = (KVStore<AetherKey, AetherValue>) mock(KVStore.class);

        when(kv.getTyped(any(), any())).thenReturn(Option.none());
        when(node.kvStore()).thenReturn(kv);
        when(node.streamPartitionManager()).thenReturn(manager);
        when(node.streamConsumerManager()).thenReturn(consumers);
        when(node.projectionNodeSupport()).thenReturn(Option.some(support));
        when(node.self()).thenReturn(NodeId.nodeId("node-1").unwrap());

        return TopicRoutes.topicRoutes(() -> node).routes();
    }

    private static ConsumerStatus status() {
        return new ConsumerStatus(TOPIC_STREAM, "topic", "app", "onEvent", GROUP, false, "Event", true, Option.none(), List.of(), List.of(), List.of(), Option.none());
    }
}
