// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.ForwardingReadRouter.LocalReader;
import org.pragmatica.aether.stream.ForwardingReadRouter.OwnerResolver;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardClient.ReadForwardResult;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// Per-arm recovery of a `PARTITION_NOT_LOCAL` local-read FAILURE into an owner forward
/// (metadata-only-node read recovery). Drives {@link ForwardingReadRouter} directly over fake local-read /
/// decode / owner-resolve / forward-client closures so each routing arm's failure handling is exercised in
/// isolation: `GOVERNOR` and the two `NEAREST` local arms forward on `PARTITION_NOT_LOCAL`, propagate any
/// OTHER failure, and — when the owner is unknown/self or no forward client is wired — surface the original
/// `PARTITION_NOT_LOCAL` rather than looping.
class ForwardingReadRouterTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OWNER = new NodeId("owner-node");
    private static final NodeId PEER_A = new NodeId("peer-a");
    private static final NodeId PEER_B = new NodeId("peer-b");
    private static final String STREAM = "read-stream";
    private static final int PARTITION = 0;

    @Test
    void route_forwardsToOwner_whenGovernorLocalReadFailsPartitionNotLocal() {
        var router = router(ReadPreference.GOVERNOR,
                            Option.none(),
                            partitionNotLocalReader(),
                            Option.some(forwardClientServing("r0", "r1")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("r0", "r1"));
    }

    @Test
    void route_propagatesPartitionNotLocal_whenGovernorOwnerUnknown() {
        var router = router(ReadPreference.GOVERNOR,
                            Option.none(),
                            partitionNotLocalReader(),
                            Option.some(forwardClientServing("r0")),
                            noOwner());

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onSuccess(events -> Assertions.fail("expected PARTITION_NOT_LOCAL to propagate, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.PARTITION_NOT_LOCAL));
    }

    @Test
    void route_propagatesPartitionNotLocal_whenGovernorOwnerIsSelf() {
        var router = router(ReadPreference.GOVERNOR,
                            Option.none(),
                            partitionNotLocalReader(),
                            Option.some(forwardClientServing("r0")),
                            owner(SELF));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onSuccess(events -> Assertions.fail("expected PARTITION_NOT_LOCAL to propagate, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.PARTITION_NOT_LOCAL));
    }

    @Test
    void route_doesNotRecover_whenGovernorLocalReadFailsOtherCause() {
        var router = router(ReadPreference.GOVERNOR,
                            Option.none(),
                            failingReader(new StreamError.StreamNotFound(STREAM)),
                            Option.some(forwardClientServing("r0")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onSuccess(events -> Assertions.fail("expected StreamNotFound to propagate un-forwarded, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
    }

    @Test
    void route_readsLocal_whenGovernorLocalReadSucceeds() {
        var router = router(ReadPreference.GOVERNOR,
                            Option.none(),
                            successReader("local"),
                            Option.some(forwardClientServing("owner-served")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    @Test
    void route_forwardsToOwner_whenNearestNoRegistryLocalReadFailsPartitionNotLocal() {
        var router = router(ReadPreference.NEAREST,
                            Option.none(),
                            partitionNotLocalReader(),
                            Option.some(forwardClientServing("r0")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("r0"));
    }

    @Test
    void route_forwardsToOwner_whenNearestSelfNotCoveringLocalReadFailsPartitionNotLocal() {
        var router = router(ReadPreference.NEAREST,
                            Option.some(ReplicaRegistry.replicaRegistry()),
                            partitionNotLocalReader(),
                            Option.some(forwardClientServing("r0", "r1")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("r0", "r1"));
    }

    @Test
    void route_doesNotRecover_whenNearestSelfNotCoveringLocalReadFailsOtherCause() {
        var router = router(ReadPreference.NEAREST,
                            Option.some(ReplicaRegistry.replicaRegistry()),
                            failingReader(new StreamError.StreamNotFound(STREAM)),
                            Option.some(forwardClientServing("r0")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onSuccess(events -> Assertions.fail("expected StreamNotFound to propagate un-forwarded, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
    }

    @Test
    void route_readsLocal_whenNearestSelfCoversPartition() {
        var registry = ReplicaRegistry.replicaRegistry();

        registry.registerReplica(STREAM, PARTITION, SELF);
        registry.updateWatermark(STREAM, PARTITION, SELF, 0L, ReplicationState.CAUGHT_UP);

        var router = router(ReadPreference.NEAREST,
                            Option.some(registry),
                            successReader("local"),
                            Option.some(forwardClientServing("owner-served")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    /// PR-2f S2 (know `e10cf838c9`): a copy behind the owner forwards a GOVERNOR read without reading local.
    @Test
    void route_forwardsToOwner_whenGovernorCopyIsBehindTheOwner() {
        var router = staleRouter(ReplicaStaleness.BEHIND, owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("owner-served"));
    }

    @Test
    void route_readsLocal_whenGovernorCopyIsNotBehindTheOwner() {
        var router = staleRouter(ReplicaStaleness.CURRENT, owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    @Test
    void route_readsLocal_whenGovernorCopyIsBehindButTheOwnerIsSelf() {
        var router = staleRouter(ReplicaStaleness.BEHIND, owner(SELF));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    /// After a restart the committed owner can differ from the HRW owner; the stale copy's read goes to the committed one.
    @Test
    void route_forwardsToTheCommittedOwner_whenGovernorCopyIsBehindAndItDiffersFromTheHrwOwner() {
        var committed = new NodeId("committed-owner");
        var router = ForwardingReadRouter.forwardingReadRouter(Option.none(),
                                                               SELF,
                                                               Option.some(forwardClientServingOnly(committed, "committed-served")),
                                                               ReadPreference.GOVERNOR,
                                                               owner(OWNER),
                                                               successReader("local"),
                                                               ForwardingReadRouterTest::decode,
                                                               StreamReadForwardMetrics.NOOP,
                                                               Option.some((_, _) -> Option.some(new CommittedStreamOwnerSource.CommittedOwner(committed,
                                                                                                                                              Epoch.ZERO))),
                                                               Option.none(),
                                                               Option.none(),
                                                               (_, _) -> ReplicaStaleness.BEHIND);

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("committed-served"));
    }

    /// The committed record can name this very node (it is the owner of record but the copy is stale): forwarding to itself
    /// is no hop, so the read goes to the HRW owner instead.
    @Test
    void route_forwardsToTheHrwOwner_whenGovernorCopyIsBehindAndTheCommittedOwnerIsSelf() {
        var router = ForwardingReadRouter.forwardingReadRouter(Option.none(),
                                                               SELF,
                                                               Option.some(forwardClientServingOnly(OWNER, "owner-served")),
                                                               ReadPreference.GOVERNOR,
                                                               owner(OWNER),
                                                               successReader("local"),
                                                               ForwardingReadRouterTest::decode,
                                                               StreamReadForwardMetrics.NOOP,
                                                               Option.some((_, _) -> Option.some(new CommittedStreamOwnerSource.CommittedOwner(SELF,
                                                                                                                                              Epoch.ZERO))),
                                                               Option.none(),
                                                               Option.none(),
                                                               (_, _) -> ReplicaStaleness.BEHIND);

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("owner-served"));
    }

    // ---- GOVERNOR, a mark an ended owner life announced (#3325) ---------------------------------

    /// The owner of the void mark's partition times out: the read fails with the transient timeout, never the copy's records.
    /// Control: the same failure under `BEHIND` degrades to the copy, as before #3325.
    @Test
    void route_voidOwnerMark_ownerTimesOut_failsWithTheTimeoutInsteadOfReadingLocal() {
        var timeout = StreamForwardError.General.READ_FORWARD_TIMEOUT;

        assertThat(governorRead(ReplicaStaleness.BEHIND, Option.some(forwardClientFailing(timeout)), owner(OWNER), Option.none()))
            .as("control: a BEHIND copy degrades to its own answer").isEqualTo(List.of("local"));
        assertThat(governorFailure(ReplicaStaleness.VOID_OWNER_MARK, Option.some(forwardClientFailing(timeout)), owner(OWNER), Option.none()))
            .as("the void mark serves nothing: the timeout is the answer").isEqualTo(timeout);
    }

    /// An owner that does not hold the partition (`PARTITION_NOT_LOCAL`) and a failure the peer reported only as text say
    /// nothing a retry may use: the read is refused with the transient `ReplicaNotVerified`. Control: under `BEHIND` they
    /// degrade to the copy.
    @Test
    void route_voidOwnerMark_ownerCouldNotBeAskedNonTransiently_isRefusedReplicaNotVerified() {
        var refused = new StreamError.ReplicaNotVerified(STREAM, PARTITION, 0L);

        for (var cause : List.<Cause>of(StreamError.General.PARTITION_NOT_LOCAL, new StreamForwardError.ReadForwardFailed("peer text"))) {
            assertThat(governorRead(ReplicaStaleness.BEHIND, Option.some(forwardClientFailing(cause)), owner(OWNER), Option.none()))
                .as("control: %s under BEHIND degrades to the copy", cause).isEqualTo(List.of("local"));
            assertThat(governorFailure(ReplicaStaleness.VOID_OWNER_MARK, Option.some(forwardClientFailing(cause)), owner(OWNER), Option.none()))
                .as("%s under a void mark", cause).isEqualTo(refused);
        }
        assertThat(refused.isTransient()).as("the refusal is retryable").isTrue();
    }

    /// A fault no retry changes (`FAULT_DEGRADE`) is returned as it is under a void mark: it is not the owner change's.
    @Test
    void route_voidOwnerMark_forwardFaultNoRetryChanges_returnsThatFault() {
        var oversized = StreamForwardError.General.READ_RESPONSE_OVERSIZED;

        assertThat(governorFailure(ReplicaStaleness.VOID_OWNER_MARK, Option.some(forwardClientFailing(oversized)), owner(OWNER), Option.none()))
            .isEqualTo(oversized);
    }

    /// With no owner to ask -- none known, no forward client, or a committed owner that is this node (where `BEHIND` goes on to
    /// the HRW owner) -- the void mark's read is refused with `ReplicaNotVerified`. Control: `BEHIND` with no owner reads local.
    @Test
    void route_voidOwnerMark_noForwardTarget_isRefusedReplicaNotVerified() {
        var refused = new StreamError.ReplicaNotVerified(STREAM, PARTITION, 0L);
        Option<CommittedStreamOwnerSource> committedSelf = Option.some((_, _) -> Option.some(new CommittedStreamOwnerSource.CommittedOwner(SELF,
                                                                                                                                           Epoch.ZERO)));

        assertThat(governorRead(ReplicaStaleness.BEHIND, Option.some(forwardClientServing("owner-served")), noOwner(), Option.none()))
            .as("control: BEHIND with no owner reads local").isEqualTo(List.of("local"));
        assertThat(governorFailure(ReplicaStaleness.VOID_OWNER_MARK, Option.some(forwardClientServing("owner-served")), noOwner(), Option.none()))
            .as("owner unknown").isEqualTo(refused);
        assertThat(governorFailure(ReplicaStaleness.VOID_OWNER_MARK, Option.some(forwardClientServing("owner-served")), owner(SELF), Option.none()))
            .as("the HRW owner is this node").isEqualTo(refused);
        assertThat(governorFailure(ReplicaStaleness.VOID_OWNER_MARK, Option.none(), owner(OWNER), Option.none()))
            .as("no forward client").isEqualTo(refused);
        assertThat(governorRead(ReplicaStaleness.BEHIND, Option.some(forwardClientServingOnly(OWNER, "owner-served")), owner(OWNER), committedSelf))
            .as("control: BEHIND with the committed owner self goes on to the HRW owner").isEqualTo(List.of("owner-served"));
        assertThat(governorFailure(ReplicaStaleness.VOID_OWNER_MARK, Option.some(forwardClientServingOnly(OWNER, "owner-served")), owner(OWNER), committedSelf))
            .as("the committed owner is this node").isEqualTo(refused);
    }

    /// The owner's typed refusal (not yet activated) is the answer, as it is; and an owner that answers serves the read.
    @Test
    void route_voidOwnerMark_ownerRefusesTyped_returnsThatRefusal_andAnAnswerIsServed() {
        var notActivated = new StreamError.OwnerNotActivated(STREAM, PARTITION);

        assertThat(governorRead(ReplicaStaleness.VOID_OWNER_MARK, Option.some(forwardClientServing("owner-served")), owner(OWNER), Option.none()))
            .as("control: the owner's answer is served").isEqualTo(List.of("owner-served"));
        assertThat(governorFailure(ReplicaStaleness.VOID_OWNER_MARK, Option.some(forwardClientFailing(notActivated)), owner(OWNER), Option.none()))
            .isEqualTo(notActivated);
    }

    private static List<String> governorRead(ReplicaStaleness staleness,
                                             Option<StreamForwardClient> forwardClient,
                                             OwnerResolver ownerResolver,
                                             Option<CommittedStreamOwnerSource> committedOwnerSource) {
        return governorRouter(staleness, forwardClient, ownerResolver, committedOwnerSource).route(STREAM, PARTITION, 0, 10)
                                                                                           .await()
                                                                                           .onFailure(ForwardingReadRouterTest::failUnexpected)
                                                                                           .or(List.of());
    }

    private static Cause governorFailure(ReplicaStaleness staleness,
                                         Option<StreamForwardClient> forwardClient,
                                         OwnerResolver ownerResolver,
                                         Option<CommittedStreamOwnerSource> committedOwnerSource) {
        return governorRouter(staleness, forwardClient, ownerResolver, committedOwnerSource).route(STREAM, PARTITION, 0, 10)
                                                                                           .await()
                                                                                           .fold(cause -> cause,
                                                                                                 events -> Assertions.fail("expected a refusal, got " + events));
    }

    private static ForwardingReadRouter<String> governorRouter(ReplicaStaleness staleness,
                                                               Option<StreamForwardClient> forwardClient,
                                                               OwnerResolver ownerResolver,
                                                               Option<CommittedStreamOwnerSource> committedOwnerSource) {
        return ForwardingReadRouter.forwardingReadRouter(Option.none(),
                                                         SELF,
                                                         forwardClient,
                                                         ReadPreference.GOVERNOR,
                                                         ownerResolver,
                                                         successReader("local"),
                                                         ForwardingReadRouterTest::decode,
                                                         StreamReadForwardMetrics.NOOP,
                                                         committedOwnerSource,
                                                         Option.none(),
                                                         Option.none(),
                                                         (_, _) -> staleness);
    }

    private static ForwardingReadRouter<String> staleRouter(ReplicaStaleness staleness, OwnerResolver ownerResolver) {
        return governorRouter(staleness, Option.some(forwardClientServing("owner-served")), ownerResolver, Option.none());
    }

    // ---- ANY_REPLICA: a read whose every remote pick failed (#946) ------------------------------

    @Test
    void route_readsLocal_whenAnyReplicaSoleRemotePeerTimesOutAndSelfCovers() {
        var router = router(ReadPreference.ANY_REPLICA,
                            Option.some(registryWith(true, PEER_A)),
                            successReader("local"),
                            Option.some(forwardClientFailing(StreamForwardError.General.READ_FORWARD_TIMEOUT)),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    @Test
    void route_readsLocal_whenAnyReplicaPrimaryAndRetryBothTimeOutAndSelfCovers() {
        var router = router(ReadPreference.ANY_REPLICA,
                            Option.some(registryWith(true, PEER_A, PEER_B)),
                            successReader("local"),
                            Option.some(forwardClientFailing(StreamForwardError.General.READ_FORWARD_TIMEOUT)),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    @Test
    void route_readsLocal_whenAnyReplicaSoleRemotePeerAnswersPartitionNotLocalAndSelfCovers() {
        var router = router(ReadPreference.ANY_REPLICA,
                            Option.some(registryWith(true, PEER_A)),
                            successReader("local"),
                            Option.some(forwardClientFailing(StreamError.General.PARTITION_NOT_LOCAL)),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    /// A node that is not itself a caught-up replica holds no authoritative copy: a local read there would answer an
    /// empty page, the "nothing happened" the caller cannot tell from health. The remote cause is the answer.
    @Test
    void route_propagatesTheRemoteCause_whenAnyReplicaRemoteFailsAndSelfDoesNotCover() {
        var router = router(ReadPreference.ANY_REPLICA,
                            Option.some(registryWith(false, PEER_A)),
                            successReader("local"),
                            Option.some(forwardClientFailing(StreamForwardError.General.READ_FORWARD_TIMEOUT)),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onSuccess(events -> Assertions.fail("expected the remote timeout, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isEqualTo(StreamForwardError.General.READ_FORWARD_TIMEOUT));
    }

    /// A typed answer of the replica (an unknown stream, an expired cursor) is not a failure to ask: a local read could
    /// only hide it.
    @Test
    void route_propagatesTheReplicaAnswer_whenAnyReplicaRemoteAnswersTypedEvenIfSelfCovers() {
        var router = router(ReadPreference.ANY_REPLICA,
                            Option.some(registryWith(true, PEER_A)),
                            successReader("local"),
                            Option.some(forwardClientFailing(new StreamError.StreamNotFound(STREAM))),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onSuccess(events -> Assertions.fail("expected StreamNotFound to propagate, got " + events.size() + " events"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.StreamNotFound.class));
    }

    /// F1 (#3633): one selected peer answered typed and the other could not be asked: the typed answer is returned, never
    /// masked by the local copy. Both orders (the typed peer may be the first pick or the retry), each over many picks so
    /// the random pick covers both orders.
    @Test
    void route_propagatesTheTypedAnswer_whenOnePeerAnswersTypedAndTheOtherTimesOutAndSelfCovers() {
        var typed = new StreamError.StreamNotFound(STREAM);

        for (int i = 0; i < 40; i++) {
            var router = router(ReadPreference.ANY_REPLICA,
                                Option.some(registryWith(true, PEER_A, PEER_B)),
                                successReader("local"),
                                Option.some(forwardClientAnswering(PEER_A, typed, PEER_B, StreamForwardError.General.READ_FORWARD_TIMEOUT)),
                                owner(OWNER));

            router.route(STREAM, PARTITION, 0, 10)
                  .await()
                  .onSuccess(events -> Assertions.fail("typed answer masked by a local read: " + events))
                  .onFailure(cause -> assertThat(cause).isSameAs(typed));
        }
    }

    /// Mirror of the above with the roles swapped: the timeout peer is A, the typed peer is B.
    @Test
    void route_propagatesTheTypedAnswer_whenOnePeerTimesOutAndTheOtherAnswersTypedAndSelfCovers() {
        var typed = new StreamError.StreamNotFound("typed-on-the-retry");

        for (int i = 0; i < 40; i++) {
            var router = router(ReadPreference.ANY_REPLICA,
                                Option.some(registryWith(true, PEER_A, PEER_B)),
                                successReader("local"),
                                Option.some(forwardClientAnswering(PEER_A, StreamForwardError.General.READ_FORWARD_TIMEOUT, PEER_B, typed)),
                                owner(OWNER));

            router.route(STREAM, PARTITION, 0, 10)
                  .await()
                  .onSuccess(events -> Assertions.fail("typed answer masked by a local read: " + events))
                  .onFailure(cause -> assertThat(cause).isSameAs(typed));
        }
    }

    @Test
    void route_readsTheRemote_whenAnyReplicaRemoteAnswers() {
        var router = router(ReadPreference.ANY_REPLICA,
                            Option.some(registryWith(true, PEER_A)),
                            successReader("local"),
                            Option.some(forwardClientServing("remote-served")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("remote-served"));
    }

    /// The degrade is the self-covering arm's local read, so a copy that does not hold the range asked still forwards once to the
    /// owner (M-9f) instead of answering from a copy that cannot serve it.
    @Test
    void route_forwardsBelowCopyTailToTheOwner_whenAnyReplicaRemoteTimesOutAndTheLocalCopyIsBelowItsTail() {
        var router = router(ReadPreference.ANY_REPLICA,
                            Option.some(registryWith(true, PEER_A)),
                            failingReader(new StreamError.BelowCopyTail(5, 9)),
                            Option.some(forwardClientTimingOutExcept(OWNER, "owner-served")),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("owner-served"));
    }

    /// m4 (#3633): the owner forward (no fresh peer, this node does not cover) falls back to the local read when the owner could
    /// not be asked -- it must NOT take the replica rule, which would return the remote cause because this node covers nothing.
    @Test
    void route_readsLocal_whenAnyReplicaOwnerForwardTimesOutAndSelfDoesNotCover() {
        var router = router(ReadPreference.ANY_REPLICA,
                            Option.some(ReplicaRegistry.replicaRegistry()),
                            successReader("local"),
                            Option.some(forwardClientFailing(StreamForwardError.General.READ_FORWARD_TIMEOUT)),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("local"));
    }

    /// m5 (#3633): the primary pick fails and the retry pick answers: the retry's answer is returned, neither the local copy
    /// (self covers, so a skipped retry would answer "local") nor the primary's cause. The client fails its FIRST call and serves
    /// every later one, so the outcome does not depend on which peer the random pick takes first.
    @Test
    void route_readsTheRetryAnswer_whenAnyReplicaPrimaryTimesOutAndTheRetrySucceeds() {
        var client = forwardClientFailingFirstThenServing(StreamForwardError.General.READ_FORWARD_TIMEOUT, "retry-served");
        var router = router(ReadPreference.ANY_REPLICA,
                            Option.some(registryWith(true, PEER_A, PEER_B)),
                            successReader("local"),
                            Option.some(client),
                            owner(OWNER));

        router.route(STREAM, PARTITION, 0, 10)
              .await()
              .onFailure(ForwardingReadRouterTest::failUnexpected)
              .onSuccess(events -> assertThat(events).containsExactly("retry-served"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static ForwardingReadRouter<String> router(ReadPreference preference,
                                                       Option<ReplicaRegistry> registry,
                                                       LocalReader<String> localReader,
                                                       Option<StreamForwardClient> forwardClient,
                                                       OwnerResolver ownerResolver) {
        return ForwardingReadRouter.forwardingReadRouter(registry,
                                                         SELF,
                                                         forwardClient,
                                                         preference,
                                                         ownerResolver,
                                                         localReader,
                                                         ForwardingReadRouterTest::decode,
                                                         StreamReadForwardMetrics.NOOP);
    }

    private static LocalReader<String> partitionNotLocalReader() {
        return failingReader(StreamError.General.PARTITION_NOT_LOCAL);
    }

    private static LocalReader<String> failingReader(Cause cause) {
        return (_, _, _, _) -> cause.promise();
    }

    private static LocalReader<String> successReader(String... values) {
        var events = List.of(values);

        return (_, _, _, _) -> Promise.success(events);
    }

    private static OwnerResolver owner(NodeId owner) {
        return (_, _) -> Option.some(owner);
    }

    private static OwnerResolver noOwner() {
        return (_, _) -> Option.none();
    }

    private static List<String> decode(List<RawEventDto> events, int partition) {
        return events.stream().map(ForwardingReadRouterTest::asString).toList();
    }

    private static String asString(RawEventDto dto) {
        return new String(dto.data());
    }

    private static StreamForwardClient forwardClientServing(String... data) {
        var dtos = Arrays.stream(data).map(ForwardingReadRouterTest::toDto).toList();

        return new StreamForwardClient() {
            @Override
            public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
                return Promise.success(-1L);
            }

            @Override
            public Promise<ReadForwardResult> readRemote(NodeId replicaId, String streamName, int partition, long fromOffset, int maxEvents) {
                return Promise.success(new ReadForwardResult(dtos, false));
            }

            @Override
            public void onPublishForwardResponse(PublishForwardResponse response) {}

            @Override
            public void onReadForwardResponse(ReadForwardResponse response) {}
        };
    }

    /// Serves `data` to a read forwarded to `target`; any other node answers `PARTITION_NOT_LOCAL` (the router then degrades
    /// to its local read).
    private static StreamForwardClient forwardClientServingOnly(NodeId target, String... data) {
        var dtos = Arrays.stream(data).map(ForwardingReadRouterTest::toDto).toList();

        return new StreamForwardClient() {
            @Override
            public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
                return Promise.success(-1L);
            }

            @Override
            public Promise<ReadForwardResult> readRemote(NodeId replicaId, String streamName, int partition, long fromOffset, int maxEvents) {
                return replicaId.equals(target)
                       ? Promise.success(new ReadForwardResult(dtos, false))
                       : StreamError.General.PARTITION_NOT_LOCAL.promise();
            }

            @Override
            public void onPublishForwardResponse(PublishForwardResponse response) {}

            @Override
            public void onReadForwardResponse(ReadForwardResponse response) {}
        };
    }

    private static ReplicaRegistry registryWith(boolean selfCovers, NodeId... peers) {
        var registry = ReplicaRegistry.replicaRegistry();

        if (selfCovers) {
            caughtUp(registry, SELF);
        }
        Arrays.stream(peers).forEach(peer -> caughtUp(registry, peer));
        return registry;
    }

    private static void caughtUp(ReplicaRegistry registry, NodeId node) {
        registry.registerReplica(STREAM, PARTITION, node);
        registry.updateWatermark(STREAM, PARTITION, node, 5L, ReplicationState.CAUGHT_UP);
    }

    private static StreamForwardClient forwardClientTimingOutExcept(NodeId target, String... data) {
        var dtos = Arrays.stream(data).map(ForwardingReadRouterTest::toDto).toList();

        return new StreamForwardClient() {
            @Override
            public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
                return Promise.success(-1L);
            }

            @Override
            public Promise<ReadForwardResult> readRemote(NodeId replicaId, String streamName, int partition, long fromOffset, int maxEvents) {
                return replicaId.equals(target)
                       ? Promise.success(new ReadForwardResult(dtos, false))
                       : StreamForwardError.General.READ_FORWARD_TIMEOUT.promise();
            }

            @Override
            public void onPublishForwardResponse(PublishForwardResponse response) {}

            @Override
            public void onReadForwardResponse(ReadForwardResponse response) {}
        };
    }

    private static StreamForwardClient forwardClientAnswering(NodeId first, Cause firstCause, NodeId second, Cause secondCause) {
        return new StreamForwardClient() {
            @Override
            public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
                return Promise.success(-1L);
            }

            @Override
            public Promise<ReadForwardResult> readRemote(NodeId replicaId, String streamName, int partition, long fromOffset, int maxEvents) {
                return (replicaId.equals(first) ? firstCause : secondCause).promise();
            }

            @Override
            public void onPublishForwardResponse(PublishForwardResponse response) {}

            @Override
            public void onReadForwardResponse(ReadForwardResponse response) {}
        };
    }

    private static StreamForwardClient forwardClientFailing(Cause cause) {
        return new StreamForwardClient() {
            @Override
            public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
                return Promise.success(-1L);
            }

            @Override
            public Promise<ReadForwardResult> readRemote(NodeId replicaId, String streamName, int partition, long fromOffset, int maxEvents) {
                return cause.promise();
            }

            @Override
            public void onPublishForwardResponse(PublishForwardResponse response) {}

            @Override
            public void onReadForwardResponse(ReadForwardResponse response) {}
        };
    }

    /// Fails the first `readRemote` with `cause` and serves `data` to every later one.
    private static StreamForwardClient forwardClientFailingFirstThenServing(Cause cause, String... data) {
        var dtos = Arrays.stream(data).map(ForwardingReadRouterTest::toDto).toList();
        var calls = new AtomicInteger();

        return new StreamForwardClient() {
            @Override
            public Promise<Long> publishRemote(NodeId governorId, String streamName, int partition, byte[] payload, long timestamp) {
                return Promise.success(-1L);
            }

            @Override
            public Promise<ReadForwardResult> readRemote(NodeId replicaId, String streamName, int partition, long fromOffset, int maxEvents) {
                return calls.getAndIncrement() == 0
                       ? cause.promise()
                       : Promise.success(new ReadForwardResult(dtos, false));
            }

            @Override
            public void onPublishForwardResponse(PublishForwardResponse response) {}

            @Override
            public void onReadForwardResponse(ReadForwardResponse response) {}
        };
    }

    private static RawEventDto toDto(String data) {
        return new RawEventDto(0L, 1L, data.getBytes());
    }

    private static void failUnexpected(Cause cause) {
        Assertions.fail("unexpected failure: " + cause.message());
    }
}
