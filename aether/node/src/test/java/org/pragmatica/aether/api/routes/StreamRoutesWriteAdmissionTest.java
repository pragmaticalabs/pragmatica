// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.api.routes.StreamApiRoutes.PublishItemStatus;
import org.pragmatica.aether.api.routes.StreamApiRoutes.PublishRequest;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #3796: a stream WRITE gives the same answer whichever node receives it, as a read does. The catalog (committed config,
/// registry entry) admits it and bounds its partition ([StreamApiRoutes] `declaredPartitions`); the receiving node's own ring
/// is not consulted, and its failure to hold the stream is not the owner's. Two nodes: OWNER answers every forwarded publish
/// with the next offset from 40; SERVING receives the request, and holds NO entry for a stream that exists in the catalog
/// (config committed here, apply lagging, or a node that cannot hold the rings).
class StreamRoutesWriteAdmissionTest {
    private static final NodeId OWNER = NodeId.nodeId("owner").unwrap();
    private static final NodeId SERVING = NodeId.nodeId("serving").unwrap();
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final RetentionPolicy RETENTION = RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000);
    private static final int DECLARED = 4;
    private static final long FIRST_OWNER_OFFSET = 40L;

    private record Forwarded(String streamName, int partition) {}

    private final List<Forwarded> forwarded = new CopyOnWriteArrayList<>();
    private final AtomicLong ownerOffsets = new AtomicLong(FIRST_OWNER_OFFSET);
    private final List<StreamPartitionManager> opened = new ArrayList<>();
    private final String engineKey = StreamManager.engineKey(address());

    @AfterEach
    void tearDown() {
        opened.forEach(StreamPartitionManager::close);
    }

    @Test
    void publish_atNonOwnerWithNoLocalEntry_succeedsWithTheOwnersOffset() {
        var serving = serving(Long.MAX_VALUE);

        var response = publish(routes(serving, true, true), DECLARED - 1);

        assertThat(response.offset()).isEqualTo(FIRST_OWNER_OFFSET);
        assertThat(forwarded).containsExactly(new Forwarded(engineKey, DECLARED - 1));
    }

    /// The node cannot hold the stream (capacity): that is not the owner's refusal, so the owner's answer is the answer.
    @Test
    void publish_nonOwnerCannotMaterializeTheStream_stillAnswersWithTheOwnersOffset() {
        var serving = serving(0);

        var response = publish(routes(serving, true, true), 0);

        assertThat(response.offset()).isEqualTo(FIRST_OWNER_OFFSET);
        assertThat(forwarded).containsExactly(new Forwarded(engineKey, 0));
    }

    /// The local ring is stale (a life with fewer partitions); the bound is the committed declaration, as on a read.
    @Test
    void publish_localRingDisagreesWithTheCatalog_boundIsTheCatalogs() {
        var serving = serving(Long.MAX_VALUE);

        serving.createStream(config(2)).onFailure(cause -> fail(cause.message()));

        var response = publish(routes(serving, true, true), DECLARED - 1);

        assertThat(response.offset()).isEqualTo(FIRST_OWNER_OFFSET);
        assertThat(forwarded).containsExactly(new Forwarded(engineKey, DECLARED - 1));
    }

    @Test
    void publish_partitionBeyondTheCatalogsCount_is400_atANodeWithNoLocalEntry() {
        var serving = serving(Long.MAX_VALUE);

        var failure = routes(serving, true, true).publishEvent(NAMESPACE, STREAM, VERSION, new PublishRequest("x", DECLARED))
                                                 .await()
                                                 .fold(cause -> cause, _ -> fail("an undeclared partition must be refused"));

        assertThat(failure).isInstanceOfSatisfying(ManagementServerError.InvalidPartition.class,
                                                   invalid -> assertThat(invalid.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(forwarded).as("refused before any forward").isEmpty();
    }

    @Test
    void batch_nonOwnerCannotMaterializeTheStream_everyItemIsTheOwnersOutcome() {
        var serving = serving(0);

        var response = routes(serving, true, true).publishBatch(NAMESPACE,
                                                                STREAM,
                                                                VERSION,
                                                                "publish-batch",
                                                                new PublishRequest[]{new PublishRequest("a", 0),
                                                                                     new PublishRequest("b", DECLARED - 1)})
                                                  .await()
                                                  .onFailure(cause -> fail("the batch must reach the owner: " + cause.message()))
                                                  .unwrap();

        assertThat(response.published()).isEqualTo(2);
        assertThat(response.outcomes()).extracting(item -> item.status()).containsOnly(PublishItemStatus.PUBLISHED);
        assertThat(forwarded).extracting(Forwarded::partition).containsExactlyInAnyOrder(0, DECLARED - 1);
    }

    @Test
    void batch_itemBeyondTheCatalogsCount_isNotAttempted_atANodeWithNoLocalEntry() {
        var serving = serving(Long.MAX_VALUE);

        var response = routes(serving, true, true).publishBatch(NAMESPACE,
                                                                STREAM,
                                                                VERSION,
                                                                "publish-batch",
                                                                new PublishRequest[]{new PublishRequest("a", 0),
                                                                                     new PublishRequest("b", DECLARED)})
                                                  .await()
                                                  .onFailure(cause -> fail("a per-item refusal never fails the batch: " + cause.message()))
                                                  .unwrap();

        assertThat(response.outcomes().get(0).status()).isEqualTo(PublishItemStatus.PUBLISHED);
        assertThat(response.outcomes().get(1).status()).isEqualTo(PublishItemStatus.NOT_ATTEMPTED);
    }

    /// The registry alone does not declare a count: until the config commits, a write is refused (503-class unavailable), never
    /// admitted against a count this node invented.
    @Test
    void publish_streamKnownOnlyToTheRegistry_isRefusedNotFabricated() {
        var serving = serving(Long.MAX_VALUE);

        var failure = routes(serving, false, true).publishEvent(NAMESPACE, STREAM, VERSION, new PublishRequest("x", 0))
                                                  .await()
                                                  .fold(cause -> cause, _ -> fail("a stream with no declared count must not be written"));

        assertThat(failure).isInstanceOf(ManagementServerError.StreamUnavailable.class);
        assertThat(forwarded).isEmpty();
        assertThat(serving.streamInfo(engineKey).isEmpty()).as("no default ring fabricated over a registered stream").isTrue();
    }

    /// The node IS the owner (it appends locally) but cannot hold the ring: the stream exists, so the answer is retry (503), never
    /// "no such stream".
    @Test
    void publish_ownerCannotMaterializeTheStream_is503NotNotFound() {
        var serving = serving(0);
        var routes = routes(serving, true, StreamNamespacesService.inMemory(), StreamWriteRouter.localOnly(serving));

        var failure = routes.publishEvent(NAMESPACE, STREAM, VERSION, new PublishRequest("x", 0))
                            .await()
                            .fold(cause -> cause, _ -> fail("a node that cannot hold the ring cannot append"));

        assertThat(failure).isInstanceOfSatisfying(ManagementServerError.StreamRefused.class,
                                                   refused -> assertThat(refused.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void delete_atNonOwnerWithNoLocalEntry_succeeds_andReleasesTheCatalogEntry() {
        var serving = serving(Long.MAX_VALUE);
        var namespaces = StreamRouteFixtures.registryWith(address(), RETENTION);

        var response = routes(serving, true, namespaces).deleteStream(NAMESPACE, STREAM, VERSION)
                                                        .await()
                                                        .onFailure(cause -> fail("the catalog knows the stream, so the delete is answered: " + cause.message()))
                                                        .unwrap();

        assertThat(response.status()).isEqualTo("deleted");
        assertThat(namespaces.lookup(address()).isEmpty()).as("catalog reference released").isTrue();
    }

    /// The delete retires the catalog entry once: after it, the catalog no longer knows the stream, so a second delete at the
    /// same node answers "not found", as it would at the owner.
    @Test
    void delete_secondDeleteAtNonOwner_afterTheCatalogEntryIsReleased_isStreamNotFound() {
        var serving = serving(Long.MAX_VALUE);
        var routes = routes(serving, false, StreamRouteFixtures.registryWith(address(), RETENTION));

        routes.deleteStream(NAMESPACE, STREAM, VERSION)
              .await()
              .onFailure(cause -> fail("control: the first delete is answered: " + cause.message()));

        var failure = routes.deleteStream(NAMESPACE, STREAM, VERSION)
                            .await()
                            .fold(cause -> cause, _ -> fail("a stream already deleted must not read as deleted again"));

        assertThat(failure).isInstanceOf(StreamError.StreamNotFound.class);
    }

    /// The control: the catalog does not know the stream either, so the answer is "not found" at every node.
    @Test
    void delete_streamUnknownToTheCatalogAndToTheNode_isStreamNotFound() {
        var serving = serving(Long.MAX_VALUE);

        var failure = routes(serving, false, StreamNamespacesService.inMemory()).deleteStream(NAMESPACE, STREAM, VERSION)
                                                                              .await()
                                                                              .fold(cause -> cause, _ -> fail("an unknown stream must not read as deleted"));

        assertThat(failure).isInstanceOf(StreamError.StreamNotFound.class);
    }

    // === helpers ===

    private PublishApi publishApi(StreamApiRoutes routes) {
        return partition -> routes.publishEvent(NAMESPACE, STREAM, VERSION, new PublishRequest("payload", partition))
                                  .await()
                                  .onFailure(cause -> fail("publish must be answered like the owner would: " + cause.message()))
                                  .unwrap();
    }

    private StreamApiRoutes.PublishResponse publish(StreamApiRoutes routes, int partition) {
        return publishApi(routes).publish(partition);
    }

    private interface PublishApi {
        StreamApiRoutes.PublishResponse publish(int partition);
    }

    private StreamPartitionManager serving(long capacity) {
        var manager = StreamPartitionManager.streamPartitionManager(capacity);

        opened.add(manager);
        return manager;
    }

    private StreamApiRoutes routes(StreamPartitionManager serving, boolean committed, boolean registered) {
        return routes(serving,
                      committed,
                      registered
                      ? StreamRouteFixtures.registryWith(address(), RETENTION)
                      : StreamNamespacesService.inMemory());
    }

    private StreamApiRoutes routes(StreamPartitionManager serving, boolean committed, StreamNamespacesService namespaces) {
        return routes(serving, committed, namespaces, forwardingWriter(serving));
    }

    private StreamApiRoutes routes(StreamPartitionManager serving,
                                   boolean committed,
                                   StreamNamespacesService namespaces,
                                   StreamWriteRouter writer) {
        var store = committed
                    ? StreamRouteFixtures.storeCommitting(engineKey, config(DECLARED))
                    : StreamRouteFixtures.emptyStore();
        var node = StreamRouteFixtures.node(serving, StreamReadRouter.localOnly(serving), writer, store);

        return StreamApiRoutes.streamApiRoutes(() -> node,
                                               namespaces,
                                               ConsumerGroupCoordinator.noOp(),
                                               ConsumerGroupRegistry.consumerGroupRegistry());
    }

    private StreamConfig config(int partitions) {
        return StreamConfig.streamConfig(engineKey, partitions, RETENTION, "earliest");
    }

    /// The transport is the wire: a PublishForward from SERVING is recorded and answered by OWNER with its next offset.
    private StreamWriteRouter forwardingWriter(StreamPartitionManager serving) {
        var clientRef = new StreamForwardClient[1];
        StreamForwardTransport servingToOwner = (target, message) -> {
            var publish = (PublishForward) message;

            forwarded.add(new Forwarded(publish.streamName(), publish.partition()));
            clientRef[0].onPublishForwardResponse(PublishForwardResponse.successResponse(OWNER,
                                                                                         publish.correlationId(),
                                                                                         ownerOffsets.getAndIncrement()));
            return Promise.success(new WriteOutcome.Sent(target));
        };

        clientRef[0] = StreamForwardClient.streamForwardClient(SERVING, servingToOwner, TimeSpan.timeSpan(5).seconds());

        return StreamWriteRouter.streamWriteRouter(serving, Option.some(clientRef[0]), SERVING, (_, _) -> Option.some(OWNER));
    }

    private static ResourceAddress address() {
        return ResourceAddress.resourceAddress(NAMESPACE, STREAM, VERSION).unwrap();
    }
}
