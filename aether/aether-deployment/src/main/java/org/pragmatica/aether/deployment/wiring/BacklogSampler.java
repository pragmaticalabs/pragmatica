// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Unit;


/// The backlog WARN depth sampler (phase-3 design 3.1 "Observation", N-5): the mailboxes are unbounded by design (an input dropped is a fact lost,
/// a producer blocked stalls consensus), so the only protection against a consumer that cannot keep up is to say so. Every `interval` it reads the
/// depth of each watched mailbox and raises the alarm `deployment-fsm-backlog` for an FSM whose depth is **above** [#RAISE_ABOVE], and clears it
/// when the depth is **below** [#CLEAR_BELOW]. Between the two the alarm keeps its state (hysteresis), so a depth hovering around one number does not
/// flap a warning.
///
/// The thresholds are `[guess: settled by the E-13 high-water]`, as the design says. The sampler holds one bit per FSM (raised or not); it reads the
/// depth, never the contents, and never touches a mailbox.
public final class BacklogSampler {
    /// A depth above this raises the alarm.
    public static final int RAISE_ABOVE = 10_000;
    /// A depth below this clears it.
    public static final int CLEAR_BELOW = 1_000;
    /// `[guess: the cadence of the seam poller; a backlog is a minutes-scale condition]`
    public static final long INTERVAL_MS = 5_000L;

    /// One mailbox to watch: the FSM it belongs to (`node`, `cluster`) and a cheap, thread-safe read of its depth.
    public record Watched(String fsm, IntSupplier depth) {}

    /// Where the alarm goes. Called from the sampler's thread, once per change of state.
    public interface Alarm {
        @Contract
        void raised(String fsm, int depth);

        @Contract
        void cleared(String fsm, int depth);
    }

    private final List<Watched> watched;
    private final Alarm alarm;
    private final FsmTimers.TimerService clock;
    private final long intervalMs;
    private final Set<String> raised = new HashSet<>();

    private Runnable cancellation = () -> {};

    private boolean stopped = true;

    private BacklogSampler(List<Watched> watched, Alarm alarm, FsmTimers.TimerService clock, long intervalMs) {
        this.watched = List.copyOf(watched);
        this.alarm = alarm;
        this.clock = clock;
        this.intervalMs = intervalMs;
    }

    public static BacklogSampler backlogSampler(List<Watched> watched, Alarm alarm, FsmTimers.TimerService clock) {
        return new BacklogSampler(watched, alarm, clock, INTERVAL_MS);
    }

    public static BacklogSampler backlogSampler(List<Watched> watched,
                                                Alarm alarm,
                                                FsmTimers.TimerService clock,
                                                long intervalMs) {
        return new BacklogSampler(watched, alarm, clock, intervalMs);
    }

    /// Starts the cadence; the first sample is taken at once.
    @Contract
    public synchronized void start() {
        stopped = false;
        poll();
        arm();
    }

    /// Stops the cadence; a sample already running finishes. The alarm state is left as it is: a stopped node says nothing more.
    @Contract
    public synchronized void stop() {
        stopped = true;
        cancellation.run();
    }

    /// One sample of every watched mailbox.
    @Contract
    public synchronized void poll() {
        watched.forEach(this::sample);
    }

    private void sample(Watched mailbox) {
        var depth = mailbox.depth().getAsInt();

        if (raised.contains(mailbox.fsm())) {
            clearIfBelow(mailbox.fsm(), depth);
        } else if (depth > RAISE_ABOVE) {
            raised.add(mailbox.fsm());
            alarm.raised(mailbox.fsm(), depth);
        }
    }

    private void clearIfBelow(String fsm, int depth) {
        if (depth < CLEAR_BELOW) {
            raised.remove(fsm);
            alarm.cleared(fsm, depth);
        }
    }

    private synchronized Unit tick() {
        if (!stopped) {
            poll();
            arm();
        }

        return Unit.unit();
    }

    private void arm() {
        cancellation = clock.schedule(this::tick, intervalMs);
    }
}
