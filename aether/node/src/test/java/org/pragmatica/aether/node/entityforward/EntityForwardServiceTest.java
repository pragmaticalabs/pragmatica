// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.entityforward;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityCancelTimerForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityCreateForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityGetForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityGetForwardResponse;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityScheduleTimerForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityScheduleTimerForwardResponse;
import org.pragmatica.aether.resource.entity.EntityForwardRegistry;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Deadline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.entityforward.EntityForwardService.entityForwardService;

/// The correlation protocol at the service seam, and #596 review S6: a send the transport REFUSES fails
/// the caller immediately with a typed cause — 02w measured the alternative, where every forward to an
/// unreachable owner silently blocked its caller for the full 30s correlation timeout.
class EntityForwardServiceTest {
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OWNER = new NodeId("owner-node");
    private static final TimeSpan LONG_TIMEOUT = TimeSpan.timeSpan(60).seconds(); // never fires in-test

    private RecordingSender sender;
    private EntityForwardService service;

    @BeforeEach
    void setUp() {
        sender = new RecordingSender();
        service = entityForwardService(SELF, sender, LONG_TIMEOUT);
    }

    @Test
    void forwardUpdate_sent_thenResponse_resolvesWithTheState() {
        var promise = service.forwardUpdate(OWNER, "orders", bytes("k1"), bytes("Add:5"));
        var request = (EntityUpdateForward) sender.lastMessage();

        service.onEntityUpdateForwardResponse(EntityUpdateForwardResponse.successResponse(OWNER,
                                                                                          request.correlationId(),
                                                                                          bytes("107")));

        var result = promise.await();

        assertThat(result.isSuccess()).isTrue();

        byte[] state = result.fold(cause -> bytes("failed: " + cause.message()), value -> value);

        assertThat(new String(state, StandardCharsets.UTF_8)).isEqualTo("107");
    }

    /// THE fast-fail pin. The transport refuses the send, so the promise must already be resolved —
    /// failed, with a cause naming the refusal — without any response and without the timeout.
    @Test
    void forwardCreate_sendRefused_failsImmediately_withTheRefusalNamed() {
        sender.refuseWith(new WriteOutcome.ConnectionDead(OWNER));

        // A SHORT-timeout service, so a broken fast-fail degrades to the timeout cause in ~1s and the
        // assertion below goes red on the cause text — rather than this test hanging for a minute.
        var result = entityForwardService(SELF, sender, TimeSpan.timeSpan(1).seconds())
                            .forwardCreate(OWNER, "orders", bytes("k1"), bytes("5"))
                            .await();

        assertThat(result.isFailure()).isTrue();

        String refusal = result.fold(cause -> cause.message(), _ -> "unexpectedly succeeded");

        assertThat(refusal)
            .as("the caller must learn the owner was never reached, not wait out a timeout")
            .contains("refused at send")
            .contains("ConnectionDead");
    }

    /// A raced or late response for a fast-failed correlation resolves nothing and throws nothing.
    @Test
    void forwardDelete_sendRefused_lateResponseForThatCorrelation_isDroppedHarmlessly() {
        sender.refuseWith(new WriteOutcome.NoPeerState(OWNER));

        var shortService = entityForwardService(SELF, sender, TimeSpan.timeSpan(1).seconds());
        var result = shortService.forwardDelete(OWNER, "orders", bytes("k1")).await();
        var request = sender.lastMessage();
        var correlationId = ((EntityForwardMessage.EntityDeleteForward) request).correlationId();

        shortService.onEntityUpdateForwardResponse(EntityUpdateForwardResponse.successResponse(OWNER, correlationId, bytes("")));

        assertThat(result.isFailure()).isTrue(); // still the fast-fail outcome, not flipped by the late response
    }

    @Test
    void onEntityCreateForward_registeredKeyspace_appliesAndAnswersSuccess() {
        service.register("orders", echoTarget());

        service.onEntityCreateForward(EntityCreateForward.entityCreateForward(OWNER, "corr-1", "orders", bytes("k1"), bytes("5"), Deadline.NO_BUDGET));

        var response = (EntityUpdateForwardResponse) sender.lastMessage();

        assertThat(response.success()).isTrue();
        assertThat(new String(response.state(), StandardCharsets.UTF_8)).isEqualTo("created:5");
    }

