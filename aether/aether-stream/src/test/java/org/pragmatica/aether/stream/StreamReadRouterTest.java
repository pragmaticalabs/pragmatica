// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.stream.forward.AcceptingStreamForwardTransport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_BACK_IN_RING;
import static org.pragmatica.utility.warning.OperatorWarningCode.STREAM_CONSUMER_BEHIND_RING;

/// #2223 (I-32): a forwarded tier read whose owner answers with an UNKNOWN ring tail (the owner's ring is closing: its
/// `tailOffset()` answers -1) is not labelled a ring read, so it neither opens nor closes the consumer's
/// `stream-consumer-behind-ring` episode. The owner holds 20..29 in its ring and 0..19 sealed; the assignee holds no ring.
class StreamReadRouterTest {
    private static final NodeId OWNER = NodeId.nodeId("owner").unwrap();
    private static final NodeId ASSIGNEE = NodeId.nodeId("assignee").unwrap();
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final String GROUP = "group-router";

    private final SealedTierFixture tier = new SealedTierFixture();
    /// How many answers carry the owner's real bounds before its ring starts answering a tail of -1.
    private volatile int realAnswers = Integer.MAX_VALUE;
    /// When set, every answer carries exactly these bounds (earliest retained, visible head).
    private volatile long[] forcedBounds = null;
    private final AtomicInteger answers = new AtomicInteger();
    /// Reads the assignee's router sent to the owner (every `ReadForward` that reached the owner's handler).
    private final AtomicInteger forwardedReads = new AtomicInteger();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final List<Long> delivered = new CopyOnWriteArrayList<>();
    private StreamPartitionManager owner;
    private StreamPartitionManager assignee;
    private StreamConsumerRuntime runtime;

