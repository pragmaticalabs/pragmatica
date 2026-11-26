// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

/// The caller's own check, run once, in the continuation of an accepted witness, immediately before the sink performs its write.
public interface DrainGate {
    /// Runs `write` only if the attempt that created this gate still owns the target; answers whether it ran.
    boolean runIfOwned(Runnable write);
}
