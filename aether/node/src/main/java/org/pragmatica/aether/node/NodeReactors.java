// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;
import org.pragmatica.utility.react.ReactGate;

import static org.pragmatica.lang.Unit.unit;


/// The node's react executors (KVStore dispatcher design §3.5 R5, §3.5.1 "Executors"; D1-react): one serial executor per
/// C9 component, all behind one engine-alive gate, with the gate predicate "engine not asked to stop **and** the node
/// not stopping". The engine half is bound once the cluster node exists ([#bindEngine]); before that the gate reads the
/// engine as alive, because a node that has no engine yet has not stopped one (boot reactions must run). `stop()` calls
/// [#markStopping] first, so a reaction still queued cannot restart what the stop cancels (#642), and [#close] last.
/// An `Error` in a reaction stops the node through the same sink as the KV dispatcher (§6.1, ruling 6).
public record NodeReactors(AtomicReference<BooleanSupplier> engineStopRequested,
                           AtomicBoolean stopping,
                           ReactFailureSink failureSink,
                           Map<String, ReactExecutor> executors) {
    public static NodeReactors nodeReactors(DispatcherFailureSink dispatcherSink) {
        var once = DispatcherFailureSink.once(dispatcherSink);

        return new NodeReactors(new AtomicReference<>(() -> false),
                                new AtomicBoolean(),
                                (component, reaction, error) -> once.stopNode("react " + component + "/" + reaction,
                                                                              error),
                                new ConcurrentHashMap<>());
    }

    /// The component's executor, created on first use; every row of one component shares it (R2).
    public ReactExecutor executor(String component) {
        return executors.computeIfAbsent(component, this::newExecutor);
    }

    public Unit bindEngine(BooleanSupplier engineStopRequested) {
        this.engineStopRequested.set(engineStopRequested);

        return unit();
    }

    public Unit markStopping() {
        stopping.set(true);

        return unit();
    }

    public Unit close() {
        executors.values().forEach(ReactExecutor::close);

        return unit();
    }

    public List<ReactExecutor> all() {
        return List.copyOf(executors.values());
    }

    private ReactExecutor newExecutor(String component) {
        return ReactExecutor.reactExecutor(component, ReactGate.reactGate(this::engineAlive, stopping::get), failureSink);
    }

    private boolean engineAlive() {
        return ! engineStopRequested.get()
                                    .getAsBoolean();
    }
}
