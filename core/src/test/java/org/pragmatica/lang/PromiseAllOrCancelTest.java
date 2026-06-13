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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.io.CoreError;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class PromiseAllOrCancelTest {
    private static final Cause TEST_FAILURE = new CoreError.Fault("Test failure");

    @Nested
    class StaticAllOrCancel {

        @Test
        void allOrCancel_allSucceed_returnsAllValues_2args() {
            var result = Promise.allOrCancel(Promise.success("a"), Promise.success(42))
                                .map((s, i) -> s + i)
                                .await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(v -> assertEquals("a42", v));
        }

        @Test
        void allOrCancel_allSucceed_returnsAllValues_3args() {
            var result = Promise.allOrCancel(Promise.success("a"), Promise.success(42), Promise.success(true))
                                .map((s, i, b) -> s + i + b)
                                .await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(v -> assertEquals("a42true", v));
        }

        @Test
        void allOrCancel_firstFails_cancelsRemaining() throws InterruptedException {
            var cancelled = new CountDownLatch(1);
            var p2 = Promise.<Integer>promise()
                            .onFailure(_ -> cancelled.countDown());

            var output = Promise.allOrCancel(Promise.<String>failure(TEST_FAILURE), p2)
                                .id();

            output.await()
                  .onSuccess(_ -> fail("Expected failure"))
                  .onFailure(c -> assertEquals(TEST_FAILURE.message(), c.message()));

            assertTrue(cancelled.await(2, TimeUnit.SECONDS), "Second promise should have been cancelled");
        }

        @Test
        void allOrCancel_secondFails_cancelsRemaining() throws InterruptedException {
            var cancelled = new CountDownLatch(1);
            var p1 = Promise.<String>promise()
                            .onFailure(_ -> cancelled.countDown());

            var output = Promise.allOrCancel(p1, Promise.<Integer>failure(TEST_FAILURE))
                                .id();

            output.await()
                  .onSuccess(_ -> fail("Expected failure"))
                  .onFailure(c -> assertEquals(TEST_FAILURE.message(), c.message()));

            assertTrue(cancelled.await(2, TimeUnit.SECONDS), "First promise should have been cancelled");
        }

        @Test
        void allOrCancel_allFail_resolvesWithFirstFailure() {
            var cause1 = new CoreError.Fault("First");
            var cause2 = new CoreError.Fault("Second");

            var output = Promise.allOrCancel(Promise.<String>failure(cause1), Promise.<Integer>failure(cause2))
                                .id()
                                .await();

            output.onSuccess(_ -> fail("Expected failure"))
                  .onFailure(c -> assertTrue(
                          c.message().equals("First") || c.message().equals("Second"),
                          "Should resolve with one of the failure causes"));
        }

        @Test
        void allOrCancel_singlePromise_behavesLikeAll() {
            var result = Promise.allOrCancel(Promise.success("hello"))
                                .map(s -> s + " world")
                                .await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(v -> assertEquals("hello world", v));
        }
    }

    @Nested
    class AllOfOrCancel {

        @Test
        void allOfOrCancel_emptyCollection_returnsEmptyList() {
            List<Promise<String>> empty = List.of();
            var result = Promise.allOfOrCancel(empty).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(list -> assertTrue(list.isEmpty()));
        }

        @Test
        void allOfOrCancel_allSucceed_returnsAllResults() {
            var promises = List.of(Promise.success("a"), Promise.success("b"), Promise.success("c"));
            var result = Promise.allOfOrCancel(promises).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(list -> assertEquals(3, list.size()));
        }

        @Test
        void allOfOrCancel_oneFails_cancelsRemaining() throws InterruptedException {
            var cancelled = new CountDownLatch(1);
            var slow = Promise.<String>promise()
                              .onFailure(_ -> cancelled.countDown());

            var promises = List.of(slow, Promise.<String>failure(TEST_FAILURE));
            var output = Promise.allOfOrCancel(promises);

            output.await()
                  .onSuccess(_ -> fail("Expected failure"))
                  .onFailure(c -> assertEquals(TEST_FAILURE.message(), c.message()));

            assertTrue(cancelled.await(2, TimeUnit.SECONDS), "Slow promise should have been cancelled");
        }
    }

    @Nested
    class InstanceAllOrCancel {

        @Test
        void instanceAllOrCancel_executesInParallel() {
            var start = new AtomicLong();
            var result = Promise.success("trigger")
                                .allOrCancel(
                                        _ -> delayedSuccess("a", 200),
                                        _ -> delayedSuccess(42, 200))
                                .map((s, i) -> s + i);

            start.set(System.currentTimeMillis());
            var awaited = result.await();
            var elapsed = System.currentTimeMillis() - start.get();

            awaited.onFailure(_ -> fail("Expected success"))
                   .onSuccess(v -> assertEquals("a42", v));

            assertTrue(elapsed < 600, "Two 200ms operations should run in parallel, took " + elapsed + "ms");
        }

        @Test
        void instanceAllOrCancel_cancelsOnFailure() throws InterruptedException {
            var cancelled = new CountDownLatch(1);

            var result = Promise.success("trigger")
                                .allOrCancel(
                                        _ -> Promise.<String>failure(TEST_FAILURE),
                                        _ -> Promise.<Integer>promise().onFailure(_ -> cancelled.countDown()))
                                .id()
                                .await();

            result.onSuccess(_ -> fail("Expected failure"));

            assertTrue(cancelled.await(2, TimeUnit.SECONDS), "Second promise should have been cancelled");
        }
    }

    @Nested
    class InstanceAllFixed {

        @Test
        void instanceAll_executesInParallel() {
            var start = new AtomicLong();
            var result = Promise.success("trigger")
                                .all(_ -> delayedSuccess("a", 200),
                                     _ -> delayedSuccess(42, 200))
                                .map((s, i) -> s + i);

            start.set(System.currentTimeMillis());
            var awaited = result.await();
            var elapsed = System.currentTimeMillis() - start.get();

            awaited.onFailure(_ -> fail("Expected success"))
                   .onSuccess(v -> assertEquals("a42", v));

            assertTrue(elapsed < 600, "Two 200ms operations should run in parallel (instance all() fix), took " + elapsed + "ms");
        }

        @Test
        void instanceAll_3args_executesInParallel() {
            var start = new AtomicLong();
            var result = Promise.success("trigger")
                                .all(_ -> delayedSuccess("a", 150),
                                     _ -> delayedSuccess(42, 150),
                                     _ -> delayedSuccess(true, 150))
                                .map((s, i, b) -> s + i + b);

            start.set(System.currentTimeMillis());
            var awaited = result.await();
            var elapsed = System.currentTimeMillis() - start.get();

            awaited.onFailure(_ -> fail("Expected success"))
                   .onSuccess(v -> assertEquals("a42true", v));

            assertTrue(elapsed < 500, "Three 150ms operations should run in parallel, took " + elapsed + "ms");
        }
    }

    private static <T> Promise<T> delayedSuccess(T value, long delayMs) {
        return Promise.promise(timeSpan(delayMs).millis(), promise -> promise.succeed(value));
    }
}
