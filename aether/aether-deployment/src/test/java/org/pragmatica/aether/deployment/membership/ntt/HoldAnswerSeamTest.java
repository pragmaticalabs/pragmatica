// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// D1 C3: the hold-answer seam in [DrainProcedure]'s quiesced exit gate. The types are pinned arm by arm; the gate is pinned by
/// the supplier being consulted (positive control) and by the exit happening only on NOT_HELD. The grace fork must ignore it.
class HoldAnswerSeamTest {
    private static final DrainReason TEST_REASON = DrainReason.OPERATOR_COMMAND;
    private static final Duration SETTLE = Duration.ofMillis(400);

    @Nested
    class Types {
        @Test
        void compare_zeroOnEitherSide_isUnknown() {
            assertThat(TokenCompare.compare(0, 7)).isEqualTo(TokenCompare.UNKNOWN);
            assertThat(TokenCompare.compare(7, 0)).isEqualTo(TokenCompare.UNKNOWN);
            assertThat(TokenCompare.compare(0, 0)).isEqualTo(TokenCompare.UNKNOWN);
        }

        @Test
        void compare_knownTokens_equalOrDifferent() {
            assertThat(TokenCompare.compare(5, 5)).isEqualTo(TokenCompare.KNOWN_EQUAL);
            assertThat(TokenCompare.compare(5, 6)).isEqualTo(TokenCompare.KNOWN_DIFFERENT);
        }

        @Test
        void strongest_allNinePairs_pickHigherRank() {
            var h = HoldAnswer.HELD;
            var n = HoldAnswer.NOT_HELD;
            var u = HoldAnswer.UNKNOWN;

            assertThat(HoldAnswer.strongest(h, h)).isEqualTo(h);
            assertThat(HoldAnswer.strongest(h, u)).isEqualTo(h);
            assertThat(HoldAnswer.strongest(h, n)).isEqualTo(h);
            assertThat(HoldAnswer.strongest(u, h)).isEqualTo(h);
            assertThat(HoldAnswer.strongest(u, u)).isEqualTo(u);
            assertThat(HoldAnswer.strongest(u, n)).isEqualTo(u);
            assertThat(HoldAnswer.strongest(n, h)).isEqualTo(h);
            assertThat(HoldAnswer.strongest(n, u)).isEqualTo(u);
            assertThat(HoldAnswer.strongest(n, n)).isEqualTo(n);
        }

        @Test
        void holdAnswer_declaredOrderEndsWithUnknown() {
            assertThat(HoldAnswer.values()).containsExactly(HoldAnswer.HELD, HoldAnswer.NOT_HELD, HoldAnswer.UNKNOWN);
        }

        @Test
        void drainEpisode_carriesNodeAndProcessToken() {
            var node = NodeId.nodeId("node-a").unwrap();
            var episode = DrainEpisode.drainEpisode(node, 42L);

            assertThat(episode.node()).isEqualTo(node);
            assertThat(episode.processToken()).isEqualTo(42L);
        }

        @Test
        void notHeld_answersNotHeldForAnyEpisode() {
            var node = NodeId.nodeId("node-a").unwrap();

            assertThat(HoldAnswerSource.notHeld().holdAnswer(DrainEpisode.drainEpisode(node, 0))).isEqualTo(HoldAnswer.NOT_HELD);
            assertThat(HoldAnswerSource.notHeld().holdAnswer(DrainEpisode.drainEpisode(node, 9))).isEqualTo(HoldAnswer.NOT_HELD);
        }
    }

    @Nested
    class Gate {
        @Test
        void defaultFactory_exitsAtQuiescence_asBefore() {
            var exits = new AtomicInteger(0);
            var procedure = DrainProcedure.drainProcedure(InFlightRequestTracker.inFlightRequestTracker(),
                                                          () -> {},
                                                          reason -> {},
                                                          window -> Promise.success(DepartureOutcome.COMPLETED),
                                                          exits::incrementAndGet);

            procedure.initiate(TEST_REASON);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
        }

