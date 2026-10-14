// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// W-08 (phase-3 design 9.2) and MT-08a/b/c: the timers of an FSM are generation-stamped, the map is the consumer's, and a zero delay is
/// immediate.
class FsmTimersTest {
    private final ManualTimerService clock = new ManualTimerService();
    private final List<FsmTimers.Tick<String>> queue = new ArrayList<>();
    private final FsmTimers<String, Long> timers = FsmTimers.fsmTimers(clock, queue::add);

    private FsmTimers.Tick<String> takeTick() {
        return queue.removeFirst();
    }

    @Nested
    class Replacement {
        /// The H4 race: the old body is already running when the timer is replaced. Its tick must not step the model.
        @Test
        void claim_dropsTheOldTick_whenTheTimerWasReplacedWhileItsBodyRan() {
            timers.schedule("w", 1L, 100);
            var oldBody = clock.last();

            timers.schedule("w", 2L, 200);
            ManualTimerService.run(oldBody);
            var staleTick = takeTick();

            assertThat(oldBody.cancelled).as("the replaced timer was cancelled").isTrue();
            assertThat(timers.claim(staleTick).isPresent()).as("MT-08a: a stale generation reaches step").isFalse();
            assertThat(timers.size()).as("the live entry survives the stale tick").isEqualTo(1);

            clock.advance(200);

            assertThat(timers.claim(takeTick()).or(-1L)).as("the replacement fires with its own attachment").isEqualTo(2L);
        }

        @Test
        void schedule_keepsOneLiveTimerPerId() {
            timers.schedule("w", 1L, 100);
            timers.schedule("w", 2L, 100);
            timers.schedule("w", 3L, 100);

            assertThat(timers.size()).isEqualTo(1);
            assertThat(clock.armedCount()).as("only the last arm stays live in the clock").isEqualTo(1);
        }

        @Test
        void claim_returnsTheEntryOnce_thenDropsARepeatedTick() {
            timers.schedule("w", 5L, 10);
            clock.advance(10);
            var tick = takeTick();

            assertThat(timers.claim(tick).or(-1L)).isEqualTo(5L);
            assertThat(timers.claim(tick).isPresent()).as("a claimed tick is spent").isFalse();
            assertThat(timers.size()).isZero();
        }

        @Test
        void distinctIds_areIndependent() {
            timers.schedule("a", 1L, 10);
            timers.schedule("b", 2L, 20);
            clock.advance(10);

            assertThat(timers.claim(takeTick()).or(-1L)).isEqualTo(1L);
            assertThat(timers.size()).isEqualTo(1);
        }
    }

    @Nested
    class Cancellation {
        /// "cancel then fire -> stepped" is the red: a cancelled timer whose body still ran enqueues a tick the consumer must drop.
        @Test
        void claim_dropsTheTick_whenTheTimerWasCancelledBeforeItFired() {
            timers.schedule("w", 1L, 100);
            var body = clock.last();

            timers.cancel("w");
            ManualTimerService.run(body);

            assertThat(body.cancelled).isTrue();
            assertThat(timers.claim(takeTick()).isPresent()).as("a cancelled timer is never stepped").isFalse();
        }

        @Test
        void cancel_ofAnUnknownId_doesNothing() {
            timers.cancel("never-armed");

            assertThat(timers.size()).isZero();
        }

        @Test
        void close_cancelsEveryEntry_andLaterTicksAreDropped() {
            timers.schedule("a", 1L, 100);
            timers.schedule("b", 2L, 200);
            var bodyA = clock.entries.getFirst();

            timers.close();
            ManualTimerService.run(bodyA);

            assertThat(clock.entries).allMatch(entry -> entry.cancelled);
            assertThat(timers.size()).isZero();
            assertThat(timers.claim(takeTick()).isPresent()).isFalse();
        }
    }

    @Nested
    class ZeroDelay {
        /// MT-08b: without the clamp a non-positive delay goes to the scheduler and the tick is not in the queue yet.
        @Test
        void schedule_enqueuesAtOnce_forAZeroDelay_withoutTheClock() {
            timers.schedule("w", 7L, 0);

            assertThat(queue).hasSize(1);
            assertThat(clock.entries).as("the scheduler is not involved").isEmpty();
            assertThat(timers.claim(takeTick()).or(-1L)).isEqualTo(7L);
        }

        @Test
        void schedule_enqueuesAtOnce_forANegativeDelay() {
            timers.schedule("w", 7L, -5);

            assertThat(queue).hasSize(1);
            assertThat(clock.entries).isEmpty();
        }

        @Test
        void schedule_replacesALiveTimer_withAnImmediateOne() {
            timers.schedule("w", 1L, 100);
            var armedBody = clock.last();

            timers.schedule("w", 2L, 0);

            assertThat(armedBody.cancelled).isTrue();
            assertThat(timers.claim(takeTick()).or(-1L)).isEqualTo(2L);
        }
    }

    @Nested
    class ProductionClock {
        @Test
        void sharedScheduler_runsTheBodyOnce_andNotAfterCancellation() throws Exception {
            var service = FsmTimers.sharedScheduler();
            var fired = new java.util.concurrent.CountDownLatch(1);
            var cancelledRan = new java.util.concurrent.atomic.AtomicBoolean();

            service.schedule(fired::countDown, 20);
            service.schedule(() -> cancelledRan.set(true), 300).run();

            assertThat(fired.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Thread.sleep(500);
            assertThat(cancelledRan).as("a cancelled body does not run").isFalse();
        }
    }

    @Nested
    class Confinement {
        /// MT-08c: the timer removal done on the scheduler thread. A body must only enqueue; the map entry is still there afterwards, so a
        /// fire that the body "consumed" cannot make the consumer's claim fail.
        @Test
        void timerBody_touchesNothingButTheQueue() throws Exception {
            timers.schedule("w", 9L, 10);
            var body = clock.last();
            var thread = new Thread(() -> ManualTimerService.run(body));

            thread.start();
            thread.join();

            assertThat(timers.size()).as("the body did not remove the entry").isEqualTo(1);
            assertThat(queue).hasSize(1);
            assertThat(timers.claim(takeTick()).or(-1L)).as("the consumer still claims the fire").isEqualTo(9L);
        }
    }
}
