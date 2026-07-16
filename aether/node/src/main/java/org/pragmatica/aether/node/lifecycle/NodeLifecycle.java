// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.lifecycle;

import java.util.function.Consumer;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public interface NodeLifecycle {
    NodeState currentState();

    @Contract
    void subsystemsReady();

    @Contract
    void signalReady();

    Promise<Unit> drain();

    @Contract
    void addStateListener(Consumer<NodeStateChanged> listener);

    static NodeLifecycle nodeLifecycle() {
        return NodeLifecycleImpl.nodeLifecycleImpl();
    }
}
