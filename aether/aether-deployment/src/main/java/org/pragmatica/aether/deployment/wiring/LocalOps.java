// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// How the node interpreter runs a `LocalOp` effect (design 4.3): the seam in front of `LocalOpExecutor`. Tests substitute a recording
/// double to drop, delay or reorder a completion.
public interface LocalOps {
    /// Runs `op` and reports its end through `settled` (a token-0 op reports nothing). `currentIncarnation` is read when the op is dequeued:
    /// a chain step of another incarnation is discarded unrun.
    @Contract
    void run(NodeFsmEffect.LocalOp op, LongSupplier currentIncarnation, Consumer<NodeFsmInput.LocalOpSettled> settled);

    /// Resolves when no op is queued or running (design 7, 2a: the `SUSPEND`s of a quorum loss have run). A runtime that runs nothing is always idle.
    default Promise<Unit> idle() {
        return Promise.unitPromise();
    }

    /// Stops accepting work (the `Close` of the node interpreter).
    @Contract
    default void close() {}
}
