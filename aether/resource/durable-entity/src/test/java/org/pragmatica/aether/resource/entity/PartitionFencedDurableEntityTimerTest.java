// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #345 I4 — durable per-entity timers at the entity level: schedule, cancel, and the tick that fires them.
///
/// ## Two things this suite does deliberately
/// **The tick takes an EXPLICIT instant.** A test that slept would be slow, flaky, and — worse — unable to
/// prove the boundary it names: passing the instant is what lets a test assert that a timer one second
/// short of due does NOT fire, which no amount of sleeping demonstrates.
///
/// **The tick is fire-and-forget, so every assertion on a fired EFFECT is barriered.** `fireDueTimers`
/// submits work onto each key's serialization tail and returns; asserting straight after it would pass
/// against code that never fired at all. A test that reads an effect therefore either flushes that tail
/// with a read on the same key ([#settle]) or waits, bounded, for the effect ([#awaitCondition]) — never
/// both. Tests that assert on the tick's OWN bookkeeping instead — the readiness backoff below, or a
/// rebuild count — need neither barrier, because the tick sets what they read before it returns.
class PartitionFencedDurableEntityTimerTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITIONS = 4;
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OTHER = new NodeId("other-node");
    private static final Duration DELAY = Duration.ofSeconds(10);

    private RecordingSubstrate substrate;
    private EntityPartitionArc arc;

    @BeforeEach
    void setUp() {
        substrate = new RecordingSubstrate();
        arc = EntityPartitionArc.entityPartitionArc(KEYSPACE, PARTITIONS);
    }

    @Nested
    class Scheduling {
        @Test
        void scheduleTimer_returnsAToken_forAnExistingKey() {
            var entity = seededEntity();

            entity.scheduleTimer("k1", DELAY, new IntOp.Add(5))
                  .await()
                  .onFailure(PartitionFencedDurableEntityTimerTest::failCause)
                  .onSuccess(token -> assertThat(token.value()).isNotBlank());
        }

        /// A timer on a key with no state has nothing to mutate on expiry. Refusing at schedule time makes
        /// that the caller's answer, instead of an error logged minutes later by a tick nobody is watching.
        @Test
        void scheduleTimer_fails_whenTheKeyDoesNotExist() {
            ownedEntity().scheduleTimer("absent", DELAY, new IntOp.Add(5))
                         .await()
                         .onSuccess(token -> fail("expected EntityNotFound, got " + token))
                         .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityError.EntityNotFound.class));
        }

        @Test
        void scheduleTimer_appendsExactlyOneScheduleRecord() {
            schedule(seededEntity(), "k1", new IntOp.Add(5));

            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_SCHEDULE)).isEqualTo(1);
        }

        /// A non-owner with no transport wired is REFUSED rather than forwarded: forwarding needs both a
        /// positive remote-owner reading and a wired transport, and this entity has only the first — the
        /// arc's owner is `OTHER`, and nothing is wired to reach it. A write never lands on the wrong node.
        @Test
        void scheduleTimer_rejectedWithNotCurrentOwner_whenAnotherNodeOwnsTheArc() {
            seededEntity();

            entityAs(SELF, fixedOwner(OTHER)).scheduleTimer("k1", DELAY, new IntOp.Add(5))
                                             .await()
                                             .onSuccess(token -> fail("a non-owner must not schedule a timer, got " + token))
                                             .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityError.NotCurrentOwner.class));
        }

        /// Two timers on one key are two independent timers, not one overwritten — each gets its own token
        /// and both come due.
        @Test
        void scheduleTimer_mintsDistinctTokens_forTwoTimersOnOneKey() {
            var entity = seededEntity();

            assertThat(schedule(entity, "k1", new IntOp.Add(1)).value())
                .isNotEqualTo(schedule(entity, "k1", new IntOp.Add(2)).value());
        }

        /// The caller-token entry answers with the token it was GIVEN, never one of its own. A schedule that
        /// silently substituted a token would hand the caller a handle to nothing: the timer it planted
        /// would be uncancellable and a re-send would plant a second one.
        @Test
        void scheduleTimer_answersWithTheCallerToken_whenOneIsSupplied() {
            assertThat(schedule(seededEntity(), "k1", new IntOp.Add(5), "caller-token").value()).isEqualTo("caller-token");
        }

        /// The owner-side dedupe through the PUBLIC caller-token entry, and the reason a caller-minted token
        /// is safe to retry with.
        ///
        /// A schedule arriving with a token this key already has pending IS that schedule, re-sent because
        /// the first answer was lost — not a request for a second timer. So exactly one TIMER_SCHEDULE
        /// record exists afterwards and BOTH calls succeed with the same token, which is what lets a caller
        /// retry an unknown-outcome schedule without planting a duplicate it holds only one handle for.
        @Test
        void scheduleTimer_appendsOneTimer_whenTheSameCallerTokenIsPresentedTwice() {
            var entity = seededEntity();

            assertThat(schedule(entity, "k1", new IntOp.Add(5), "resent-token").value()).isEqualTo("resent-token");
            assertThat(schedule(entity, "k1", new IntOp.Add(5), "resent-token").value()).isEqualTo("resent-token");

            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_SCHEDULE))
                .as("a re-sent schedule is the SAME schedule — a second record would be a timer nobody asked for")
                .isEqualTo(1);
        }

        /// The token-minting overload is the caller-token one with a fresh token, so it can never dedupe
        /// against itself. Without this the test above would pass against code that refuses every second
        /// schedule on a key regardless of token.
        @Test
        void scheduleTimer_appendsTwoTimers_whenTheMintingEntryIsCalledTwice() {
            var entity = seededEntity();

            schedule(entity, "k1", new IntOp.Add(1));
            schedule(entity, "k1", new IntOp.Add(2));

            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_SCHEDULE)).isEqualTo(2);
        }

        /// The owner-side dedupe on the ARRIVING-forward verb — the same guard, reached through the hop a
        /// non-owner's schedule actually takes, so a re-sent forward cannot plant a second timer either.
        @Test
        void scheduleTimerForwarded_appendsOneTimer_whenTheSameTokenArrivesTwice() {
            var entity = seededEntity();

            assertThat(scheduleForwarded(entity, "k1", "resent-token")).isEqualTo("resent-token");
            assertThat(scheduleForwarded(entity, "k1", "resent-token")).isEqualTo("resent-token");

            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_SCHEDULE))
                .as("a re-sent schedule is the SAME schedule — a second record would be a timer nobody asked for")
                .isEqualTo(1);
        }

        /// The dedupe is keyed on the TOKEN, not on the key: two DIFFERENT tokens on one key are two
        /// timers. Without this the test above would pass against code that refuses every second schedule.
        @Test
        void scheduleTimerForwarded_appendsTwoTimers_whenTwoTokensArriveForOneKey() {
            var entity = seededEntity();

            scheduleForwarded(entity, "k1", "token-a");
            scheduleForwarded(entity, "k1", "token-b");

            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_SCHEDULE)).isEqualTo(2);
        }
    }

    @Nested
    class Firing {
        @Test
        void fireDueTimers_appliesTheCommand_onceTheInstantHasPassed() {
            var entity = seededEntity();

            schedule(entity, "k1", new IntOp.Add(41));
            fireAndSettle(entity, "k1", farFuture());

            assertThat(read(entity, "k1")).isEqualTo(Option.some(42));
        }

        /// The boundary the tick is named for. A timer one second short of due must NOT fire — an early
        /// fire is the one failure a caller cannot compensate for.
        @Test
        void fireDueTimers_leavesTheStateUntouched_beforeTheInstantArrives() {
            var entity = seededEntity();

            schedule(entity, "k1", new IntOp.Add(41));
            fireAndSettle(entity, "k1", System.currentTimeMillis() + DELAY.toMillis() - 1_000);

            assertThat(read(entity, "k1")).isEqualTo(Option.some(1));
            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_FIRE)).isZero();
        }

        /// One-shot, proven by ticking three times. A second application would show as `83` rather than
        /// `42`, so this reaches the double-fire guard rather than merely re-asserting that the first tick
        /// worked.
        @Test
        void fireDueTimers_appliesTheCommandExactlyOnce_acrossRepeatedTicks() {
            var entity = seededEntity();

            schedule(entity, "k1", new IntOp.Add(41));
            fireAndSettle(entity, "k1", farFuture());
            fireAndSettle(entity, "k1", farFuture());
            fireAndSettle(entity, "k1", farFuture());

            assertThat(read(entity, "k1")).isEqualTo(Option.some(42));
            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_FIRE)).isEqualTo(1);
        }

        /// The double-fire guard's no-op branch, reached deterministically.
        ///
        /// A fire that has FINISHED removes its token, so a later tick never re-observes it and the guard is
        /// never consulted — which means the ordinary repeated-tick test above cannot reach this branch at
        /// all. The gate holds tick 1's fire open inside its append, so tick 2 genuinely re-observes a timer
        /// whose fire is in flight: exactly the race the guard exists for. Without the guard, tick 2's fire
        /// would read the post-fire state and apply the command a second time, giving 83 and two fire
        /// records.
        @Test
        void fireDueTimers_firesOnce_whenASecondTickReObservesAnInFlightFire() {
            var entity = seededEntity();

            schedule(entity, "k1", new IntOp.Add(41));
            substrate.gateAppends();

            entity.fireDueTimers(farFuture());
            awaitCondition(() -> substrate.gatedCount() == 1, "tick 1's fire must reach its append before tick 2 runs");

            entity.fireDueTimers(farFuture());
            substrate.releaseGate();
            settle(entity, "k1");

            assertThat(read(entity, "k1")).isEqualTo(Option.some(42));
            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_FIRE)).isEqualTo(1);
        }

        /// ONE record carrying the POST-FIRE state, which is what makes replay of a fired timer produce the
        /// fired state instead of re-arming it.
        @Test
        void fireDueTimers_appendsOneFireRecordCarryingThePostFireState() {
            var entity = seededEntity();

            schedule(entity, "k1", new IntOp.Add(41));
            fireAndSettle(entity, "k1", farFuture());

            var fired = substrate.recordsOf(EntityLogRecord.Op.TIMER_FIRE);

            assertThat(fired).hasSize(1);
            assertThat(payloadBodyText(fired.getFirst())).isEqualTo("42");
        }

        @Test
        void fireDueTimers_firesEveryDueTimerOfAKey() {
            var entity = seededEntity();

            schedule(entity, "k1", new IntOp.Add(10));
            schedule(entity, "k1", new IntOp.Add(30));
            fireAndSettle(entity, "k1", farFuture());

            assertThat(read(entity, "k1")).isEqualTo(Option.some(41));
        }

        /// A non-owner HOLDS the partition (it is a replica) and therefore folds its timers, but must not
        /// fire them: the owner will. Firing here would apply the command on two nodes. Its fold is driven
        /// ready BEFORE the tick, so the refusal is what the assertion sees rather than a rebuild that had
        /// not finished.
        @Test
        void fireDueTimers_firesNothing_whenAnotherNodeOwnsTheArc() {
            var owner = seededEntity();

            schedule(owner, "k1", new IntOp.Add(41));

            var replica = entityAs(SELF, fixedOwner(OTHER));

            settle(replica, "k1");
            fireAndSettle(replica, "k1", farFuture());

            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_FIRE)).isZero();
            assertThat(read(owner, "k1")).isEqualTo(Option.some(1));
        }

        /// A replica must not pay for the tick AT ALL. The ownership answer is available without a key, so
        /// it is asked before readiness is driven — otherwise every replica would reload a checkpoint and
        /// replay a log per partition per interval, as a side effect of a tick that was always going to fire
        /// nothing. Counting rebuild attempts is the observation: the refusal alone would look identical
        /// whether the work happened or not.
        @Test
        void fireDueTimers_rebuildsNoFold_whenAnotherNodeOwnsTheArcs() {
            var owner = seededEntity();

            schedule(owner, "k1", new IntOp.Add(41));

            var replica = entityAs(SELF, fixedOwner(OTHER));
            var rebuildsBefore = substrate.checkpointLoads();

            replica.fireDueTimers(farFuture());

            assertThat(substrate.checkpointLoads())
                .as("a non-owner must not rebuild folds as a timer-tick side effect")
                .isEqualTo(rebuildsBefore);
        }

        /// The handover case, in the form a single-JVM test can reach: a SECOND entity handle over the same
        /// log, whose fold has never been rebuilt. It must recover the pending timer by REPLAYING — nothing
        /// was handed to it — and fire it. This is why the tick drives readiness rather than only reading
        /// the fold; without that, an inherited timer stays dormant until unrelated traffic happens to
        /// rebuild the partition, which is the whole failure mode this increment exists to remove.
        @Test
        void fireDueTimers_firesATimerInheritedThroughTheLog_onAFoldNeverRebuilt() {
            var original = seededEntity();

            schedule(original, "k1", new IntOp.Add(41));

            var successor = ownedEntity();

            successor.fireDueTimers(farFuture());

            awaitCondition(() -> successor.get("k1").await().or(Option.none()).equals(Option.some(42)),
                           "an inherited timer must fire on a fold the tick itself rebuilt");
        }
    }

    @Nested
    class Cancellation {
        @Test
        void cancelTimer_preventsTheFire() {
            var entity = seededEntity();
            var token = schedule(entity, "k1", new IntOp.Add(41));

            entity.cancelTimer("k1", token).await().onFailure(PartitionFencedDurableEntityTimerTest::failCause);
            fireAndSettle(entity, "k1", farFuture());

            assertThat(read(entity, "k1")).isEqualTo(Option.some(1));
            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_FIRE)).isZero();
        }

        @Test
        void cancelTimer_succeeds_forAnUnknownToken() {
            seededEntity().cancelTimer("k1", DurableEntity.TimerToken.timerToken("never-scheduled"))
                          .await()
                          .onFailure(PartitionFencedDurableEntityTimerTest::failCause);
        }

        /// Idempotent means idempotent in the LOG too: a cancel with nothing to consume appends no record,
        /// so a caller retrying does not grow the log for an operation that already happened.
        @Test
        void cancelTimer_appendsNoRecord_whenThereIsNothingToCancel() {
            var entity = seededEntity();
            var token = schedule(entity, "k1", new IntOp.Add(41));

            entity.cancelTimer("k1", token).await().onFailure(PartitionFencedDurableEntityTimerTest::failCause);
            entity.cancelTimer("k1", token).await().onFailure(PartitionFencedDurableEntityTimerTest::failCause);

            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_CANCEL)).isEqualTo(1);
        }

        /// Spec §5.1 — an absent key counts as already-cancelled, because [DurableEntity#delete]
        /// auto-cancels. A caller cancelling after a delete has nothing to fix.
        @Test
        void cancelTimer_succeeds_forAKeyThatWasDeleted() {
            var entity = seededEntity();
            var token = schedule(entity, "k1", new IntOp.Add(41));

            entity.delete("k1").await().onFailure(PartitionFencedDurableEntityTimerTest::failCause);

            entity.cancelTimer("k1", token).await().onFailure(PartitionFencedDurableEntityTimerTest::failCause);
        }

        @Test
        void cancelTimer_rejectedWithNotCurrentOwner_whenAnotherNodeOwnsTheArc() {
            seededEntity();

            entityAs(SELF, fixedOwner(OTHER)).cancelTimer("k1", DurableEntity.TimerToken.timerToken("t"))
                                             .await()
                                             .onSuccess(_ -> fail("a non-owner must not cancel a timer"))
                                             .onFailure(cause -> assertThat(cause.stream()).hasAtLeastOneElementOfType(EntityError.NotCurrentOwner.class));
        }

        /// Delete auto-cancels, so a later tick fires nothing — not even a consume record for a timer whose
        /// key is gone. The alternative is one failed fire per pending timer per tick, forever.
        @Test
        void delete_clearsPendingTimers_soNoLaterTickFires() {
            var entity = seededEntity();

            schedule(entity, "k1", new IntOp.Add(41));
            entity.delete("k1").await().onFailure(PartitionFencedDurableEntityTimerTest::failCause);
            fireAndSettle(entity, "k1", farFuture());

            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_FIRE)).isZero();
            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_CANCEL)).isZero();
            assertThat(read(entity, "k1")).isEqualTo(Option.none());
        }
    }

    /// Consume-on-failure. A failed fire that left its timer pending would be re-observed by every
    /// subsequent tick — an unbounded retry loop wearing a one-shot API — so the timer is durably consumed
    /// and the failure logged. The entity's state is left exactly as it was.
    @Nested
    class FailedFires {
        @Test
        void fireDueTimers_consumesTheTimer_whenTheCommandThrows() {
            var entity = seededThrowingEntity();

            schedule(entity, "k1", new IntOp.Multiply(3));
            fireAndSettle(entity, "k1", farFuture());

            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_CANCEL))
                .as("a failed fire must consume its timer durably")
                .isEqualTo(1);
            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_FIRE)).isZero();
        }

        @Test
        void fireDueTimers_leavesTheStateUntouched_whenTheCommandThrows() {
            var entity = seededThrowingEntity();

            schedule(entity, "k1", new IntOp.Multiply(3));
            fireAndSettle(entity, "k1", farFuture());

            assertThat(read(entity, "k1")).isEqualTo(Option.some(1));
        }

        /// The point of consuming: the NEXT tick finds nothing. A timer left pending after a deterministic
        /// failure would append a consume record on every tick for the life of the node.
        @Test
        void fireDueTimers_doesNotRetry_aCommandThatAlreadyFailed() {
            var entity = seededThrowingEntity();

            schedule(entity, "k1", new IntOp.Multiply(3));
            fireAndSettle(entity, "k1", farFuture());
            fireAndSettle(entity, "k1", farFuture());
            fireAndSettle(entity, "k1", farFuture());

            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_CANCEL)).isEqualTo(1);
        }
    }

    /// The other half of the failure split. An APPEND failure is environmental, not deterministic: nothing
    /// reached the log, the condition clears by itself, and the timer must survive to be fired by whoever
    /// succeeds. Consuming here would destroy a perfectly good timer on a routine handover and log an ERROR
    /// naming a broken command when nothing is broken.
    @Nested
    class DeferredFires {
        /// The assertion is on what the entity TRIED to append, not on what landed. With appends failing,
        /// an attempted consume leaves the log identical to a consume that was never attempted — so a test
        /// counting records would pass against code that consumes on every failure, which is precisely the
        /// behaviour this branch replaced.
        @Test
        void fireDueTimers_attemptsNoConsume_whenTheAppendIsFencedOut() {
            var entity = seededEntity();

            schedule(entity, "k1", new IntOp.Add(41));
            substrate.failAppendsWith(new EntityLogError.StaleOwnerAppend(KEYSPACE, 0, "presented 0, current 1"));
            fireAndSettle(entity, "k1", farFuture());

            assertThat(substrate.attemptsOf(EntityLogRecord.Op.TIMER_FIRE))
                .as("the fire itself must have been attempted, or this test proves nothing")
                .isEqualTo(1);
            assertThat(substrate.attemptsOf(EntityLogRecord.Op.TIMER_CANCEL))
                .as("a deposed owner must not even attempt to consume the timer it failed to fire")
                .isZero();
        }

        /// The consequence that makes deferral worth doing: the timer is still there, so the tick that runs
        /// once the fence clears fires it exactly once.
        @Test
        void fireDueTimers_firesTheTimer_onceTheFenceClears() {
            var entity = seededEntity();

            schedule(entity, "k1", new IntOp.Add(41));
            substrate.failAppendsWith(new EntityLogError.StaleOwnerAppend(KEYSPACE, 0, "presented 0, current 1"));
            fireAndSettle(entity, "k1", farFuture());

            substrate.allowAppends();
            fireAndSettle(entity, "k1", farFuture());

            assertThat(read(entity, "k1")).isEqualTo(Option.some(42));
            assertThat(substrate.opsOf(EntityLogRecord.Op.TIMER_FIRE)).isEqualTo(1);
        }

        /// A quorum or transport fault takes the same path as a fence rejection: both are environmental and
        /// both clear.
        @Test
        void fireDueTimers_attemptsNoConsume_whenTheAppendFailsForStorage() {
            var entity = seededEntity();

            schedule(entity, "k1", new IntOp.Add(41));
            substrate.failAppendsWith(Causes.cause("quorum unreachable"));
            fireAndSettle(entity, "k1", farFuture());

            assertThat(substrate.attemptsOf(EntityLogRecord.Op.TIMER_CANCEL)).isZero();
            assertThat(read(entity, "k1")).isEqualTo(Option.some(1));
        }

        /// The other side of the split, asserted the same way so the two are comparable: a DETERMINISTIC
        /// failure DOES attempt the consume. Without this, "attempts no consume" above could pass because
        /// nothing ever consumes anything.
        @Test
        void fireDueTimers_attemptsTheConsume_whenTheCommandThrows() {
            var entity = seededThrowingEntity();

            schedule(entity, "k1", new IntOp.Multiply(3));
            fireAndSettle(entity, "k1", farFuture());

            assertThat(substrate.attemptsOf(EntityLogRecord.Op.TIMER_CANCEL)).isEqualTo(1);
        }
    }

    /// A rebuild that keeps failing must not turn the tick into a storm against shared storage — and must
    /// stay visible while it does not. `EntityFold` clears its rebuild memo on failure, deliberately, which
    /// is precisely what makes an un-backed-off tick re-load a checkpoint and replay a log every interval,
    /// forever.
    @Nested
    class ReadinessBackoff {
        private static final long TICK_BASE = 4_000_000_000_000L;

        @Test
        void fireDueTimers_doesNotReattemptAFailedRebuild_withinTheBackoffWindow() {
            var entity = ownedEntity();

            substrate.failCheckpointLoads();

            entity.fireDueTimers(TICK_BASE);

            var afterFirstTick = substrate.checkpointLoads();

            assertThat(afterFirstTick).as("the first tick must actually attempt the rebuild").isPositive();

            entity.fireDueTimers(TICK_BASE);

            assertThat(substrate.checkpointLoads())
                .as("a backed-off partition must not be re-attempted on the very next tick")
                .isEqualTo(afterFirstTick);
        }

        @Test
        void fireDueTimers_reattemptsAFailedRebuild_onceTheBackoffElapses() {
            var entity = ownedEntity();

            substrate.failCheckpointLoads();

            entity.fireDueTimers(TICK_BASE);

            var afterFirstTick = substrate.checkpointLoads();

            entity.fireDueTimers(TICK_BASE + PartitionFencedDurableEntity.retryDelayMillis(1L));

            assertThat(substrate.checkpointLoads())
                .as("the retry must resume once the backoff window has elapsed")
                .isGreaterThan(afterFirstTick);
        }

        /// A partition that recovers pays no penalty for having been broken: the counter resets on the first
        /// success, so a later failure starts at the base delay rather than at the cap.
        ///
        /// Driven on the REAL clock throughout, unlike its siblings. The backoff window is measured against
        /// the tick's own instant, so a test that recorded a failure at a synthetic far-future instant and
        /// then ticked at a wall-clock one would leave the partition backed off until the year the constant
        /// names — which is what the first draft of this test did.
        @Test
        void fireDueTimers_firesNormally_afterTheRebuildRecovers() {
            var entity = ownedEntity();

            substrate.failCheckpointLoads();
            entity.fireDueTimers(System.currentTimeMillis());
            substrate.allowCheckpointLoads();

            entity.create("k1", 1).await().onFailure(PartitionFencedDurableEntityTimerTest::failCause);
            schedule(entity, "k1", new IntOp.Add(41));
            fireAndSettle(entity, "k1", farFuture());

            assertThat(read(entity, "k1")).isEqualTo(Option.some(42));
        }

        /// The schedule pinned as a RULE. Inferring it from tick counts would leave the cap — the part that
        /// stops a transiently broken partition from being abandoned — entirely unasserted.
        @Test
        void retryDelayMillis_growsExponentially_andStopsAtTheCap() {
            assertThat(PartitionFencedDurableEntity.retryDelayMillis(1L)).isEqualTo(1_000L);
            assertThat(PartitionFencedDurableEntity.retryDelayMillis(2L)).isEqualTo(2_000L);
            assertThat(PartitionFencedDurableEntity.retryDelayMillis(3L)).isEqualTo(4_000L);
            assertThat(PartitionFencedDurableEntity.retryDelayMillis(7L)).isEqualTo(60_000L);
            assertThat(PartitionFencedDurableEntity.retryDelayMillis(1_000L)).isEqualTo(60_000L);
        }

        /// The rate limit pinned as a RULE too. Observable only as log volume, it could drift into a flood
        /// or a silence and no test would notice — and a silence is the worse of the two, because a
        /// partition that fires no timers looks exactly like timers scheduled for later.
        @Test
        void isWarnWorthy_reportsTheFirstFailure_andThenEveryTenth() {
            assertThat(PartitionFencedDurableEntity.isWarnWorthy(1L)).isTrue();
            assertThat(PartitionFencedDurableEntity.isWarnWorthy(2L)).isFalse();
            assertThat(PartitionFencedDurableEntity.isWarnWorthy(9L)).isFalse();
            assertThat(PartitionFencedDurableEntity.isWarnWorthy(10L)).isTrue();
            assertThat(PartitionFencedDurableEntity.isWarnWorthy(11L)).isFalse();
            assertThat(PartitionFencedDurableEntity.isWarnWorthy(20L)).isTrue();
        }
    }

    // --- fixtures ---

    /// Comfortably past every timer this suite schedules, so "due" is never in question — the NOT-due
    /// boundary is pinned by its own test rather than by this value.
    private static long farFuture() {
        return System.currentTimeMillis() + DELAY.toMillis() + 1_000;
    }

    /// Tick, then FLUSH the key's serialization tail with a read on the same key. The read queues behind
    /// whatever the tick submitted, so awaiting it means the fire has finished — which is what lets a test
    /// assert that nothing happened without the assertion merely outrunning the work.
    ///
    /// Sound only when the partition's fold is already rebuilt, so that the tick enqueues before it
    /// returns. Every caller here has driven a create, a schedule or a read through that same tail first;
    /// tests that cannot pre-rebuild, or that must order one tick against another, use [#awaitCondition]
    /// instead.
    private static void fireAndSettle(PartitionFencedDurableEntity<String, Integer, IntOp> entity,
                                      String key,
                                      long nowMillis) {
        entity.fireDueTimers(nowMillis);
        settle(entity, key);
    }

    private static void settle(DurableEntity<String, Integer, IntOp> entity, String key) {
        entity.get(key).await().onFailure(PartitionFencedDurableEntityTimerTest::failCause);
    }

    /// Bounded wait for an asynchronous effect, failing loudly rather than hanging. Used where no read can
    /// flush the effect into view first: the fresh owner whose rebuild the tick itself must drive, and the
    /// in-flight fire a second tick must not overtake.
    private static void awaitCondition(BooleanSupplier condition, String description) {
        for (var attempt = 0; attempt < 500; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }

            Promise.<Unit> promise(timeSpan(10).millis(), promise -> promise.succeed(unit())).await();
        }

        fail(description);
    }

    private PartitionFencedDurableEntity<String, Integer, IntOp> seededEntity() {
        var entity = ownedEntity();

        entity.create("k1", 1).await().onFailure(PartitionFencedDurableEntityTimerTest::failCause);

        return entity;
    }

    private PartitionFencedDurableEntity<String, Integer, IntOp> seededThrowingEntity() {
        var entity = entity(SELF, fixedOwner(SELF), new IntDeserializer(true));

        entity.create("k1", 1).await().onFailure(PartitionFencedDurableEntityTimerTest::failCause);

        return entity;
    }

    private PartitionFencedDurableEntity<String, Integer, IntOp> ownedEntity() {
        return entityAs(SELF, fixedOwner(SELF));
    }

    private PartitionFencedDurableEntity<String, Integer, IntOp> entityAs(NodeId self, CommittedPartitionOwnerSource owners) {
        return entity(self, owners, new IntDeserializer(false));
    }

    /// The concrete type, because [PartitionFencedDurableEntity#fireDueTimers] is the package-private tick
    /// entry the driver calls and is deliberately not on the [DurableEntity] contract.
    @SuppressWarnings("unchecked")
    private PartitionFencedDurableEntity<String, Integer, IntOp> entity(NodeId self,
                                                                        CommittedPartitionOwnerSource owners,
                                                                        Deserializer deserializer) {
        return (PartitionFencedDurableEntity<String, Integer, IntOp>)
                   PartitionFencedDurableEntity.<String, Integer, IntOp> partitionFencedDurableEntity(KEYSPACE,
                                                                                                      substrate,
                                                                                                      arc,
                                                                                                      new IntSerializer(),
                                                                                                      deserializer,
                                                                                                      self,
                                                                                                      owners,
                                                                                                      Option.none(),
                                                                                                      Option.some((_, _) -> Promise.success(Unit.unit())));
    }

    private static CommittedPartitionOwnerSource fixedOwner(NodeId owner) {
        return (_, _) -> Option.some(new CommittedOwner(owner, Epoch.ZERO));
    }

    private static DurableEntity.TimerToken schedule(DurableEntity<String, Integer, IntOp> entity, String key, IntOp onFire) {
        return entity.scheduleTimer(key, DELAY, onFire)
                     .await()
                     .fold(cause -> fail(cause.message()), token -> token);
    }

    /// Schedule through the CALLER-TOKEN entry — the public overload that takes the handle rather than
    /// minting one, and therefore the only public way to express a re-send.
    private static DurableEntity.TimerToken schedule(DurableEntity<String, Integer, IntOp> entity,
                                                     String key,
                                                     IntOp onFire,
                                                     String token) {
        return entity.scheduleTimer(key, DELAY, onFire, DurableEntity.TimerToken.timerToken(token))
                     .await()
                     .fold(cause -> fail(cause.message()), applied -> applied);
    }

    /// Schedule through the ARRIVING-forward verb — the entry a forwarded schedule lands on. It carries the
    /// sender's token across the hop and answers the token the entity actually applied, which is the owner's
    /// echo the sender verifies.
    private static String scheduleForwarded(PartitionFencedDurableEntity<String, Integer, IntOp> entity,
                                            String key,
                                            String token) {
        return entity.scheduleTimerForwarded(key.getBytes(StandardCharsets.UTF_8),
                                             DELAY.toMillis(),
                                             "Add:5".getBytes(StandardCharsets.UTF_8),
                                             token)
                     .await()
                     .fold(cause -> fail(cause.message()), applied -> applied);
    }

    private static Option<Integer> read(DurableEntity<String, Integer, IntOp> entity, String key) {
        return entity.get(key).await().fold(cause -> fail(cause.message()), value -> value);
    }

    private static String payloadBodyText(EntityLogRecord record) {
        return record.timerPayload()
                     .fold(cause -> fail(cause.message()),
                           payload -> new String(payload.body(), StandardCharsets.UTF_8));
    }

    private static void failCause(Cause cause) {
        fail(cause.message());
    }

    /// A permissive log that REMEMBERS what was appended, so a test can assert on the records the entity
    /// actually wrote rather than on the state they happen to produce — the difference between proving a
    /// timer was consumed durably and proving it merely stopped firing.
    private static final class RecordingSubstrate implements EntityLogSubstrate {
        private final Map<Integer, List<byte[]>> log = new ConcurrentHashMap<>();
        private final List<GatedAppend> gatedAppends = new CopyOnWriteArrayList<>();
        private final List<byte[]> attempts = new CopyOnWriteArrayList<>();
        private final AtomicInteger checkpointLoads = new AtomicInteger();
        private volatile boolean gated;
        private volatile Option<Cause> appendFailure = Option.none();
        private volatile boolean checkpointLoadFails;

        /// Make every subsequent append FAIL with `cause`, as a deposed owner's or an unreachable quorum's
        /// would. The timer must survive this: nothing reached the log, so there is nothing to consume.
        void failAppendsWith(Cause cause) {
            appendFailure = Option.some(cause);
        }

        void allowAppends() {
            appendFailure = Option.none();
        }

        /// Make every rebuild fail at its checkpoint load — the cheapest way to produce a partition that is
        /// held, owned, and permanently un-ready.
        void failCheckpointLoads() {
            checkpointLoadFails = true;
        }

        void allowCheckpointLoads() {
            checkpointLoadFails = false;
        }

        /// How many times a fold has tried to REBUILD. `EntityFold` clears its memo on failure, so this
        /// counts attempts, which is exactly what a backoff has to hold down — and what a replica must not
        /// incur at all.
        int checkpointLoads() {
            return checkpointLoads.get();
        }

        /// Hold every subsequent append OPEN — the record is neither logged nor acknowledged until
        /// [#releaseGate]. This is what makes a fire observable while it is still IN FLIGHT, which is the
        /// only state in which a second tick can re-observe an already-firing timer.
        ///
        /// The bytes are withheld from the log too, deliberately: adding them immediately would let the
        /// next tick's catch-up fold the fire in and consume the token by a different route, which is a
        /// different scenario wearing the same test name.
        void gateAppends() {
            gated = true;
        }

        int gatedCount() {
            return gatedAppends.size();
        }

        void releaseGate() {
            gated = false;

            var drained = List.copyOf(gatedAppends);

            gatedAppends.clear();
            drained.forEach(RecordingSubstrate::completeGated);
        }

        private static void completeGated(GatedAppend held) {
            held.records().add(held.record());
            held.promise().succeed((long) held.records().size() - 1);
        }

        private record GatedAppend(List<byte[]> records, byte[] record, Promise<Long> promise) {}

        long opsOf(EntityLogRecord.Op op) {
            return recordsOf(op).size();
        }

        /// Records the entity TRIED to append, whether or not the append succeeded.
        ///
        /// Counting landed records is not enough once appends are failing: a consume record that was
        /// attempted and refused leaves the log looking exactly like one that was never attempted, so a test
        /// asserting "no consume record" would pass against code that consumes on every failure. This is the
        /// observation that tells the two apart.
        long attemptsOf(EntityLogRecord.Op op) {
            return attempts.stream()
                           .map(raw -> EntityLogRecord.decode(raw).fold(cause -> fail(cause.message()), record -> record))
                           .filter(record -> record.op() == op)
                           .count();
        }

        List<EntityLogRecord> recordsOf(EntityLogRecord.Op op) {
            return log.values()
                      .stream()
                      .flatMap(List::stream)
                      .map(raw -> EntityLogRecord.decode(raw).fold(cause -> fail(cause.message()), record -> record))
                      .filter(record -> record.op() == op)
                      .toList();
        }

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            var records = log.computeIfAbsent(partition, _ -> new ArrayList<>());

            attempts.add(record);

            return appendFailure.fold(() -> gated
                                            ? holdAppend(records, record)
                                            : appendNow(records, record),
                                      Cause::promise);
        }

        private Promise<Long> holdAppend(List<byte[]> records, byte[] record) {
            var promise = Promise.<Long> promise();

            gatedAppends.add(new GatedAppend(records, record, promise));

            return promise;
        }

        private static Promise<Long> appendNow(List<byte[]> records, byte[] record) {
            records.add(record);

            return Promise.success((long) records.size() - 1);
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            var records = log.getOrDefault(partition, List.of());
            var start = (int) fromOffset;

            return Promise.success(start < 0 || start >= records.size()
                                   ? List.of()
                                   : List.copyOf(records.subList(start, Math.min(records.size(), start + maxRecords))));
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            return log.getOrDefault(partition, List.of()).size() - 1L;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return log.getOrDefault(partition, List.of()).isEmpty() ? -1L : 0L;
        }

        @Override
        public boolean localLogComplete(String keyspace, int partition) {
            return true;
        }

        @Override
        public boolean holdsPartition(String keyspace, int partition) {
            return true;
        }

        @Override
        public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            checkpointLoads.incrementAndGet();

            return checkpointLoadFails
                   ? Causes.cause("checkpoint storage is unreachable").promise()
                   : Promise.success(Option.none());
        }
    }

    private static final class IntSerializer implements Serializer {
        @Override
        public byte[] encode(Object value) {
            return switch (value) {
                case IntOp.Add add -> ("Add:" + add.delta()).getBytes(StandardCharsets.UTF_8);
                case IntOp.Multiply multiply -> ("Multiply:" + multiply.factor()).getBytes(StandardCharsets.UTF_8);
                case null, default -> String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            };
        }

        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    /// `throwOnMultiply` decodes a `Multiply` command into one that THROWS when applied — which is how an
    /// author's buggy mutator fails: inside `apply`, on the per-key tail, not at the codec and not at the
    /// append. A fixture that failed the append instead would exercise a different path entirely.
    ///
    /// Non-numeric text decodes to itself: the arriving-forward verbs decode a KEY through this same
    /// deserializer, and this suite's keys (`k1`) are strings while its states are digits.
    private record IntDeserializer(boolean throwOnMultiply) implements Deserializer {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {
            var text = new String(bytes, StandardCharsets.UTF_8);

            if (text.startsWith("Add:")) {
                return (T) new IntOp.Add(Integer.parseInt(text.substring(4)));
            }

            if (text.startsWith("Multiply:")) {
                return (T) multiplyCommand(Integer.parseInt(text.substring(9)));
            }

            return (T) (text.chars().allMatch(Character::isDigit) ? Integer.valueOf(text) : text);
        }

        private IntOp multiplyCommand(int factor) {
            return throwOnMultiply
                   ? new IntOp.Exploding()
                   : new IntOp.Multiply(factor);
        }

        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
