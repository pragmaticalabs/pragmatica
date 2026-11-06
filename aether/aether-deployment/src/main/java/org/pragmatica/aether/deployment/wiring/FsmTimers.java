// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;


/// The timers of one deployment FSM instance (phase-3 design 4.3, review H4, MQ-6).
///
/// **The map lives on the consumer.** `id -> (generation, attachment, cancellation)` is read and written only by the FSM's consumer
/// thread ([#schedule], [#cancel], [#claim], [#close]). A timer body runs on a scheduler thread and does exactly one thing: it enqueues
/// `Tick(id, generation)` through the `enqueue` callback it was built with. It never touches the map.
///
/// **Generations.** [#schedule] gives every arm a fresh generation and cancels the entry it replaces: one live timer per id. The consumer
/// steps a tick only when [#claim] finds the id at that tick's generation, and the entry is then removed. A replaced or cancelled timer
/// whose body was already running therefore cannot fire into the model: its tick carries a generation the map no longer holds. Without
/// this a same-incarnation stale tick fires early, because the FSMs filter timers by incarnation only.
///
/// **`delay <= 0`** enqueues the tick at once, without the scheduler (`v-2172-report.md:275`): a zero delay must not depend on a scheduler
/// thread winning a race against the next input.
///
/// `A` is what the FSM wants back with the fire: the cluster FSM's incarnation, `Unit` for the node FSM (its `TimerId` carries it).
public final class FsmTimers<K, A> {
    /// What a timer body enqueues.
    public record Tick<K>(K id, long generation) {}

    /// The clock side: runs `body` once after `delayMs` and returns the way to cancel it. Production is `SharedScheduler`.
    public interface TimerService {
        Runnable schedule(Runnable body, long delayMs);
    }

    /// The production clock: the process-wide `SharedScheduler`, whose bodies run on virtual threads (so a body must only enqueue).
    public static TimerService sharedScheduler() {
        return (body, delayMs) -> cancellationOf(SharedScheduler.schedule(body,
                                                                          TimeSpan.timeSpan(delayMs).millis()));
    }

    private static Runnable cancellationOf(ScheduledFuture<?> future) {
        return () -> future.cancel(false);
    }

    private record Armed<A>(long generation, A attachment, Runnable cancellation) {}

    private static final Runnable NOT_CANCELLABLE = () -> {};

    private final TimerService service;
    private final Consumer<Tick<K>> enqueue;
    private final Map<K, Armed<A>> armed = new HashMap<>();
    private long counter;

    private FsmTimers(TimerService service, Consumer<Tick<K>> enqueue) {
        this.service = service;
        this.enqueue = enqueue;
    }

    public static <K, A> FsmTimers<K, A> fsmTimers(TimerService service, Consumer<Tick<K>> enqueue) {
        return new FsmTimers<>(service, enqueue);
    }

    /// Arms `id` for `delayMs`, replacing (and cancelling) any live timer of the same id.
    @Contract
    public void schedule(K id, A attachment, long delayMs) {
        var generation = ++counter;
        var tick = new Tick<>(id, generation);
        var cancellation = delayMs <= 0L
                           ? fireNow(tick)
                           : service.schedule(() -> enqueue.accept(tick), delayMs);

        cancelPrevious(armed.put(id, new Armed<>(generation, attachment, cancellation)));
    }

    /// Disarms `id`. A body already running enqueues a tick that [#claim] then drops.
    @Contract
    public void cancel(K id) {
        cancelPrevious(armed.remove(id));
    }

    /// Takes the tick off the books: the attachment if the map holds the tick's id at the tick's generation (the entry is removed),
    /// nothing for a superseded, cancelled or already-claimed tick.
    public Option<A> claim(Tick<K> tick) {
        return Option.option(armed.get(tick.id()))
                     .filter(current -> current.generation() == tick.generation())
                     .map(_ -> takeOut(tick.id()));
    }

    private A takeOut(K id) {
        return armed.remove(id)
                    .attachment();
    }

    /// How many timers are armed.
    public int size() {
        return armed.size();
    }

    /// Cancels every entry (the `Close` of the FSM).
    @Contract
    public void close() {
        armed.values().forEach(entry -> entry.cancellation()
                                             .run());
        armed.clear();
    }

    private Runnable fireNow(Tick<K> tick) {
        enqueue.accept(tick);

        return NOT_CANCELLABLE;
    }

    private static <A> void cancelPrevious(Armed<A> previous) {
        Option.option(previous).onPresent(entry -> entry.cancellation()
                                                        .run());
    }
}
