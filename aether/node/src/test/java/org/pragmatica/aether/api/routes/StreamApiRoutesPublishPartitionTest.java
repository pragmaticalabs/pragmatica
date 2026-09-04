// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.node.ManageableNode;
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
import org.pragmatica.http.HttpStatus;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #524: Management-API publish (`StreamApiRoutes#publishOne`) hardwired every write to partition
/// 0, diverging from the app-publish path (#507), which routes by `@PartitionKey`. Management-API
/// publish writes untyped bytes with no event class to extract a key from, so the fix is an
/// explicit `partition` on [StreamApiRoutes.PublishRequest] — never key-based routing — validated
/// against the stream's actual partition count before ever reaching [StreamWriteRouter#publish].
/// This file pins four properties: (1) an omitted `partition` still targets 0 (unchanged
/// behavior), (2) an explicit in-range `partition` targets THAT partition and no other, (3) an
/// out-of-range `partition` fails 4xx naming the valid range instead of silently writing to
/// partition 0 or 500ing, and (4) when the stream's partition count cannot be determined (a genuine
/// materialization failure, not a race) the route reports the stream unavailable rather than
/// validating against a guessed count.
class StreamApiRoutesPublishPartitionTest {
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final String STREAM_ADDRESS = NAMESPACE + ":" + STREAM + ":" + VERSION;
    private static final int PARTITIONS = 4;

    private static ManageableNode nodeWith(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager));
    }

    /// `publishOne`'s auto-create guard reads `kvStore()` before falling back to the management
    /// default, and `streamWriteRouter()` needs a real router over the SAME manager to append
    /// locally — both additional to the single `streamPartitionManager()` stub the delete-only
    /// proxy in `StreamApiRoutesDeleteStreamTest` required.
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

    @Test
    void publish_partitionOmitted_targetsPartitionZero_unchangedBehavior() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            manager.createStream(StreamConfig.streamConfig(STREAM_ADDRESS, PARTITIONS, retention(), "latest"))
                   .onFailure(cause -> fail("stream create must succeed: " + cause));

            routesFor(manager).publishEvent(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.PublishRequest("payload", null))
                              .await()
                              .onFailure(cause -> fail("publish with an omitted partition must succeed: " + cause))
                              .onSuccess(response -> assertThat(response.offset()).isEqualTo(0L));

            manager.readLocal(STREAM_ADDRESS, 0, 0, 10)
                   .onFailure(cause -> fail("partition 0 must hold the event: " + cause))
                   .onSuccess(events -> assertThat(events).hasSize(1));
        } finally {
            manager.close();
        }
    }

    @Test
    void publish_explicitInRangePartition_targetsThatPartitionOnly() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            manager.createStream(StreamConfig.streamConfig(STREAM_ADDRESS, PARTITIONS, retention(), "latest"))
                   .onFailure(cause -> fail("stream create must succeed: " + cause));

            routesFor(manager).publishEvent(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.PublishRequest("payload", 2))
                              .await()
                              .onFailure(cause -> fail("publish to an in-range partition must succeed: " + cause))
                              .onSuccess(response -> assertThat(response.offset()).isEqualTo(0L));

            manager.readLocal(STREAM_ADDRESS, 2, 0, 10)
                   .onFailure(cause -> fail("partition 2 must hold the event: " + cause))
                   .onSuccess(events -> assertThat(events).hasSize(1));

            manager.readLocal(STREAM_ADDRESS, 0, 0, 10)
                   .onFailure(cause -> fail("partition 0 read must succeed even when empty: " + cause))
                   .onSuccess(events -> assertThat(events).as("an explicit partition must not ALSO write partition 0")
                                                          .isEmpty());
        } finally {
            manager.close();
        }
    }

    @Test
    void publish_outOfRangePartition_fails400NamingValidRange_neverPartitionZeroNever500() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            manager.createStream(StreamConfig.streamConfig(STREAM_ADDRESS, PARTITIONS, retention(), "latest"))
                   .onFailure(cause -> fail("stream create must succeed: " + cause));

            routesFor(manager).publishEvent(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.PublishRequest("payload", PARTITIONS))
                              .await()
                              .onSuccess(response -> fail("an out-of-range partition must fail, not publish at offset "
                                                          + response.offset()))
                              .onFailure(cause -> {
                                  assertThat(cause).isInstanceOf(ManagementServerError.InvalidPartition.class);
                                  assertThat(((ManagementServerError.InvalidPartition) cause).httpStatus())
                                          .isEqualTo(HttpStatus.BAD_REQUEST);
                              });

            manager.readLocal(STREAM_ADDRESS, 0, 0, 10)
                   .onFailure(cause -> fail("partition 0 read must succeed even when empty: " + cause))
                   .onSuccess(events -> assertThat(events).as("a rejected out-of-range partition must never silently "
                                                              + "fall back to writing partition 0")
                                                          .isEmpty());
        } finally {
            manager.close();
        }
    }

    /// #524 SHOULD-FIX 1 review finding: `ensureStreamExists`'s prior `.recover(_ -> Unit.unit())`
    /// swallowed a genuine materialization failure, leaving `streamInfo()` empty and letting
    /// `validatePartition` guess a hardcoded partition count instead. A zero-capacity manager forces
    /// `ensureStreamMaterialized`'s very first reservation to fail deterministically (never a
    /// transient race, so this pins a real failure, not a flake) — the route must surface a typed 4xx
    /// naming the stream, never guess a count and either wrongly accept or wrongly reject the request.
    @Test
    void publish_streamMaterializationFails_reportsStreamUnavailable_neverGuessesPartitionCount() {
        var manager = streamPartitionManager(0);
        try {
            routesFor(manager).publishEvent(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.PublishRequest("payload", null))
                              .await()
                              .onSuccess(response -> fail("materialization must fail when capacity is exhausted, not "
                                                          + "publish at offset " + response.offset()))
                              .onFailure(cause -> {
                                  assertThat(cause).isInstanceOf(ManagementServerError.StreamUnavailable.class);
                                  assertThat(((ManagementServerError.StreamUnavailable) cause).httpStatus())
                                          .isEqualTo(HttpStatus.CONFLICT);
                              });

            assertThat(manager.streamInfo(STREAM_ADDRESS).isEmpty())
                    .as("a failed materialization must never leave a partition count to guess")
                    .isTrue();
        } finally {
            manager.close();
        }
    }

    private static RetentionPolicy retention() {
        return RetentionPolicy.retentionPolicy();
    }
}
