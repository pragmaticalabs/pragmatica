// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1268 — an operation that THROWS, instead of returning a failed promise, must fail its own caller and
/// leave the key usable. Before the fix the throw escaped the executor's launch task, so the operation's
/// promise was never resolved — and every later operation on that key, chained behind it, never ran.
class PerKeySerialExecutorLaunchTest {
    private static final TimeSpan AWAIT = timeSpan(5).seconds();

    @Test
    @Timeout(60)
    void submit_failsTheThrowingOperation_andRunsTheNextOperationOnTheKey() {
        var executor = PerKeySerialExecutor.<String> perKeySerialExecutor();
        var thrown = executor.<Integer> submit("k", PerKeySerialExecutorLaunchTest::throwingOperation);
        var next = executor.submit("k", () -> Promise.success(42));

        assertThat(thrown.await(AWAIT).isFailure()).as("a throwing operation must fail its own promise").isTrue();

        next.await(AWAIT)
            .onFailure(cause -> fail("the next operation on the key must run, got: " + cause.message()))
            .onSuccess(value -> assertThat(value).isEqualTo(42));
    }

    private static Promise<Integer> throwingOperation() {
        throw new IllegalStateException("operation threw instead of failing its promise");
    }
}
