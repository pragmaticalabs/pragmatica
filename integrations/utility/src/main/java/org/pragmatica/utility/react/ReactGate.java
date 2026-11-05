// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.utility.react;

import java.util.function.BooleanSupplier;


/// The engine-alive gate of a react executor (KVStore dispatcher design §3.5 R5, §3.5.1): a side-effect reaction runs
/// only while the gate is open. It is read when the reaction RUNS, never when it is queued, so a reaction queued before
/// an engine stop and run after it is dropped.
@FunctionalInterface
public interface ReactGate {
    boolean isOpen();

    /// Open while the consensus engine is alive **and** the node is not stopping (§3.5.1 "Gate predicate":
    /// `AetherNode.stop` sets the stopping flag first, so a queued reaction cannot restart what `stop()` already
    /// cancelled, #642).
    static ReactGate reactGate(BooleanSupplier engineAlive, BooleanSupplier nodeStopping) {
        return () -> engineAlive.getAsBoolean() && !nodeStopping.getAsBoolean();
    }

    /// Always open: a host with no engine to stop (an inline test store, a standalone component).
    static ReactGate alwaysOpen() {
        return () -> true;
    }
}
