// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1921: no [StreamError] carried an HTTP status, so every engine refusal on a stream read route answered 500, including a
/// partition the stream does not have (`partition=99` on a 2-partition stream). One exhaustive mapper now classifies them.
/// The table is checked against the sealed hierarchy itself: a variant or a `General` constant with no row fails
/// `everyVariant_hasARow`, so adding an error type forces a decision in the mapper (compile) and in this table (test).
class StreamErrorStatusTest {
    private static final Epoch EPOCH = Epoch.epoch(1, 1, 1);
    private static final NodeId NODE = NodeId.nodeId("node-1").unwrap();
    private static final Path WAL = Path.of("/tmp/wal");

    private static Map<Object, HttpStatus> expectations() {
        var all = new java.util.LinkedHashMap<Object, HttpStatus>();

        for (var general : StreamError.General.values()) {
            all.put(general, switch (general) {
                case EVENT_DROPPED, AHSE_REQUIRED_FOR_STRONG -> HttpStatus.BAD_REQUEST;
                case CONSUMER_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case STREAM_ALREADY_EXISTS, CONSUMER_ALREADY_SUBSCRIBED -> HttpStatus.CONFLICT;
                case BUFFER_CLOSED, STREAM_CLOSED, CONSUMER_RUNTIME_CLOSED, CONSUMER_STALLED, STREAM_MEMORY_EXCEEDED, SEALING_BEHIND,
                     SEGMENT_TIER_FULL, BUFFER_FULL, CONSENSUS_PATH_UNAVAILABLE, PARTITION_NOT_LOCAL -> HttpStatus.SERVICE_UNAVAILABLE;
                case BUFFER_EMPTY, UNREADABLE_CONSISTENCY_MODE, STREAM_CONFIG_COMMIT_FAILED, RUN_DOES_NOT_FIT -> HttpStatus.INTERNAL_SERVER_ERROR;
            });
        }

        all.put(new StreamError.EventTooLarge(10, 5), HttpStatus.BAD_REQUEST);
        all.put(new StreamError.CursorExpired(1, 5), HttpStatus.GONE);
        all.put(new StreamError.EpochDiverged(EPOCH, 3, 3), HttpStatus.CONFLICT);
        all.put(new StreamError.SeedRejected(1, 2), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.WalReplayMismatch("s", 0, WAL, 1, 3), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.WalHeadLost("s", 0, WAL, 1, 3), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.StreamNotFound("s"), HttpStatus.NOT_FOUND);
        all.put(new StreamError.StreamConfigNotYetVisible("s"), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.PartitionOutOfRange("s", 99, 2), HttpStatus.BAD_REQUEST);
        all.put(new StreamError.MaterializeBudgetExceeded("s", 0, 1, 0, 1), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.ReshufflePaced("s", 0, 2), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.PartitionHeldNotMaterialized("s", 0, -1, false), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.RingIndexCorrupted("s", 0, "d"), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.EventProcessingFailed("s", 0, 1, "r"), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.PartitionCeilingExceeded("s", 9, 4), HttpStatus.BAD_REQUEST);
        all.put(new StreamError.RetentionCountUnindexable("s", 1, 2), HttpStatus.BAD_REQUEST);
        all.put(new StreamError.RetentionBoundInvalid("s", "b", -1), HttpStatus.BAD_REQUEST);
        all.put(new StreamError.ReplicationRefused("s", Causes.cause("c")), HttpStatus.CONFLICT);
        all.put(new StreamError.PartitionCapExceeded("s", 1, 1, 1, 1), HttpStatus.BAD_REQUEST);
        all.put(new StreamError.StaleEpochAppend("s", 0, EPOCH, EPOCH), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.ProvenanceRegression("s", 0, EPOCH, EPOCH), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.ProvenanceMismatch("s", 0, 1), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.ReplicaOffsetGap("s", 0, 1, 2), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.ReplicaEntryConflict("s", 0, 1), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.ReplicaQuarantined("s", 0, 1, 2), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.NotOwnerAppend("s", 0, NODE), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.OwnerNotActivated("s", 0), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.NotCurrentOwner("s", 0, NODE, NODE), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.StaleEpochRead("s", 0, EPOCH, EPOCH), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.OwnerCatchupPending("s", 0), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.LinearizableRoundTimeout("s", 0), HttpStatus.SERVICE_UNAVAILABLE);

        return all;
    }

