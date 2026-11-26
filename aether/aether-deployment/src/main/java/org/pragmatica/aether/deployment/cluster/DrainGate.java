// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

/// The caller's own check, run once, in the continuation of an accepted witness, immediately before the sink performs its write.
/// A gate answers two questions, so it is no lambda target: whether its attempt still owns the target, and whether the
/// calling thread holds the owner's monitor.
public interface DrainGate {
    /// Runs `write` only if the attempt that created this gate still owns the target; answers whether it ran.
    boolean runIfOwned(Runnable write);
    /// Whether the calling thread holds the owner's monitor. The sink reads it at entry of `request` and refuses a request made
    /// under that monitor. Abstract on purpose: a gate that forgot to answer would pass the guard by default.
    boolean ownerMonitorHeld();

    /// A gate that always runs the write and answers `true`, and never reports the owner's monitor as held.
    static DrainGate always() {
        return new DrainGate() {
            @Override
            public boolean runIfOwned(Runnable write) {
                write.run();

                return true;
            }

            @Override
            public boolean ownerMonitorHeld() {
                return false;
            }
        };
    }
}
