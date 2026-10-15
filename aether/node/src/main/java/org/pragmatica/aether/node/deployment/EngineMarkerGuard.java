// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

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
/// **Not carried.** The restore half (#1533: a pre-phase-3 backup removes the locally minted marker) and the worker arms are deferred (report 1.2).
public final class EngineMarkerGuard {
    private static final Logger log = LoggerFactory.getLogger(EngineMarkerGuard.class);

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
    private final Map<String, Runnable> parked = new LinkedHashMap<>();
    private State state = State.WAITING;
    private boolean windowExpired;

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

    /// Runs `action` now if the guard is open; otherwise parks it under `name`, replacing an earlier parked action of that name (an activation
    /// is level-triggered: a later `Deactivate` supersedes a parked `Activate`). Parked actions run, in the order they were first parked, when
    /// the guard opens; a refused node drops them.
    @Contract
    public synchronized void whenOpen(String name, Runnable action) {
        if (state == State.OPEN) {
            action.run();
        } else if (state != State.REFUSED) {
            parked.put(name, action);
        }
    }

    /// Reads the committed mirror and moves to the state it justifies. Idempotent; called on every notification of the three keys it depends on,
    /// on a leader change, and when the window ends.
    @Contract
    public synchronized void evaluate() {
        if (state != State.REFUSED) {
            decide(verdict());
        }
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
        return marker.fold(() -> incarnationPresent
                                 ? Verdict.MISMATCH
                                 : Verdict.WAIT,
                           value -> value.engine() == DeploymentEngine.FSM2
                                    ? Verdict.OPEN
                                    : Verdict.MISMATCH);
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
        endWindow();
        if (state != State.OPEN) {
            state = State.OPEN;
            runParked();
        }
    }

    private void waiting() {
        endWindow();
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
        cancelWindow = clock.schedule(this::windowEnded, windowMs);
    }

    @Contract
    private synchronized void windowEnded() {
        windowExpired = true;
        evaluate();
    }

    private void refuseIfExpired() {
        if (windowExpired) {
            refuse();
        }
    }

    private void refuse() {
        state = State.REFUSED;
        parked.clear();
        var reason = describe();

        log.error("[{}] {} The node refuses itself and exits.",
                  OperatorWarningCode.DEPLOYMENT_ENGINE_MISMATCH.code(),
                  reason);
        exit.accept(reason);
    }

    private void endWindow() {
        cancelWindow.run();
        cancelWindow = () -> {};
        windowExpired = false;
    }

    private void runParked() {
        var actions = new ArrayList<>(parked.values());

        parked.clear();
        actions.forEach(Runnable::run);
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
             + ". Recovery: fix this node's engine and restart it under a fresh NodeId; if the committed marker is the wrong one for this cluster"
             + " (a misconfigured genesis), the cluster needs re-genesis (wipe and re-form); fixing configuration does not clear a committed marker.";
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