    @TestFactory
    Stream<DynamicTest> everyVariant_answersItsClassOfStatus() {
        return expectations().entrySet()
                             .stream()
                             .map(row -> DynamicTest.dynamicTest(row.getKey().getClass().getSimpleName() + " " + describe(row.getKey()),
                                                                 () -> assertThat(StreamErrorStatus.statusOf((StreamError) row.getKey())).isEqualTo(row.getValue())));
    }

    /// The table cannot silently fall behind the hierarchy: every permitted subtype, and every `General` constant, has a row.
    @Test
    void everyVariant_hasARow() {
        var covered = new HashSet<Class<?>>();

        expectations().keySet().forEach(key -> covered.add(key instanceof StreamError.General ? StreamError.General.class : key.getClass()));

        var permitted = new HashSet<>(Arrays.asList(StreamError.class.getPermittedSubclasses()));

        assertThat(permitted).as("permitted subtypes of StreamError").isNotEmpty();
        assertThat(covered).containsExactlyInAnyOrderElementsOf(permitted);
        assertThat(expectations().keySet().stream().filter(StreamError.General.class::isInstance).count())
                .isEqualTo(StreamError.General.values().length);
    }

    @Test
    void typed_keepsTheEnginesMessage_andLeavesAnyOtherCauseAlone() {
        Cause untyped = Causes.cause("boom");
        var refused = StreamErrorStatus.typed(new StreamError.PartitionOutOfRange("s", 99, 2));

        assertThat(refused.message()).isEqualTo(new StreamError.PartitionOutOfRange("s", 99, 2).message());
        assertThat(StreamErrorStatus.typed(untyped)).isSameAs(untyped);
    }

    /// Driven through the real route handlers: `partition=99` on a 2-partition stream, which measured 500.
    @Test
    void streamPartition_answers400_whenThePartitionIsOutOfRange() {
        var manager = streamWith2Partitions();

        try {
            var failure = RouteProbe.failureOf(apiRoutes(manager), ManagementRoute.STREAM_PARTITION, List.of("ns", "orders", "1.0.0", "partitions", "99"), Map.of());

            assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            manager.close();
        }
    }

    /// Control: an in-range partition of the same stream is served, so the 400 is the range check and not a blanket refusal.
    @Test
    void streamPartition_answersThePartition_whenItIsInRange() {
        var manager = streamWith2Partitions();

        try {
            RouteProbe.run(apiRoutes(manager), ManagementRoute.STREAM_PARTITION, List.of("ns", "orders", "1.0.0", "partitions", "1"), Map.of())
                      .onFailure(cause -> fail("an in-range partition must be served: " + cause.message()));
        } finally {
            manager.close();
        }
    }

    /// The read route reaches the engine through the read router, a different path from `STREAM_PARTITION`'s direct call.
    @Test
    void streamRead_answers400_whenThePartitionIsOutOfRange() {
        var manager = streamWith2Partitions();

        try {
            var failure = RouteProbe.failureOf(apiRoutes(manager), ManagementRoute.STREAM_READ, List.of("ns", "orders", "1.0.0", "read", "99"), Map.of());

            assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            manager.close();
        }
    }

