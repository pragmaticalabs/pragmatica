// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardHandler;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForward;
import org.pragmatica.aether.stream.forward.StreamForwardMessage.ReadForwardResponse;
import org.pragmatica.aether.stream.forward.StreamForwardTransport;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.segment.ConsumerCursorStore;
import org.pragmatica.consensus.NodeId;
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

    private StreamReadRouter forwardingRouter() {
        var clientRef = new StreamForwardClient[1];
        StreamForwardTransport ownerToAssignee = (_, message) -> clientRef[0].onReadForwardResponse(boundsOf((ReadForwardResponse) message));
        var ownerHandler = StreamForwardHandler.streamForwardHandler(OWNER, owner, ownerToAssignee);
        StreamForwardTransport assigneeToOwner = (_, message) -> ownerHandler.onReadForward((ReadForward) message);

        clientRef[0] = StreamForwardClient.streamForwardClient(ASSIGNEE, assigneeToOwner, TimeSpan.timeSpan(5).seconds());

        return StreamReadRouter.streamReadRouter(assignee,
                                                 Option.none(),
                                                 Option.some(clientRef[0]),
                                                 ASSIGNEE,
                                                 (_, _) -> Option.some(OWNER),
                                                 StreamReadForwardMetrics.NOOP);
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

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10, 1_048_576L, 600_000L), "earliest");
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
