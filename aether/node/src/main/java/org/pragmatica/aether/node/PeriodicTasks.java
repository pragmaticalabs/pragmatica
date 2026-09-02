// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

import org.pragmatica.lang.Contract;


/// #644: the node's recurring work, DEFERRED at assembly and armed only once cluster formation
/// resolves in `start()`. `AetherNode.assembleNode` used to hand every task to
/// `SharedScheduler.scheduleAtFixedRate` directly, so a node that was CONSTRUCTED but never started
/// ran all of its periodic work (#642's evidence run: two held-back Ember nodes, 274 snapshot ticks
/// each over 45 minutes) — and the family includes tasks that write to disk, raise operator-visible
/// alerts, or (pre-#702) issued KV removals into consensus. The contract this class enforces: a
/// created-but-unstarted node performs no periodic IO and holds no timers; recurring work arms in
/// `start()` and disarms in `stop()`.
///
/// [#defer] accumulates arming thunks during assembly without scheduling anything; [#arm] invokes
/// them once and retains the live handles; [#cancel] discards unarmed thunks AND cancels armed
/// handles. That last combination makes the failed-boot-guard path (`cancelArmedWork`) strictly
/// safer than before this class existed: a guard failure now discards work that was never
/// scheduled, instead of racing to cancel timers already ticking against half-built state (the
/// #499 zombie class).
///
/// All state transitions are synchronized on this object because `stop()` can race a `start()`
/// whose cluster-formation promise resolves late: CANCELLED is terminal, and an [#arm] that loses
/// the race to [#cancel] must schedule nothing. `cancel(false)` on armed handles lets an in-flight
/// tick finish benignly, mirroring the prior wholesale-cancel in `stop()`.
///
/// The class is public ONLY for the [#deferredCount]/[#armedCount] observation seam the Ember-level
/// contract test reads (a held-back node must hold zero armed tasks; its start must arm exactly the
/// deferred set). The mutating operations stay package-private: `AetherNode` is the only caller.
public final class PeriodicTasks {
    private enum State {
        ASSEMBLING,
        ARMED,
        CANCELLED
    }

    private final List<Supplier<ScheduledFuture<?>>> deferred = new ArrayList<>();
    private final List<ScheduledFuture<?>> armed = new ArrayList<>();
    private State state = State.ASSEMBLING;

    private PeriodicTasks() {}

    static PeriodicTasks periodicTasks() {
        return new PeriodicTasks();
    }

    /// Accumulate one arming thunk. During assembly nothing is scheduled. After [#arm] the thunk is
    /// invoked immediately — level semantics: whatever is deferred on a RUNNING node runs on it.
    /// After [#cancel] the thunk is discarded: a stopped node never gains work.
    @Contract
    synchronized void defer(Supplier<ScheduledFuture<?>> armer) {
        switch (state) {
            case ASSEMBLING -> deferred.add(armer);
            case ARMED -> armed.add(armer.get());
            case CANCELLED -> {}
        }
    }

    /// Invoke every deferred thunk once, retaining the handles for [#cancel]. Idempotent — a second
    /// call finds no deferred thunks — and a no-op after [#cancel], which is the guard against the
    /// stop-races-start window: a cluster-formation promise resolving after `stop()` must not arm
    /// work for a node already torn down.
    @Contract
    synchronized void arm() {
        if (state != State.ASSEMBLING) {
            return;
        }

        deferred.forEach(armer -> armed.add(armer.get()));
        deferred.clear();
        state = State.ARMED;
    }

    /// Discard unarmed thunks and cancel armed handles; terminal. Reached from `stop()` and from the
    /// failed-boot-guard path (`cancelArmedWork`).
    @Contract
    synchronized void cancel() {
        deferred.clear();
        armed.forEach(task -> task.cancel(false));
        armed.clear();
        state = State.CANCELLED;
    }

    /// Observation seam for the never-started contract test: thunks accumulated but not yet armed.
    public synchronized int deferredCount() {
        return deferred.size();
    }

    /// Observation seam for the never-started contract test: live scheduled handles this node holds.
    public synchronized int armedCount() {
        return armed.size();
    }
}
