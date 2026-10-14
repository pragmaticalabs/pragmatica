// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.machine.LocalOutcome;
import org.pragmatica.aether.deployment.node.machine.LocalStep;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.deployment.node.machine.NodeFsmTimings;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The local-op executor of the node interpreter (phase-3 design 4.3, review H3, MQ-3, D-2, D7).
///
/// **Structure.** Each artifact has a FIFO of ops and at most one running op; ops of different artifacts run concurrently. When an op's body
/// returns, the executor takes the head of that artifact's FIFO next. The body runs on a virtual thread, so a hung op stalls its own artifact
/// and nothing else (P3-R4). An op with no artifact (`NOTIFY_CONFIG`) has a lane of its own.
///
/// **Hazards and rules.**
/// - **H3b (quick flap).** The old incarnation's fire-and-forget `SUSPEND` is still running when the new incarnation's reactivation ops are
///   queued: they wait behind it in the artifact's FIFO.
/// - **H3c (stale ops).** On dequeue, a chain op (token non-zero) whose incarnation differs from the interpreter's current one is discarded
///   unrun and counted `dropped_stale`. Token-0 teardown ops (`SUSPEND`, `UNPUBLISH`, `FORCED_CLEANUP`, `EVICT`) still run: they were queued
///   before the new incarnation's ops, so they run first, and teardown is safe. **The timeout clock starts when the op starts**, not when it is
///   queued, so a queued op's bound cannot expire before it has run.
/// - **MQ-3 (ruled).** When a stale-incarnation op that publishes (`PUBLISH_ROUTES`, `REGISTER_BRIDGE`, `REGISTER_CONFIG`) finishes, a
///   best-effort `UNPUBLISH` of that artifact is inserted at the **head** of the artifact's FIFO, ahead of every queued op (D-2): the stale op
///   was the lane's one running op, so nothing behind it has run yet, the compensation runs before any op of the new incarnation, and the new
///   incarnation's own `PUBLISH_ROUTES` re-publishes afterwards. Appended at the tail it would unroute the newer incarnation's publish. This is
///   an interpreter rule ("undo a serving side effect no live incarnation owns"), not deployment logic. `[guess: the compensation also runs
///   when the stale op ended Failed or TimedOut, since a failed publish may have left part of its effect; UNPUBLISH is best effort and
///   idempotent]`.
/// - **Timeouts.** An op with `timeoutMs > 0` is settled `TimedOut` once that long after its start; the lane stays occupied until the body
///   returns, so a runaway op cannot be overtaken by the next op on the same artifact. An op with `timeoutMs = 0` is bounded by
///   `NodeFsmTimings.boundOf(step)` for observation only: `deployment-localop-overrun` is logged once the bound passes.
///
/// A settlement is delivered once; a token-0 op delivers none. After [#close()] queued ops are dropped and nothing is delivered.
public final class LocalOpExecutor implements LocalOps {
    private static final Logger log = LoggerFactory.getLogger(LocalOpExecutor.class);

    private static final Set<LocalStep> COMPENSATED_ON_STALE = EnumSet.of(LocalStep.PUBLISH_ROUTES,
                                                                          LocalStep.REGISTER_BRIDGE,
                                                                          LocalStep.REGISTER_CONFIG);

    private static final Object NO_ARTIFACT = new Object();

    /// Runs one step of the slice runtime; the production implementation is `SliceRuntimeAdapter`.
    public interface StepRunner {
        Promise<LocalOutcome> run(NodeFsmEffect.LocalOp op);
    }

    /// `aether_deployment_fsm_localops_total{step, result}`: `done`, `failed`, `timed_out`, `dropped_stale`, `compensated`.
    public interface Counter {
        @Contract
        void counted(String step, String result);

        Counter NONE = (_, _) -> {};
    }

    private record Queued(NodeFsmEffect.LocalOp op,
                          LongSupplier currentIncarnation,
                          Consumer<NodeFsmInput.LocalOpSettled> settled) {
        boolean isStale() {
            return op.token() != 0L && op.incarnation() != currentIncarnation.getAsLong();
        }
    }

    private static final class Lane {
        final ArrayDeque<Queued> queue = new ArrayDeque<>();
        boolean running;
    }

    private final StepRunner runner;
    private final FsmTimers.TimerService clock;
    private final NodeFsmTimings timings;
    private final Counter counter;
    private final Object lock = new Object();
    private final Map<Object, Lane> lanes = new HashMap<>();
    private boolean closed;

    private LocalOpExecutor(StepRunner runner, FsmTimers.TimerService clock, NodeFsmTimings timings, Counter counter) {
        this.runner = runner;
        this.clock = clock;
        this.timings = timings;
        this.counter = counter;
    }

    public static LocalOpExecutor localOpExecutor(StepRunner runner,
                                                  FsmTimers.TimerService clock,
                                                  NodeFsmTimings timings,
                                                  Counter counter) {
        return new LocalOpExecutor(runner, clock, timings, counter);
    }

    @Override
    @Contract
    public void run(NodeFsmEffect.LocalOp op,
                    LongSupplier currentIncarnation,
                    Consumer<NodeFsmInput.LocalOpSettled> settled) {
        enqueue(new Queued(op, currentIncarnation, settled)).onPresent(this::start);
    }

