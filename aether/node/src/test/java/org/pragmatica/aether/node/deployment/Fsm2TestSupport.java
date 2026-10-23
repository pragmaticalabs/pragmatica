// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.SwimConfig;

/// Test-support access to the deployment wiring's package-private derivations, for tests in other packages (the cluster FSM's fixtures).
public final class Fsm2TestSupport {
    private Fsm2TestSupport() {}

    /// [Fsm2System#suspicionLag], the lag the core suspicion window adds to SWIM's own window.
    public static TimeSpan suspicionLag(SwimConfig swim, int maxCores, TimeSpan splitTimeout) {
        return Fsm2System.suspicionLag(swim, maxCores, splitTimeout);
    }
}