    /// `STREAMS_EVENTS` is its own read path (catalog lookup first, then the router). When the catalog CONFIRMED the stream exists,
    /// the engine's `StreamNotFound` means "not materialized on this node", not "unknown stream": 503, retry. (This test first
    /// pinned 404 here, which encoded the defect: it told the caller a stream the catalog just confirmed does not exist.)
    @Test
    void streamsEvents_answers503_whenTheCatalogKnowsTheStreamButTheEngineDoesNot() {
        var manager = StreamPartitionManager.streamPartitionManager();
        var namespaces = org.pragmatica.aether.slice.stream.StreamNamespacesService.inMemory();
        var address = ResourceAddress.resourceAddress("ns", "orders", "1.0.0").unwrap();

        try {
            namespaces.registry()
                      .register(org.pragmatica.aether.slice.stream.StreamRegistryEntry.operator(address,
                                                                                               RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000),
                                                                                               java.time.Instant.now()))
                      .onFailure(cause -> fail(cause.message()));

            var failure = RouteProbe.failureOf(apiRoutes(manager, namespaces), ManagementRoute.STREAMS_EVENTS, List.of("ns", "orders", "1.0.0", "events"), Map.of());

            assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        } finally {
            manager.close();
        }
    }

    /// An unregistered stream stays 404: the catalog is what says unknown.
    @Test
    void streamsEvents_answers404_whenTheCatalogDoesNotKnowTheStream() {
        var manager = StreamPartitionManager.streamPartitionManager();

        try {
            var failure = RouteProbe.failureOf(apiRoutes(manager), ManagementRoute.STREAMS_EVENTS, List.of("ns", "orders", "1.0.0", "events"), Map.of());

            assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.NOT_FOUND);
        } finally {
            manager.close();
        }
    }

    /// M2: retention has rolled the stream's tail past offset 0. A read that supplied NO offset must start at the earliest
    /// retained one; defaulting to 0 answered 410 to a caller who asked for nothing in particular. An EXPLICIT offset that
    /// retention has reclaimed keeps its 410.
    @Test
    void streamsEvents_defaultRead_startsAtTheEarliestRetainedOffset_onARetentionRolledStream() {
        withRolledStream((manager, namespaces) -> RouteProbe.run(apiRoutes(manager, namespaces), ManagementRoute.STREAMS_EVENTS, EVENTS_PATH, Map.of())
                                                          .onFailure(cause -> fail("a default read must succeed, got: " + cause.message()))
                                                          .onSuccess(value -> assertThat(value).isInstanceOfSatisfying(StreamApiRoutes.StreamEventsResponse.class,
                                                                                                                       response -> assertThat(response.events()).isNotEmpty())));
    }

    @Test
    void streamsEvents_explicitExpiredOffset_answers410() {
        withRolledStream((manager, namespaces) -> assertThat(RouteProbe.problemStatus(RouteProbe.failureOf(apiRoutes(manager, namespaces),
                                                                                                           ManagementRoute.STREAMS_EVENTS,
                                                                                                           EVENTS_PATH,
                                                                                                           Map.of("fromOffset", List.of("0")))))
                .isEqualTo(HttpStatus.GONE));
    }

    @Test
    void streamRead_defaultRead_startsAtTheEarliestRetainedOffset_onARetentionRolledStream() {
        withRolledStream((manager, namespaces) -> RouteProbe.run(apiRoutes(manager, namespaces),
                                                                 ManagementRoute.STREAM_READ,
                                                                 List.of("ns", "orders", "1.0.0", "read", "0"),
                                                                 Map.of())
                                                          .onFailure(cause -> fail("a default read must succeed, got: " + cause.message()))
                                                          .onSuccess(value -> assertThat(value).isInstanceOfSatisfying(StreamApiRoutes.ReadEventsResponse.class,
                                                                                                                       response -> assertThat(response.events()).isNotEmpty())));
    }

    @Test
    void streamRead_explicitExpiredOffset_answers410() {
        withRolledStream((manager, namespaces) -> assertThat(RouteProbe.problemStatus(RouteProbe.failureOf(apiRoutes(manager, namespaces),
                                                                                                           ManagementRoute.STREAM_READ,
                                                                                                           List.of("ns", "orders", "1.0.0", "read", "0"),
                                                                                                           Map.of("from", List.of("0")))))
                .isEqualTo(HttpStatus.GONE));
    }

    private static final List<String> EVENTS_PATH = List.of("ns", "orders", "1.0.0", "events");

    private static void withRolledStream(java.util.function.BiConsumer<StreamPartitionManager, org.pragmatica.aether.slice.stream.StreamNamespacesService> body) {
        var manager = StreamPartitionManager.streamPartitionManager();
        var namespaces = org.pragmatica.aether.slice.stream.StreamNamespacesService.inMemory();
        var address = ResourceAddress.resourceAddress("ns", "orders", "1.0.0").unwrap();
        var retention = RetentionPolicy.retentionPolicy(5, 1024 * 1024, 600_000);

        try {
            manager.createStream(StreamConfig.streamConfig(StreamManager.engineKey(address), 1, retention, "earliest")).onFailure(cause -> fail(cause.message()));
            namespaces.registry()
                      .register(org.pragmatica.aether.slice.stream.StreamRegistryEntry.operator(address, retention, java.time.Instant.now()))
                      .onFailure(cause -> fail(cause.message()));

            for (int i = 0; i < 30; i++) {
                manager.publishLocal(StreamManager.engineKey(address), 0, ("event-" + i).getBytes(), System.currentTimeMillis())
                       .onFailure(cause -> fail("publish: " + cause.message()));
            }

            assertThat(manager.partitionInfo(StreamManager.engineKey(address), 0).unwrap().tailOffset()).as("retention rolled the tail past 0").isGreaterThan(0);
            body.accept(manager, namespaces);
        } finally {
            manager.close();
        }
    }

    /// `STREAMS_EVENTS` must read the engine by `StreamManager#engineKey`, not the address string: the two differ only for the
    /// `system` namespace, where the engine keys the stream by its bare name. Read by the address string it answered the
    /// engine's StreamNotFound for a stream that exists.
    @Test
    void streamsEvents_readsASystemStream_underItsBareEngineKey() {
        var manager = StreamPartitionManager.streamPartitionManager();
        var namespaces = org.pragmatica.aether.slice.stream.StreamNamespacesService.inMemory();
        var address = ResourceAddress.resourceAddress(ResourceAddress.SYSTEM_NAMESPACE, "cluster-events", "1.0.0").unwrap();

        try {
            manager.createStream(StreamConfig.streamConfig(StreamManager.engineKey(address),
                                                           1,
                                                           RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000),
                                                           "earliest"))
                   .onFailure(cause -> fail(cause.message()));
            namespaces.registry()
                      .register(org.pragmatica.aether.slice.stream.StreamRegistryEntry.operator(address,
                                                                                               RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000),
                                                                                               java.time.Instant.now()))
                      .onFailure(cause -> fail("register: " + cause.message()));

            RouteProbe.run(apiRoutes(manager, namespaces),
                           ManagementRoute.STREAMS_EVENTS,
                           List.of(ResourceAddress.SYSTEM_NAMESPACE, "cluster-events", "1.0.0", "events"),
                           Map.of())
                      .onFailure(cause -> fail("a system stream must be readable by its address, got: " + cause.message()));
        } finally {
            manager.close();
        }
    }

    private static StreamPartitionManager streamWith2Partitions() {
        var manager = StreamPartitionManager.streamPartitionManager();
        var engineKey = StreamManager.engineKey(ResourceAddress.resourceAddress("ns", "orders", "1.0.0").unwrap());

        manager.createStream(StreamConfig.streamConfig(engineKey, 2, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000), "earliest"))
               .onFailure(cause -> fail(cause.message()));

        return manager;
    }

    private static Stream<org.pragmatica.http.routing.Route<?>> apiRoutes(StreamPartitionManager manager) {
        return apiRoutes(manager, org.pragmatica.aether.slice.stream.StreamNamespacesService.inMemory());
    }

    private static Stream<org.pragmatica.http.routing.Route<?>> apiRoutes(StreamPartitionManager manager,
                                                                         org.pragmatica.aether.slice.stream.StreamNamespacesService namespaces) {
        var node = (ManageableNode) java.lang.reflect.Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                                             new Class[]{ManageableNode.class},
                                                                             (_, method, _) -> {
                                                                                 if (method.getName().equals("streamPartitionManager")) {
                                                                                     return manager;
                                                                                 }

                                                                                 if (method.getName().equals("streamReadRouter")) {
                                                                                     return org.pragmatica.aether.stream.StreamReadRouter.localOnly(manager);
                                                                                 }
                                                                                 throw new UnsupportedOperationException(method.getName());
                                                                             });

        return StreamApiRoutes.streamApiRoutes(() -> node, namespaces, ConsumerGroupCoordinator.noOp(), null)
                              .routes();
    }

    private static String describe(Object key) {
        return key instanceof StreamError.General general
               ? general.name()
               : ((StreamError) key).message().substring(0, Math.min(40, ((StreamError) key).message().length()));
    }
}