    @Override
    @Contract
    public void close() {
        synchronized (lock) {
            closed = true;
            lanes.clear();
        }
    }

    // -- the lanes ---------------------------------------------------------------------------------------------------
    private Option<Queued> enqueue(Queued queued) {
        synchronized (lock) {
            if (closed) {
                return Option.none();
            }

            var lane = lanes.computeIfAbsent(laneOf(queued.op()), _ -> new Lane());

            lane.queue.addLast(queued);

            return lane.running
                   ? Option.none()
                   : takeNext(laneOf(queued.op()), lane);
        }
    }

    /// Called under the lock: the next runnable op of the lane, or none when the lane drains (a stale chain op is dropped and counted).
    private Option<Queued> takeNext(Object key, Lane lane) {
        while (!lane.queue.isEmpty()) {
            var next = lane.queue.pollFirst();

            if (next.isStale()) {
                counter.counted(next.op().step().name(),
                                "dropped_stale");
            } else {
                lane.running = true;

                return Option.some(next);
            }
        }

        lane.running = false;
        lanes.remove(key);

        return Option.none();
    }

    /// The lane is free again: compensate a stale publisher (MQ-3), then take the next op.
    private Option<Queued> complete(Queued finished) {
        synchronized (lock) {
            var key = laneOf(finished.op());
            var lane = lanes.get(key);

            if (closed || lane == null) {
                return Option.none();
            }

            if (finished.op().token() != 0L && finished.isStale() && COMPENSATED_ON_STALE.contains(finished.op().step())) {
                counter.counted(finished.op().step().name(),
                                "compensated");
                lane.queue.addFirst(compensation(finished));
            }

            return takeNext(key, lane);
        }
    }

    private static Queued compensation(Queued stale) {
        var op = new NodeFsmEffect.LocalOp(stale.op().incarnation(),
                                           0L,
                                           stale.op().artifact(),
                                           LocalStep.UNPUBLISH,
                                           0L,
                                           Option.none());

        return new Queued(op, stale.currentIncarnation(), stale.settled());
    }

    private static Object laneOf(NodeFsmEffect.LocalOp op) {
        return op.artifact()
                 .<Object> map(artifact -> artifact)
                 .or(NO_ARTIFACT);
    }

    // -- one op --------------------------------------------------------------------------------------------------------
    private void start(Queued queued) {
        var once = new AtomicBoolean();
        var guard = guardTimer(queued, once);

        Thread.ofVirtual().name("local-op-" + queued.op().step().name()).start(() -> execute(queued, once, guard));
    }

    private void execute(Queued queued, AtomicBoolean once, Runnable guard) {
        Result.lift(Causes::fromThrowable,
                    () -> runner.run(queued.op()))
              .fold(cause -> Promise.success(LocalOutcome.failed(cause)),
                    promise -> promise.map(outcome -> outcome)
                                      .recover(LocalOutcome::failed))
              .onResult(result -> finished(queued,
                                           once,
                                           guard,
                                           result.or(LocalOutcome.timedOut())));
    }

    private void finished(Queued queued, AtomicBoolean once, Runnable guard, LocalOutcome outcome) {
        guard.run();
        report(queued, once, outcome);
        complete(queued).onPresent(this::start);
    }

    /// Delivers the settlement once, to whoever gets there first: the body or the timeout.
    private void report(Queued queued, AtomicBoolean once, LocalOutcome outcome) {
        if (once.compareAndSet(false, true)) {
            counter.counted(queued.op().step().name(),
                            resultOf(outcome));
            deliver(queued, outcome);
        }
    }

    private void deliver(Queued queued, LocalOutcome outcome) {
        var open = !isClosed();

        queued.op()
              .artifact()
              .filter(_ -> open && queued.op()
                                         .token() != 0L)
              .onPresent(artifact -> queued.settled()
                                           .accept(new NodeFsmInput.LocalOpSettled(queued.op().incarnation(),
                                                                                   artifact,
                                                                                   queued.op().token(),
                                                                                   outcome)));
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private static String resultOf(LocalOutcome outcome) {
        return switch (outcome) {
            case LocalOutcome.Done _ -> "done";
            case LocalOutcome.Failed _ -> "failed";
            case LocalOutcome.TimedOut _ -> "timed_out";
        };
    }

    /// Starts the clock of this op, now: the timeout that settles `TimedOut`, or, for an unbounded op, the overrun log. Returns the way to
    /// stop it.
    private Runnable guardTimer(Queued queued, AtomicBoolean once) {
        var op = queued.op();

        if (op.timeoutMs() > 0L && op.token() != 0L) {
            return clock.schedule(() -> report(queued, once, LocalOutcome.timedOut()), op.timeoutMs());
        }

        return clock.schedule(() -> overran(op),
                              Math.max(1L,
                                       timings.boundOf(op.step())));
    }

    private static void overran(NodeFsmEffect.LocalOp op) {
        log.warn("deployment-localop-overrun artifact={} step={} incarnation={} is still running past its bound",
                 op.artifact().map(Artifact::asString).or("-"),
                 op.step(),
                 op.incarnation());
    }
}
