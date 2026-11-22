// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.CommittedStreamOwnerSource.CommittedOwner;
import org.pragmatica.aether.stream.ForwardingReadRouter.LocalReader;
import org.pragmatica.aether.stream.ForwardingReadRouter.OwnerResolver;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardClient.ReadForwardResult;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.PublishForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/// M-9f (stream-mem): a local read refused with [StreamError.BelowCopyTail] (the copy holds nothing at the offset asked) is
/// forwarded ONCE to the owner by both routers, and the owner's answer is the read's answer. Nothing raises `BelowCopyTail` in
/// production until M-2r-1, so the arm is reached through a scripted local reader ([ForwardingReadRouter.LocalReader]) and
/// [StreamReadRouter#withLocalValidatedRead]. Every arm asserts the answer AND the exact list of forward targets.
///
/// The fixture helpers ([Arm], [CountingClient], [#armRouter], [#assertFailsWith]) are shared: M-9b, M-9a2 and M-9e add their
/// arms to this file.
class BelowCopyTailForwardTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OWNER = new NodeId("hrw-owner");
    private static final NodeId COMMITTED = new NodeId("committed-owner");
    private static final NodeId REPLICA_A = new NodeId("replica-a");
    private static final NodeId REPLICA_B = new NodeId("replica-b");
    private static final String STREAM = "read-stream";
    private static final int PARTITION = 0;
    private static final Epoch EPOCH = Epoch.epoch(1L, 1L, 1L);
    private static final StreamError LOCAL_REFUSAL = new StreamError.BelowCopyTail(5, 9);
    private static final StreamError OWNER_REFUSAL = new StreamError.BelowCopyTail(7, 12);

    private final StreamPartitionManager partitions = StreamPartitionManager.streamPartitionManager();

    @AfterEach
    void tearDown() {
        partitions.close();
    }

    /// The first local read of each arm of [ForwardingReadRouter].
    private enum Arm {
        GOVERNOR_NOT_BEHIND,
        NEAREST_NO_REGISTRY,
        NEAREST_SELF_COVERS,
        NEAREST_NOT_COVERING,
        ANY_REPLICA_NO_REGISTRY,
        ANY_REPLICA_SELF_COVERS
    }

    // ---- (ii) A2, A3, A4, A5: one forward from the first local read ------------------------------------------------

    @Test
    void route_forwardsOnceToTheHrwOwner_onEveryArm_whenThereIsNoCommittedRecordAndTheOwnerServes() {
        for (var arm : Arm.values()) {
            var client = CountingClient.serving("owner-served");
            var router = armRouter(arm, LOCAL_REFUSAL, Option.some(client), owner(OWNER), Option.none());

            assertEvents(arm, router.route(STREAM, PARTITION, 0, 10), "owner-served");
            assertThat(client.targets).as(arm.name()).containsExactly(OWNER);
        }
    }

    @Test
    void route_forwardsOnceToTheCommittedOwner_onEveryArm_whenACommittedRecordNamesAnotherNode() {
        for (var arm : Arm.values()) {
            var client = CountingClient.serving("committed-served");
            var router = armRouter(arm, LOCAL_REFUSAL, Option.some(client), owner(OWNER), committedAt(COMMITTED));

            assertEvents(arm, router.route(STREAM, PARTITION, 0, 10), "committed-served");
            assertThat(client.targets).as(arm.name()).containsExactly(COMMITTED);
        }
    }

    /// The copy that answered IS the committed owner (its role view lags by one apply): no forward, and NO fall-through to
    /// the HRW owner, which `forwardStale` would take.
    @Test
    void route_doesNotForward_onEveryArm_whenTheCommittedOwnerIsSelf() {
        for (var arm : Arm.values()) {
            var client = CountingClient.serving("owner-served");
            var router = armRouter(arm, LOCAL_REFUSAL, Option.some(client), owner(OWNER), committedAt(SELF));

            assertFailsWith(arm, router.route(STREAM, PARTITION, 0, 10), LOCAL_REFUSAL);
            assertThat(client.targets).as(arm.name()).isEmpty();
        }
    }

    @Test
    void route_doesNotForward_onEveryArm_whenTheHrwOwnerIsSelf() {
        for (var arm : Arm.values()) {
            var client = CountingClient.serving("owner-served");
            var router = armRouter(arm, LOCAL_REFUSAL, Option.some(client), owner(SELF), Option.none());

            assertFailsWith(arm, router.route(STREAM, PARTITION, 0, 10), LOCAL_REFUSAL);
            assertThat(client.targets).as(arm.name()).isEmpty();
        }
    }

    /// With no target, or with no forward client, the original `BelowCopyTail` surfaces: never `PARTITION_NOT_LOCAL`, never
    /// an empty read.
    @Test
    void route_surfacesTheOriginalBelowCopyTail_onEveryArm_whenThereIsNoTargetOrNoClient() {
        for (var arm : Arm.values()) {
            var client = CountingClient.serving("owner-served");
            var noOwner = armRouter(arm, LOCAL_REFUSAL, Option.some(client), (_, _) -> Option.none(), Option.none());
            var noClient = armRouter(arm, LOCAL_REFUSAL, Option.none(), owner(OWNER), Option.none());

            assertFailsWith(arm, noOwner.route(STREAM, PARTITION, 0, 10), LOCAL_REFUSAL);
            assertFailsWith(arm, noClient.route(STREAM, PARTITION, 0, 10), LOCAL_REFUSAL);
            assertThat(client.targets).as(arm.name()).isEmpty();
        }
    }

    // ---- no second forward ----------------------------------------------------------------------------------------

    /// The target answers `BelowCopyTail`: a typed answer, returned as it is, after exactly ONE forward. A wrap of the arm
    /// around all of `route` would forward the answer that came back from the forward, and the list would have length 2.
    @Test
    void route_returnsTheTargetsBelowCopyTail_afterExactlyOneForward_onEveryArm() {
        for (var arm : Arm.values()) {
            var client = CountingClient.answering(_ -> OWNER_REFUSAL.<ReadForwardResult> promise());
            var router = armRouter(arm, LOCAL_REFUSAL, Option.some(client), owner(OWNER), Option.none());

            assertFailsWith(arm, router.route(STREAM, PARTITION, 0, 10), OWNER_REFUSAL);
            assertThat(client.targets).as(arm.name()).containsExactly(OWNER);
        }
    }

    /// ANY_REPLICA with two fresh remote replicas that each answer `BelowCopyTail`: today's two remote reads
    /// (`retryOrFail`), the answer is `BelowCopyTail`, and no third forward follows.
    @Test
    void route_makesNoThirdForward_whenBothAnyReplicaRemotesAnswerBelowCopyTail() {
        var registry = ReplicaRegistry.replicaRegistry();

        caughtUp(registry, REPLICA_A);
        caughtUp(registry, REPLICA_B);
        var client = CountingClient.answering(_ -> OWNER_REFUSAL.<ReadForwardResult> promise());
        var router = frr(ReadPreference.ANY_REPLICA, Option.some(registry), reader(LOCAL_REFUSAL), Option.some(client), owner(OWNER), Option.none());

        assertFailsWith("ANY_REPLICA two remotes", router.route(STREAM, PARTITION, 0, 10), OWNER_REFUSAL);
        assertThat(client.targets).containsExactlyInAnyOrder(REPLICA_A, REPLICA_B);
    }

    // ---- (v) the owner is unreachable ------------------------------------------------------------------------------

    /// A failed transport degrades exactly as today: the un-armed local read answers the same `BelowCopyTail` (503 by W-4's
    /// status), after one failed forward.
    @Test
    void route_answersTheLocalBelowCopyTail_afterOneFailedForward_onEveryArm() {
        for (var arm : Arm.values()) {
            var client = CountingClient.answering(_ -> new StreamForwardError.ReadForwardFailed("down").<ReadForwardResult> promise());
            var router = armRouter(arm, LOCAL_REFUSAL, Option.some(client), owner(OWNER), Option.none());

            assertFailsWith(arm, router.route(STREAM, PARTITION, 0, 10), LOCAL_REFUSAL);
            assertThat(client.targets).as(arm.name()).containsExactly(OWNER);
        }
    }

    // ---- only this type ---------------------------------------------------------------------------------------------

    /// A read inside the range a copy holds but below its tail is that copy's own `CursorExpired`: returned as at the tip.
    @Test
    void route_returnsCursorExpiredAsItIs_withZeroForwards_onEveryArm() {
        var expired = new StreamError.CursorExpired(3, 9);

        for (var arm : Arm.values()) {
            var client = CountingClient.serving("owner-served");
            var router = armRouter(arm, expired, Option.some(client), owner(OWNER), committedAt(COMMITTED));

            assertFailsWith(arm, router.route(STREAM, PARTITION, 0, 10), expired);
            assertThat(client.targets).as(arm.name()).isEmpty();
        }
    }

    // ---- (i) G1: StreamReadRouter.readValidated ------------------------------------------------------------------------

    @Test
    void readValidated_forwardsOnceToTheHrwOwner_andReturnsItsEvents() {
        var client = CountingClient.serving("owner-served");
        var router = validatedRouter(LOCAL_REFUSAL, Option.some(client), owner(OWNER));

        router.readValidated(STREAM, PARTITION, 0, 10, EPOCH)
              .await()
              .onFailure(BelowCopyTailForwardTest::failUnexpected)
              .onSuccess(read -> assertThat(read.events()).hasSize(1));
        assertThat(client.validatedTargets).containsExactly(OWNER);
        assertThat(client.targets).isEmpty();
    }

    @Test
    void readValidated_returnsTheTargetsBelowCopyTail_afterOneForward() {
        var client = CountingClient.answering(_ -> OWNER_REFUSAL.<ReadForwardResult> promise());
        var router = validatedRouter(LOCAL_REFUSAL, Option.some(client), owner(OWNER));

        assertFailsWith("G1", router.readValidated(STREAM, PARTITION, 0, 10, EPOCH), OWNER_REFUSAL);
        assertThat(client.validatedTargets).containsExactly(OWNER);
    }

    @Test
    void readValidated_surfacesTheOriginalBelowCopyTail_whenTheHrwOwnerIsSelf() {
        var client = CountingClient.serving("owner-served");
        var router = validatedRouter(LOCAL_REFUSAL, Option.some(client), owner(SELF));

        assertFailsWith("G1", router.readValidated(STREAM, PARTITION, 0, 10, EPOCH), LOCAL_REFUSAL);
        assertThat(client.validatedTargets).isEmpty();
    }

    /// The no-target branch returns the ORIGINAL cause: never `PARTITION_NOT_LOCAL`.
    @Test
    void readValidated_surfacesTheOriginalBelowCopyTail_whenThereIsNoOwnerOrNoClient() {
        var client = CountingClient.serving("owner-served");

        assertFailsWith("G1 no owner",
                        validatedRouter(LOCAL_REFUSAL, Option.some(client), (_, _) -> Option.none()).readValidated(STREAM, PARTITION, 0, 10, EPOCH),
                        LOCAL_REFUSAL);
        assertFailsWith("G1 no client",
                        validatedRouter(LOCAL_REFUSAL, Option.none(), owner(OWNER)).readValidated(STREAM, PARTITION, 0, 10, EPOCH),
                        LOCAL_REFUSAL);
        assertThat(client.validatedTargets).isEmpty();
    }

    /// A failed forward is NOT softened: the pass fails with that cause, with no local fallback and no empty read.
    @Test
    void readValidated_failsWithTheForwardsCause_whenTheForwardFails() {
        var failure = new StreamForwardError.ReadForwardFailed("down");
        var client = CountingClient.answering(_ -> failure.<ReadForwardResult> promise());
        var router = validatedRouter(LOCAL_REFUSAL, Option.some(client), owner(OWNER));

        assertFailsWith("G1", router.readValidated(STREAM, PARTITION, 0, 10, EPOCH), failure);
        assertThat(client.validatedTargets).containsExactly(OWNER);
    }

    @Test
    void readValidated_returnsCursorExpiredAsItIs_withZeroForwards() {
        var expired = new StreamError.CursorExpired(3, 9);
        var client = CountingClient.serving("owner-served");
        var router = validatedRouter(expired, Option.some(client), owner(OWNER));

        assertFailsWith("G1", router.readValidated(STREAM, PARTITION, 0, 10, EPOCH), expired);
        assertThat(client.validatedTargets).isEmpty();
    }

    /// The seam defaults to the partition manager on every public factory: a colocated consumer read through the
    /// committed-owner overload is served by the manager, not by a scripted local answer.
    @Test
    void readValidated_isServedByThePartitionManager_onTheCommittedOwnerOverload() {
        partitions.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000), "earliest"))
                  .onFailure(BelowCopyTailForwardTest::failUnexpected);
        partitions.publishLocal(STREAM, PARTITION, new byte[]{1}, 1_000L)
                  .onFailure(BelowCopyTailForwardTest::failUnexpected);
        partitions.ownerLease(OwnerLease.TEST_VALID);
        var router = StreamReadRouter.streamReadRouter(partitions,
                                                       Option.none(),
                                                       Option.none(),
                                                       SELF,
                                                       (_, _) -> Option.none(),
                                                       StreamReadForwardMetrics.NOOP,
                                                       Option.none(),
                                                       Option.none(),
                                                       Option.none());

        router.readValidated(STREAM, PARTITION, 0, 10, EPOCH)
              .await()
              .onFailure(BelowCopyTailForwardTest::failUnexpected)
              .onSuccess(read -> assertThat(read.events()).hasSize(1));
    }

    /// The base (six-argument) public factory wires the same production local read: a colocated consumer read through it is
    /// served by the manager. A factory whose local read always answered `PARTITION_NOT_LOCAL` would fail every real read.
    @Test
    void readValidated_isServedByThePartitionManager_onTheBaseFactory() {
        partitions.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000), "earliest"))
                  .onFailure(BelowCopyTailForwardTest::failUnexpected);
        partitions.publishLocal(STREAM, PARTITION, new byte[]{1}, 1_000L)
                  .onFailure(BelowCopyTailForwardTest::failUnexpected);
        partitions.ownerLease(OwnerLease.TEST_VALID);
        var router = StreamReadRouter.streamReadRouter(partitions,
                                                       Option.none(),
                                                       Option.none(),
                                                       SELF,
                                                       (_, _) -> Option.none(),
                                                       StreamReadForwardMetrics.NOOP);

        router.readValidated(STREAM, PARTITION, 0, 10, EPOCH)
              .await()
              .onFailure(BelowCopyTailForwardTest::failUnexpected)
              .onSuccess(read -> assertThat(read.events()).hasSize(1));
    }

    // ---- the forwardToOwner fallback stays un-armed ------------------------------------------------------------------

    /// The first local read is `PARTITION_NOT_LOCAL` or empty, the HRW owner is self (so the owner forward soft-fails to a
    /// second local read), a committed owner names ANOTHER node, and the second local read is `BelowCopyTail`. That second read
    /// follows a forward decision, so it is not armed: zero forwards, the `BelowCopyTail` surfaces. An armed fallback would
    /// forward to the committed owner.
    @Test
    void route_doesNotForwardTheBelowCopyTailOfTheFallbackRead_whenTheFirstReadWasNotLocalOrEmpty() {
        var arms = List.of(Arm.GOVERNOR_NOT_BEHIND, Arm.NEAREST_NO_REGISTRY, Arm.NEAREST_NOT_COVERING);

        for (var arm : arms) {
            for (var notLocal : firstReadsOf(arm)) {
                var calls = new AtomicInteger();
                LocalReader<String> scripted = (_, _, _, _) -> scriptedRead(calls.getAndIncrement(), notLocal);
                var client = CountingClient.serving("committed-served");
                var router = frr(preferenceOf(arm), registryOf(arm), scripted, Option.some(client), owner(SELF), committedAt(COMMITTED));
                var label = arm.name() + (notLocal ? " (first read PARTITION_NOT_LOCAL)" : " (first read empty)");

                assertFailsWith(label, router.route(STREAM, PARTITION, 0, 10), LOCAL_REFUSAL);
                assertThat(calls.get()).as(label + " local reads").isEqualTo(2);
                assertThat(client.targets).as(label).isEmpty();
            }
        }
    }

    /// The GOVERNOR arm forwards only on `PARTITION_NOT_LOCAL`; an empty first read is its answer. NEAREST forwards on both.
    private static List<Boolean> firstReadsOf(Arm arm) {
        return arm == Arm.GOVERNOR_NOT_BEHIND
               ? List.of(true)
               : List.of(true, false);
    }

    private static Promise<List<String>> scriptedRead(int call, boolean firstIsNotLocal) {
        return call > 0
               ? LOCAL_REFUSAL.promise()
               : firstIsNotLocal
                 ? StreamError.General.PARTITION_NOT_LOCAL.promise()
                 : Promise.success(List.of());
    }

    // ---- fixture -----------------------------------------------------------------------------------------------------

    private StreamReadRouter validatedRouter(StreamError localRefusal, Option<StreamForwardClient> client, OwnerResolver resolver) {
        return StreamReadRouter.withLocalValidatedRead(partitions,
                                                       client,
                                                       SELF,
                                                       resolver,
                                                       (_, _, _, _, _) -> localRefusal.promise());
    }

    private static ForwardingReadRouter<String> armRouter(Arm arm,
                                                          StreamError localRefusal,
                                                          Option<StreamForwardClient> client,
                                                          OwnerResolver resolver,
                                                          Option<CommittedStreamOwnerSource> committed) {
        return frr(preferenceOf(arm), registryOf(arm), reader(localRefusal), client, resolver, committed);
    }

    private static ReadPreference preferenceOf(Arm arm) {
        return switch (arm) {
            case GOVERNOR_NOT_BEHIND -> ReadPreference.GOVERNOR;
            case NEAREST_NO_REGISTRY, NEAREST_SELF_COVERS, NEAREST_NOT_COVERING -> ReadPreference.NEAREST;
            case ANY_REPLICA_NO_REGISTRY, ANY_REPLICA_SELF_COVERS -> ReadPreference.ANY_REPLICA;
        };
    }

    private static Option<ReplicaRegistry> registryOf(Arm arm) {
        return switch (arm) {
            case GOVERNOR_NOT_BEHIND, NEAREST_NO_REGISTRY, ANY_REPLICA_NO_REGISTRY -> Option.none();
            case NEAREST_SELF_COVERS, ANY_REPLICA_SELF_COVERS -> Option.some(selfCoveringRegistry());
            case NEAREST_NOT_COVERING -> Option.some(ReplicaRegistry.replicaRegistry());
        };
    }

    private static ReplicaRegistry selfCoveringRegistry() {
        var registry = ReplicaRegistry.replicaRegistry();

        caughtUp(registry, SELF);
        return registry;
    }

    private static void caughtUp(ReplicaRegistry registry, NodeId node) {
        registry.registerReplica(STREAM, PARTITION, node);
        registry.updateWatermark(STREAM, PARTITION, node, 5L, ReplicationState.CAUGHT_UP);
    }

    private static ForwardingReadRouter<String> frr(ReadPreference preference,
                                                    Option<ReplicaRegistry> registry,
                                                    LocalReader<String> local,
                                                    Option<StreamForwardClient> client,
                                                    OwnerResolver resolver,
                                                    Option<CommittedStreamOwnerSource> committed) {
        return ForwardingReadRouter.forwardingReadRouter(registry,
                                                         SELF,
                                                         client,
                                                         preference,
                                                         resolver,
                                                         local,
                                                         BelowCopyTailForwardTest::decode,
                                                         StreamReadForwardMetrics.NOOP,
                                                         committed,
                                                         Option.none(),
                                                         Option.none(),
                                                         (_, _) -> false);
    }

    private static LocalReader<String> reader(StreamError refusal) {
        return (_, _, _, _) -> refusal.promise();
    }

    private static OwnerResolver owner(NodeId node) {
        return (_, _) -> Option.some(node);
    }

    private static Option<CommittedStreamOwnerSource> committedAt(NodeId node) {
        return Option.some((_, _) -> Option.some(new CommittedOwner(node, Epoch.ZERO)));
    }

    private static List<String> decode(List<RawEventDto> events, int partition) {
        return events.stream()
                     .map(dto -> new String(dto.data()))
                     .toList();
    }

    private static void assertEvents(Arm arm, Promise<List<String>> read, String... expected) {
        read.await()
            .onFailure(cause -> Assertions.fail(arm + ": unexpected failure " + cause.message()))
            .onSuccess(events -> assertThat(events).as(arm.name()).containsExactly(expected));
    }

    private static void assertFailsWith(Arm arm, Promise<List<String>> read, Cause expected) {
        assertFailsWith(arm.name(), read, expected);
    }

    private static void assertFailsWith(String label, Promise<?> read, Cause expected) {
        var result = read.await();

        assertThat(result.isFailure()).as(label + " must fail with " + expected.message()).isTrue();
        result.onFailure(cause -> assertThat(cause).as(label).isEqualTo(expected));
    }

    private static void failUnexpected(Cause cause) {
        Assertions.fail("unexpected failure: " + cause.message());
    }

    /// Records every forward target, plain and validated, and answers through `answer`.
    private static final class CountingClient implements StreamForwardClient {
        private final List<NodeId> targets = new CopyOnWriteArrayList<>();
        private final List<NodeId> validatedTargets = new CopyOnWriteArrayList<>();
        private final Function<NodeId, Promise<ReadForwardResult>> answer;

        private CountingClient(Function<NodeId, Promise<ReadForwardResult>> answer) {
            this.answer = answer;
        }

        static CountingClient answering(Function<NodeId, Promise<ReadForwardResult>> answer) {
            return new CountingClient(answer);
        }

        static CountingClient serving(String... data) {
            var dtos = Arrays.stream(data)
                             .map(value -> new RawEventDto(0L, 1L, value.getBytes()))
                             .toList();

            return new CountingClient(_ -> Promise.success(new ReadForwardResult(dtos, false)));
        }

        @Override
        public Promise<ReadForwardResult> readRemote(NodeId replicaId, String streamName, int partition, long fromOffset, int maxEvents) {
            targets.add(replicaId);
            return answer.apply(replicaId);
        }

        @Override
        public Promise<ReadForwardResult> readRemoteValidated(NodeId ownerId,
                                                              String streamName,
                                                              int partition,
                                                              long fromOffset,
                                                              int maxEvents,
                                                              Epoch consumerEpoch) {
            validatedTargets.add(ownerId);
            return answer.apply(ownerId);
        }

        @Override
        public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
            return Promise.success(-1L);
        }

        @Override
        public void onReadForwardResponse(ReadForwardResponse response) {}

        @Override
        public void onPublishForwardResponse(PublishForwardResponse response) {}
    }
}
