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
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public sealed interface ControlLoopState extends FsmState<ControlLoopState, ClusterFsmEvent> permits ControlLoopState.Dormant, ControlLoopState.Warmup, ControlLoopState.Evaluating, ControlLoopState.Cooldown, ControlLoopState.Stopped {
    Logger log = LoggerFactory.getLogger(ControlLoopState.class);

    ControlLoopContext ctx();

    record Dormant(ControlLoopContext ctx) implements ControlLoopState {
        @Override@Contract public void handle(ClusterFsmEvent event,
                                              TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            switch (event){
                case Activate _ -> tx.transitionTo(Warmup.warmup(ctx, ctx.nowMs()));
                case ClusterFsmEvent.QuorumEstablished _ -> tx.ignore();
                case ClusterFsmEvent.LeaderChange lc when lc.localIsLeader() -> tx.transitionTo(Warmup.warmup(ctx,
                                                                                                              ctx.nowMs()));
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }
    }

    record Warmup(ControlLoopContext ctx, long activationTimeMs, ScheduledFuture<?> warmupTimer) implements ControlLoopState {
        static Warmup warmup(ControlLoopContext ctx, long activationTimeMs) {
            var warmUpMs = ctx.config().warmUpPeriodMs();
            return new Warmup(ctx,
                              activationTimeMs,
                              SharedScheduler.schedule(() -> ctx.dispatch(new ActivationTimeReached()),
                                                       TimeSpan.timeSpan(warmUpMs).millis()));
        }

        @Override@Contract public void onEntry() {
            ctx.resetSliceProtectionState();
            ctx.restoreCooldownsFromKvStore();
            log.info("Control loop Warmup: activation={}, warmup-period={}ms",
                     activationTimeMs,
                     ctx.config().warmUpPeriodMs());
        }

        @Override@Contract public void onExit() {
            warmupTimer.cancel(false);
        }

        @Override@Contract public void onCasLost() {
            warmupTimer.cancel(false);
        }

        @Override@Contract public void handle(ClusterFsmEvent event,
                                              TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            switch (event){
                case ActivationTimeReached _ -> tx.transitionToOrDrop(Evaluating.evaluating(ctx));
                case Deactivate _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.LeaderChange lc when!lc.localIsLeader() -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case CooldownRequested _ -> tx.ignore();
                default -> tx.ignore();
            }
        }
    }

    record Evaluating(ControlLoopContext ctx, ScheduledFuture<?> evaluationTimer) implements ControlLoopState {
        static Evaluating evaluating(ControlLoopContext ctx) {
            return new Evaluating(ctx,
                                  SharedScheduler.scheduleAtFixedRate(ctx::runEvaluationCycle, ctx.interval()));
        }

        @Override@Contract public void onEntry() {
            log.info("Control loop Evaluating (interval={}ms)",
                     ctx.interval().millis());
        }

        @Override@Contract public void onExit() {
            evaluationTimer.cancel(false);
        }

        @Override@Contract public void onCasLost() {
            evaluationTimer.cancel(false);
        }

        @Override@Contract public void handle(ClusterFsmEvent event,
                                              TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            switch (event){
                case CooldownRequested cr -> tx.transitionTo(Cooldown.cooldown(ctx, cr.cooldownStartMs()));
                case Deactivate _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.LeaderChange lc when!lc.localIsLeader() -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case CooldownExpired _ -> tx.ignore();
                default -> tx.ignore();
            }
        }
    }

    record Cooldown(ControlLoopContext ctx,
                    long lastCooldownStartMs,
                    ScheduledFuture<?> evaluationTimer,
                    AtomicReference<ScheduledFuture<?>> cooldownTicker) implements ControlLoopState {
        static Cooldown cooldown(ControlLoopContext ctx, long cooldownStartMs) {
            var evaluationTimer = SharedScheduler.scheduleAtFixedRate(ctx::runEvaluationCycle, ctx.interval());
            var cooldownTicker = new AtomicReference<ScheduledFuture<?>>(scheduleExpiryTick(ctx,
                                                                                            () -> ctx.dispatch(new CooldownExpired())));
            return new Cooldown(ctx, cooldownStartMs, evaluationTimer, cooldownTicker);
        }

        @Override@Contract public void onEntry() {
            log.info("Control loop Cooldown started (lastCooldownStart={})", lastCooldownStartMs);
        }

        @Override@Contract public void onExit() {
            evaluationTimer.cancel(false);
            cooldownTicker.get().cancel(false);
        }

        @Override@Contract public void onCasLost() {
            evaluationTimer.cancel(false);
            cooldownTicker.get().cancel(false);
        }

        @Override@Contract public void handle(ClusterFsmEvent event,
                                              TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            switch (event){
                case CooldownExpired _ -> handleCooldownExpired(tx);
                case CooldownRequested _ -> tx.handle(this::rearmExpiryTick);
                case Deactivate _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.LeaderChange lc when!lc.localIsLeader() -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                default -> tx.ignore();
            }
        }

        private void handleCooldownExpired(TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            var now = ctx.nowMs();
            if (ctx.allCooldownsExpired(now)) {
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
            var pollIntervalMs = Math.max(ctx.config().sliceCooldownMs() / 4L,
                                          100L);
            return SharedScheduler.schedule(task,
                                            TimeSpan.timeSpan(pollIntervalMs).millis());
        }
    }

    record Stopped(ControlLoopContext ctx) implements ControlLoopState {
        @Override@Contract public void onEntry() {
            log.info("Control loop Stopped");
        }

        @Override@Contract public void handle(ClusterFsmEvent event,
                                              TransitionRequest<ControlLoopState, ClusterFsmEvent> tx) {
            tx.ignore();
        }
    }
}
