// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// A configuration section delivered as a whole record, pushed whenever the section changes.
///
/// A plain configuration record is read once, when the slice is provisioned. A slice that declares
/// `Watched<T>` instead receives the whole new record whenever its section changes; [Live] keeps the latest
/// record for pull access.
public interface Watched<T> {
    /// Registers `callback`. On SUCCESS the registration delivery has run: `callback` was invoked once, on
    /// the calling thread, with the section's value at registration, and its Promise has resolved. Failure =
    /// the value could not be bound, OR the registration callback threw / failed / timed out: nothing is
    /// registered (the caller - slice creation - fails with that cause).
    ///
    /// Deliveries after registration are serial per subscription and continue from creation until slice UNLOAD,
    /// including while the slice is deactivated or suspended: a callback must tolerate delivery after `stop()`,
    /// and provisioned resources (released by the generated `stop()`) may already be closed by then.
    ///
    /// A callback that exceeds the timeout is abandoned and the next delivery may start while it still runs
    /// (serialisation is best-effort under a hung callback).
    Result<Subscription> watch(Fn1<Promise<Unit>, T> callback);

    /// A registration made by [#watch].
    interface Subscription {
        /// Idempotent; no delivery starts after the returned `release()` call returns.
        Promise<Unit> release();

        /// A subscription with nothing to release.
        static Subscription noop() {
            return Promise::unitPromise;
        }
    }

    /// Test-kit and non-Aether entry: registration delivery of `value`, never changes.
    static <T> Watched<T> constant(T value) {
        return callback -> deliverOnce(value, callback);
    }

    /// The registration delivery runs on the calling thread and `watch` returns only after its Promise has
    /// resolved, so the wait is part of the contract.
    @TerminalOperation
    private static <T> Result<Subscription> deliverOnce(T value, Fn1<Promise<Unit>, T> callback) {
        return Result.lift(Causes::fromThrowable,
                           () -> callback.apply(value))
                     .flatMap(Promise::await)
                     .map(_ -> Subscription.noop());
    }
}
