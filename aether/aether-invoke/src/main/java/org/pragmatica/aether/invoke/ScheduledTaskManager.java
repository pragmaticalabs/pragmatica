// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Contract
public interface ScheduledTaskManager {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onQuorumStateChange(ClusterStateNotification notification);

    int activeTimerCount();
    void stop();

    static ScheduledTaskManager scheduledTaskManager(ScheduledTaskRegistry registry,
                                                     SliceInvoker invoker,
                                                     NodeId self,
                                                     Consumer<KVCommand<AetherKey>> stateWriter,
                                                     LeaderManager leaderManager) {
        var ctxHolder = new AtomicReference<Context>();
        Function<Fsm<SchedulerState, ClusterFsmEvent>, SchedulerState> initialStateFactory = f -> buildContextAndInitialState(ctxHolder,
                                                                                                                              f,
                                                                                                                              registry,
                                                                                                                              invoker,
                                                                                                                              self,
                                                                                                                              stateWriter,
                                                                                                                              leaderManager);
        var fsm = Fsm.fsm("scheduled-task", self.id(), initialStateFactory);
        var ctx = ctxHolder.get();

        registry.setChangeListener(new RegistryListener(ctx, fsm)::onChange);

        return new ScheduledTaskManagerAdapter(ctx, fsm);
    }

    private static SchedulerState buildContextAndInitialState(AtomicReference<Context> ctxHolder,
                                                              Fsm<SchedulerState, ClusterFsmEvent> fsm,
                                                              ScheduledTaskRegistry registry,
                                                              SliceInvoker invoker,
                                                              NodeId self,
                                                              Consumer<KVCommand<AetherKey>> stateWriter,
                                                              LeaderManager leaderManager) {
        var ctx = new Context(fsm, registry, invoker, self, stateWriter, leaderManager);

        ctxHolder.set(ctx);

        return ctx.dormant;
    }

    final class Context {
        final Fsm<SchedulerState, ClusterFsmEvent> fsm;
        final ScheduledTaskRegistry registry;
        final SliceInvoker invoker;
        final NodeId self;
        final Consumer<KVCommand<AetherKey>> stateWriter;
        final LeaderManager leaderManager;
        final Map<ScheduledTaskKey, ScheduledFuture<?>> activeTimers = new ConcurrentHashMap<>();
        final AtomicLong quorumSequence = new AtomicLong(0);
        final Dormant dormant;
        final Following following;
        final Leading leading;
        final Stopped stopped;

        Context(Fsm<SchedulerState, ClusterFsmEvent> fsm,
                ScheduledTaskRegistry registry,
                SliceInvoker invoker,
                NodeId self,
                Consumer<KVCommand<AetherKey>> stateWriter,
                LeaderManager leaderManager) {
            this.fsm = fsm;
            this.registry = registry;
            this.invoker = invoker;
            this.self = self;
            this.stateWriter = stateWriter;
            this.leaderManager = leaderManager;
            this.dormant = new Dormant(this);
            this.following = new Following(this);
            this.leading = new Leading(this);
            this.stopped = new Stopped(this);
        }
    }

    sealed interface SchedulerState extends FsmState<SchedulerState, ClusterFsmEvent> permits Dormant, Following, Leading, Stopped {}

