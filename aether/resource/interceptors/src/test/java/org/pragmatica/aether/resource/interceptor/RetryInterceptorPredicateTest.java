// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #280 R26: the retry interceptor retried EVERY failure — a slice's own business verdict
/// (`InsufficientFunds`) as eagerly as a timeout, on methods that may not be idempotent. The
/// interceptor cannot tell a business failure from an infrastructure one by where it came from,
/// so retry is opt-in by classification: by default only a cause that declares itself transient
/// is retried, and an unclassified cause — which is what every business failure is — is returned
/// after the first attempt.
class RetryInterceptorPredicateTest {
    private static final TimeSpan ONE_MS = TimeSpan.timeSpan(1).millis();

    /// A slice author's business failure: unclassified, as they all are.
    private record InsufficientFunds(String message) implements Cause {}

    @Test
    void defaultPolicy_unclassifiedBusinessFailure_isNotRetried() {
        var attempts = new AtomicInteger();
        var intercepted = interceptor(RetryConfig.retryConfig(3, ONE_MS)).intercept(failingWith(attempts,
                                                                                                new InsufficientFunds("no")));

        intercepted.apply("x").await().onSuccess(_ -> fail("must fail"));
        assertThat(attempts.get()).as("a business failure must not be retried by default").isEqualTo(1);
    }

    @Test
    void defaultPolicy_unclassifiedBusinessFailure_returnsTheOriginalCause() {
        var intercepted = interceptor(RetryConfig.retryConfig(3, ONE_MS)).intercept(failingWith(new AtomicInteger(),
                                                                                                new InsufficientFunds("no")));

        intercepted.apply("x")
                   .await()
                   .onSuccess(_ -> fail("must fail"))
                   .onFailure(cause -> assertThat(cause).as("the caller sees its own cause, not a retry wrapper")
                                                 .isInstanceOf(InsufficientFunds.class));
    }

    /// A cause that declares itself transient — what infrastructure failures classify as.
    private record PeerBusy(String message) implements Cause.Transient {}

    /// Could not be written before the fix (`Cause.Transient` is new); the budget is what the pin
    /// counts: `max_attempts` is calls at the METHOD, the first included.
    @Test
    void defaultPolicy_transientFailure_isRetried_upToMaxAttempts() {
        var attempts = new AtomicInteger();
        var intercepted = interceptor(RetryConfig.retryConfig(3, ONE_MS)).intercept(failingWith(attempts,
                                                                                                new PeerBusy("later")));

        intercepted.apply("x").await().onSuccess(_ -> fail("must fail"));
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void nonTerminalPolicy_unclassifiedFailure_isRetried_theOldBehaviourOptedInto() {
        var attempts = new AtomicInteger();
        var config = RetryConfig.retryConfig(3, ONE_MS).map(c -> c.withRetryOn(RetryOn.NON_TERMINAL));
        var intercepted = interceptor(config).intercept(failingWith(attempts, new InsufficientFunds("no")));

        intercepted.apply("x").await().onSuccess(_ -> fail("must fail"));
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void singleAttemptBudget_transientFailure_isNotRetried() {
        var attempts = new AtomicInteger();
        var intercepted = interceptor(RetryConfig.retryConfig(1, ONE_MS)).intercept(failingWith(attempts,
                                                                                                new PeerBusy("later")));

        intercepted.apply("x").await().onSuccess(_ -> fail("must fail"));
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void transientFailure_thenSuccess_returnsTheSuccess() {
        var attempts = new AtomicInteger();
        Fn1<Promise<String>, String> flaky = _ -> attempts.incrementAndGet() < 3
                                                  ? new PeerBusy("later").<String> promise()
                                                  : Promise.success("ok");
        var intercepted = interceptor(RetryConfig.retryConfig(3, ONE_MS)).intercept(flaky);
        var value = intercepted.apply("x").await().fold(cause -> fail("must succeed: " + cause.message()),
                                                        v -> v);

        assertThat(value).isEqualTo("ok");
        assertThat(attempts.get()).isEqualTo(3);
    }

    /// A settled verdict, never worth retrying.
    private record AccountClosed(String message) implements Cause.Terminal {}

    @Test
    void terminalFailure_isNeverRetried_underEitherPolicy() {
        for (var policy : RetryOn.values()) {
            var attempts = new AtomicInteger();
            var config = RetryConfig.retryConfig(3, ONE_MS).map(c -> c.withRetryOn(policy));
            var intercepted = interceptor(config).intercept(failingWith(attempts, new AccountClosed("closed")));

            intercepted.apply("x").await().onSuccess(_ -> fail("must fail"));

            assertThat(attempts.get()).as("terminal under " + policy).isEqualTo(1);
        }
    }

    /// B1 (review of #1088): the policy must hold on EVERY failure, not the first. Round 1 classified
    /// the first failure and then handed the budget to a loop that stops only on terminal causes,
    /// so a business verdict on attempt two was re-driven to the budget.
    @Test
    void defaultPolicy_transientThenBusinessFailure_stopsAtTheBusinessFailure() {
        var attempts = new AtomicInteger();
        Fn1<Promise<String>, String> flaky = _ -> attempts.incrementAndGet() == 1
                                                 ? new PeerBusy("later").<String> promise()
                                                 : new InsufficientFunds("no").<String> promise();
        var intercepted = interceptor(RetryConfig.retryConfig(3, ONE_MS)).intercept(flaky);

        intercepted.apply("x")
                   .await()
                   .onSuccess(_ -> fail("must fail"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(InsufficientFunds.class));

        assertThat(attempts.get()).as("one transient retry, then the business verdict stops the loop").isEqualTo(2);
    }

    @Test
    void defaultPolicy_twoTransientThenBusinessFailure_stopsAtTheBusinessFailure_underALargerBudget() {
        var attempts = new AtomicInteger();
        Fn1<Promise<String>, String> flaky = _ -> attempts.incrementAndGet() <= 2
                                                 ? new PeerBusy("later").<String> promise()
                                                 : new InsufficientFunds("no").<String> promise();
        var intercepted = interceptor(RetryConfig.retryConfig(5, ONE_MS)).intercept(flaky);

        intercepted.apply("x").await().onSuccess(_ -> fail("must fail"));

        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void defaultPolicy_transientFailure_withBudgetOfTwo_isRetriedOnce() {
        var attempts = new AtomicInteger();
        var intercepted = interceptor(RetryConfig.retryConfig(2, ONE_MS)).intercept(failingWith(attempts, new PeerBusy("later")));

        intercepted.apply("x").await().onSuccess(_ -> fail("must fail"));

        assertThat(attempts.get()).isEqualTo(2);
    }

    private static RetryMethodInterceptor interceptor(org.pragmatica.lang.Result<RetryConfig> config) {
        return config.flatMap(c -> new RetryInterceptorFactory().provision(c)
                                                                .await())
                     .fold(cause -> fail("provision must succeed: " + cause.message()),
                           i -> i);
    }

    static Fn1<Promise<String>, String> failingWith(AtomicInteger attempts, Cause cause) {
        return _ -> {
            attempts.incrementAndGet();

            return cause.promise();
        };
    }
}
