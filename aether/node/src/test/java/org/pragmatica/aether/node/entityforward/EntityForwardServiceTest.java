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
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityCreateForward;
import org.pragmatica.aether.resource.entity.EntityForwardRegistry;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForwardResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
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

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /// A forward target that counts how many times the entity was actually reached — the difference
    /// between "refused before dispatch" and "applied then failed" is invisible from the response alone.
    private static EntityForwardRegistry.ForwardTarget countingTarget(AtomicInteger applications) {
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
        };
    }

    /// A live keyspace's forward target: it echoes what it was handed, so a successful response proves
    /// the command reached THIS registration rather than any other path.
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
