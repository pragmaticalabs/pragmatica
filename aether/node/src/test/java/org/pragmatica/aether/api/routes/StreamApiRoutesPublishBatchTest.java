// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;

import org.pragmatica.aether.api.routes.StreamApiRoutes.PublishItemOutcome;
import org.pragmatica.aether.api.routes.StreamApiRoutes.PublishItemStatus;
import org.pragmatica.aether.api.routes.StreamApiRoutes.PublishRequest;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1342: `publish-batch` is not atomic — every item is written concurrently — and `collectOffsets` folded the
/// per-item results with `Result.allOf`, so a partial batch reported the first failure and DISCARDED the offsets
/// of the items that had landed. The response now carries one outcome per item at its request index: the
/// offset of a published item, `NOT_ATTEMPTED` for an item rejected before writing (safe to retry), and
/// `OUTCOME_UNKNOWN` for an item the write router refused (#1236: it may already be in the log).
class StreamApiRoutesPublishBatchTest {
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final String STREAM_ADDRESS = NAMESPACE + ":" + STREAM + ":" + VERSION;
    private static final int PARTITIONS = 4;

    @Test
    void partialBatch_reportsTheOffsetThatLanded_andNotAttemptedForTheRejectedItem() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            createStream(manager, ConsistencyMode.EVENTUAL);
            var response = routesFor(manager).publishBatch(NAMESPACE,
                                                           STREAM,
                                                           VERSION,
                                                           "publish-batch",
                                                           requests(new PublishRequest("ok", 0),
                                                                    new PublishRequest("bad", PARTITIONS)))
                                    .await()
                                    .unwrap();

            assertThat(response.published()).isEqualTo(1);
            assertThat(response.notPublished()).isEqualTo(1);
            assertThat(response.outcomes()).hasSize(2);
            assertThat(response.outcomes().getFirst()).isEqualTo(new PublishItemOutcome(0,
                                                                                        PublishItemStatus.PUBLISHED,
                                                                                        Option.some(0L),
                                                                                        Option.none()));
            assertThat(response.outcomes().getLast().index()).isEqualTo(1);
            assertThat(response.outcomes().getLast().status()).isEqualTo(PublishItemStatus.NOT_ATTEMPTED);
            assertThat(response.outcomes().getLast().offset().isEmpty()).isTrue();
            assertThat(response.outcomes().getLast().cause().or("")).contains("out of range");
            manager.readLocal(STREAM_ADDRESS, 0, 0, 10)
                   .onFailure(cause -> fail("partition 0 read: " + cause))
                   .onSuccess(events -> assertThat(events).as("item 0 IS durably in partition 0")
                                                  .hasSize(1));
        } finally {
            manager.close();
        }
    }

    @Test
    void fullBatch_reportsEveryOffset_inRequestOrder() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            createStream(manager, ConsistencyMode.EVENTUAL);
            var response = routesFor(manager).publishBatch(NAMESPACE,
                                                           STREAM,
                                                           VERSION,
                                                           "publish-batch",
                                                           requests(new PublishRequest("a", 2),
                                                                    new PublishRequest("b", 0)))
                                    .await()
                                    .unwrap();

            assertThat(response.published()).isEqualTo(2);
            assertThat(response.notPublished()).isZero();
            assertThat(response.outcomes()).containsExactly(new PublishItemOutcome(0,
                                                                                   PublishItemStatus.PUBLISHED,
                                                                                   Option.some(0L),
                                                                                   Option.none()),
                                                            new PublishItemOutcome(1,
                                                                                   PublishItemStatus.PUBLISHED,
                                                                                   Option.some(0L),
                                                                                   Option.none()));
        } finally {
            manager.close();
        }
    }

    /// A refusal from the write router (here #964's unreadable mode) reaches the caller as OUTCOME_UNKNOWN, not
    /// NOT_ATTEMPTED: the route cannot tell a pre-append refusal from a post-append one until #1236.
    @Test
    void routerRefusal_isReportedOutcomeUnknown() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            createStream(manager, ConsistencyMode.UNKNOWN);
            var response = routesFor(manager).publishBatch(NAMESPACE,
                                                           STREAM,
                                                           VERSION,
                                                           "publish-batch",
                                                           requests(new PublishRequest("a", 0)))
                                    .await()
                                    .unwrap();

            assertThat(response.published()).isZero();
            assertThat(response.notPublished()).isEqualTo(1);
            assertThat(response.outcomes().getFirst().status()).isEqualTo(PublishItemStatus.OUTCOME_UNKNOWN);
            assertThat(response.outcomes().getFirst().cause().or("")).contains("#964");
        } finally {
            manager.close();
        }
    }

    private static PublishRequest[] requests(PublishRequest... requests) {
        return requests;
    }

    private static void createStream(StreamPartitionManager manager, ConsistencyMode mode) {
        manager.createStream(StreamConfig.streamConfig(STREAM_ADDRESS,
                                                       PARTITIONS,
                                                       RetentionPolicy.retentionPolicy(1_000, 1024 * 1024, 60_000),
                                                       "earliest",
                                                       1_048_576L,
                                                       mode))
               .onFailure(cause -> fail("stream create must succeed: " + cause));
    }

    private static ManageableNode nodeWith(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager));
    }

    private static Object stubbed(String method, StreamPartitionManager manager) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            case "kvStore" -> new KVStore<AetherKey, AetherValue>(null, null, null);
            case "streamWriteRouter" -> StreamWriteRouter.localOnly(manager);
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }

    private static StreamApiRoutes routesFor(StreamPartitionManager manager) {
        return StreamApiRoutes.streamApiRoutes(() -> nodeWith(manager),
                                               StreamNamespacesService.inMemory(),
                                               ConsumerGroupCoordinator.noOp(),
                                               ConsumerGroupRegistry.consumerGroupRegistry());
    }
}
