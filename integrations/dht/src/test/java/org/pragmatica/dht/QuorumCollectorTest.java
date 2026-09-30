/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.dht;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.dht.QuorumCollector.quorumCollector;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Fix-2 defense-in-depth: the quorum collector must abort the moment quorum becomes
/// arithmetically impossible (enough failures accrued that the remaining responses cannot reach
/// quorum) rather than letting the promise stall to the per-op timeout, and a quorum reached via
/// successes must resolve exactly once.
class QuorumCollectorTest {
    private static final Cause FAILURE = new TestCause();

    private record TestCause() implements Cause {
        @Override
        public String message() {
            return "test failure";
        }
    }

    @Nested
    class FailureAccrualFastFail {
        /// total=3, quorum=2: after two failures only one possible success remains (< quorum), so
        /// the SECOND failure aborts immediately with QuorumNotReached — no third response and no
        /// timeout wait.
        @Test
        void onFailure_remainingBelowQuorum_failsImmediatelyWithQuorumNotReached() {
            Promise<Unit> promise = Promise.promise();
            var collector = quorumCollector(2, 3, promise);

            collector.onFailure(FAILURE);
            assertThat(promise.isResolved()).as("one failure of three still leaves two possible successes").isFalse();

            collector.onFailure(FAILURE);

            assertThat(promise.isResolved()).as("second failure makes quorum unreachable — abort now").isTrue();
            promise.await()
                   .onSuccess(_ -> fail("Expected QuorumNotReached failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.QuorumNotReached.class));
        }

        /// total=quorum=2: a SINGLE failure already makes quorum unreachable (one possible success
        /// remains, quorum is two) — fail on the first failure.
        @Test
        void onFailure_totalEqualsQuorum_firstFailureAborts() {
            Promise<Unit> promise = Promise.promise();
            var collector = quorumCollector(2, 2, promise);

            collector.onFailure(FAILURE);

            assertThat(promise.isResolved()).isTrue();
            promise.await()
                   .onSuccess(_ -> fail("Expected QuorumNotReached failure"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(DHTError.QuorumNotReached.class));
        }
    }

    @Nested
    class SuccessQuorum {
        /// total=3, quorum=2: two successes resolve the promise; a tolerated third response (over
        /// delivery) does not flip the already-succeeded promise.
        @Test
        void onSuccess_reachesQuorum_succeedsOnceAndIgnoresOverDelivery() {
            Promise<Unit> promise = Promise.promise();
            var collector = quorumCollector(2, 3, promise);

            collector.onSuccess(unit());
            assertThat(promise.isResolved()).as("one success of two needed is not yet quorum").isFalse();

            collector.onSuccess(unit());
            assertThat(promise.isResolved()).as("quorum reached at two successes").isTrue();

            collector.onSuccess(unit());

            promise.await()
                   .onFailure(cause -> fail("Expected success: " + cause.message()));
        }

        /// A mix that still reaches quorum: one failure then two successes (total=3, quorum=2) must
        /// SUCCEED — the single failure never made quorum unreachable.
        @Test
        void onSuccess_quorumReachedDespiteOneFailure_succeeds() {
            Promise<Unit> promise = Promise.promise();
            var collector = quorumCollector(2, 3, promise);

            collector.onFailure(FAILURE);
            collector.onSuccess(unit());
            collector.onSuccess(unit());

            assertThat(promise.isResolved()).isTrue();
            promise.await()
                   .onFailure(cause -> fail("Expected success: " + cause.message()));
        }
    }

    @Nested
    class GraceMode {
        private final Cause failure = DHTError.OPERATION_TIMEOUT;
        private final AtomicInteger triggers = new AtomicInteger();

        private QuorumCollector<Option<String>> collector(Promise<Option<String>> promise) {
            return QuorumCollector.graceCollector(2, 3, promise, _ -> {
                triggers.incrementAndGet();
                return unit();
            });
        }

        @Test
        void onSuccess_staysOpenAndTriggersOnce_afterQuorumOfEmptyAnswers() {
            Promise<Option<String>> promise = Promise.promise();
            var collector = collector(promise);

            collector.onSuccess(Option.none());
            assertThat(triggers.get()).isZero();
            collector.onSuccess(Option.none());

            assertThat(promise.isResolved()).isFalse();
            assertThat(triggers.get()).isEqualTo(1);
        }

        @Test
        void onSuccess_resolvesAbsentAtOnce_whenEverySlotAnsweredEmpty() {
            Promise<Option<String>> promise = Promise.promise();
            var collector = collector(promise);

            collector.onSuccess(Option.none());
            collector.onSuccess(Option.none());
            collector.onSuccess(Option.none());

            promise.await(timeSpan(2).seconds())
                   .onFailure(c -> fail("Expected absent"))
                   .onSuccess(o -> assertThat(o.isEmpty()).isTrue());
        }

        @Test
        void onSuccess_resolvesFound_atFirstPresentAnswerEvenAfterQuorumOfEmpties() {
            Promise<Option<String>> promise = Promise.promise();
            var collector = collector(promise);

            collector.onSuccess(Option.none());
            collector.onSuccess(Option.none());
            collector.onSuccess(Option.some("v"));

            promise.await(timeSpan(2).seconds())
                   .onFailure(c -> fail("Expected found"))
                   .onSuccess(o -> assertThat(o).isEqualTo(Option.some("v")));
        }

        @Test
        void onFailure_resolvesAbsent_whenLastSlotFailsAfterQuorumOfEmpties() {
            Promise<Option<String>> promise = Promise.promise();
            var collector = collector(promise);

            collector.onSuccess(Option.none());
            collector.onSuccess(Option.none());
            collector.onFailure(failure);

            promise.await(timeSpan(2).seconds())
                   .onFailure(c -> fail("Expected absent"))
                   .onSuccess(o -> assertThat(o.isEmpty()).isTrue());
        }

        @Test
        void onFailure_failsFast_whenQuorumBecomesImpossible() {
            Promise<Option<String>> promise = Promise.promise();
            var collector = collector(promise);

            collector.onFailure(failure);
            collector.onFailure(failure);

            assertThat(promise.isResolved()).isTrue();
            promise.await(timeSpan(2).seconds()).onSuccess(_ -> fail("Expected failure"));
        }

        @Test
        void resolveWithBest_resolvesAbsent_afterQuorumOfEmpties() {
            Promise<Option<String>> promise = Promise.promise();
            var collector = collector(promise);

            collector.onSuccess(Option.none());
            collector.onSuccess(Option.none());
            collector.resolveWithBest();

            promise.await(timeSpan(2).seconds())
                   .onFailure(c -> fail("Expected absent"))
                   .onSuccess(o -> assertThat(o.isEmpty()).isTrue());
        }
    }
}
