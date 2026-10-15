// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.deployment.cluster.fsm2.Seams;
import org.pragmatica.aether.deployment.wiring.FsmTimers.TimerService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// Samples the read seams of the cluster FSM and hands the interpreter what changed (phase-3 design 5.3.1, CI-15, CI-15b, CI-15c).
///
/// | When | What |
/// |---|---|
/// | every `interval` (5 s) | one sample |
/// | right after a membership input was enqueued ([#afterMembershipInput]) | one sample, so a sample that already lacks a node is appended **behind** that node's removal (review M1) |
///
/// A sample is delivered as `SeamsRefreshed` only when it differs from the one delivered before (W-14: no enqueue without a change, one enqueue
/// per change), through the interpreter's *drop the older, append the newer* coalescing. A token that a sample sees go from 0 to `t` is
/// delivered once as `ProcessKnown(node, t)`; the registry only ever moves that way (`BootTokens`).
///
/// **Atomic sample and enqueue.** [#poll] samples and enqueues under one monitor. The 5 s timer and a membership input's own sample run on
/// different threads, and a sample taken before a removal but enqueued after the sample that follows it would put a stale view behind the
/// newer one.
///
/// **Unwired values** stay `none` (`coreView`, `localAlive`) or empty (the rest), as in `Seams.UNWIRED`: the caller passes the suppliers it
/// has. `coreView` is `MembershipFsm.coreMembers()` (H-2 ruled): it feeds the core liveness evidence (R-2.11 (b)) and the deferral of C17b for
/// a liveness-pending core (G-4); `none` would make both no-ops.
public final class SeamPoller {
    public static final long INTERVAL_MS = 5_000L;

    /// Where the seams come from. Each supplier is read on the sampling thread and must be cheap and thread-safe.
    public record Sources(Supplier<Set<NodeId>> countedCores,
                          Supplier<Set<NodeId>> ready,
                          Supplier<Set<NodeId>> draining,
                          Supplier<Option<Set<NodeId>>> coreView,
                          Supplier<Option<Set<NodeId>>> localAlive,
                          Supplier<Set<NodeId>> superseded,
                          Supplier<Map<NodeId, String>> memberSource,
                          Supplier<Map<String, CommunityPlacement>> communityPolicies,
                          Supplier<Set<NodeId>> members,
                          ToLongFunction<NodeId> tokenOf) {}

    /// What the poller delivers to: the cluster interpreter.
    public interface Sink {
        boolean seamsRefreshed(Seams seams);
        boolean processKnown(NodeId node, long token);
    }

    private final Sources sources;
    private final Sink sink;
    private final TimerService clock;
    private final long intervalMs;
    private final Map<NodeId, Long> tokens = new HashMap<>();
    private Option<Seams> delivered = none();

    private Runnable cancellation = () -> {};

    private boolean stopped;

    private SeamPoller(Sources sources, Sink sink, TimerService clock, long intervalMs) {
        this.sources = sources;
        this.sink = sink;
        this.clock = clock;
        this.intervalMs = intervalMs;
    }

    public static SeamPoller seamPoller(Sources sources, Sink sink, TimerService clock) {
        return new SeamPoller(sources, sink, clock, INTERVAL_MS);
    }

    public static SeamPoller seamPoller(Sources sources, Sink sink, TimerService clock, long intervalMs) {
        return new SeamPoller(sources, sink, clock, intervalMs);
    }

    /// A fresh sample, for the snapshot a `Rebuild` is stepped from: the interpreter reads it on its consumer before the first reconcile (W-33).
    public Seams sample() {
        return Seams.seams(sources.countedCores().get(),
                           sources.ready().get(),
                           sources.draining().get(),
                           sources.coreView().get(),
                           sources.localAlive().get(),
                           sources.superseded().get(),
                           sources.memberSource().get(),
                           sources.communityPolicies().get());
    }

    /// Starts the 5 s cadence. The first sample is taken at once so the FSM does not wait a full interval for its first view.
    @Contract
    public synchronized void start() {
        stopped = false;
        poll();
        arm();
    }

    /// Stops the cadence; a sample already running finishes.
    @Contract
    public synchronized void stop() {
        stopped = true;
        cancellation.run();
    }

    /// A membership input was just enqueued: sample now, behind it.
    @Contract
    public void afterMembershipInput() {
        poll();
    }

    /// One sample and, if anything changed, its delivery.
    @Contract
    public synchronized void poll() {
        var seams = sample();

        deliverProcessKnown();
        deliverIfChanged(seams);
    }

    private void deliverIfChanged(Seams seams) {
        if (!delivered.equals(some(seams))) {
            delivered = some(seams);
            sink.seamsRefreshed(seams);
        }
    }

    private void deliverProcessKnown() {
        sources.members().get().forEach(this::observeToken);
    }

    /// 0 to `t` is reported once; a node first seen with `t` already known was known at its first sample, not learned later (CI-15b).
    private void observeToken(NodeId node) {
        var token = sources.tokenOf().applyAsLong(node);
        var before = option(tokens.put(node, token));

        before.filter(known -> known == 0L && token != 0L).onPresent(_ -> sink.processKnown(node, token));
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