    @Test
    void onEntityCreateForward_unknownKeyspace_answersFailure_neverSilence() {
        service.onEntityCreateForward(EntityCreateForward.entityCreateForward(OWNER, "corr-2", "ghosts", bytes("k1"), bytes("5"), Deadline.NO_BUDGET));

        var response = (EntityUpdateForwardResponse) sender.lastMessage();

        assertThat(response.success()).isFalse();
        assertThat(response.errorMessage()).contains("ghosts");
    }

    /// The unload half of the registry (`DurableEntityFactory`'s close hook). After the keyspace's
    /// entity resource unloads, an arriving forward must reach the SAME typed refusal a never-registered
    /// keyspace gets — not the unloaded entity, whose slice classloader is gone. Armed by the first
    /// assertion: the identical forward succeeds while the registration stands, so the refusal below is
    /// the unregister and not a broken fixture.
    @Test
    void onEntityCreateForward_afterUnregister_refusesWithTheUnknownKeyspaceCause() {
        service.register("orders", echoTarget());
        service.onEntityCreateForward(EntityCreateForward.entityCreateForward(OWNER, "corr-3", "orders", bytes("k1"), bytes("5"), Deadline.NO_BUDGET));

        assertThat(((EntityUpdateForwardResponse) sender.lastMessage()).success())
            .as("registered keyspace must serve the forward — else the refusal below proves nothing")
            .isTrue();

        service.unregister("orders");
        service.onEntityCreateForward(EntityCreateForward.entityCreateForward(OWNER, "corr-4", "orders", bytes("k1"), bytes("5"), Deadline.NO_BUDGET));

        assertRefusedAsUnknownKeyspace((EntityUpdateForwardResponse) sender.lastMessage());
    }

    @Test
    void onEntityUpdateForward_afterUnregister_refusesWithTheUnknownKeyspaceCause() {
        service.register("orders", echoTarget());
        service.onEntityUpdateForward(EntityUpdateForward.entityUpdateForward(OWNER, "corr-5", "orders", bytes("k1"), bytes("Add:5"), Deadline.NO_BUDGET));

        assertThat(((EntityUpdateForwardResponse) sender.lastMessage()).success())
            .as("registered keyspace must serve the forward — else the refusal below proves nothing")
            .isTrue();

        service.unregister("orders");
        service.onEntityUpdateForward(EntityUpdateForward.entityUpdateForward(OWNER, "corr-6", "orders", bytes("k1"), bytes("Add:5"), Deadline.NO_BUDGET));

        assertRefusedAsUnknownKeyspace((EntityUpdateForwardResponse) sender.lastMessage());
    }

    /// Idempotent by contract: the close hook runs per keyspace and a node that never hosted one still
    /// calls through. Unregistering an unknown keyspace must not disturb the ones that ARE registered.
    @Test
    void unregister_unknownKeyspace_leavesRegisteredKeyspacesServing() {
        service.register("orders", echoTarget());

        service.unregister("ghosts");

        service.onEntityCreateForward(EntityCreateForward.entityCreateForward(OWNER, "corr-7", "orders", bytes("k1"), bytes("5"), Deadline.NO_BUDGET));

        var response = (EntityUpdateForwardResponse) sender.lastMessage();

        assertThat(response.success()).isTrue();
        assertThat(new String(response.state(), StandardCharsets.UTF_8)).isEqualTo("created:5");
    }

    /// Deadline budget, exhausted case: the command must never be SENT — the caller is gone, and an
    /// unsendable ack would leave the owner doing work nobody collects. The refusal cause says so.
    @Test
    void forwardCreate_underExhaustedBudget_refusesBeforeSending() {
        var result = Deadline.runWith(Deadline.fromWireMillis(0),
                                      () -> service.forwardCreate(OWNER, "orders", bytes("k1"), bytes("5")))
                             .await();

        assertThat(result.isFailure()).isTrue();

        String refusal = result.fold(Cause::message, _ -> "unexpectedly succeeded");

        assertThat(refusal)
            .as("an exhausted budget refuses up front, naming that the command never left this node")
            .contains("budget exhausted")
            .contains("never sent");
        assertThat(sender.messageCount()).as("nothing may reach the transport").isZero();
    }

