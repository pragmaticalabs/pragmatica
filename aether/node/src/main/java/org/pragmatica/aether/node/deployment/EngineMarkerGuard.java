// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import org.pragmatica.aether.deployment.wiring.FsmTimers.TimerService;
import org.pragmatica.aether.node.ClusterIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentEngineKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngineValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Keeps a node of one deployment engine out of a cluster of the other (phase-3 design 8.2, D1, W-29). The engine is a committed cluster fact:
/// `DeploymentEngineKey` is minted with the cluster's lineage, and **every refusal is a node refusing itself**, on positive evidence only.
///
/// | Committed state this node observes | FSM2 node | LEGACY node |
/// |---|---|---|
/// | marker = FSM2 | opens | **refuses itself** |
/// | marker = LEGACY | **refuses itself** | opens |
/// | incarnation present, marker absent (a pre-phase-3 cluster, G-2) | **refuses itself** | opens |
/// | both absent (genesis still running) | **waits**: closed, no WARN, never refuses | opens |
///
/// **The window.** A refusal is measured from an observed committed `LeaderValue`, never from "runtime ready". When the evidence of a mismatch
/// is first seen with a leader committed, one WARN `deployment-engine-mismatch` names the evidence and the recovery, and the node exits only if
/// the mismatch still stands when `windowMs` has passed. A mismatch that clears inside the window (the marker the node waited for arrives, or a
/// restore replaces it) cancels the exit.
///
/// **The activation gate, both directions.** While the verdict is not "open", no engine's deployment FSMs activate on this node. [#whenOpen]
/// parks the **latest** action of each name until the guard opens, and drops it if the node is refused, so a misconfigured newcomer that wins an
/// election writes nothing before it exits. An FSM2 node opens only once it reads an FSM2 marker.
///
/// **Not carried here.** The restore half (#1533; FSM replacement plan 3.2 delta (a): a pre-phase-3 backup restores into the running
/// engine's marker, never into "marker absent") is the restore's finish
/// (`BackupRestoreCoordinator`, W-34). The worker arms are deferred (report 1.2).
public final class EngineMarkerGuard {
    private static final Logger log = LoggerFactory.getLogger(EngineMarkerGuard.class);
    /// D.6 row 1: the reason of the recovery raised when the node refuses itself (its subject leaves).
    static final String REFUSED_ITSELF = "node-refused-itself";

    /// What the guard reads from the committed mirror, on every evaluation.
    public interface Reader {
        Option<DeploymentEngineValue> marker();
        boolean incarnationPresent();
        Option<LeaderValue> leader();
    }

