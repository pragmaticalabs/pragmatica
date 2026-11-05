// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


/// rc6 §4.1: one step of a workflow, as typed data. `precondition` and `postcondition` are evaluated on the server from the
/// observation `O` (committed state and observed metrics), never by a client. `action` calls an EXISTING mechanism and returns when
/// that mechanism accepted the request, not when its effect is complete: completion is the postcondition's to observe. `settle` is
/// how long an issued effect is given before the step may issue it again. `postconditionName` is what a `WAITING` run and an
/// incident name.
public record WorkflowStep<O>(String name,
                              Fn1<Readiness, O> precondition,
                              Fn1<Promise<Unit>, WorkflowRunValue> action,
                              Fn1<Boolean, O> postcondition,
                              String postconditionName,
                              TimeSpan settle) {
    /// Whether the step may issue its effect now.
    public sealed interface Readiness {
        /// The precondition holds: issue.
        record Ready() implements Readiness {}

        /// Not yet: hold, and re-evaluate on the next tick (the elapsed-time budget bounds the wait).
        record NotYet(String why) implements Readiness {}

        /// It can never hold: the run FAILS with `why` and releases its claims.
        record Impossible(String why) implements Readiness {}

        Ready READY = new Ready();
    }
}
