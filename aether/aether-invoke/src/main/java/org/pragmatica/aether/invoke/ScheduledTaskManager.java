// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.consensus.topology.MembershipDecision;
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
import org.pragmatica.lang.Promise;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Contract
public interface ScheduledTaskManager {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onQuorumStateChange(ClusterStateNotification notification);

    /// #273 item 1: ALL-mode eligibility is hosting AND not draining. This node's own drain cancels its
    /// ALL-mode timers — a draining node must not keep firing — and nothing re-registers them, because
    /// `DrainProcedure` is single-shot and irreversible (`INACTIVE -> DRAINING -> EXITED`): the node
    /// halts, it never returns to service. SINGLE-mode is leader-owned and untouched.
    ///
    /// Not a `@MessageReceiver`: the producer is `DrainProcedure.initiate`, which invokes its
    /// `drainInitiatedEmitter` once inside the CAS to DRAINING. `AetherNode` composes this call into
    /// that emitter, so every drain trigger (QUORUM_LOSS, CORE_ABSENCE, COMMANDED) funnels through it.
    /// An earlier revision keyed this on `MembershipDecision.NodeDraining`, which no producer emits —
    /// the per-node lifecycle-projection layer was removed in the membership-v2 finale. Default no-op
    /// so the route-test stubs that implement this interface stay compilable.
    default void onDrainInitiated() {}

    /// A node left the cluster for good (the committed membership decision, never a transient suspicion): the LEADER closes
    /// the open UNKNOWN of every per-node (ALL-mode) row that node was firing, because nothing will ever write those rows
    /// again (#1723). Default no-op so the route-test stubs that implement this interface stay compilable.
    default void onNodeRemoved(MembershipDecision.NodeRemoved event) {}

    default void onNodeDecommissioned(MembershipDecision.NodeDecommissioned event) {}

    int activeTimerCount();
    void stop();
    /// Atomic try-claim against the SAME in-flight guard `TaskOps` uses for automatic fires
    /// (fixed-rate ticks and cron), so a manual trigger and an automatic fire can never run
    /// concurrently for the same task (#273 review, item 1). Returns `false` when the key is
    /// already claimed — by an automatic fire in progress, or by a manual trigger that claimed
    /// first. A caller that claims MUST call [#release] exactly once, after its invocation
    /// settles, regardless of outcome.
    boolean tryClaim(ScheduledTaskKey key);
    /// Releases a claim taken via [#tryClaim].
    void release(ScheduledTaskKey key);

