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
import org.pragmatica.aether.slice.stream.RemovalCause;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.segment.SegmentError;
import org.pragmatica.aether.stream.forward.ReadRefusal;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.storage.StorageError;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// #1921: no [StreamError] carried an HTTP status, so every engine refusal on a stream read route answered 500, including a
/// partition the stream does not have (`partition=99` on a 2-partition stream). One exhaustive mapper now classifies them.
/// The table is checked against the sealed hierarchy itself: a variant or a `General` constant with no row fails
/// `everyVariant_hasARow`, so adding an error type forces a decision in the mapper (compile) and in this table (test).
class StreamErrorStatusTest {
    private static final Epoch EPOCH = Epoch.epoch(1, 1, 1);
    private static final NodeId NODE = NodeId.nodeId("node-1").unwrap();
    private static final Path WAL = Path.of("/tmp/wal");

    static Map<Object, HttpStatus> expectations() {
        var all = new java.util.LinkedHashMap<Object, HttpStatus>();

        for (var general : StreamError.General.values()) {
            all.put(general, switch (general) {
                case EVENT_DROPPED, AHSE_REQUIRED_FOR_STRONG -> HttpStatus.BAD_REQUEST;
                case CONSUMER_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case STREAM_ALREADY_EXISTS, CONSUMER_ALREADY_SUBSCRIBED -> HttpStatus.CONFLICT;
                case BUFFER_CLOSED, STREAM_CLOSED, CONSUMER_RUNTIME_CLOSED, CONSUMER_STALLED, STREAM_MEMORY_EXCEEDED, SEALING_BEHIND,
                     SEGMENT_TIER_FULL, REPLICATION_BEHIND, BUFFER_FULL, CONSENSUS_PATH_UNAVAILABLE, PARTITION_NOT_LOCAL -> HttpStatus.SERVICE_UNAVAILABLE;
                case BUFFER_EMPTY, UNREADABLE_CONSISTENCY_MODE, STREAM_CONFIG_COMMIT_FAILED, RUN_DOES_NOT_FIT -> HttpStatus.INTERNAL_SERVER_ERROR;
            });
        }

        all.put(new StreamError.EventTooLarge(10, 5), HttpStatus.BAD_REQUEST);
        all.put(new StreamError.CursorExpired(1, 5), HttpStatus.GONE);
        all.put(new StreamError.CursorExpired(1, 5, StreamError.CursorExpired.Reason.LOST), HttpStatus.GONE);
        all.put(new StreamError.StreamNotMaterialized("s"), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.BelowCopyTail(1, 5), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.EpochDiverged(EPOCH, 3, 3), HttpStatus.CONFLICT);
        all.put(new StreamError.SeedRejected(1, 2), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.WalReplayMismatch("s", 0, WAL, 1, 3), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.WalHeadLost("s", 0, WAL, 1, 3), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.StreamNotFound("s"), HttpStatus.NOT_FOUND);
        all.put(new StreamError.SystemStreamWriteRefused("system:s:1.0.0"), HttpStatus.METHOD_NOT_ALLOWED);
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
        all.put(new StreamError.StreamStorageUnknown("s"), HttpStatus.CONFLICT);
        all.put(new StreamError.PartitionCapExceeded("s", 1, 1, 1, 1), HttpStatus.BAD_REQUEST);
        all.put(new StreamError.StaleEpochAppend("s", 0, EPOCH, EPOCH), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.ProvenanceRegression("s", 0, EPOCH, EPOCH), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.ProvenanceMismatch("s", 0, 1), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.ReplicaOffsetGap("s", 0, 1, 2), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.ReplicaEntryConflict("s", 0, 1), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.ReplicaQuarantined("s", 0, 1, 2), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.NotOwnerAppend("s", 0, NODE), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.OwnerNotActivated("s", 0), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.OwnerLeaseLapsed("s", 0), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.OwnershipMovedBeforeAck("s", 0), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.OwnerLeaseLapsedBeforeAck("s", 0), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.NotCurrentOwner("s", 0, NODE, NODE), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.StaleEpochRead("s", 0, EPOCH, EPOCH), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.OwnerCatchupPending("s", 0), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.LinearizableRoundTimeout("s", 0), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.ReplicaNotVerified("s", 0, 3L), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.RepairNotAuthorized("s", 0, 3L), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.RepairWitnessFailed("s", 0, "r"), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.RepairPreserveFailed("s", 0, "r"), HttpStatus.SERVICE_UNAVAILABLE);
        all.put(new StreamError.DivergenceNotEstablished("s", 0, 3L), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.TruncateBelowRetained("s", 0, 1L, 5L), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.TruncateBelowVisible("s", 0, 1L, 5L), HttpStatus.INTERNAL_SERVER_ERROR);
        all.put(new StreamError.PartitionFailStopped("s", 0, Causes.cause("c")), HttpStatus.SERVICE_UNAVAILABLE);

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

    /// stream-mem M-1b-delta: a cursor refusal answers 410 whatever removal it names, for every cause a `CursorExpired` can carry
    /// (all but `NOT_APPLICABLE`, which its constructor refuses) under every reason that cause goes with. Mutant: a status chosen
    /// by cause -> RED.
    @Test
    void aCursorExpired_answers410_forEveryCauseItCanCarry() {
        for (var cause : RemovalCause.values()) {
            for (var reason : StreamError.CursorExpired.Reason.values()) {
                if (cause != RemovalCause.NOT_APPLICABLE && StreamError.CursorExpired.reasonOf(cause).fold(() -> true, fixed -> fixed == reason)) {
                    assertThat(StreamErrorStatus.statusOf(new StreamError.CursorExpired(1L, 5L, reason, cause))).as("%s %s", reason, cause)
                                                                                                                 .isEqualTo(HttpStatus.GONE);
                }
            }
        }
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

    /// T-P8's status arms and T-P12(b), stream-mem M-1b: the ONE answer switch reaches the status of every failed read.
    @Nested
    class AnswerStatus {
        /// The kinds whose cause is a `SegmentError` or the rebuilt text of `UNKNOWN`; the pin's switch has no row for them.
        private static final Set<ReadRefusal.Kind> NOT_STREAM_ERROR_KINDS = Set.of(ReadRefusal.Kind.SEAL_IN_FLIGHT,
                                                                                   ReadRefusal.Kind.SEALED_RANGE_MISSING,
                                                                                   ReadRefusal.Kind.UNKNOWN);

        /// Arm (1), the status half: every row has a status, counted against `values()`.
        @Test
        void everyRow_hasAStatus() {
            var statuses = Arrays.stream(ReadRefusal.Answer.values()).map(StreamErrorStatus::statusOf).toList();

            assertThat(statuses).hasSize(ReadRefusal.Answer.values().length).doesNotContainNull();
        }

        /// Arm (2): every kind whose cause is a `StreamError` has the pin's status; the row's status is tied to the pin's
        /// switch, not to a literal. Plus (5b): `L_EPOCH` = `EpochDiverged`'s, the NN rows share one status, `FAULT_DEGRADE` = `FAULT`.
        @Test
        void everyStreamErrorKind_hasThePinsStatus_andTheRowsAreTiedToIt() {
            var checked = 0;

            for (var kind : ReadRefusal.Kind.values()) {
                if (causeOf(kind) instanceof StreamError error) {
                    assertThat(StreamErrorStatus.statusOf(kind.answer())).as(kind.name()).isEqualTo(StreamErrorStatus.statusOf(error));
                    checked++;
                } else {
                    assertThat(NOT_STREAM_ERROR_KINDS).as("a kind whose cause is not a StreamError: " + kind).contains(kind);
                }
            }

            assertThat(checked).as("kinds whose cause is a StreamError").isEqualTo(ReadRefusal.Kind.values().length - NOT_STREAM_ERROR_KINDS.size());
            assertThat(StreamErrorStatus.statusOf(ReadRefusal.Answer.L_EPOCH)).isEqualTo(StreamErrorStatus.statusOf(new StreamError.EpochDiverged(EPOCH, 3, 3)));
            assertThat(StreamErrorStatus.statusOf(ReadRefusal.Answer.NN_DEGRADE)).isEqualTo(StreamErrorStatus.statusOf(ReadRefusal.Answer.NN_SURFACE));
            assertThat(StreamErrorStatus.statusOf(ReadRefusal.Answer.NN_RETRY)).isEqualTo(StreamErrorStatus.statusOf(ReadRefusal.Answer.NN_SURFACE));
            assertThat(StreamErrorStatus.statusOf(ReadRefusal.Answer.FAULT_DEGRADE)).isEqualTo(StreamErrorStatus.statusOf(ReadRefusal.Answer.FAULT));
        }

        /// W-4 (stream-mem): the neutral "this copy never held it" answers 503 as a record and through its row `NN_RETRY`, as
        /// literals (a derived expectation cannot see a wrong group). Mutant: `BelowCopyTail` moved to another group -> RED.
        @Test
        void belowCopyTail_answers503_asARecord_andThroughItsRetryRow() {
            assertThat(StreamErrorStatus.statusOf(new StreamError.BelowCopyTail(5L, 9L))).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(StreamErrorStatus.statusOf(ReadRefusal.Answer.NN_RETRY)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(ReadRefusal.answerOf(new StreamError.BelowCopyTail(5L, 9L))).isEqualTo(ReadRefusal.Answer.NN_RETRY);
        }

        /// Arm (4): the row reaches the caller, for every sample cause, with no test of the cause's type.
        @Test
        void typedRead_isAStreamRefusedHoldingTheCause_underTheStatusOfItsRow() {
            var samples = new ArrayList<Cause>();

            for (var kind : ReadRefusal.Kind.values()) {
                samples.add(causeOf(kind));
            }

            samples.add(new StreamError.EpochDiverged(EPOCH, 3, 3));
            samples.addAll(Arrays.asList(StreamForwardError.General.values()));
            samples.add(new StreamForwardError.ReadForwardFailed("d"));
            samples.add(new StreamError.WalReplayMismatch("s", 0, WAL, 1, 3));
            samples.add(new SegmentError.WalRangeMissing("s", 0, 1L, 2L, 0));
            samples.add(new StorageError.ReadError("disk"));

            for (var cause : samples) {
                var typed = StreamErrorStatus.typedRead(cause);

                assertThat(typed).as(cause.message()).isInstanceOf(ManagementServerError.StreamRefused.class);

                var refused = (ManagementServerError.StreamRefused) typed;

                assertThat(refused.refusal()).isSameAs(cause);
                assertThat(refused.status()).as(cause.message()).isEqualTo(decidedOrRowStatus(cause));
            }
        }

        /// The one invariant: a StreamError's pinned status is decided, every other cause takes its row.
        private static HttpStatus decidedOrRowStatus(Cause cause) {
            return cause instanceof StreamError error
                   ? StreamErrorStatus.statusOf(error)
                   : StreamErrorStatus.statusOf(ReadRefusal.answerOf(cause));
        }

        private static List<StreamError> pinnedAt500() {
            return List.of(new StreamError.WalReplayMismatch("orders", 0, WAL, 1L, 2L),
                           new StreamError.WalHeadLost("orders", 0, WAL, 5L, 9L),
                           new StreamError.SeedRejected(3L, 4L),
                           new StreamError.ProvenanceMismatch("orders", 0, 7L),
                           StreamError.General.BUFFER_EMPTY);
        }

        /// Item 1 (#3481 fix round): a cause with a decided status passes through UNCHANGED. A route's own `StreamRefused(NOT_FOUND)`
        /// is the same instance afterwards, on the helper and on route `:615`. Mutant: `typedRead` re-rows every cause (503).
        @Test
        void anAlreadyTypedCause_keepsItsStatus() {
            var decided = new ManagementServerError.StreamRefused(HttpStatus.NOT_FOUND, new StreamError.StreamNotFound("ns/orders"));

            assertThat(StreamErrorStatus.typedRead((Cause) decided)).isSameAs(decided);
            assertThat(statusOfReadFailing(decided)).isEqualTo(HttpStatus.NOT_FOUND);
        }

        /// Item 1: a kindless StreamError pinned at 500 stays 500 on the helper and on routes `:615` and `:716`, holding the
        /// cause itself (not nested). Mutant: re-row it under `UNKNOWN`'s 503.
        @Test
        void aStreamErrorPinnedAt500_keeps500_onTheHelperAndOnBothReadRoutes() {
            for (var error : pinnedAt500()) {
                assertThat(StreamErrorStatus.statusOf(error)).as(error.message()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(((ManagementServerError.StreamRefused) StreamErrorStatus.typedRead((Cause) error)).status()).as(error.message())
                                                                                                                      .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(statusOfReadFailing(error)).as(":615 " + error.message()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                assertThat(statusOfEventsReadFailing(error)).as(":716 " + error.message()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }

        /// Item 1: a cause outside both decided classes takes the `UNKNOWN` row, 503, on the helper and both routes.
        @Test
        void aStorageError_takesTheUnknownRow_503() {
            var storage = new StorageError.ReadError("disk");

            assertThat(((ManagementServerError.StreamRefused) StreamErrorStatus.typedRead((Cause) storage)).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(statusOfReadFailing(storage)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(statusOfEventsReadFailing(storage)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        /// Item 1: nothing is nested. A second pass is the same instance, and no `StreamRefused` holds a `StreamRefused`.
        /// Mutant: re-wrap an `HttpStatusAware` cause.
        @Test
        void typedRead_neverNests() {
            var causes = new ArrayList<Cause>(pinnedAt500());

            causes.add(new StorageError.ReadError("disk"));
            causes.add(new StreamError.CursorExpired(1L, 2L, StreamError.CursorExpired.Reason.LOST));
            causes.add(new ManagementServerError.StreamRefused(HttpStatus.SERVICE_UNAVAILABLE, new StreamError.StreamNotFound("ns/orders")));

            for (var cause : causes) {
                var once = StreamErrorStatus.typedRead(cause);
                var twice = StreamErrorStatus.typedRead(once);

                assertThat(twice).as(cause.message()).isSameAs(once);
                assertThat(((ManagementServerError.StreamRefused) once).refusal()).as(cause.message())
                                                                                  .isNotInstanceOf(ManagementServerError.StreamRefused.class);
            }
        }

        /// T-P12(b): route `:615` read twice with a stubbed router, failing with X (the local path) and with W (what the
        /// forward leaves of X). Both answer the same status, which is the row's. Mutants: `typed` at `StreamApiRoutes:630`
        /// (X1 and X2 read the funnel's status); the pin's `statusOf(StreamError)` for X3 (500 on the local path).
        @Test
        void aKindlessCause_answersTheSameStatusLocallyAndOnceForwarded() {
            var kindless = List.<Cause> of(new StorageError.ReadError("disk"),
                                           new SegmentError.WalRangeMissing("orders", 0, 1L, 2L, 0));

            for (var x : kindless) {
                var forwarded = new StreamForwardError.ReadForwardFailed(x.message());
                var local = statusOfReadFailing(x);

                assertThat(statusOfReadFailing(forwarded)).as(x.message()).isEqualTo(local);
                assertThat(local).as(x.message()).isEqualTo(StreamErrorStatus.statusOf(ReadRefusal.answerOf(x)));
            }
        }

        /// N1 pin (green before and after M-1b): route `:716`, the catalog confirms the stream and the read refuses with
        /// `StreamNotFound`: 503 with the pin's text, not the typed 404.
        @Test
        void streamsEvents_streamNotFoundFromTheRead_stays503WithThePinsText() {
            var manager = StreamPartitionManager.streamPartitionManager();
            var namespaces = org.pragmatica.aether.slice.stream.StreamNamespacesService.inMemory();
            var address = ResourceAddress.resourceAddress("ns", "orders", "1.0.0").unwrap();

            try {
                namespaces.registry()
                          .register(org.pragmatica.aether.slice.stream.StreamRegistryEntry.operator(address,
                                                                                                   RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000),
                                                                                                   java.time.Instant.now()))
                          .onFailure(cause -> fail(cause.message()));

                var failure = RouteProbe.failureOf(apiRoutes(manager, namespaces, routerFailingWith(new StreamError.StreamNotFound("ns/orders"))),
                                                   ManagementRoute.STREAMS_EVENTS,
                                                   List.of("ns", "orders", "1.0.0", "events"),
                                                   Map.of());

                assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(failure.message()).contains("is registered but not materialized on this node yet");
            } finally {
                manager.close();
            }
        }

        private static Cause causeOf(ReadRefusal.Kind kind) {
            return new ReadRefusal(kind, "orders", 1, 0L, 0L, false, "", "p1", "p2", Epoch.ZERO, Epoch.ZERO).toCause("");
        }

        /// Route `:716` (STREAMS_EVENTS) with the catalog confirming the stream and the read failing with `cause`.
        private HttpStatus statusOfEventsReadFailing(Cause cause) {
            var manager = StreamPartitionManager.streamPartitionManager();
            var namespaces = org.pragmatica.aether.slice.stream.StreamNamespacesService.inMemory();
            var address = ResourceAddress.resourceAddress("ns", "orders", "1.0.0").unwrap();

            try {
                namespaces.registry()
                          .register(org.pragmatica.aether.slice.stream.StreamRegistryEntry.operator(address,
                                                                                                   RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000),
                                                                                                   java.time.Instant.now()))
                          .onFailure(failure -> fail(failure.message()));

                return RouteProbe.problemStatus(RouteProbe.failureOf(apiRoutes(manager, namespaces, routerFailingWith(cause)),
                                                                     ManagementRoute.STREAMS_EVENTS,
                                                                     List.of("ns", "orders", "1.0.0", "events"),
                                                                     Map.of()));
            } finally {
                manager.close();
            }
        }

        private HttpStatus statusOfReadFailing(Cause cause) {
            var manager = StreamPartitionManager.streamPartitionManager();

            try {
                var failure = RouteProbe.failureOf(apiRoutes(manager,
                                                             org.pragmatica.aether.slice.stream.StreamNamespacesService.inMemory(),
                                                             routerFailingWith(cause)),
                                                   ManagementRoute.STREAM_READ,
                                                   List.of("ns", "orders", "1.0.0", "read", "0"),
                                                   Map.of());

                return RouteProbe.problemStatus(failure);
            } finally {
                manager.close();
            }
        }

        private static StreamReadRouter routerFailingWith(Cause cause) {
            var router = mock(StreamReadRouter.class);

            when(router.read(any(), anyInt(), anyLong(), anyInt(), any())).thenAnswer(_ -> cause.promise());

            return router;
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
        return apiRoutes(manager, namespaces, org.pragmatica.aether.stream.StreamReadRouter.localOnly(manager));
    }

    private static Stream<org.pragmatica.http.routing.Route<?>> apiRoutes(StreamPartitionManager manager,
                                                                         org.pragmatica.aether.slice.stream.StreamNamespacesService namespaces,
                                                                         StreamReadRouter router) {
        var node = (ManageableNode) java.lang.reflect.Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                                             new Class[]{ManageableNode.class},
                                                                             (_, method, _) -> {
                                                                                 if (method.getName().equals("streamPartitionManager")) {
                                                                                     return manager;
                                                                                 }

                                                                                 if (method.getName().equals("streamReadRouter")) {
                                                                                     return router;
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