        @Test
        void notHeldSupplier_exitsAtQuiescence() {
            var exits = new AtomicInteger(0);
            var procedure = procedure(exits, () -> HoldAnswer.NOT_HELD, timeSpan(30).seconds());

            procedure.initiate(TEST_REASON);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
        }

        @Test
        void heldSupplier_noExit_afterTrackerAndPushSettle() {
            var exits = new AtomicInteger(0);
            var asked = new AtomicInteger(0);
            var procedure = procedure(exits, () -> counted(asked, HoldAnswer.HELD), timeSpan(30).seconds());

            procedure.initiate(TEST_REASON);
            assertWaits(procedure, exits, asked);
        }

        @Test
        void unknownSupplier_noQuiescedExit() {
            var exits = new AtomicInteger(0);
            var asked = new AtomicInteger(0);
            var procedure = procedure(exits, () -> counted(asked, HoldAnswer.UNKNOWN), timeSpan(30).seconds());

            procedure.initiate(TEST_REASON);
            assertWaits(procedure, exits, asked);
        }

        @Test
        void graceExpiry_exitsEvenWhenHeld() {
            var exits = new AtomicInteger(0);
            var asked = new AtomicInteger(0);
            var procedure = procedure(exits, () -> counted(asked, HoldAnswer.HELD), timeSpan(150).millis());

            procedure.initiate(TEST_REASON);
            await().atMost(2, TimeUnit.SECONDS)
                 .untilAsserted(() -> {
                                    assertThat(exits.get()).isEqualTo(1);
                                    assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.EXITED);
                                });
        }

