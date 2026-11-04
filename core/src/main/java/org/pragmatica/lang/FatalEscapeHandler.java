/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
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

/// The process-wide receiver of every [Error] that escapes a [Promise] continuation: an `onResult`, `onSuccess` or
/// `onFailure` handler, a `map`, `flatMap` or `fold` transformation, or an `async` consumer.
///
/// Without a handler such an error is logged at ERROR and the dependent promise fails, and that is all anyone
/// sees: an error that is not a [VirtualMachineError] (an `AssertionError`, a `LinkageError`) ends up as a failed
/// `Result`, and a [VirtualMachineError] is rethrown on whichever thread ran the continuation, often an executor
/// thread nobody watches. A host that must react (stop the process, fail a test) installs one handler at its
/// entry point with [Promise#installFatalEscapeHandler(FatalEscapeHandler)]. A [RuntimeException] never reaches
/// the handler; it keeps the existing handling (logged, dependent fails).
///
/// The handler runs on the escaping thread, inside the catch at the continuation boundary, after the ERROR log
/// line and before the dependent promise is failed. It should do as little as possible there: a handler that
/// needs real work (a flush, a halt) hands the escape to a thread of its own. Whatever the handler throws is
/// caught and logged, and the original escape then proceeds unchanged. An error escaping a continuation that
/// the handler itself runs, on the same thread, does not reach the handler again, so a handler that uses
/// [Promise] cannot recurse into itself.
///
/// Delivery is exactly once per continuation boundary crossed, not once per [Error] object: a
/// [VirtualMachineError] is rethrown by the boundary that caught it, so when that rethrow crosses further
/// boundaries (a continuation that itself resolves another promise) the same object is delivered once at each.
/// A handler that must act once per error compares [FatalEscape#error()] by identity. Delivery is not ordered
/// either: an escape delivered directly after an install can arrive before the replay of the escapes recorded
/// before that install.
@FunctionalInterface
public interface FatalEscapeHandler {
    /// Receive one escape.
    ///
    /// @param escape the escaped error and where it escaped
    ///
    /// @return [Unit]; the value is ignored
    Unit onFatalEscape(FatalEscape escape);

    /// The holder's handle on an installed handler.
    @FunctionalInterface
    interface Registration {
        /// Free the slot so that another handler can be installed. After this returns, an escape with no handler
        /// is logged and counted in [Promise#unownedFatalEscapes()]; it is never buffered for a later handler.
        ///
        /// Not fenced against an in-flight delivery: an escape that read the slot just before the uninstall can
        /// still call this registration's handler once after the uninstall has returned.
        ///
        /// @return success when this registration held the slot, [FatalEscapeError#NOT_INSTALLED] otherwise
        Result<Unit> uninstall();
    }
}
