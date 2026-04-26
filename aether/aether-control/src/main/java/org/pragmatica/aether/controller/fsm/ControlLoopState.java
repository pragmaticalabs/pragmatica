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
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

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
                case Activate _ -> tx.transitionTo(Warmup.warmup(ctx, ctx.nowMs()));
                case ClusterFsmEvent.QuorumEstablished _ -> tx.ignore();
                case ClusterFsmEvent.LeaderChange lc when lc.localIsLeader() ->
                        tx.transitionTo(Warmup.warmup(ctx, ctx.nowMs()));
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }

    /// Post-activation protection window. The warm-up timer is scheduled eagerly in the static
    /// factory — see [`#warmup`] — so the full warm-up period is honoured before the first
    /// evaluation tick fires. If the CAS that would make this record current loses to another
    /// thread, [`#onCasLost`] cancels the eagerly scheduled timer.
    record Warmup(ControlLoopContext ctx,
                  long activationTimeMs,
                  ScheduledFuture<?> warmupTimer) implements ControlLoopState {

        static Warmup warmup(ControlLoopContext ctx, long activationTimeMs) {
            var warmUpMs = ctx.config().warmUpPeriodMs();
            return new Warmup(ctx,
                              activationTimeMs,
                              SharedScheduler.schedule(() -> ctx.dispatch(new ActivationTimeReached()),
                                                       TimeSpan.timeSpan(warmUpMs).millis()));
        }

        @Override
        public void onEntry() {
            ctx.resetSliceProtectionState();
            ctx.restoreCooldownsFromKvStore();
            log.info("Control loop Warmup: activation={}, warmup-period={}ms",
                     activationTimeMs, ctx.config().warmUpPeriodMs());
        }

        @Override
        public void onExit() {
            warmupTimer.cancel(false);
        }

        @Override
        public void onCasLost() {
            warmupTimer.cancel(false);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            switch (event) {
                case ActivationTimeReached _ -> tx.transitionToOrDrop(Evaluating.evaluating(ctx));
                case Deactivate _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.LeaderChange lc when !lc.localIsLeader() -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case CooldownRequested _ -> tx.ignore();
                default -> tx.ignore();
            }
        }
    }

    /// Normal operation. Owns the periodic evaluation timer, scheduled eagerly in the static
    /// factory — see [`#evaluating`].
    record Evaluating(ControlLoopContext ctx,
                      ScheduledFuture<?> evaluationTimer) implements ControlLoopState {

        static Evaluating evaluating(ControlLoopContext ctx) {
            return new Evaluating(ctx,
                                  SharedScheduler.scheduleAtFixedRate(ctx::runEvaluationCycle, ctx.interval()));
        }

        @Override
        public void onEntry() {
            log.info("Control loop Evaluating (interval={}ms)", ctx.interval().millis());
        }

        @Override
        public void onExit() {
            evaluationTimer.cancel(false);
        }

        @Override
        public void onCasLost() {
            evaluationTimer.cancel(false);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            switch (event) {
                case CooldownRequested cr -> tx.transitionTo(Cooldown.cooldown(ctx, cr.cooldownStartMs()));
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
    ///
    /// Both timers (evaluation, cooldown ticker) are scheduled eagerly in the static factory —
    /// see [`#cooldown`]. The cooldown ticker is replaced in-flight on `CooldownRequested` /
    /// post-`CooldownExpired` re-arm via the [`AtomicReference`]; that holder is the canonical
    /// active future at any moment.
    record Cooldown(ControlLoopContext ctx,
                    long lastCooldownStartMs,
                    ScheduledFuture<?> evaluationTimer,
                    AtomicReference<ScheduledFuture<?>> cooldownTicker)
            implements ControlLoopState {

        static Cooldown cooldown(ControlLoopContext ctx, long cooldownStartMs) {
            var evaluationTimer = SharedScheduler.scheduleAtFixedRate(ctx::runEvaluationCycle, ctx.interval());
            var cooldownTicker = new AtomicReference<ScheduledFuture<?>>(
                    scheduleExpiryTick(ctx, () -> ctx.dispatch(new CooldownExpired())));
            return new Cooldown(ctx, cooldownStartMs, evaluationTimer, cooldownTicker);
        }

        @Override
        public void onEntry() {
            log.info("Control loop Cooldown started (lastCooldownStart={})", lastCooldownStartMs);
        }

        @Override
        public void onExit() {
            evaluationTimer.cancel(false);
            cooldownTicker.get().cancel(false);
        }

        @Override
        public void onCasLost() {
            evaluationTimer.cancel(false);
            cooldownTicker.get().cancel(false);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            switch (event) {
                case CooldownExpired _ -> handleCooldownExpired(tx);
                case CooldownRequested _ -> tx.handle(this::rearmExpiryTick);
                case Deactivate _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.LeaderChange lc when !lc.localIsLeader() -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }

        private void handleCooldownExpired(TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            var now = ctx.nowMs();
            if (ctx.allCooldownsExpired(now)) {
                // Cleanup runs INSIDE the transition action so it only fires on CAS success,
                // not on every dispatch attempt.
                tx.transitionToOrDrop(Evaluating.evaluating(ctx), () -> ctx.cleanupExpiredCooldowns(now));
                return;
            }
            tx.handle(() -> cleanupAndRearm(now));
        }

        private void cleanupAndRearm(long now) {
            ctx.cleanupExpiredCooldowns(now);
            rearmExpiryTick();
        }

        private void rearmExpiryTick() {
            var previous = cooldownTicker.getAndSet(scheduleExpiryTick(ctx, () -> ctx.dispatch(new CooldownExpired())));
            previous.cancel(false);
        }

        private static ScheduledFuture<?> scheduleExpiryTick(ControlLoopContext ctx, Runnable task) {
            var pollIntervalMs = Math.max(ctx.config().sliceCooldownMs() / 4L, 100L);
            return SharedScheduler.schedule(task, TimeSpan.timeSpan(pollIntervalMs).millis());
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
}
