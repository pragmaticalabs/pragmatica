// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.lifecycle;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


final class NodeLifecycleImpl implements NodeLifecycle {
    private static final Logger log = LoggerFactory.getLogger(NodeLifecycleImpl.class);

    private final AtomicReference<NodeState> state = new AtomicReference<>(NodeState.STARTING);
    private final CopyOnWriteArrayList<Consumer<NodeStateChanged>> listeners = new CopyOnWriteArrayList<>();
    private final Object transitionLock = new Object();

    private NodeLifecycleImpl() {}

    static NodeLifecycleImpl nodeLifecycleImpl() {
        return new NodeLifecycleImpl();
    }

    @Override
    public NodeState currentState() {
        return state.get();
    }

    @Override
    @Contract
    public void subsystemsReady() {
        transition(NodeState.STARTING, NodeState.JOINING);
    }

    @Override
    @Contract
    public void signalReady() {
        transition(NodeState.JOINING, NodeState.ACTIVE);
    }

    @Override
    public Promise<Unit> drain() {
        if (!transition(NodeState.ACTIVE, NodeState.DRAINING)) {
            var current = state.get();

            if (current == NodeState.DRAINING || current == NodeState.STOPPED) {
                return Promise.unitPromise();
            }

            return NodeLifecycleError.General.NOT_ACTIVE.promise();
        }

        completeDrain();

        return Promise.unitPromise();
    }

    @Contract
    private void completeDrain() {
        transition(NodeState.DRAINING, NodeState.STOPPED);
    }

    @Override
    @Contract
    public void addStateListener(Consumer<NodeStateChanged> listener) {
        listeners.add(listener);
        var current = state.get();

        notifyListener(listener, NodeStateChanged.nodeStateChanged(current, current));
    }

    private boolean transition(NodeState from, NodeState to) {
        synchronized (transitionLock) {
            if (!state.compareAndSet(from, to)) {
                return false;
            }

            log.info("NodeLifecycle: {} -> {}",
                     from,
                     to);
            var event = NodeStateChanged.nodeStateChanged(from, to);

            listeners.forEach(listener -> notifyListener(listener, event));

            return true;
        }
    }

    private static void notifyListener(Consumer<NodeStateChanged> listener, NodeStateChanged event) {
        Result.lift(Causes::fromThrowable, () -> deliverEvent(listener, event)).onFailure(cause -> log.warn("NodeLifecycle: state listener failed: {}",
                                                                                                            cause.message()));
    }

    private static Unit deliverEvent(Consumer<NodeStateChanged> listener, NodeStateChanged event) {
        listener.accept(event);

        return Unit.unit();
    }
}