        @Test
        void holdReleasedAfterPushSettles_isNotSeen_exitComesAtGrace() {
            var exits = new AtomicInteger(0);
            var asked = new AtomicInteger(0);
            var answer = new AtomicReference<>(HoldAnswer.HELD);
            var procedure = procedure(exits, () -> counted(asked, answer.get()), timeSpan(2).seconds());

            procedure.initiate(TEST_REASON);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(asked.get()).isEqualTo(1));
            answer.set(HoldAnswer.NOT_HELD);
            // TRIPWIRE: pins the C3 contract (one read, at push settle). Fails when a re-read exists.
            await().during(SETTLE)
                 .atMost(Duration.ofSeconds(2))
                 .untilAsserted(() -> assertThat(exits.get())
                     .as("enable the re-read arm when D2 binds a real HoldAnswerSource")
                     .isZero());
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(asked.get()).as("the supplier is read once per drain, never again").isEqualTo(1);
        }

        @Test
        @Disabled("enable the re-read arm when D2 binds a real HoldAnswerSource: a hold released after the push settled takes the quiesced exit")
        void holdReleasedAfterPushSettles_exitsBeforeGrace() {
            var exits = new AtomicInteger(0);
            var asked = new AtomicInteger(0);
            var answer = new AtomicReference<>(HoldAnswer.HELD);
            var procedure = procedure(exits, () -> counted(asked, answer.get()), timeSpan(5).seconds());

            procedure.initiate(TEST_REASON);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(asked.get()).isGreaterThanOrEqualTo(1));
            answer.set(HoldAnswer.NOT_HELD);
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
        }

        @Test
        void graceFork_neverReadsTheSupplier() {
            var exits = new AtomicInteger(0);
            var asked = new AtomicInteger(0);
            var procedure = DrainProcedure.drainProcedure(InFlightRequestTracker.inFlightRequestTracker(),
                                                          () -> {},
                                                          reason -> {},
                                                          window -> Promise.<DepartureOutcome>promise(),
                                                          exits::incrementAndGet,
                                                          timeSpan(200).millis(),
                                                          () -> counted(asked, HoldAnswer.HELD));

            procedure.initiate(TEST_REASON);
            await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            await().during(SETTLE)
                 .atMost(Duration.ofSeconds(2))
                 .untilAsserted(() -> assertThat(asked.get()).as("the grace fork must not consult the hold seam").isZero());
        }

        @Test
        void supplier_isNotReadBeforeThePushSettles() {
            var exits = new AtomicInteger(0);
            var asked = new AtomicInteger(0);
            var push = Promise.<DepartureOutcome>promise();
            var procedure = DrainProcedure.drainProcedure(InFlightRequestTracker.inFlightRequestTracker(),
                                                          () -> {},
                                                          reason -> {},
                                                          window -> push,
                                                          exits::incrementAndGet,
                                                          timeSpan(30).seconds(),
                                                          () -> counted(asked, HoldAnswer.NOT_HELD));

            procedure.initiate(TEST_REASON);
            await().during(SETTLE)
                 .atMost(Duration.ofSeconds(2))
                 .untilAsserted(() -> assertThat(asked.get()).as("no read while the push is pending").isZero());
            push.succeed(DepartureOutcome.COMPLETED);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(exits.get()).isEqualTo(1));
            assertThat(asked.get()).isEqualTo(1);
        }

        private static void assertWaits(DrainProcedure procedure, AtomicInteger exits, AtomicInteger asked) {
            // Positive control: the gate was reached and asked, so the missing exit comes from the answer, not from an unsettled push.
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(asked.get()).isGreaterThanOrEqualTo(1));
            await().during(SETTLE)
                 .atMost(Duration.ofSeconds(2))
                 .untilAsserted(() -> {
                                    assertThat(exits.get()).isZero();
                                    assertThat(procedure.state()).isEqualTo(DrainProcedure.DrainState.DRAINING);
                                });
        }

        private static HoldAnswer counted(AtomicInteger asked, HoldAnswer answer) {
            asked.incrementAndGet();

            return answer;
        }

        private static DrainProcedure procedure(AtomicInteger exits, Supplier<HoldAnswer> answer, TimeSpan grace) {
            return DrainProcedure.drainProcedure(InFlightRequestTracker.inFlightRequestTracker(),
                                                 () -> {},
                                                 reason -> {},
                                                 window -> Promise.success(DepartureOutcome.COMPLETED),
                                                 exits::incrementAndGet,
                                                 grace,
                                                 answer);
        }
    }

    /// The hold-aware factory DrainAssembly calls: its supplier is the gate, and its grace window is the default 30 s, which the
    /// push's [DrainWindow] deadline (grace less HALT_SLACK) reveals.
    @Nested
    class HoldAwareFactory {
        @Test
        void heldSupplier_isConsulted_andForgoesTheQuiescedExit() {
            var exits = new AtomicInteger(0);
            var asked = new AtomicInteger(0);
            var procedure = DrainProcedure.drainProcedure(InFlightRequestTracker.inFlightRequestTracker(),
                                                          () -> {},
                                                          reason -> {},
                                                          window -> Promise.success(DepartureOutcome.COMPLETED),
                                                          exits::incrementAndGet,
                                                          () -> Gate.counted(asked, HoldAnswer.HELD));

            procedure.initiate(TEST_REASON);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(asked.get()).isEqualTo(1));
            await().during(SETTLE)
                 .atMost(Duration.ofSeconds(2))
                 .untilAsserted(() -> assertThat(exits.get()).as("a HELD answer through the factory must forgo the quiesced exit").isZero());
        }

        @Test
        void window_isDefaultGraceLessHaltSlack() {
            var remainingNanos = new AtomicReference<Long>();
            var procedure = DrainProcedure.drainProcedure(InFlightRequestTracker.inFlightRequestTracker(),
                                                          () -> {},
                                                          reason -> {},
                                                          window -> settledAt(remainingNanos, window),
                                                          () -> {},
                                                          () -> HoldAnswer.NOT_HELD);

            procedure.initiate(TEST_REASON);
            await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> assertThat(remainingNanos.get()).isNotNull());
            // default grace 30 s less HALT_SLACK 1 s = 29 s from the drain start; the push runs within the first seconds.
            assertThat(TimeUnit.NANOSECONDS.toMillis(remainingNanos.get())).isBetween(27_000L, 29_000L);
        }

        private static Promise<DepartureOutcome> settledAt(AtomicReference<Long> remainingNanos, DrainWindow window) {
            remainingNanos.set(window.legsDeadlineNanos() - System.nanoTime());

            return Promise.success(DepartureOutcome.COMPLETED);
        }
    }
}