    @BeforeEach
    void setUp() {
        owner = streamPartitionManager();
        owner.createStream(config()).onFailure(cause -> fail(cause.message()));
        for (var offset = 0L; offset < 30; offset++) {
            owner.publishLocal(STREAM, PARTITION, SealedTierFixture.payload(offset), 1_000L + offset)
                 .onFailure(cause -> fail("publish: " + cause.message()));
        }
        owner.tieredReader(tier.reader());
        tier.seal(STREAM, PARTITION, 0, 19);
        assignee = streamPartitionManager();
        assignee.placementRoleSupplier((_, _) -> Role.NONE);
        assignee.createStream(config()).onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void tearDown() {
        Option.option(runtime).onPresent(StreamConsumerRuntime::close);
        assignee.close();
        owner.close();
    }

    @Test
    void forwardedTierRead_ownerRingClosing_isNotLabelledRing() {
        var router = forwardingRouter();

        realAnswers = 1;
        consume(router);
        awaitUntil(() -> delivered.size() >= 30 && answers.get() >= 4, "delivered 0..29 and read on while the owner's ring tail is unknown");
        pause(500);

        assertThat(codes()).as("an unknown tail neither closes the episode nor opens one").containsExactly(STREAM_CONSUMER_BEHIND_RING);
    }

    /// An owner whose ring tail is unknown from the first answer never opens the episode either: nothing says the read was
    /// served from the tier.
    @Test
    void forwardedRead_ownerRingClosingFromTheStart_opensNoEpisode() {
        var router = forwardingRouter();

        realAnswers = 0;
        consume(router);
        awaitUntil(() -> delivered.size() >= 30 && answers.get() >= 4, "delivered 0..29 while the owner's ring tail is unknown");
        pause(500);

        assertThat(codes()).as("an unknown tail names no tier read").isEmpty();
    }

    @Test
    void epochRead_unknownPosition_isNotATierRead() {
        var unknown = StreamPartitionManager.EpochRead.epochRead(List.of(), Epoch.ZERO, StreamPartitionManager.EpochRead.Position.UNKNOWN);

        assertThat(unknown.fromTier()).isFalse();
        assertThat(StreamPartitionManager.EpochRead.epochRead(List.of(), Epoch.ZERO, true).position()).isEqualTo(StreamPartitionManager.EpochRead.Position.TIER);
        assertThat(StreamPartitionManager.EpochRead.epochRead(List.of(), Epoch.ZERO, false).position()).isEqualTo(StreamPartitionManager.EpochRead.Position.RING);
    }

    /// NT-164 control: the same consumer with the owner's real bounds is back in the ring once it reads from it; the control
    /// shows the episode machinery and the warning sink reach the assertion above.
    @Test
    void forwardedTierRead_ownerRingKnown_closesTheEpisodeAtTheRing() {
        var router = forwardingRouter();

        consume(router);
        awaitUntil(() -> delivered.size() >= 30 && codes().contains(STREAM_CONSUMER_BACK_IN_RING), "back in the ring with real bounds");

        assertThat(codes()).containsExactly(STREAM_CONSUMER_BEHIND_RING, STREAM_CONSUMER_BACK_IN_RING);
    }

    /// PR-2f S2 wiring pin: the router built over a partition manager consults [StreamPartitionManager#replicaStaleness].
    /// A REPLICA copy that holds the partition's ring but has no announcement from the owner forwards the GOVERNOR read: the
    /// owner's 20..22 arrive, where the copy's own (empty) ring would answer nothing.
    @Test
    void governorRead_ofAReplicaCopyWithNoAnnouncement_forwardsToTheOwner() {
        var copy = streamPartitionManager();

        try {
            copy.placementRoleSupplier((_, _) -> Role.REPLICA);
            copy.createStream(config()).onFailure(cause -> fail(cause.message()));

            assertThat(copy.readLocal(STREAM, PARTITION, 20L, 3).isSuccess()).as("control: the copy holds the ring, so only the stale probe can forward").isTrue();
            assertThat(forwardingRouter(copy).read(STREAM, PARTITION, 20L, 3, ReadPreference.GOVERNOR).await().unwrap())
                .as("served by the owner").hasSize(3);
        } finally {
            copy.close();
        }
    }

    /// #3325: the mark a replica heard from owner A belongs to A's life. Ownership moves to B (the committed epoch advances) and
    /// B has announced nothing, as while its activation is blocked: the replica's GOVERNOR read is forwarded to the owner instead
    /// of being served from A's mark. Control: under A's epoch the same copy, with the same mark, serves locally; and once B
    /// announces under its own epoch the copy serves again.
    @Test
    void governorRead_afterAnOwnerChange_forwardsInsteadOfServingThePreviousOwnersMark() {
        var epochA = Epoch.epoch(1L, 1L, 1L);
        var epochB = Epoch.epoch(1L, 1L, 2L);
        var committedEpoch = new AtomicReference<>(epochA);
        var copy = streamPartitionManager();

        try {
            copy.placementRoleSupplier((_, _) -> Role.REPLICA);
            copy.ownerEpochSource((_, _) -> committedEpoch.get());
            copy.createStream(config()).onFailure(cause -> fail(cause.message()));
            for (var record = 0; record < 3; record++) {
                copy.appendRecovered(STREAM, PARTITION, ("copy-" + record).getBytes(UTF_8), 1L + record)
                    .onFailure(cause -> fail("append: " + cause.message()));
            }
            copy.commitAdvanced(STREAM, PARTITION, 2L, epochA);
            copy.markVerified(STREAM, PARTITION, 2L);
            copy.markVerifiedForEpoch(STREAM, PARTITION, epochA);
            var router = forwardingRouter(copy);

            assertThat(router.read(STREAM, PARTITION, 0L, 3, ReadPreference.GOVERNOR).await().unwrap())
                .as("control: under the epoch that announced the mark, the copy serves its own records")
                .extracting(event -> new String(event.data(), UTF_8))
                .containsExactly("copy-0", "copy-1", "copy-2");
            assertThat(forwardedReads.get()).as("control: nothing was forwarded").isZero();

            committedEpoch.set(epochB);
            copy.markVerifiedForEpoch(STREAM, PARTITION, epochB);
            router.read(STREAM, PARTITION, 0L, 3, ReadPreference.GOVERNOR).await();

            assertThat(forwardedReads.get()).as("the previous owner life's mark is void: the read goes to the owner").isEqualTo(1);

            copy.commitAdvanced(STREAM, PARTITION, 2L, epochB);

            assertThat(router.read(STREAM, PARTITION, 0L, 3, ReadPreference.GOVERNOR).await().unwrap())
                .as("control: the new owner's own announcement is served from again")
                .extracting(event -> new String(event.data(), UTF_8))
                .containsExactly("copy-0", "copy-1", "copy-2");
            assertThat(forwardedReads.get()).as("control: served locally, not forwarded").isEqualTo(1);
        } finally {
            copy.close();
        }
    }

    /// #3325: the owner of a void mark cannot be asked (the forward times out). The read fails with the transient timeout and
    /// never answers with the copy's records, which follow the ended life's mark. Control: under the epoch that announced the
    /// mark the same unreachable owner is never asked, and the copy serves.
    @Test
    void governorRead_ofAVoidMark_ownerUnreachable_failsInsteadOfServingThePreviousOwnersMark() {
        var epochA = Epoch.epoch(1L, 1L, 1L);
        var committedEpoch = new AtomicReference<>(epochA);
        var copy = copyServingMarkOf(epochA, committedEpoch, 3, 2L);

        try {
            var router = unreachableOwnerRouter(copy);

            assertThat(texts(router.read(STREAM, PARTITION, 0L, 3, ReadPreference.GOVERNOR).await().unwrap()))
                .as("control: under the announcing epoch the copy serves").containsExactly("copy-0", "copy-1", "copy-2");

            committedEpoch.set(Epoch.epoch(1L, 1L, 2L));

            assertThat(refusalOf(router.read(STREAM, PARTITION, 0L, 3, ReadPreference.GOVERNOR)))
                .as("the timeout is the answer, never the copy").isEqualTo(StreamForwardError.General.READ_FORWARD_TIMEOUT);
        } finally {
            copy.close();
        }
    }

    /// #3325: the node asked for a void mark's read does not hold the partition (`PARTITION_NOT_LOCAL`): the read is refused
    /// with the transient `ReplicaNotVerified` from the read's offset, not served from the copy.
    @Test
    void governorRead_ofAVoidMark_ownerDoesNotHoldThePartition_isRefusedReplicaNotVerified() {
        var epochA = Epoch.epoch(1L, 1L, 1L);
        var committedEpoch = new AtomicReference<>(epochA);
        var copy = copyServingMarkOf(epochA, committedEpoch, 3, 2L);
        var stranger = streamPartitionManager();

        try {
            stranger.placementRoleSupplier((_, _) -> Role.NONE);
            stranger.createStream(config()).onFailure(cause -> fail(cause.message()));
            assertThat(stranger.readServing(STREAM, PARTITION, 0L, 3).isFailure()).as("control: the asked node does not hold the partition").isTrue();
            committedEpoch.set(Epoch.epoch(1L, 1L, 2L));

            assertThat(refusalOf(routerOver(copy, stranger, TimeSpan.timeSpan(5).seconds()).read(STREAM, PARTITION, 1L, 3, ReadPreference.GOVERNOR)))
                .isEqualTo(new StreamError.ReplicaNotVerified(STREAM, PARTITION, 1L));
            assertThat(forwardedReads.get()).as("control: the read was forwarded").isEqualTo(1);
        } finally {
            stranger.close();
            copy.close();
        }
    }

    /// A copy `BEHIND` the mark (it holds 0..1, the owner announced 2) and a copy with nothing announced keep today's degrade:
    /// with the owner unreachable the read is the copy's own answer.
    @Test
    void governorRead_behindOrNothingAnnounced_ownerUnreachable_degradesToTheLocalRead() {
        var epochA = Epoch.epoch(1L, 1L, 1L);
        var behind = copyServingMarkOf(epochA, new AtomicReference<>(epochA), 2, 2L);
        var unannounced = streamPartitionManager();

        try {
            unannounced.placementRoleSupplier((_, _) -> Role.REPLICA);
            unannounced.createStream(config()).onFailure(cause -> fail(cause.message()));

            assertThat(behind.replicaStaleness(STREAM, PARTITION)).as("control").isEqualTo(ReplicaStaleness.BEHIND);
            assertThat(unannounced.replicaStaleness(STREAM, PARTITION)).as("control").isEqualTo(ReplicaStaleness.BEHIND);
            assertThat(texts(unreachableOwnerRouter(behind).read(STREAM, PARTITION, 0L, 3, ReadPreference.GOVERNOR).await().unwrap()))
                .as("behind: the copy's committed prefix").containsExactly("copy-0", "copy-1");
            assertThat(unreachableOwnerRouter(unannounced).read(STREAM, PARTITION, 0L, 3, ReadPreference.GOVERNOR).await().unwrap())
                .as("nothing announced: the copy's own (empty) answer").isEmpty();
        } finally {
            unannounced.close();
            behind.close();
        }
    }

    /// A REPLICA copy holding `records` records, verified for `epochA`, that heard `through` announced under `epochA`, judged
    /// against the committed epoch `committedEpoch` holds. Verified for `epochA` only: a void mark's read never reaches it.
    private static StreamPartitionManager copyServingMarkOf(Epoch epochA, AtomicReference<Epoch> committedEpoch, int records, long through) {
        var copy = streamPartitionManager();

        copy.placementRoleSupplier((_, _) -> Role.REPLICA);
        copy.ownerEpochSource((_, _) -> committedEpoch.get());
        copy.createStream(config()).onFailure(cause -> fail(cause.message()));
        for (var record = 0; record < records; record++) {
            copy.appendRecovered(STREAM, PARTITION, ("copy-" + record).getBytes(UTF_8), 1L + record)
                .onFailure(cause -> fail("append: " + cause.message()));
        }
        copy.commitAdvanced(STREAM, PARTITION, through, epochA);
        copy.markVerified(STREAM, PARTITION, records - 1L);
        copy.markVerifiedForEpoch(STREAM, PARTITION, epochA);

        return copy;
    }

    /// The cause a read failed with; a served read fails the test with what it served.
    private static Cause refusalOf(Promise<List<OffHeapRingBuffer.RawEvent>> read) {
        return read.await()
                   .fold(cause -> cause, events -> fail("served " + texts(events)));
    }

    private static List<String> texts(List<OffHeapRingBuffer.RawEvent> events) {
        return events.stream()
                     .map(event -> new String(event.data(), UTF_8))
                     .toList();
    }

    private StreamReadRouter forwardingRouter() {
        return forwardingRouter(assignee);
    }

    private StreamReadRouter forwardingRouter(StreamPartitionManager local) {
        return routerOver(local, owner, TimeSpan.timeSpan(5).seconds());
    }

    /// The forward to the owner is never answered: every read forward ends in its 300 ms timeout.
    private StreamReadRouter unreachableOwnerRouter(StreamPartitionManager local) {
        StreamForwardTransport dropping = AcceptingStreamForwardTransport.accepting((_, _) -> forwardedReads.incrementAndGet());

        return routerOver(local, StreamForwardClient.streamForwardClient(ASSIGNEE, dropping, TimeSpan.timeSpan(300).millis()));
    }

    private StreamReadRouter routerOver(StreamPartitionManager local, StreamPartitionManager target, TimeSpan timeout) {
        var clientRef = new StreamForwardClient[1];
        StreamForwardTransport ownerToAssignee = AcceptingStreamForwardTransport.accepting((_, message) -> clientRef[0].onReadForwardResponse(boundsOf((ReadForwardResponse) message)));
        var ownerHandler = StreamForwardHandler.streamForwardHandler(OWNER, target, ownerToAssignee);
        StreamForwardTransport assigneeToOwner = AcceptingStreamForwardTransport.accepting((_, message) -> forwardToOwnerHandler(ownerHandler, (ReadForward) message));

        clientRef[0] = StreamForwardClient.streamForwardClient(ASSIGNEE, assigneeToOwner, timeout);

        return routerOver(local, clientRef[0]);
    }

    private static StreamReadRouter routerOver(StreamPartitionManager local, StreamForwardClient client) {
        return StreamReadRouter.streamReadRouter(local,
                                                 Option.none(),
                                                 Option.some(client),
                                                 ASSIGNEE,
                                                 (_, _) -> Option.some(OWNER),
                                                 StreamReadForwardMetrics.NOOP);
    }

    private void forwardToOwnerHandler(StreamForwardHandler ownerHandler, ReadForward message) {
        forwardedReads.incrementAndGet();
        ownerHandler.onReadForward(message);
    }

    /// What the wire carries when the owner's ring is closing: `tailOffset()` answers -1, the visible head is as it was.
    private ReadForwardResponse boundsOf(ReadForwardResponse response) {
        return Option.option(forcedBounds).map(bounds -> withBounds(response, bounds[0], bounds[1])).or(() -> closingAfterRealAnswers(response));
    }

    private ReadForwardResponse closingAfterRealAnswers(ReadForwardResponse response) {
        return answers.incrementAndGet() > realAnswers
               ? withBounds(response, -1L, response.visibleHead())
               : response;
    }

    private static ReadForwardResponse withBounds(ReadForwardResponse response, long earliestRetained, long visibleHead) {
        return new ReadForwardResponse(response.sender(),
                                       response.correlationId(),
                                       response.success(),
                                       response.events(),
                                       response.truncated(),
                                       response.errorMessage(),
                                       earliestRetained,
                                       visibleHead,
                                       response.history(),
                                       response.historyVouched(),
                                       response.ownerEpoch(),
                                       response.divergenceResumeAt(),
                                       response.divergenceLossFrom(),
                                       response.refusal());
    }

    private StreamPartitionManager.EpochRead.Position positionOfForwardedRead(long fromOffset, long earliestRetained, long visibleHead) {
        forcedBounds = new long[] {earliestRetained, visibleHead};

        return forwardingRouter().readValidated(STREAM, PARTITION, fromOffset, 3, Epoch.ZERO).await().unwrap().position();
    }

    @Test
    void forwardedRead_belowAKnownTail_isATierRead() {
        assertThat(positionOfForwardedRead(5L, 20L, 29L)).isEqualTo(StreamPartitionManager.EpochRead.Position.TIER);
    }

    @Test
    void forwardedRead_atAKnownTail_isARingRead() {
        assertThat(positionOfForwardedRead(20L, 20L, 29L)).isEqualTo(StreamPartitionManager.EpochRead.Position.RING);
    }

    @Test
    void forwardedRead_ownerRingTailUnknown_isNamedUnknown() {
        assertThat(positionOfForwardedRead(5L, -1L, 29L)).isEqualTo(StreamPartitionManager.EpochRead.Position.UNKNOWN);
    }

    /// An owner that answers no bounds at all (it holds no ring) names no tier read.
    @Test
    void forwardedRead_ownerWithoutARing_isARingRead() {
        assertThat(positionOfForwardedRead(5L, -1L, -1L)).isEqualTo(StreamPartitionManager.EpochRead.Position.RING);
    }

    private void consume(StreamReadRouter router) {
        var created = StreamConsumerRuntime.streamConsumerRuntime(assignee,
                                                                  DeadLetterHandler.deadLetterHandler(),
                                                                  nullStore(),
                                                                  StreamConsumerRuntime.validatingReader((_, _, _, _) -> Promise.success(List.of()),
                                                                                                         router::readValidated));

        created.operatorWarnings(OperatorWarningSink.handingOffTo(warnings::add));
        runtime = created;
        created.subscribe(STREAM, PARTITION, ConsumerConfig.consumerConfig(GROUP), (offset, _, _) -> deliver(offset));
    }

    private Promise<Unit> deliver(long offset) {
        delivered.add(offset);

        return Promise.unitPromise();
    }

    /// The stream under test: a 10-entry ring. The hot window is declared equal to the retention so the ring holds the same
    /// 10 entries whether it is sized from retention or from the declared window; `max_event_size` is pinned to the first
    /// segment (256 KiB) so the ring's first data region is the same in both.
    private static StreamConfig config() {
        return HotWindowFixture.declaredHot(StreamConfig.streamConfig(STREAM,
                                                                      1,
                                                                      RetentionPolicy.retentionPolicy(10, 1_048_576L, 600_000L),
                                                                      "earliest",
                                                                      256 * 1024L),
                                            10,
                                            1_048_576L);
    }

    private static ConsumerCursorStore nullStore() {
        return new ConsumerCursorStore() {
            @Override
            public Promise<CommitOutcome> commit(String group, String stream, int partition, long offset) {
                return Promise.success(CommitOutcome.persisted());
            }

            @Override
            public Promise<Option<Long>> fetch(String group, String stream, int partition) {
                return Promise.success(Option.none());
            }
        };
    }

    private List<OperatorWarningCode> codes() {
        return warnings.stream().map(OperatorWarning::code).toList();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitUntil(BooleanSupplier done, String what) {
        var deadline = System.currentTimeMillis() + 10_000L;

        while (!done.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(done.getAsBoolean()).as("%s within 10 s (delivered %s, warnings %s)", what, delivered, codes()).isTrue();
    }
}
