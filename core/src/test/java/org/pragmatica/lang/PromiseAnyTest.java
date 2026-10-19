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
 *
 */

package org.pragmatica.lang;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PromiseAnyTest {
    private static final Cause FIRST = new CoreError.Fault("first failure");
    private static final Cause SECOND = new CoreError.Fault("second failure");
    private static final Cause THIRD = new CoreError.Fault("third failure");

    @Nested
    class AllInputsFail {
        @Test
        void any_failsWithCompositeOfEveryCause_varargsResolvedOutOfOrder() {
            var p1 = Promise.<String>promise();
            var p2 = Promise.<String>promise();
            var p3 = Promise.<String>promise();
            var any = Promise.any(p1, p2, p3);

            resolveOnOtherThread(p3, THIRD);
            resolveOnOtherThread(p1, FIRST);
            resolveOnOtherThread(p2, SECOND);

            assertCompositeOf(any, FIRST, SECOND, THIRD);
        }

        @Test
        void any_failsWithCompositeOfEveryCause_listResolvedOutOfOrder() {
            var p1 = Promise.<String>promise();
            var p2 = Promise.<String>promise();
            var p3 = Promise.<String>promise();
            var any = Promise.any(List.of(p1, p2, p3));

            resolveOnOtherThread(p2, SECOND);
            resolveOnOtherThread(p3, THIRD);
            resolveOnOtherThread(p1, FIRST);

            assertCompositeOf(any, FIRST, SECOND, THIRD);
        }

        @Test
        void any_failsWithCompositeOfEveryCause_alreadyResolvedInputs() {
            assertCompositeOf(Promise.any(FIRST.<String>promise(), SECOND.<String>promise()), FIRST, SECOND);
        }

        @Test
        void any_failsWithCompositeOfSingleCause_singleInput() {
            assertCompositeOf(Promise.any(FIRST.<String>promise()), FIRST);
        }

        @Test
        void any_doesNotReportOtherSucceeded_whenNothingSucceeded() {
            Promise.any(FIRST.<String>promise(), SECOND.<String>promise())
                   .await()
                   .onSuccess(_ -> fail("Expected failure"))
                   .onFailure(cause -> assertFalse(cause.message().contains("other Promise instance succeeded")));
        }
    }

    @Nested
    class SomeInputSucceeds {
        @Test
        void any_succeeds_whenOneInputSucceedsAmongFailures() {
            var p1 = Promise.<String>promise();
            var p2 = Promise.<String>promise();
            var p3 = Promise.<String>promise();
            var any = Promise.any(p1, p2, p3);

            p1.fail(FIRST);
            p3.succeed("winner");

            any.await()
               .onFailure(_ -> fail("Expected success"))
               .onSuccess(value -> assertEquals("winner", value));
        }

        @Test
        void any_succeeds_listWithLateSuccess() {
            var p1 = Promise.<String>promise();
            var p2 = Promise.<String>promise();
            var any = Promise.any(List.of(p1, p2));

            resolveOnOtherThread(p1, FIRST);
            p2.succeed("late");

            any.await()
               .onFailure(_ -> fail("Expected success"))
               .onSuccess(value -> assertEquals("late", value));
        }

        @Test
        void any_cancelsLosers_withCancelledCause() {
            var winner = Promise.<String>promise();
            var loser = Promise.<String>promise();
            var any = Promise.any(winner, loser);

            winner.succeed("winner");
            any.await();

            loser.await()
                 .onSuccess(_ -> fail("Expected the loser to be cancelled"))
                 .onFailure(cause -> assertInstanceOf(CoreError.Cancelled.class, cause));
        }
    }

    @Nested
    class CallerListIsNotRetained {
        @Test
        void any_cancelsLosers_whenCallerClearsItsListAfterwards() {
            var winner = Promise.<String>promise();
            var loser = Promise.<String>promise();
            var inputs = new ArrayList<>(List.of(winner, loser));
            var any = Promise.any(inputs);

            inputs.clear();
            winner.succeed("winner");
            any.await();

            loser.await()
                 .onSuccess(_ -> fail("Expected the loser to be cancelled"))
                 .onFailure(cause -> assertInstanceOf(CoreError.Cancelled.class, cause));
        }
    }

    @Nested
    class ExplicitFailureResult {
        @Test
        void any_keepsProvidedFailureResult_whenAllInputsFail() {
            Promise.any(THIRD.<String>result(), FIRST.<String>promise(), SECOND.<String>promise())
                   .await()
                   .onSuccess(_ -> fail("Expected failure"))
                   .onFailure(cause -> assertSame(THIRD, cause));
        }

        @Test
        void any_keepsProvidedFailureResult_listForm() {
            Promise.any(THIRD.<String>result(), List.of(FIRST.<String>promise(), SECOND.<String>promise()))
                   .await()
                   .onSuccess(_ -> fail("Expected failure"))
                   .onFailure(cause -> assertSame(THIRD, cause));
        }
    }

    @Nested
    class ExplicitFailureResultWithSuccess {
        @Test
        void any_succeeds_explicitFailureResultVarargs() {
            Promise.any(THIRD.<String>result(), FIRST.<String>promise(), Promise.success("winner"))
                   .await()
                   .onFailure(_ -> fail("Expected success"))
                   .onSuccess(value -> assertEquals("winner", value));
        }

        @Test
        void any_succeeds_explicitFailureResultList() {
            Promise.any(THIRD.<String>result(), List.of(FIRST.<String>promise(), Promise.success("winner")))
                   .await()
                   .onFailure(_ -> fail("Expected success"))
                   .onSuccess(value -> assertEquals("winner", value));
        }
    }

    @Nested
    class EmptyInput {
        // An empty input can never produce a success or an event, so it resolves at once instead of hanging.
        @Test
        void any_failsImmediately_emptyList() {
            assertEmptyAnyFailure(Promise.any(List.<Promise<String>>of()));
        }

        @Test
        void any_failsImmediately_emptyVarargs() {
            assertEmptyAnyFailure(Promise.<String>any());
        }

        @Test
        void any_resolvesWithProvidedFailureResult_emptyVarargs() {
            var any = Promise.any(THIRD.<String>result());

            assertTrue(any.isResolved());
            any.await()
               .onSuccess(_ -> fail("Expected failure"))
               .onFailure(cause -> assertSame(THIRD, cause));
        }

        @Test
        void any_resolvesWithProvidedFailureResult_emptyList() {
            var any = Promise.any(THIRD.<String>result(), List.<Promise<String>>of());

            assertTrue(any.isResolved());
            any.await()
               .onSuccess(_ -> fail("Expected failure"))
               .onFailure(cause -> assertSame(THIRD, cause));
        }

        private void assertEmptyAnyFailure(Promise<String> any) {
            assertTrue(any.isResolved());
            any.await()
               .onSuccess(_ -> fail("Expected failure"))
               .onFailure(cause -> assertEquals(CoreError.emptyAny(), cause))
               .onFailure(cause -> assertEquals("any() of no promises", cause.message()));
        }
    }

    private static void resolveOnOtherThread(Promise<String> promise, Cause cause) {
        try {
            Thread.ofVirtual()
                  .start(() -> promise.fail(cause))
                  .join();
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            fail("Interrupted while resolving " + cause.message());
        }
    }

    private static void assertCompositeOf(Promise<String> any, Cause... expected) {
        any.await()
           .onSuccess(_ -> fail("Expected failure"))
           .onFailure(cause -> assertCauses(cause, expected));
    }

    private static void assertCauses(Cause cause, Cause... expected) {
        assertInstanceOf(Causes.CompositeCause.class, cause);
        assertEquals(List.of(expected),
                     cause.stream()
                          .toList());
    }
}
