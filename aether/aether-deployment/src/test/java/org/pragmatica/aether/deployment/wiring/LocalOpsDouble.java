// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.node.machine.LocalOutcome;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;

/// A scriptable local runtime for interpreter tests: every step succeeds at once unless scripted; a held step settles only when released.
final class LocalOpsDouble implements LocalOps {
    record Run(NodeFsmEffect.LocalOp op) {}

    private record Pending(NodeFsmEffect.LocalOp op, Consumer<NodeFsmInput.LocalOpSettled> settled) {}

    final List<Run> runs = Collections.synchronizedList(new ArrayList<>());
    final Map<LocalStep, Supplier<LocalOutcome>> script = Collections.synchronizedMap(new HashMap<>());
    final Set<LocalStep> held = Collections.synchronizedSet(new HashSet<>());
    private final List<Pending> pending = Collections.synchronizedList(new ArrayList<>());
    volatile boolean closed;
    /// Settle from a thread of its own, as the real executor's virtual threads do.
    volatile boolean asynchronous;

    @Override
    public void run(NodeFsmEffect.LocalOp op, LongSupplier currentIncarnation, Consumer<NodeFsmInput.LocalOpSettled> settled) {
        runs.add(new Run(op));

        if (held.contains(op.step())) {
            pending.add(new Pending(op, settled));
        } else {
            settle(op, settled);
        }
    }

    @Override
    public void close() {
        closed = true;
    }

    /// Settles every held op of `step`.
    void release(LocalStep step) {
        held.remove(step);
        var due = new ArrayList<Pending>();

        synchronized (pending) {
            pending.removeIf(p -> p.op().step() == step && due.add(p));
        }

        due.forEach(p -> settle(p.op(), p.settled()));
    }

    List<LocalStep> steps() {
        synchronized (runs) {
            return runs.stream().map(run -> run.op().step()).toList();
        }
    }

    long count(LocalStep step) {
        return steps().stream().filter(s -> s == step).count();
    }

    private void settle(NodeFsmEffect.LocalOp op, Consumer<NodeFsmInput.LocalOpSettled> settled) {
        var outcome = script.getOrDefault(op.step(), LocalOutcome::done).get();

        op.artifact()
          .filter(_ -> op.token() != 0L)
          .onPresent(artifact -> deliver(settled, new NodeFsmInput.LocalOpSettled(op.incarnation(), artifact, op.token(), outcome)));
    }

    private void deliver(Consumer<NodeFsmInput.LocalOpSettled> settled, NodeFsmInput.LocalOpSettled input) {
        if (asynchronous) {
            Thread.ofVirtual().name("local-op-double").start(() -> settled.accept(input));
        } else {
            settled.accept(input);
        }
    }
}