    /// The reader over a node's own committed mirror: the marker, whether an incarnation is committed, and the committed leader.
    public static Reader readerOver(KVStore<AetherKey, AetherValue> store) {
        return new Reader() {
            @Override
            public Option<DeploymentEngineValue> marker() {
                return store.getTyped(DeploymentEngineKey.deploymentEngineKey(), DeploymentEngineValue.class);
            }

            @Override
            public boolean incarnationPresent() {
                return ClusterIncarnation.committed(store).isPresent();
            }

            @Override
            public Option<LeaderValue> leader() {
                return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class);
            }
        };
    }

    /// Where a guard stands.
    public enum State {
        /// This node's engine matches the cluster's: its deployment FSMs may activate.
        OPEN,
        /// Genesis is still running (both keys absent): wait, do nothing, never refuse.
        WAITING,
        /// Positive evidence of a mismatch is held, with no leader committed yet: closed, the window has not started.
        MISMATCH_UNTIMED,
        /// Mismatch, WARN raised: closed, exits when the window ends if the mismatch stands.
        MISMATCH_WINDOW,
        /// The node refused itself.
        REFUSED
    }

    private enum Verdict {
        OPEN,
        WAIT,
        MISMATCH
    }

    private final DeploymentEngine configured;
    private final NodeId self;
    private final long windowMs;
    private final Reader reader;
    private final TimerService clock;
    private final LongSupplier wallClock;
    private final OperatorWarningSink warnings;
    private final Consumer<String> exit;
    private final Map<String, List<Runnable>> parked = new LinkedHashMap<>();
    /// Serializes the actions; held while one runs. A thread that only decides takes it in one case: an evaluation that finds drained work
    /// pending (its own opening, or an earlier opening whose thread has not yet got the lock, #3270) queues on it to run that work, and may then
    /// run nothing, because another thread ran the work first or a refusal dropped it (#3271).
    private final ReentrantLock running = new ReentrantLock();
    private List<Runnable> drained = List.of();
    private State state = State.WAITING;
    private boolean windowExpired;
    /// The window a timer belongs to: a timer whose body was already running when its window was cancelled must not end a later window.
    private long windowGeneration;

    private Runnable cancelWindow = () -> {};

    private EngineMarkerGuard(DeploymentEngine configured,
                              NodeId self,
                              long windowMs,
                              Reader reader,
                              TimerService clock,
                              LongSupplier wallClock,
                              OperatorWarningSink warnings,
                              Consumer<String> exit) {
        this.configured = configured;
        this.self = self;
        this.windowMs = windowMs;
        this.reader = reader;
        this.clock = clock;
        this.wallClock = wallClock;
        this.warnings = warnings;
        this.exit = exit;
        this.state = configured == DeploymentEngine.LEGACY
                     ? State.OPEN
                     : State.WAITING;
    }

    public static EngineMarkerGuard engineMarkerGuard(DeploymentEngine configured,
                                                      NodeId self,
                                                      long windowMs,
                                                      Reader reader,
                                                      TimerService clock,
                                                      LongSupplier wallClock,
                                                      OperatorWarningSink warnings,
                                                      Consumer<String> exit) {
        return new EngineMarkerGuard(configured, self, windowMs, reader, clock, wallClock, warnings, exit);
    }

    public synchronized State state() {
        return state;
    }

    /// Whether this node's deployment FSMs may activate.
    public synchronized boolean isOpen() {
        return state == State.OPEN;
    }

    /// Runs `action` now if the guard is open; otherwise parks it under `name`, replacing everything parked under that name (an activation
    /// is level-triggered: a later `Deactivate` supersedes a parked `Activate`, and a parked `Deactivate`-then-`Activate` pair). Parked actions
    /// run, in the order they were first parked, when the guard opens. A refused node drops them (#3271): the parked ones, and the drained ones
    /// an opening has not yet run, whether still pending or already handed to a thread; each drained action checks for a refusal just before it
    /// runs. What the refusal cannot stop is an action already started, or past that check, when it lands: that one runs to completion.
    @Contract
    public void whenOpen(String name, Runnable action) {
        running.lock();
        try {
            runAdmitted(name, action, false);
        } finally {
            running.unlock();
        }
    }

    /// Like [#whenOpen], but a parked action of that name is kept and `action` runs after it (#3253): a parked `Activate` must not erase a parked
    /// `Deactivate`, because the pair re-incarnates the FSM (`Deactivate`, then `Activate`, the B14 rule) while the `Activate` alone is ignored by
    /// an FSM that is still Active. A later [#whenOpen] under the same name still replaces the whole parked sequence. The guard cannot tell the
    /// kinds apart (the actions are opaque), so the caller picks the method.
    @Contract
    public void whenOpenAfterParked(String name, Runnable action) {
        running.lock();
        try {
            runAdmitted(name, action, true);
        } finally {
            running.unlock();
        }
    }

    /// Reads the committed mirror and moves to the state it justifies. Idempotent; called on every notification of the three keys it depends on,
    /// on a leader change, and when the window ends. It does not wait for a running action unless drained work is pending: an evaluation that
    /// finds parked work drained (its own opening, or an earlier opening whose thread has not yet run it) queues behind a running action to run it.
    /// Otherwise it only decides and returns.
    @Contract
    public void evaluate() {
        if (decided()) {
            running.lock();
            try {
                runDrained();
            } finally {
                running.unlock();
            }
        }
    }

    /// True when drained work is pending after this evaluation: this evaluation opened the guard, or an earlier opening is still waiting to run it.
    private synchronized boolean decided() {
        if (state != State.REFUSED) {
            decide(verdict());
        }

        return ! drained.isEmpty();
    }

    /// With `running` held: runs `action` behind everything drained so far, or parks it.
    private void runAdmitted(String name, Runnable action, boolean keepParked) {
        admitted(name, action, keepParked).forEach(Runnable::run);
    }

    /// Run or park, decided in one step, and only once nothing drained is left ahead (P2, v-2643 finding 2). Drained work is handed back first,
    /// followed by a fresh decision for `action`: an opening that lands while that work runs drains more, and the fresh decision runs it ahead
    /// too, so an older parked action never runs after a newer admitted one. Actions themselves never run under the decision monitor.
    private synchronized List<Runnable> admitted(String name, Runnable action, boolean keepParked) {
        if (!drained.isEmpty()) {
            return Stream.concat(takeDrained().stream(),
                                 Stream.<Runnable> of(() -> runAdmitted(name, action, keepParked)))
                         .toList();
        }

        if (state == State.OPEN) {
            return List.of(action);
        }

        if (state != State.REFUSED) {
            park(name, action, keepParked);
        }

        return List.of();
    }

    private void park(String name, Runnable action, boolean keepParked) {
        var sequence = keepParked
                       ? new ArrayList<>(parked.getOrDefault(name, List.of()))
                       : new ArrayList<Runnable>();

        sequence.add(action);
        parked.put(name, sequence);
    }

    /// Hands the drained work out, each action behind a refusal check (#3271): a refusal that lands while an earlier action of the same batch
    /// runs drops the rest.
    private synchronized List<Runnable> takeDrained() {
        var actions = drained;

        drained = List.of();

        return actions.stream()
                      .map(this::unlessRefused)
                      .toList();
    }

    private Runnable unlessRefused(Runnable action) {
        return () -> runUnlessRefused(action);
    }

    private void runUnlessRefused(Runnable action) {
        if (state() != State.REFUSED) {
            action.run();
        }
    }

    /// With `running` held: the actions that were parked when the guard opened run first, in the order they were first parked, so an action
    /// admitted after the opening can never overtake an older parked one.
    private void runDrained() {
        takeDrained().forEach(Runnable::run);
    }

    private Verdict verdict() {
        var marker = reader.marker();
        var incarnation = reader.incarnationPresent();

        return switch (configured) {
            case FSM2 -> fsm2Verdict(marker, incarnation);
            case LEGACY, UNKNOWN -> legacyVerdict(marker);
        };
    }

    private static Verdict fsm2Verdict(Option<DeploymentEngineValue> marker, boolean incarnationPresent) {
        return marker.fold(() -> absentMarker(incarnationPresent), EngineMarkerGuard::committedFsm2Verdict);
    }

    /// Both keys absent is genesis still running; an incarnation without a marker is a pre-phase-3 cluster.
    private static Verdict absentMarker(boolean incarnationPresent) {
        return incarnationPresent
               ? Verdict.MISMATCH
               : Verdict.WAIT;
    }

    private static Verdict committedFsm2Verdict(DeploymentEngineValue value) {
        return value.engine() == DeploymentEngine.FSM2
               ? Verdict.OPEN
               : Verdict.MISMATCH;
    }

    private static Verdict legacyVerdict(Option<DeploymentEngineValue> marker) {
        return marker.filter(value -> value.engine() == DeploymentEngine.FSM2)
                     .isPresent()
               ? Verdict.MISMATCH
               : Verdict.OPEN;
    }

    private void decide(Verdict verdict) {
        switch (verdict) {
            case OPEN -> open();
            case WAIT -> waiting();
            case MISMATCH -> mismatch();
        }
    }

    private void open() {
        closeWindow("the configured engine matches the cluster's committed state");
        if (state != State.OPEN) {
            state = State.OPEN;
            drain();
        }
    }

    private void waiting() {
        closeWindow("the cluster's committed engine is undecided again");
        state = State.WAITING;
    }

    private void mismatch() {
        if (state == State.MISMATCH_WINDOW) {
            refuseIfExpired();
        } else {
            state = State.MISMATCH_UNTIMED;
            reader.leader().onPresent(_ -> startWindow());
        }
    }

    private void startWindow() {
        state = State.MISMATCH_WINDOW;
        windowExpired = false;
        warn();
        var generation = ++windowGeneration;

        cancelWindow = clock.schedule(() -> windowEnded(generation), windowMs);
    }

    @Contract
    private void windowEnded(long generation) {
        markExpired(generation);
        evaluate();
    }

    private synchronized void markExpired(long generation) {
        windowExpired = windowExpired || generation == windowGeneration;
    }

    private void refuseIfExpired() {
        if (windowExpired) {
            refuse();
        }
    }

    private void refuse() {
        state = State.REFUSED;
        parked.clear();
        drained = List.of();
        var reason = describe();

        log.error("[{}] {} The node refuses itself and exits.",
                  OperatorWarningCode.DEPLOYMENT_ENGINE_MISMATCH.code(),
                  reason);
        matched(REFUSED_ITSELF);
        exit.accept(reason);
    }

    /// D.6 row 1: a reported mismatch window that ends without a refusal closes with `deployment-engine-matched`, same subject; a window
    /// never reported (no leader yet) closes silently.
    private void closeWindow(String why) {
        if (state == State.MISMATCH_WINDOW) {
            matched(why);
        }

        endWindow();
    }

    /// The recovery of the window's warning: the condition ended (`why`), or the node refused itself and exits (`node-refused-itself`), so
    /// the warning is closed either way. Raised before the exit: whether it reaches the cluster event log before the process ends is not
    /// guaranteed (the sink hands it off asynchronously).
    private Unit matched(String why) {
        return OperatorWarnings.raise(log,
                                      warnings,
                                      OperatorWarningCode.DEPLOYMENT_ENGINE_MATCHED,
                                      self.id(),
                                      "Deployment engine mismatch window ended: {}",
                                      why);
    }

    private void endWindow() {
        cancelWindow.run();
        cancelWindow = () -> {};
        windowExpired = false;
    }

    /// Appends the parked actions behind any drained work still pending (#3261): an opening whose evaluating thread has not yet got `running`
    /// leaves its drain in `drained`, and a close and reopen before it does must not discard that work.
    /// Invariant: an action parks only while `drained` is empty, and `drain()` clears `parked`, so `parked` is empty whenever `drained` is pending.
    /// The append order is therefore unobservable; the append itself is still required, because a second `drain()` while older work is pending
    /// must keep that work (#3261).
    private void drain() {
        drained = Stream.concat(drained.stream(),
                                parked.values().stream().flatMap(List::stream)).toList();
        parked.clear();
    }

    private Unit warn() {
        return OperatorWarnings.raise(log,
                                      warnings,
                                      OperatorWarningCode.DEPLOYMENT_ENGINE_MISMATCH,
                                      self.id(),
                                      "{} The node exits when the window ends ({}) unless the mismatch clears.",
                                      describe(),
                                      Instant.ofEpochMilli(wallClock.getAsLong() + windowMs));
    }

    /// The evidence, then the recovery (design 8.2, H-5): the node's configured engine; the committed marker's engine, writer and time, or the
    /// absence of a marker beside a present incarnation; and what an operator does about it.
    private String describe() {
        return "Deployment engine mismatch: this node is configured for " + configured
             + "; the cluster's committed state says " + committed()
             + ". Recovery: a node's engine is not an operator setting, so restarting it changes nothing. A cluster formed by an earlier build (marker absent,"
             + " incarnation present) cannot be upgraded in place: form a new cluster on this build and restore a backup into it. If the committed"
             + " marker is the wrong one for this cluster (a misconfigured genesis), the cluster needs re-genesis (wipe and re-form).";
    }

    private String committed() {
        return reader.marker()
                     .map(value -> "engine=" + value.engine()
                                  + ", minted by " + value.mintedBy()
                                                          .id()
                                  + " at " + Instant.ofEpochMilli(value.mintedAt()))
                     .or("marker absent, incarnation present = a pre-phase-3 LEGACY cluster");
    }
}
