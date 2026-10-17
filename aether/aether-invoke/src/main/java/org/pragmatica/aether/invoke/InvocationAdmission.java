// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.Consumer;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;


/// Admission and lifetime accounting for actual local execution, shared by remote and local calls.
public interface InvocationAdmission {
    enum Error implements Cause {
        DRAINING;
        @Override
        public String message() {
            return "Node is draining and cannot accept new execution";
        }
    }

    boolean tryEnter();

    /// Account for execution settlement, independently of a caller's response deadline.
    default <T> Promise<T> execute(Supplier<Promise<T>> execution) {
        return execute(execution,
                       _ -> {});
    }

    /// Emit exactly one result, including admission refusal, before releasing execution accounting.
    default <T> Promise<T> execute(Supplier<Promise<T>> execution, Consumer<Result<T>> completion) {
        if (!tryEnter()) {
            var refused = Error.DRAINING.<T> result();

            completion.accept(refused);

            return refused.async();
        }

        var response = Promise.<T> promise();

        execution.get().withResult(completion).withResult(response::resolve).onResultRun(this::exit);

        return response;
    }

    @Contract
    void exit();

    /// Independent admission conditions compose with the lifetime counter; they never reopen
    /// a tracker already closed by drain. Calls admitted before closure retain their accounting.
    static InvocationAdmission gated(InvocationAdmission delegate, BooleanSupplier isCurrent) {
        record gated(InvocationAdmission delegate, BooleanSupplier isCurrent) implements InvocationAdmission {
            @Override
            public boolean tryEnter() {
                return isCurrent.getAsBoolean() && delegate.tryEnter();
            }

            @Override
            @Contract
            public void exit() {
                delegate.exit();
            }
        }

        return new gated(delegate, isCurrent);
    }

    static InvocationAdmission open() {
        record open() implements InvocationAdmission {
            @Override
            public boolean tryEnter() {
                return true;
            }

            @Override
            @Contract
            public void exit() {}
        }

        return new open();
    }
}