    /// The floor, not just zero: below ~50ms the owner round trip cannot complete, so sending would
    /// only widen the unknown-outcome window on a non-idempotent write. Refused before the send.
    @Test
    void forwardCreate_underBudgetBelowTheFloor_refusesBeforeSending() {
        var result = Deadline.runWith(Deadline.fromWireMillis(30),
                                      () -> service.forwardCreate(OWNER, "orders", bytes("k1"), bytes("5")))
                             .await();

        assertThat(result.isFailure()).isTrue();
        assertThat(sender.messageCount()).as("a sub-floor budget must not buy a doomed send").isZero();
    }

    /// Deadline budget, bounded case: the correlation wait is min(configured, remaining). With a 60s
    /// configured timeout and ~150ms of budget, an unanswered forward must fail in well under the
    /// configured minute — the pre-fix behavior (waiting the full configured timeout) turns this red.
    @Test
    void forwardCreate_underSmallRemainingBudget_timesOutAtRemainingNotConfigured() {
        var startedAt = System.nanoTime();
        var result = Deadline.runWith(Deadline.fromWireMillis(150),
                                      () -> service.forwardCreate(OWNER, "orders", bytes("k1"), bytes("5")))
                             .await();
        var elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result.isFailure()).isTrue();
        assertThat(sender.messageCount()).as("within budget, the command IS sent").isEqualTo(1);
        assertThat(elapsedMillis)
            .as("the wait is capped by the remaining budget, not the 60s configured timeout")
            .isLessThan(10_000);

        String cause = result.fold(Cause::message, _ -> "unexpectedly succeeded");