    record Dormant(Context ctx) implements SchedulerState {
        private static final Logger log = LoggerFactory.getLogger(Dormant.class);

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<SchedulerState, ClusterFsmEvent> tx) {
            switch (event) {
                case ClusterFsmEvent.QuorumEstablished _ -> tx.transitionTo(ctx.leaderManager.isLeader()
                                                                            ? ctx.leading
                                                                            : ctx.following);
                case ClusterFsmEvent.LeaderChange _ -> tx.ignore();
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped);
                default -> tx.ignore();
            }
        }
    }

    record Following(Context ctx) implements SchedulerState {
        private static final Logger log = LoggerFactory.getLogger(Following.class);

        @Override
        public void onEntry() {
            log.info("Node {} entering Following — running ALL-mode scheduled tasks", ctx.self);
            TaskOps.startEligibleTasks(ctx, false);
        }

        @Override
        public void onExit() {
            TaskOps.cancelAllTimers(ctx);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<SchedulerState, ClusterFsmEvent> tx) {
            switch (event) {
                case ClusterFsmEvent.LeaderChange lc -> {
                    if (lc.localIsLeader()) {
                        tx.transitionTo(ctx.leading);
                    }
                }
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant);
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped);
                default -> tx.ignore();
            }
        }
    }

    record Leading(Context ctx) implements SchedulerState {
        private static final Logger log = LoggerFactory.getLogger(Leading.class);

        @Override
        public void onEntry() {
            log.info("Node {} entering Leading — running ALL + SINGLE mode scheduled tasks", ctx.self);
            TaskOps.startEligibleTasks(ctx, true);
        }

        @Override
        public void onExit() {
            TaskOps.cancelAllTimers(ctx);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<SchedulerState, ClusterFsmEvent> tx) {
            switch (event) {
                case ClusterFsmEvent.LeaderChange lc -> {
                    if (!lc.localIsLeader()) {
                        tx.transitionTo(ctx.following);
                    }
                }
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant);
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped);
                default -> tx.ignore();
            }
        }
    }

    record Stopped(Context ctx) implements SchedulerState {
        private static final Logger log = LoggerFactory.getLogger(Stopped.class);

        @Override
        public void onEntry() {
            log.info("Node {} entering Stopped — all scheduled tasks cancelled", ctx.self);
            TaskOps.cancelAllTimers(ctx);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<SchedulerState, ClusterFsmEvent> tx) {
            tx.ignore();
        }
    }

    final class RegistryListener {
        private final Context ctx;
        private final Fsm<SchedulerState, ClusterFsmEvent> fsm;

        RegistryListener(Context ctx, Fsm<SchedulerState, ClusterFsmEvent> fsm) {
            this.ctx = ctx;
            this.fsm = fsm;
        }

        void onChange(ScheduledTaskKey key, Option<ScheduledTask> taskOption) {
            taskOption.onPresent(task -> handleTaskAdded(key, task)).onEmpty(() -> handleTaskRemoved(key));
        }

        private void handleTaskAdded(ScheduledTaskKey key, ScheduledTask task) {
            TaskOps.cancelTimer(ctx, key);
            if (shouldRunInCurrentState(task)) {
                TaskOps.startTimer(ctx, key, task);
            }
        }

        private void handleTaskRemoved(ScheduledTaskKey key) {
            TaskOps.cancelTimer(ctx, key);
        }

        private boolean shouldRunInCurrentState(ScheduledTask task) {
            if (task.paused()) {
                return false;
            }

            var state = fsm.current();

            return switch (task.executionMode()) {
                case ALL -> state instanceof Following || state instanceof Leading;
                case SINGLE -> state instanceof Leading;
            };
        }
    }

    final class TaskOps {
        private static final Logger log = LoggerFactory.getLogger(TaskOps.class);

        private TaskOps() {}

        static void startEligibleTasks(Context ctx, boolean leader) {
            ctx.registry.allTasks().stream().filter(task -> !task.paused()).filter(task -> task.executionMode() == ExecutionMode.ALL || (leader && task.executionMode() == ExecutionMode.SINGLE)).forEach(task -> {
                var key = ScheduledTaskKey.scheduledTaskKey(task.configSection(), task.artifact(), task.methodName());

                if (!ctx.activeTimers.containsKey(key)) {
                    startTimer(ctx, key, task);
                }
            });
        }

        static void startTimer(Context ctx, ScheduledTaskKey key, ScheduledTask task) {
            if (task.isInterval()) {
                startIntervalTimer(ctx, key, task);
            } else if (task.isCron()) {
                startCronTimer(ctx, key, task);
            }
        }

        private static void startIntervalTimer(Context ctx, ScheduledTaskKey key, ScheduledTask task) {
            IntervalParser.parse(task.interval()).onSuccess(interval -> scheduleAtFixedRate(ctx, key, task, interval)).onFailure(cause -> log.warn("Failed to parse interval '{}' for task {}: {}",
                                                                                                                                                   task.interval(),
                                                                                                                                                   key,
                                                                                                                                                   cause.message()));
        }

        private static void scheduleAtFixedRate(Context ctx,
                                                ScheduledTaskKey key,
                                                ScheduledTask task,
                                                TimeSpan interval) {
            var future = SharedScheduler.scheduleAtFixedRate(() -> executeTask(ctx, task), interval);

            replaceTimer(ctx, key, future);
            log.info("Started scheduled task {} with interval {}", key, task.interval());
        }

        private static void startCronTimer(Context ctx, ScheduledTaskKey key, ScheduledTask task) {
            CronExpression.parse(task.cron()).onSuccess(cron -> scheduleNextCronFire(ctx, key, task, cron)).onFailure(cause -> log.warn("Failed to parse cron '{}' for task {}: {}",
                                                                                                                                        task.cron(),
                                                                                                                                        key,
                                                                                                                                        cause.message()));
        }

        private static void scheduleNextCronFire(Context ctx,
                                                 ScheduledTaskKey key,
                                                 ScheduledTask task,
                                                 CronExpression cron) {
            cron.delayUntilNext(Instant.now()).onSuccess(delay -> registerCronFire(ctx, key, task, cron, delay)).onFailure(cause -> log.warn("Failed to compute next cron fire for task {}: {}",
                                                                                                                                             key,
                                                                                                                                             cause.message()));
        }

        private static void registerCronFire(Context ctx,
                                             ScheduledTaskKey key,
                                             ScheduledTask task,
                                             CronExpression cron,
                                             TimeSpan delay) {
            var future = SharedScheduler.schedule(() -> executeCronTask(ctx, key, task, cron), delay);

            replaceTimer(ctx, key, future);
            log.info("Scheduled cron task {} next fire in {}ms", key, delay.millis());
        }

        private static void executeCronTask(Context ctx,
                                            ScheduledTaskKey key,
                                            ScheduledTask task,
                                            CronExpression cron) {
            executeTask(ctx, task);
            if (ctx.activeTimers.containsKey(key)) {
                scheduleNextCronFire(ctx, key, task, cron);
            }
        }

        private static void executeTask(Context ctx, ScheduledTask task) {
            ctx.invoker.invoke(task.artifact(), task.methodName(), Unit.unit()).onSuccess(_ -> writeSuccessState(ctx,
                                                                                                                 task)).onFailure(cause -> handleTaskFailure(ctx,
                                                                                                                                                             task,
                                                                                                                                                             cause.message()));
        }

        private static void handleTaskFailure(Context ctx, ScheduledTask task, String message) {
            log.warn("Scheduled task {}.{} failed: {}",
                     task.configSection(),
                     task.methodName().name(),
                     message);
            writeFailureState(ctx, task, message);
        }

        private static void writeSuccessState(Context ctx, ScheduledTask task) {
            var key = ScheduledTaskStateKey.scheduledTaskStateKey(task.configSection(),
                                                                  task.artifact(),
                                                                  task.methodName());
            var value = ScheduledTaskStateValue.successState(0, 0);

            ctx.stateWriter.accept(new KVCommand.Put<>(key, value));
        }

        private static void writeFailureState(Context ctx, ScheduledTask task, String message) {
            var key = ScheduledTaskStateKey.scheduledTaskStateKey(task.configSection(),
                                                                  task.artifact(),
                                                                  task.methodName());
            var value = ScheduledTaskStateValue.failureState(0, 1, 0, message);

            ctx.stateWriter.accept(new KVCommand.Put<>(key, value));
        }

        static void cancelTimer(Context ctx, ScheduledTaskKey key) {
            Option.option(ctx.activeTimers.remove(key)).onPresent(future -> {
                future.cancel(false);
                log.debug("Cancelled scheduled task timer: {}", key);
            });
        }

        static void replaceTimer(Context ctx, ScheduledTaskKey key, ScheduledFuture<?> future) {
            var prior = ctx.activeTimers.put(key, future);

            if (prior != null) {
                prior.cancel(false);
                log.debug("Replaced scheduled task timer for key {} (prior future cancelled)", key);
            }
        }

        static void cancelAllTimers(Context ctx) {
            ctx.activeTimers.forEach((_, future) -> future.cancel(false));
            var count = ctx.activeTimers.size();

            ctx.activeTimers.clear();
            if (count > 0) {
                log.info("Cancelled {} scheduled task timers", count);
            }
        }
    }

    record ScheduledTaskManagerAdapter(Context ctx, Fsm<SchedulerState, ClusterFsmEvent> fsm) implements ScheduledTaskManager {
        @Override
        public void onLeaderChange(LeaderChange leaderChange) {
            fsm.dispatch(new ClusterFsmEvent.LeaderChange(leaderChange.leaderId(), leaderChange.localNodeIsLeader()));
        }

        @Override
        public void onQuorumStateChange(ClusterStateNotification notification) {
            if (!notification.advanceSequence(ctx.quorumSequence)) {
                return;
            }

            if (notification.state() == ClusterStateNotification.State.ACTIVE) {
                fsm.dispatch(new ClusterFsmEvent.QuorumEstablished());
            } else {
                fsm.dispatch(new ClusterFsmEvent.QuorumDisappeared());
            }
        }

        @Override
        public int activeTimerCount() {
            return ctx.activeTimers.size();
        }

        @Override
        public void stop() {
            fsm.dispatch(new ClusterFsmEvent.Shutdown());
        }
    }

    interface IntervalParser {
        Cause EMPTY_INTERVAL = () -> "Interval string is empty";

        Functions.Fn1<Cause, String> INVALID_INTERVAL = Causes.forOneValue("Invalid interval format: %s (expected e.g. '30s', '5m', '1h', '2d', '2w')");

        static Result<TimeSpan> parse(String interval) {
            return Verify.ensure(interval, Verify.Is::present, EMPTY_INTERVAL).flatMap(IntervalParser::parseInterval);
        }

        private static Result<TimeSpan> parseInterval(String interval) {
            var trimmed = interval.trim();

            if (trimmed.length() < 2) {
                return INVALID_INTERVAL.apply(interval).result();
            }

            var suffix = trimmed.charAt(trimmed.length() - 1);
            var numberPart = trimmed.substring(0, trimmed.length() - 1);

            return parseNumber(numberPart, interval).flatMap(value -> applyUnit(value, suffix, interval));
        }

        private static Result<Long> parseNumber(String numberPart, String original) {
            return Result.lift(() -> Long.parseLong(numberPart)).mapError(_ -> INVALID_INTERVAL.apply(original));
        }

        private static Result<TimeSpan> applyUnit(long value, char suffix, String original) {
            return switch (suffix) {
                case 's' -> Result.success(TimeSpan.timeSpan(value).seconds());
                case 'm' -> Result.success(TimeSpan.timeSpan(value).minutes());
                case 'h' -> Result.success(TimeSpan.timeSpan(value).hours());
                case 'd' -> Result.success(TimeSpan.timeSpan(value).days());
                case 'w' -> Result.success(TimeSpan.timeSpan(value * 7).days());
                default -> INVALID_INTERVAL.apply(original).result();
            };
        }
    }
}
