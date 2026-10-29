// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.pragmatica.lang.Cause;


/// How a [LocalStep] ended. `commands` and `methods` are the facts the step produced for the batch the FSM submits
/// next; steps that produce none return [#done()].
public sealed interface LocalOutcome {
    /// The step completed.
    record Done(List<LegCommand> commands, List<String> methods) implements LocalOutcome {
        public Done {
            commands = List.copyOf(commands);
            methods = List.copyOf(methods);
        }
    }

    /// The step failed with a cause.
    record Failed(Cause cause) implements LocalOutcome {}

    /// The step ran past the bound the FSM gave it.
    record TimedOut() implements LocalOutcome {}

    static LocalOutcome done() {
        return new Done(List.of(), List.of());
    }

    static LocalOutcome done(List<LegCommand> commands) {
        return new Done(commands, List.of());
    }

    static LocalOutcome described(List<String> methods, List<LegCommand> commands) {
        return new Done(commands, methods);
    }

    static LocalOutcome failed(Cause cause) {
        return new Failed(cause);
    }

    static LocalOutcome timedOut() {
        return new TimedOut();
    }
}