        assertThat(cause).contains("timed out");
    }

    /// Stage 2 of deadline propagation, RECEIVER half (#634 follow-up): a command that arrived with its
    /// budget already spent must be refused WITHOUT touching the entity. The sender's hop timeout has
    /// already fired, so applying would be a non-idempotent write whose ack nobody collects — the
    /// zombie-dispatch amplification 02w measured. Armed by the second half: the identical command with
    /// budget applies and reaches the entity, so the refusal is the budget and not a broken fixture.
    @Test
    void onEntityUpdateForward_arrivedExpired_refusesWithoutTouchingTheEntity() {
        var applications = new AtomicInteger();

        service.register("orders", countingTarget(applications));
        service.onEntityUpdateForward(EntityUpdateForward.entityUpdateForward(OWNER, "corr-expired", "orders",
                                                                              bytes("k1"), bytes("Add:5"), 1L));

        var refusal = (EntityUpdateForwardResponse) sender.lastMessage();

        assertThat(refusal.success()).isFalse();
        assertThat(refusal.failureType())
            .as("the sender reconstructs a cause from failureType — an untyped refusal reaches the caller"
                + " as an unexplained generic error")
            .isEqualTo("ForwardBudgetExhausted");
        assertThat(applications.get())
            .as("the entity must NEVER be touched: the ack has no collector, and the write is not idempotent")
            .isZero();

        service.onEntityUpdateForward(EntityUpdateForward.entityUpdateForward(OWNER, "corr-live", "orders",
                                                                              bytes("k1"), bytes("Add:5"),
                                                                              Deadline.NO_BUDGET));

        assertThat(((EntityUpdateForwardResponse) sender.lastMessage()).success())
            .as("the same command WITH budget applies — else the refusal above is a broken fixture")
            .isTrue();
        assertThat(applications.get()).isEqualTo(1);
    }

    /// The floor is 50ms, not zero: a budget that is positive but too small to complete a round trip is
    /// refused for the same reason an expired one is.
    @Test
    void onEntityCreateForward_arrivedBelowTheBudgetFloor_refusesWithoutTouchingTheEntity() {
        var applications = new AtomicInteger();

        service.register("orders", countingTarget(applications));
        service.onEntityCreateForward(EntityCreateForward.entityCreateForward(OWNER, "corr-floor", "orders",
                                                                               bytes("k1"), bytes("5"), 30L));

        assertThat(((EntityUpdateForwardResponse) sender.lastMessage()).failureType()).isEqualTo("ForwardBudgetExhausted");
        assertThat(applications.get()).as("a sub-floor budget must not buy a doomed apply").isZero();
    }

    // === #596 read half: the get protocol ===

    /// The full sender-side round trip: request out, PRESENT response in, promise resolves to the
    /// explicit some — the `present` flag decides, never the state length.
    @Test
    void forwardGet_sent_thenPresentResponse_resolvesWithSomeState() {
        var promise = service.forwardGet(OWNER, "orders", bytes("k1"));
        var request = (EntityGetForward) sender.lastMessage();

        service.onEntityGetForwardResponse(EntityGetForwardResponse.presentResponse(OWNER,
                                                                                    request.correlationId(),
                                                                                    bytes("41")));

        var result = promise.await();

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(state -> {
            assertThat(state.isPresent()).isTrue();
            state.onPresent(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("41"));
        });
    }

    /// ABSENT is a SUCCESS with an explicit empty Option — never a failure, never inferred from
    /// zero-length state bytes.
    @Test
    void forwardGet_absentResponse_resolvesWithEmptySuccess() {
        var promise = service.forwardGet(OWNER, "orders", bytes("k1"));
        var request = (EntityGetForward) sender.lastMessage();

        service.onEntityGetForwardResponse(EntityGetForwardResponse.absentResponse(OWNER, request.correlationId()));

        var result = promise.await();

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(state -> assertThat(state.isEmpty()).isTrue());
    }

    /// Receiver side: a registered keyspace serves both arms of the `present` flag through the
    /// target's explicit Option — the echo target answers present only for the key "present".
    @Test
    void onEntityGetForward_registeredKeyspace_answersPresentAndAbsentExplicitly() {
        service.register("orders", echoTarget());

        service.onEntityGetForward(EntityGetForward.entityGetForward(OWNER, "corr-g1", "orders", bytes("present"), Deadline.NO_BUDGET));
        var present = (EntityGetForwardResponse) sender.lastMessage();

        service.onEntityGetForward(EntityGetForward.entityGetForward(OWNER, "corr-g2", "orders", bytes("missing"), Deadline.NO_BUDGET));
        var absent = (EntityGetForwardResponse) sender.lastMessage();

        assertThat(present.success()).isTrue();
        assertThat(present.present()).isTrue();
        assertThat(new String(present.state(), StandardCharsets.UTF_8)).isEqualTo("41");
        assertThat(absent.success()).isTrue();
        assertThat(absent.present()).as("absence is the explicit flag, not a failure").isFalse();
    }

    /// The read shares the mutation trio's budget discipline: an arrived-expired read is refused
    /// without touching the entity — serving an answer nobody collects is wasted fold work.
    @Test
    void onEntityGetForward_arrivedExpired_refusesWithoutServing() {
        service.register("orders", echoTarget());

        service.onEntityGetForward(EntityGetForward.entityGetForward(OWNER, "corr-g3", "orders", bytes("present"), 30L));

        var response = (EntityGetForwardResponse) sender.lastMessage();

        assertThat(response.success()).isFalse();
        assertThat(response.failureType()).isEqualTo("ForwardBudgetExhausted");
    }

    /// Unknown keyspace answers a typed failure, never silence — same contract as the writes.
    @Test
    void onEntityGetForward_unknownKeyspace_answersFailure() {
        service.onEntityGetForward(EntityGetForward.entityGetForward(OWNER, "corr-g4", "ghosts", bytes("k"), Deadline.NO_BUDGET));

        var response = (EntityGetForwardResponse) sender.lastMessage();

        assertThat(response.success()).isFalse();
        assertThat(response.errorMessage()).contains("ghosts");
    }

    /// Stage 2, SENDER half: the remaining budget is stamped ONTO THE WIRE, which is the only way the
    /// owner can make the refusal decision above. Both directions are pinned in one test — a bounded
    /// caller ships what is left of its budget, an unbounded one ships NO_BUDGET — because a stamp that
    /// is always present or always absent would satisfy either half alone.
    ///
    /// Driven through a SHORT-timeout service so both forwards resolve (unanswered, at their own
    /// timeout) inside the test rather than lingering as scheduled work after it.
    @Test
    void forwardUpdate_stampsTheRemainingBudgetOnTheWire_andNoBudgetWhenUnbounded() {
        var shortService = entityForwardService(SELF, sender, TimeSpan.timeSpan(200).millis());

        Deadline.runWith(Deadline.fromWireMillis(5_000),
                         () -> shortService.forwardUpdate(OWNER, "orders", bytes("k1"), bytes("Add:5")))
                .await();

        assertThat(((EntityUpdateForward) sender.lastMessage()).remainingMillis())
            .as("the owner must receive what is LEFT of the caller's budget, never more than it started with")
            .isPositive()
            .isLessThanOrEqualTo(5_000L);

        shortService.forwardUpdate(OWNER, "orders", bytes("k2"), bytes("Add:6")).await();

        assertThat(((EntityUpdateForward) sender.lastMessage()).remainingMillis())
            .as("an unbounded caller must ship NO_BUDGET — a fabricated number would make the receiver"
                + " refuse work that had all the time it needed")
            .isEqualTo(Deadline.NO_BUDGET);
    }

    // === #345 I4 timer schedule: the caller's token crosses both directions ===

    /// The token is minted by the caller and must survive the whole round trip: onto the wire in the
    /// request, into the registered target, back in the response, and out of the correlation map unchanged.
    /// Every hop is asserted, because a drop or a re-mint at any one of them hands the caller a handle to a
    /// timer nobody planted — and, when the response is lost, no handle at all to one that WAS planted.
    ///
    /// The target answers a token DISTINCT from the one it was handed, and that is what makes the second
    /// half non-vacuous. With an echoing target, a service that filled the response from `request.token()`
    /// instead of from the owner's answer would be indistinguishable from a correct one. The distinct marker
    /// separates the two sources, so the response is pinned to what the ENTITY said. Surfacing the owner's
    /// answer verbatim is this seam's whole job; comparing it against what was sent belongs one layer up, in
    /// `PartitionFencedDurableEntity`, which fails a mismatch with `TimerTokenMismatch`.
    @Test
    void forwardScheduleTimer_putsTheCallersTokenOnTheWire_andSurfacesTheOwnersAnswer() {
        service.register("orders", echoTarget());

        var promise = service.forwardScheduleTimer(OWNER, "orders", bytes("k1"), 10_000L, bytes("Add:5"), "caller-token");
        var request = (EntityScheduleTimerForward) sender.lastMessage();

        assertThat(request.token()).as("the caller's token must be on the wire, not minted at the owner").isEqualTo("caller-token");

        service.onEntityScheduleTimerForward(request);

        var response = (EntityScheduleTimerForwardResponse) sender.lastMessage();

        assertThat(response.success()).isTrue();
        assertThat(response.token())
            .as("the response must carry what the TARGET answered — a service echoing request.token() back"
                + " would answer 'caller-token' here without ever consulting the entity")
            .isEqualTo("applied:caller-token");

        service.onEntityScheduleTimerForwardResponse(response);

        var result = promise.await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.fold(Cause::message, token -> token))
            .as("and the sender surfaces the owner's answer unaltered, for the layer above to verify")
            .isEqualTo("applied:caller-token");
    }

    // === #345 I4 timer cancel: the same correlation protocol, the other direction ===

    /// The sender half of cancel. It rides the mutation trio's correlation map rather than a carrier of its
    /// own, so the pin is that the TOKEN reaches the wire and that an `EntityUpdateForwardResponse` on that
    /// correlation resolves the caller — a cancel that correlated on the wrong map would hang until timeout.
    @Test
    void forwardCancelTimer_putsTheTokenOnTheWire_andResolvesOnTheOwnersAnswer() {
        var promise = service.forwardCancelTimer(OWNER, "orders", bytes("k1"), "tok-7");
        var request = (EntityCancelTimerForward) sender.lastMessage();

        assertThat(request.token()).as("cancel names its timer by token; there is no cancel-by-key verb").isEqualTo("tok-7");
        assertThat(new String(request.key(), StandardCharsets.UTF_8)).isEqualTo("k1");

        service.onEntityUpdateForwardResponse(EntityUpdateForwardResponse.successResponse(OWNER,
                                                                                          request.correlationId(),
                                                                                          new byte[0]));

        assertThat(promise.await().isSuccess())
            .as("the empty answer bytes cancel carries by contract are discarded, not decoded")
            .isTrue();
    }

    /// The receiver half. A cancel answers [Unit] and its response carries a FIXED empty payload, so the
    /// arrived token leaves no trace in what goes back on the wire — the recording target is the only place
    /// a dropped or substituted token can be seen, and asserting on the response alone would pass with the
    /// entity asked to cancel nothing in particular.
    @Test
    void onEntityCancelTimerForward_registeredKeyspace_cancelsWithTheArrivedToken() {
        var applications = new AtomicInteger();
        var cancelledTokens = new ArrayList<String>();

        service.register("orders", countingTarget(applications, cancelledTokens));
        service.onEntityCancelTimerForward(EntityCancelTimerForward.entityCancelTimerForward(OWNER, "corr-c1", "orders",
                                                                                              bytes("k1"), "tok-7",
                                                                                              Deadline.NO_BUDGET));

        var response = (EntityUpdateForwardResponse) sender.lastMessage();

        assertThat(response.success()).isTrue();
        assertThat(applications.get()).as("the cancel must reach the entity, not be answered from the seam").isEqualTo(1);
        assertThat(cancelledTokens)
            .as("the entity must be asked to cancel the token that ARRIVED — there is no cancel-by-key verb,"
                + " so a lost token cancels nothing while still answering success")
            .containsExactly("tok-7");
    }

    /// The unload half, for cancel: after the keyspace's entity resource unloads, an arriving cancel must
    /// reach the same typed refusal a never-registered keyspace gets. Armed by the first assertion — the
    /// identical cancel succeeds while the registration stands.
    @Test
    void onEntityCancelTimerForward_afterUnregister_refusesWithTheUnknownKeyspaceCause() {
        service.register("orders", echoTarget());
        service.onEntityCancelTimerForward(EntityCancelTimerForward.entityCancelTimerForward(OWNER, "corr-c4", "orders",
                                                                                              bytes("k1"), "tok-7",
                                                                                              Deadline.NO_BUDGET));

        assertThat(((EntityUpdateForwardResponse) sender.lastMessage()).success())
            .as("registered keyspace must serve the cancel — else the refusal below proves nothing")
            .isTrue();

        service.unregister("orders");
        service.onEntityCancelTimerForward(EntityCancelTimerForward.entityCancelTimerForward(OWNER, "corr-c5", "orders",
                                                                                              bytes("k1"), "tok-7",
                                                                                              Deadline.NO_BUDGET));

        assertRefusedAsUnknownKeyspace((EntityUpdateForwardResponse) sender.lastMessage());
    }

    /// Cancel shares the arrived-budget discipline of every other forward: a cancel whose sender has already
    /// timed out is refused WITHOUT touching the entity. Armed by the second half — the identical cancel with
    /// budget does reach it — so the refusal is the budget and not a broken fixture.
    @Test
    void onEntityCancelTimerForward_arrivedExpired_refusesWithoutTouchingTheEntity() {
        var applications = new AtomicInteger();

        service.register("orders", countingTarget(applications));
        service.onEntityCancelTimerForward(EntityCancelTimerForward.entityCancelTimerForward(OWNER, "corr-c2", "orders",
                                                                                              bytes("k1"), "tok-7", 1L));

        var refusal = (EntityUpdateForwardResponse) sender.lastMessage();

        assertThat(refusal.success()).isFalse();
        assertThat(refusal.failureType()).isEqualTo("ForwardBudgetExhausted");
        assertThat(applications.get()).as("an ack nobody collects must not cost a fenced append").isZero();

        service.onEntityCancelTimerForward(EntityCancelTimerForward.entityCancelTimerForward(OWNER, "corr-c3", "orders",
                                                                                              bytes("k1"), "tok-7",
                                                                                              Deadline.NO_BUDGET));

        assertThat(((EntityUpdateForwardResponse) sender.lastMessage()).success())
            .as("the same cancel WITH budget reaches the entity — else the refusal above proves nothing")
            .isTrue();
        assertThat(applications.get()).isEqualTo(1);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /// A forward target that counts how many times the entity was actually reached — the difference
    /// between "refused before dispatch" and "applied then failed" is invisible from the response alone.
    private static EntityForwardRegistry.ForwardTarget countingTarget(AtomicInteger applications) {
        return countingTarget(applications, new ArrayList<>());
    }

    /// The counting target, additionally recording the CANCEL tokens it was handed. Cancel answers [Unit]
    /// and its response carries a fixed empty payload, so unlike every other verb the arrived token cannot
    /// be recovered from the wire — recording it here is the only way a test can pin what the entity was
    /// asked to cancel.
    private static EntityForwardRegistry.ForwardTarget countingTarget(AtomicInteger applications,
                                                                       List<String> cancelledTokens) {
        return new EntityForwardRegistry.ForwardTarget() {
            @Override
            public Promise<byte[]> applyForwarded(byte[] key, byte[] command) {
                applications.incrementAndGet();

                return Promise.success(bytes("updated"));
            }

            @Override
            public Promise<byte[]> createForwarded(byte[] key, byte[] initial) {
                applications.incrementAndGet();

                return Promise.success(bytes("created"));
            }

            @Override
            public Promise<byte[]> deleteForwarded(byte[] key) {
                applications.incrementAndGet();

                return Promise.success(new byte[0]);
            }

            @Override
            public Promise<Option<byte[]>> getForwarded(byte[] key) {
                applications.incrementAndGet();

                return Promise.success(Option.some(bytes("41")));
            }

            @Override
            public Promise<String> scheduleTimerForwarded(byte[] key, long delayMillis, byte[] onFire, String token) {
                applications.incrementAndGet();

                return Promise.success(token);
            }

            @Override
            public Promise<Unit> cancelTimerForwarded(byte[] key, String token) {
                applications.incrementAndGet();
                cancelledTokens.add(token);

                return Promise.unitPromise();
            }
        };
    }

    /// A live keyspace's forward target: it answers with a marker BUILT FROM what it was handed, so a
    /// successful response proves the command reached THIS registration rather than any other path — and,
    /// for the token-bearing verbs, that the response was filled from the entity's answer rather than
    /// copied back off the request.
    private static EntityForwardRegistry.ForwardTarget echoTarget() {
        return new EntityForwardRegistry.ForwardTarget() {
            @Override
            public Promise<byte[]> applyForwarded(byte[] key, byte[] command) {
                return Promise.success(bytes("updated"));
            }

            @Override
            public Promise<byte[]> createForwarded(byte[] key, byte[] initial) {
                return Promise.success(bytes("created:" + new String(initial, StandardCharsets.UTF_8)));
            }

            @Override
            public Promise<byte[]> deleteForwarded(byte[] key) {
                return Promise.success(new byte[0]);
            }

            /// Present for the key literally named "present", explicit-absent for everything else —
            /// so the response tests can drive BOTH arms of the `present` flag.
            @Override
            public Promise<Option<byte[]>> getForwarded(byte[] key) {
                return "present".equals(new String(key, StandardCharsets.UTF_8))
                       ? Promise.success(Option.some(bytes("41")))
                       : Promise.success(Option.none());
            }

            /// Answers a token DISTINGUISHABLE from the arrived one. A real owner echoes what it applied,
            /// but a fake that echoes cannot tell a response filled from the entity's answer from one
            /// filled off `request.token()` — both would read "caller-token". The marker separates them
            /// while still proving the caller's token is what arrived.
            @Override
            public Promise<String> scheduleTimerForwarded(byte[] key, long delayMillis, byte[] onFire, String token) {
                return Promise.success("applied:" + token);
            }

            /// Cancel answers [Unit] — the outcome IS the answer, and this target's is success, which is
            /// what the unregister test needs on the "still registered" side of its arming assertion.
            @Override
            public Promise<Unit> cancelTimerForwarded(byte[] key, String token) {
                return Promise.unitPromise();
            }
        };
    }

    /// The refusal must be the TYPED one — `failureType` is what the sender reconstructs a cause from,
    /// so an untyped failure would reach the caller as an unexplained generic error.
    private static void assertRefusedAsUnknownKeyspace(EntityUpdateForwardResponse response) {
        assertThat(response.success()).isFalse();
        assertThat(response.failureType()).isEqualTo("UnknownKeyspace");
        assertThat(response.errorMessage()).contains("no entity registered for keyspace orders");
    }

    private static final class RecordingSender implements EntityForwardService.Sender {
        private final List<EntityForwardMessage> messages = new ArrayList<>();
        private WriteOutcome refusal;

        void refuseWith(WriteOutcome outcome) {
            this.refusal = outcome;
        }

        EntityForwardMessage lastMessage() {
            return messages.getLast();
        }

        int messageCount() {
            return messages.size();
        }

        @Override
        public Promise<WriteOutcome> send(NodeId target, EntityForwardMessage message) {
            messages.add(message);

            return Promise.success(refusal == null ? new WriteOutcome.Sent(target) : refusal);
        }
    }
}