    static ScheduledTaskManager scheduledTaskManager(ScheduledTaskRegistry registry,
                                                     SliceInvoker invoker,
                                                     NodeId self,
                                                     Consumer<KVCommand<AetherKey>> stateWriter,
                                                     Function<ScheduledTaskStateKey, Option<ScheduledTaskStateValue>> stateReader,
                                                     LeaderManager leaderManager) {
        var ctxHolder = new AtomicReference<Context>();
        Function<Fsm<SchedulerState, ClusterFsmEvent>, SchedulerState> initialStateFactory = f -> buildContextAndInitialState(ctxHolder,
                                                                                                                              f,
                                                                                                                              registry,
                                                                                                                              invoker,
                                                                                                                              self,
                                                                                                                              stateWriter,
                                                                                                                              stateReader,
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
                                                              Function<ScheduledTaskStateKey, Option<ScheduledTaskStateValue>> stateReader,
                                                              LeaderManager leaderManager) {
        var ctx = new Context(fsm, registry, invoker, self, stateWriter, stateReader, leaderManager);

        ctxHolder.set(ctx);

        return ctx.dormant;
    }

    final class Context {
        final Fsm<SchedulerState, ClusterFsmEvent> fsm;
        final ScheduledTaskRegistry registry;
        final SliceInvoker invoker;
        final NodeId self;
        final Consumer<KVCommand<AetherKey>> stateWriter;
        final Function<ScheduledTaskStateKey, Option<ScheduledTaskStateValue>> stateReader;
        final LeaderManager leaderManager;
        /// The last row THIS manager submitted per state key. The writer is asynchronous consensus and the reader the
        /// COMMITTED registry, so for a few milliseconds after a write the committed row is older than what this manager
        /// already decided. Every decision that builds on "the row" (the next fire's prior, a late resolution) builds on
        /// the newer of the two by `fireSeq`, so a row in flight is never overwritten with an older fire's.
        final Map<ScheduledTaskStateKey, ScheduledTaskStateValue> submitted = new ConcurrentHashMap<>();
        final Map<ScheduledTaskKey, ScheduledFuture<?>> activeTimers = new ConcurrentHashMap<>();
        final Set<ScheduledTaskKey> inFlight = ConcurrentHashMap.newKeySet();
        final AtomicLong quorumSequence = new AtomicLong(0);
        /// #273: set once by [ScheduledTaskManager#onDrainInitiated], never cleared — the drain is
        /// irreversible. Gates ALL-mode only.
        final AtomicBoolean draining = new AtomicBoolean(false);
        final Dormant dormant;
        final Following following;
        final Leading leading;
        final Stopped stopped;

        Context(Fsm<SchedulerState, ClusterFsmEvent> fsm,
                ScheduledTaskRegistry registry,
                SliceInvoker invoker,
                NodeId self,
                Consumer<KVCommand<AetherKey>> stateWriter,
                Function<ScheduledTaskStateKey, Option<ScheduledTaskStateValue>> stateReader,
                LeaderManager leaderManager) {
            this.fsm = fsm;
            this.registry = registry;
            this.invoker = invoker;
            this.self = self;
            this.stateWriter = stateWriter;
            this.stateReader = stateReader;
            this.leaderManager = leaderManager;
            this.dormant = new Dormant(this);
            this.following = new Following(this);
            this.leading = new Leading(this);
            this.stopped = new Stopped(this);
        }

        /// The newer, by `fireSeq`, of the committed row and the last row this manager submitted (the submitted one on a
        /// tie: it carries every write this manager has made since).
        Option<ScheduledTaskStateValue> currentRow(ScheduledTaskStateKey key) {
            var committed = stateReader.apply(key);
            var mine = Option.option(submitted.get(key));

            mine.filter(own -> caughtUp(own, committed)).onPresent(own -> submitted.remove(key, own));

            return mine.filter(own -> !caughtUp(own, committed))
                       .filter(own -> committed.map(row -> own.fireSeq() >= row.fireSeq())
                                               .or(true))
                       .fold(() -> committed,
                             Option::some);
        }

        /// The commit has caught up with what this manager submitted: the committed row IS it, or a newer fire's. The
        /// entry has nothing left to protect and is dropped, so the map stays as small as the writes in flight.
        private static boolean caughtUp(ScheduledTaskStateValue own, Option<ScheduledTaskStateValue> committed) {
            return committed.map(row -> row.equals(own) || row.fireSeq() > own.fireSeq())
                            .or(false);
        }

        void submit(ScheduledTaskStateKey key, ScheduledTaskStateValue value) {
            submitted.put(key, value);
            stateWriter.accept(new KVCommand.Put<>(key, value));
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
            TaskOps.clearOpenConditions(ctx, key, fsm.current() instanceof Leading);
        }

        private boolean shouldRunInCurrentState(ScheduledTask task) {
            var state = fsm.current();

            if (! (state instanceof Following || state instanceof Leading)) {
                return false;
            }

            return TaskOps.eligible(ctx, task, state instanceof Leading);
        }
    }

    final class TaskOps {
        private static final Logger log = LoggerFactory.getLogger(TaskOps.class);

        private TaskOps() {}

        /// Where a task runs (#272 R12, #273 item 1). ALL-mode = every node that HOSTS the slice and is
        /// not draining — a non-hosting node has no bridge to fire through and used to write a failure
        /// state every interval; a draining node must stop firing. SINGLE-mode = the leader, hosting or
        /// not: the fire is a `Unit` fire-and-forget the invoker can encode without a local bridge.
        /// An execution mode this node cannot read runs nowhere (#964, fail closed).
        static boolean eligible(Context ctx, ScheduledTask task, boolean leader) {
            if (task.paused()) {
                return false;
            }

            return switch (task.executionMode()) {
                case ALL -> hostingAndNotDraining(ctx, task);
                case SINGLE -> leader;
                case UNKNOWN -> false;
            };
        }

        static boolean hostingAndNotDraining(Context ctx, ScheduledTask task) {
            return ! ctx.draining.get() && ctx.invoker.hasLocalSlice(task.artifact());
        }

        /// Fire-time re-check for ALL-mode: hosting can end (the slice unloaded while other replicas keep
        /// the cluster-scoped task key alive) and draining can begin between timer registration and a
        /// tick. A tick that is no longer eligible is skipped without any state write.
        private static boolean shouldFire(Context ctx, ScheduledTask task) {
            return task.executionMode() != ExecutionMode.ALL || hostingAndNotDraining(ctx, task);
        }

        static void startEligibleTasks(Context ctx, boolean leader) {
            ctx.registry.allTasks()
                        .stream()
                        .filter(task -> eligible(ctx, task, leader))
                        .forEach(task -> {
                                     var key = ScheduledTaskKey.scheduledTaskKey(task.configSection(),
                                                                                 task.artifact(),
                                                                                 task.methodName());

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
            IntervalParser.parse(task.interval())
                          .onSuccess(interval -> scheduleAtFixedRate(ctx, key, task, interval))
                          .onFailure(cause -> log.warn("Failed to parse interval '{}' for task {}: {}",
                                                       task.interval(),
                                                       key,
                                                       cause.message()));
        }

        private static void scheduleAtFixedRate(Context ctx,
                                                ScheduledTaskKey key,
                                                ScheduledTask task,
                                                TimeSpan interval) {
            var future = SharedScheduler.scheduleAtFixedRate(() -> fireFixedRate(ctx, key, task, interval), interval);

            replaceTimer(ctx, key, future);
            log.info("Started scheduled task {} with interval {}", key, task.interval());
        }

        /// Fixed-rate ticks are wall-clock driven ([SharedScheduler#scheduleAtFixedRate] does not wait
        /// for the prior invocation to settle), so a slow invocation can still be running when the next
        /// tick fires. `ctx.inFlight` is an atomic try-claim guard: a tick that finds the key already
        /// claimed skips this fire (recording the skip in state) instead of running concurrently with
        /// the in-progress invocation.
        private static void fireFixedRate(Context ctx, ScheduledTaskKey key, ScheduledTask task, TimeSpan interval) {
            if (!shouldFire(ctx, task)) {
                return;
            }

            if (!ctx.inFlight.add(key)) {
                recordSkippedOverlap(ctx, task);

                return;
            }

            LongSupplier nextFireAt = () -> System.currentTimeMillis() + interval.millis();

            executeTask(ctx, task, nextFireAt).onResultRun(() -> ctx.inFlight.remove(key));
        }

        /// ALL-mode runs independently on every node (`Following` + `Leading`), so a shared,
        /// no-node key would have every node clobber the same counter row (#841). Scoping the key
        /// by `ctx.self()` gives each node its own row. SINGLE-mode runs on exactly one node at a
        /// time (the leader) — leadership can change hands, but only one node ever writes at a
        /// given moment, so the pre-#841 global (no-node) key is kept unchanged.
        private static ScheduledTaskStateKey stateKeyFor(Context ctx, ScheduledTask task) {
            return task.executionMode() == ExecutionMode.ALL
                   ? ScheduledTaskStateKey.scheduledTaskStateKey(task.configSection(),
                                                                 task.artifact(),
                                                                 task.methodName(),
                                                                 ctx.self)
                   : ScheduledTaskStateKey.scheduledTaskStateKey(task.configSection(),
                                                                 task.artifact(),
                                                                 task.methodName());
        }

        private static void recordSkippedOverlap(Context ctx, ScheduledTask task) {
            var key = stateKeyFor(ctx, task);
            var prior = ctx.currentRow(key);
            var value = ScheduledTaskStateValue.skippedOverlapState(prior, System.currentTimeMillis());

            ctx.submit(key, value);
            log.warn("Scheduled task {}.{} skipped fire: previous execution still in flight",
                     task.configSection(),
                     task.methodName().name());
        }

        private static void startCronTimer(Context ctx, ScheduledTaskKey key, ScheduledTask task) {
            CronExpression.parse(task.cron())
                          .onSuccess(cron -> scheduleNextCronFire(ctx, key, task, cron))
                          .onFailure(cause -> log.warn("Failed to parse cron '{}' for task {}: {}",
                                                       task.cron(),
                                                       key,
                                                       cause.message()));
        }

        private static void scheduleNextCronFire(Context ctx,
                                                 ScheduledTaskKey key,
                                                 ScheduledTask task,
                                                 CronExpression cron) {
            cron.delayUntilNext(Instant.now())
                .onSuccess(delay -> registerCronFire(ctx, key, task, cron, delay))
                .onFailure(cause -> log.warn("Failed to compute next cron fire for task {}: {}",
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

        /// Cron re-schedules only AFTER the invocation settles (via [Promise#onResultRun]) — one-shot
        /// timers cannot overlap THEMSELVES by construction, so cron never raced its own next fire.
        /// It CAN still overlap a manual trigger on the same key (`ScheduledTaskRoutes#triggerTask`),
        /// so this claims/releases `ctx.inFlight` too — the same guard `fireFixedRate` uses — purely so
        /// that guard is accurate for cron tasks: a claim taken here is visible to [#tryClaim], and a
        /// claim taken by a manual trigger is visible here. A skip re-arms immediately (cron has no
        /// other trigger to resume it) and is recorded the same way an overlapping fixed-rate tick is.
        /// Package-private (not `private`) so `ScheduledTaskManagerTest` can drive it directly with a
        /// real `Context` and `CronExpression` — cron's minute-granularity `delayUntilNext` makes
        /// waiting on a real timer tick impractically slow for a unit test.
        static void executeCronTask(Context ctx, ScheduledTaskKey key, ScheduledTask task, CronExpression cron) {
            if (!shouldFire(ctx, task)) {
                scheduleNextCronFire(ctx, key, task, cron);

                return;
            }

            if (!ctx.inFlight.add(key)) {
                recordSkippedOverlap(ctx, task);
                scheduleNextCronFire(ctx, key, task, cron);

                return;
            }

            LongSupplier nextFireAt = () -> nextCronFireAt(cron);

            executeTask(ctx, task, nextFireAt).onResultRun(() -> {
                ctx.inFlight.remove(key);
                if (ctx.activeTimers.containsKey(key)) {
                    scheduleNextCronFire(ctx, key, task, cron);
                }
            });
        }

        /// Computed lazily, AFTER invoke settles: computing this eagerly (before invoke) would report
        /// the WRONG next match whenever the invocation runs longer than the interval between cron
        /// matches. `0` on parse failure mirrors the pre-existing warn-only defense-in-depth fallback in
        /// [#startCronTimer] — activation-time validation ([NodeDeploymentState]) is the real gate.
        ///
        /// **Clock source is NODE-LOCAL (#273 item 3, documented rather than changed).** `Instant.now()`
        /// is this JVM's wall clock, read as UTC by [CronExpression]; there is no cluster clock and the
        /// HLC is not consulted. So an ALL-mode cron fires on each node at that node's own reading of
        /// the boundary — skew between nodes is skew between their fires — and a SINGLE-mode cron uses
        /// the leader's clock, so a leader change moves the reference clock along with the timer. A
        /// clock that steps across a minute boundary on one node can fire that boundary twice or skip
        /// it once on that node; nothing here detects it.
        private static long nextCronFireAt(CronExpression cron) {
            var now = Instant.now();

            return cron.delayUntilNext(now)
                       .map(delay -> now.toEpochMilli() + delay.millis())
                       .or(0L);
        }

        private static Promise<Unit> executeTask(Context ctx, ScheduledTask task, LongSupplier nextFireAtSupplier) {
            var firedAt = System.currentTimeMillis();

            return ctx.invoker.invokeAwaitingCompletion(task.artifact(),
                                                        task.methodName(),
                                                        Unit.unit())
                              .onSuccess(_ -> writeSuccessState(ctx,
                                                                task,
                                                                nextFireAtSupplier.getAsLong(),
                                                                firedAt))
                              .onFailure(cause -> recordFailedFire(ctx,
                                                                   task,
                                                                   cause,
                                                                   nextFireAtSupplier.getAsLong(),
                                                                   firedAt));
        }

        /// A fire that ended without a success. A timeout of a REMOTE fire ([SliceInvokerError.CompletionUnknown]) says
        /// nothing about the callee: the outcome is UNKNOWN, recorded as such and counted neither as an execution nor as a
        /// failure. Every other failure (a failure response, a departed node, a request that could not be sent) is one.
        private static void recordFailedFire(Context ctx,
                                             ScheduledTask task,
                                             Cause cause,
                                             long nextFireAt,
                                             long firedAt) {
            if (cause instanceof SliceInvokerError.CompletionUnknown unknown) {
                var written = writeUnknownOutcomeState(ctx, task, cause.message(), nextFireAt, firedAt);
                var fireSeq = written.fireSeq();

                unknown.lateOutcome()
                       .onSuccess(_ -> resolveLate(ctx,
                                                   task,
                                                   written,
                                                   base -> ScheduledTaskStateValue.lateSuccessState(base, fireSeq)))
                       .onFailure(late -> resolveLate(ctx,
                                                      task,
                                                      written,
                                                      base -> lateFailure(task, base, fireSeq, late)));
            } else {
                handleTaskFailure(ctx, task, cause.message(), nextFireAt, firedAt);
            }
        }

        private static ScheduledTaskStateValue lateFailure(ScheduledTask task,
                                                           ScheduledTaskStateValue base,
                                                           int fireSeq,
                                                           Cause late) {
            log.warn("Scheduled task {}.{} failed (late response): {}",
                     task.configSection(),
                     task.methodName().name(),
                     late.message());

            return ScheduledTaskStateValue.lateFailureState(base, fireSeq, late.message());
        }

        private static void handleTaskFailure(Context ctx,
                                              ScheduledTask task,
                                              String message,
                                              long nextFireAt,
                                              long firedAt) {
            log.warn("Scheduled task {}.{} failed: {}",
                     task.configSection(),
                     task.methodName().name(),
                     message);
            writeFailureState(ctx, task, message, nextFireAt, firedAt);
        }

        private static void writeSuccessState(Context ctx, ScheduledTask task, long nextFireAt, long firedAt) {
            var key = stateKeyFor(ctx, task);
            var prior = ctx.currentRow(key);

            logOutcomeTransitionOut(task, prior);
            ctx.submit(key, ScheduledTaskStateValue.successState(prior, nextFireAt, firedAt));
        }

        private static void writeFailureState(Context ctx,
                                              ScheduledTask task,
                                              String message,
                                              long nextFireAt,
                                              long firedAt) {
            var key = stateKeyFor(ctx, task);
            var prior = ctx.currentRow(key);

            logOutcomeTransitionOut(task, prior);
            ctx.submit(key, ScheduledTaskStateValue.failureState(prior, nextFireAt, firedAt, message));
        }

        /// The outcome is UNKNOWN (#1723). Logged ONCE per transition into the unknown state, not per fire: a task that
        /// keeps timing out is counted in `completionTimeouts`, and the log line says so; the line for leaving the state is
        /// [#logOutcomeTransitionOut].
        private static ScheduledTaskStateValue writeUnknownOutcomeState(Context ctx,
                                                                        ScheduledTask task,
                                                                        String message,
                                                                        long nextFireAt,
                                                                        long firedAt) {
            var key = stateKeyFor(ctx, task);
            var prior = ctx.currentRow(key);
            var alreadyUnknown = prior.map(ScheduledTaskStateValue::outcomeUnknown).or(false);

            if (!alreadyUnknown) {
                log.warn("Scheduled task {}.{} outcome UNKNOWN: {} (not counted as an execution or a failure; further unknown"
                        + " outcomes are counted in the task state, not logged, until a fire completes)",
                         task.configSection(),
                         task.methodName().name(),
                         message);
            }

            var value = ScheduledTaskStateValue.unknownOutcomeState(prior, nextFireAt, firedAt);

            ctx.submit(key, value);

            return value;
        }

        /// A late answer for the fire that wrote `written`. Applied to the newer, by `fireSeq`, of the committed row and the
        /// last row this manager submitted ([ScheduledTaskStateValue#resolutionBase]): a newer fire's outcome stands (see
        /// [ScheduledTaskStateValue#lateSuccessState]) even while its row is still in flight, and the sequence never goes
        /// backwards. A row that is gone (the task was removed) has nothing to resolve.
        private static void resolveLate(Context ctx,
                                        ScheduledTask task,
                                        ScheduledTaskStateValue written,
                                        UnaryOperator<ScheduledTaskStateValue> resolution) {
            var key = stateKeyFor(ctx, task);

            ctx.stateReader.apply(key)
                           .map(row -> ScheduledTaskStateValue.resolutionBase(row,
                                                                              Option.option(ctx.submitted.get(key)).or(written)))
                           .onPresent(base -> writeResolved(ctx,
                                                            task,
                                                            key,
                                                            base,
                                                            resolution.apply(base)));
        }

        private static void writeResolved(Context ctx,
                                          ScheduledTask task,
                                          ScheduledTaskStateKey key,
                                          ScheduledTaskStateValue base,
                                          ScheduledTaskStateValue resolved) {
            if (base.outcomeUnknown() && !resolved.outcomeUnknown()) {
                log.info("Scheduled task {}.{} outcome known again: the unknown fire was answered late",
                         task.configSection(),
                         task.methodName().name());
            }

            ctx.submit(key, resolved);
        }

        /// The task is gone: an open UNKNOWN condition ends with it, so a task registered again under the same key starts
        /// with a fresh condition instead of inheriting an UNKNOWN (#1723). The counters, the sequence and the history
        /// stay; only the unknown outcome is cleared, and that committed change is what tells the operator
        /// (`task-removed`). This node's own ALL-mode row is cleared by this node; the unscoped SINGLE-mode row, shared by
        /// every node, only by the leader.
        static void clearOpenConditions(Context ctx, ScheduledTaskKey key, boolean leader) {
            clearOpenCondition(ctx,
                               ScheduledTaskStateKey.scheduledTaskStateKey(key.configSection(),
                                                                           key.artifact(),
                                                                           key.methodName(),
                                                                           ctx.self));
            if (leader) {
                clearOpenCondition(ctx,
                                   ScheduledTaskStateKey.scheduledTaskStateKey(key.configSection(),
                                                                               key.artifact(),
                                                                               key.methodName()));
            }
        }

        /// The leader closes the open UNKNOWN of the per-node rows `departed` was firing (`node-departed`).
        static void closeDepartedNodeRows(Context ctx, NodeId departed, boolean leader) {
            if (!leader) {
                return;
            }

            ctx.registry.allTasks()
                        .stream()
                        .filter(task -> task.executionMode() == ExecutionMode.ALL)
                        .map(task -> ScheduledTaskStateKey.scheduledTaskStateKey(task.configSection(),
                                                                                 task.artifact(),
                                                                                 task.methodName(),
                                                                                 departed))
                        .forEach(stateKey -> ctx.currentRow(stateKey)
                                                .filter(ScheduledTaskStateValue::outcomeUnknown)
                                                .onPresent(row -> ctx.submit(stateKey,
                                                                             ScheduledTaskStateValue.nodeDepartedState(row))));
            // The departed node's rows are never written by anyone again.
            ctx.registry.allTasks()
                        .stream()
                        .map(task -> ScheduledTaskStateKey.scheduledTaskStateKey(task.configSection(),
                                                                                 task.artifact(),
                                                                                 task.methodName(),
                                                                                 departed))
                        .forEach(ctx.submitted::remove);
        }

        private static void clearOpenCondition(Context ctx, ScheduledTaskStateKey stateKey) {
            ctx.currentRow(stateKey)
               .filter(ScheduledTaskStateValue::outcomeUnknown)
               .onPresent(row -> ctx.submit(stateKey,
                                            ScheduledTaskStateValue.conditionClearedState(row)));
            // The task is gone: nothing fires on this row any more, so nothing in flight needs protecting.
            ctx.submitted.remove(stateKey);
        }

        /// A fire that completed (success or failure) after the task had been UNKNOWN: the outcome is known again.
        private static void logOutcomeTransitionOut(ScheduledTask task, Option<ScheduledTaskStateValue> prior) {
            if (prior.map(state -> ScheduledTaskStateValue.OUTCOME_UNKNOWN.equals(state.lastOutcome())).or(false)) {
                log.info("Scheduled task {}.{} outcome known again: a fire completed",
                         task.configSection(),
                         task.methodName().name());
            }
        }

        /// #273: drop this node's ALL-mode timers (SINGLE-mode timers, leader-owned, stay). The count is
        /// logged rather than returned: it is the node's only operator-visible evidence that the drain
        /// reached the scheduler, and `ScheduledTaskDrainWiringBootTest` reads this line to pin that the
        /// real `DrainProcedure.initiate` gets here.
        static void cancelAllModeTimers(Context ctx) {
            // Materialised, not `peek`-counted: `count()` is permitted to skip a pipeline whose size it
            // can derive, which would cancel nothing while still reporting a plausible number.
            var keys = ctx.registry.allTasks()
                                   .stream()
                                   .filter(task -> task.executionMode() == ExecutionMode.ALL)
                                   .map(task -> ScheduledTaskKey.scheduledTaskKey(task.configSection(),
                                                                                  task.artifact(),
                                                                                  task.methodName()))
                                   .toList();

            keys.forEach(key -> cancelTimer(ctx, key));
            log.info("Drain initiated on {} — cancelled {} ALL-mode scheduled timer(s); ALL-mode fires stop here",
                     ctx.self.id(),
                     keys.size());
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
        public void onNodeRemoved(MembershipDecision.NodeRemoved event) {
            TaskOps.closeDepartedNodeRows(ctx, event.nodeId(), fsm.current() instanceof Leading);
        }

        @Override
        public void onNodeDecommissioned(MembershipDecision.NodeDecommissioned event) {
            TaskOps.closeDepartedNodeRows(ctx, event.nodeId(), fsm.current() instanceof Leading);
        }

        @Override
        public void onDrainInitiated() {
            if (ctx.draining.compareAndSet(false, true)) {
                TaskOps.cancelAllModeTimers(ctx);
            }
        }

        @Override
        public int activeTimerCount() {
            return ctx.activeTimers.size();
        }

        /// Rows this manager submitted that the commit has not yet caught up with (test seam).
        int submittedRowCount() {
            return ctx.submitted.size();
        }

        /// The row decisions build on (test seam).
        Option<ScheduledTaskStateValue> currentRowFor(ScheduledTaskStateKey key) {
            return ctx.currentRow(key);
        }

        @Override
        public boolean tryClaim(ScheduledTaskKey key) {
            return ctx.inFlight.add(key);
        }

        @Override
        public void release(ScheduledTaskKey key) {
            ctx.inFlight.remove(key);
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
