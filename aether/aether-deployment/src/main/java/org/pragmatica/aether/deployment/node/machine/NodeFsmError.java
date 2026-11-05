// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.utils.Causes;


/// Failures the node FSM itself names. Each is retryable: none is a [org.pragmatica.aether.slice.SliceLoadingFailure.Fatal],
/// so the disposition the FSM writes for them is always non-fatal (spec N3: a failed state write is RETRY).
public sealed interface NodeFsmError extends Cause {
    /// An observed write was resubmitted `attempts` times with the same version and never confirmed (spec R-3.4.4).
    record WriteExhausted(Artifact artifact, long version, int attempts, String message) implements NodeFsmError {
        public static final Fn3<WriteExhausted, Artifact, Long, Integer> FACTORY = Causes.forThreeValues("Observed write of %s at version %s was not confirmed after %s attempts",
                                                                                                         WriteExhausted::new);
    }

    /// A local step ran past its bound.
    record StepTimedOut(LocalStep step, long timeoutMs, String message) implements NodeFsmError {
        public static final Fn2<StepTimedOut, LocalStep, Long> FACTORY = Causes.forTwoValues("Local step %s did not finish within %s ms",
                                                                                             StepTimedOut::new);
    }

    /// An activation chain ran past its overall bound (B-169).
    record ChainTimedOut(Artifact artifact, long boundMs, String message) implements NodeFsmError {
        public static final Fn2<ChainTimedOut, Artifact, Long> FACTORY = Causes.forTwoValues("Activation chain of %s did not finish within %s ms",
                                                                                             ChainTimedOut::new);
    }
}
