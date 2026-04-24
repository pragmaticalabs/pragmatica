// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import org.pragmatica.aether.controller.fsm.ControlLoopEvents.ActivationTimeReached;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.Activate;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.CooldownExpired;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.CooldownRequested;
import org.pragmatica.aether.controller.fsm.ControlLoopEvents.Deactivate;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Sealed state hierarchy for the [`ControlLoop`] FSM.
///
/// ```text
/// Dormant  ──Activate | QuorumEstablished(localIsLeader)──►  Warmup
/// Warmup   ──ActivationTimeReached──►  Evaluating
/// Evaluating ──CooldownRequested──►  Cooldown
/// Cooldown ──CooldownExpired (all expired)──►  Evaluating
/// Any (except Stopped) ──Deactivate | QuorumDisappeared | LeaderChange(!localIsLeader)──►  Dormant
/// Any ──Shutdown──►  Stopped
/// ```
///
/// `Dormant` and `Stopped` are per-context singletons (data-free); `Warmup`, `Evaluating`, and
/// `Cooldown` are data-carrying fresh records per entry. Each data-carrying state owns its own
/// timers / entry-time state; configuration / long-lived collaborators live on [`ControlLoopContext`].
public sealed interface ControlLoopState extends FsmState<ControlLoopState, ClusterFsmEvent>
        permits ControlLoopState.Dormant,
                ControlLoopState.Warmup,
                ControlLoopState.Evaluating,
                ControlLoopState.Cooldown,
                ControlLoopState.Stopped {

    Logger log = LoggerFactory.getLogger(ControlLoopState.class);

    ControlLoopContext ctx();

    // --- State records ---

    record Dormant(ControlLoopContext ctx) implements ControlLoopState {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            switch (event) {
                case Activate _ -> tx.transitionTo(newWarmup(ctx));
                case ClusterFsmEvent.QuorumEstablished _ -> tx.ignore();
                case ClusterFsmEvent.LeaderChange lc when lc.localIsLeader() -> tx.transitionTo(newWarmup(ctx));
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }

    /// Post-activation protection window. Scheduling happens inside Warmup so the full warm-up
    /// period is honoured before the first evaluation tick fires.
    record Warmup(ControlLoopContext ctx,
                  long activationTimeMs,
                  CancellableTask warmupTimer) implements ControlLoopState {

        @Override
        public void onEntry() {
            ctx.resetSliceProtectionState();
            ctx.restoreCooldownsFromKvStore();
            var warmUpMs = ctx.config().warmUpPeriodMs();
            log.info("Control loop Warmup: activation={}, warmup-period={}ms", activationTimeMs, warmUpMs);
            warmupTimer.set(SharedScheduler.schedule(this::fireActivationReached,
                                                      TimeSpan.timeSpan(warmUpMs).millis()));
        }

        @Override
        public void onExit() {
            warmupTimer.cancel();
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            switch (event) {
                case ActivationTimeReached _ -> tx.transitionToOrDrop(newEvaluating(ctx));
                case Deactivate _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.LeaderChange lc when !lc.localIsLeader() -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case CooldownRequested _ -> tx.ignore();
                default -> tx.ignore();
            }
        }

        @Contract
        private void fireActivationReached() {
            ctx.dispatch(new ActivationTimeReached());
        }
    }

    /// Normal operation. Owns the periodic evaluation timer.
    record Evaluating(ControlLoopContext ctx,
                      CancellableTask evaluationTimer) implements ControlLoopState {

        @Override
        public void onEntry() {
            log.info("Control loop Evaluating (interval={}ms)", ctx.interval().millis());
            evaluationTimer.set(SharedScheduler.scheduleAtFixedRate(ctx::runEvaluationCycle, ctx.interval()));
        }

        @Override
        public void onExit() {
            evaluationTimer.cancel();
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            switch (event) {
                case CooldownRequested cr -> tx.transitionTo(newCooldown(ctx, cr.cooldownStartMs()));
                case Deactivate _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.LeaderChange lc when !lc.localIsLeader() -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case CooldownExpired _ -> tx.ignore();
                default -> tx.ignore();
            }
        }
    }

    /// Active cooldown phase — evaluation continues but the guard rails block scaling while any
    /// slice cooldown is in flight. Entry timestamp identifies the most recent cooldown trigger;
    /// the expiry tick re-reads [`ControlLoopContext`] cooldowns (other slices may have started or
    /// finished since this entry).
    record Cooldown(ControlLoopContext ctx,
                    long lastCooldownStartMs,
                    CancellableTask evaluationTimer,
                    CancellableTask cooldownTicker) implements ControlLoopState {

        @Override
        public void onEntry() {
            log.info("Control loop Cooldown started (lastCooldownStart={})", lastCooldownStartMs);
            evaluationTimer.set(SharedScheduler.scheduleAtFixedRate(ctx::runEvaluationCycle, ctx.interval()));
            scheduleNextExpiryTick();
        }

        @Override
        public void onExit() {
            evaluationTimer.cancel();
            cooldownTicker.cancel();
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            switch (event) {
                case CooldownExpired _ -> handleCooldownExpired(tx);
                case CooldownRequested _ -> scheduleNextExpiryTick();
                case Deactivate _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.LeaderChange lc when !lc.localIsLeader() -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }

        private void handleCooldownExpired(TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            var now = System.currentTimeMillis();
            ctx.cleanupExpiredCooldowns(now);
            if (ctx.allCooldownsExpired(now)) {
                tx.transitionToOrDrop(newEvaluating(ctx));
                return;
            }
            scheduleNextExpiryTick();
        }

        private void scheduleNextExpiryTick() {
            var pollIntervalMs = Math.max(ctx.config().sliceCooldownMs() / 4L, 100L);
            cooldownTicker.set(SharedScheduler.schedule(this::fireCooldownExpired,
                                                         TimeSpan.timeSpan(pollIntervalMs).millis()));
        }

        @Contract
        private void fireCooldownExpired() {
            ctx.dispatch(new CooldownExpired());
        }
    }

    record Stopped(ControlLoopContext ctx) implements ControlLoopState {
        @Override
        public void onEntry() {
            log.info("Control loop Stopped");
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            tx.ignore();
        }
    }

    // --- Factory helpers for data-carrying states ---

    private static Warmup newWarmup(ControlLoopContext ctx) {
        return new Warmup(ctx, System.currentTimeMillis(), CancellableTask.cancellableTask());
    }

    private static Evaluating newEvaluating(ControlLoopContext ctx) {
        return new Evaluating(ctx, CancellableTask.cancellableTask());
    }

    private static Cooldown newCooldown(ControlLoopContext ctx, long cooldownStartMs) {
        return new Cooldown(ctx,
                            cooldownStartMs,
                            CancellableTask.cancellableTask(),
                            CancellableTask.cancellableTask());
    }
}
