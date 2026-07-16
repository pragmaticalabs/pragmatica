// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.delegation.TaskAssignmentError;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;


/// Timeout-bounded gate for leader-pinned task-group ownership (#329).
///
/// Every control-plane task group is leader-pinned: the owner of any group is the current
/// consensus leader. A freshly-elected *replacement* leader whose Rabia log is still draining
/// (`isPendingCatchUp`) is `isActive` but not caught up, so synchronous leader-local commits
/// (`node.apply().await`) stall and the management forwarder 503s (the `13-edge-cases`
/// `Concurrent_deploy` signature).
///
/// This gate withholds ownership (returns [TaskAssignmentError.NotAssigned]) from a
/// pending-catch-up leader, which degrades into the existing `HttpForwarder` re-resolution
/// retry path — once catch-up completes the resolver returns the leader (self) and the request
/// handles locally.
///
/// CRITICAL: the suppression is time-bounded so it can never become a permanent no-owner wedge
/// (interaction with the zero-leader-wedge work). After [#suppressionWindow] elapses from the
/// first observation of self as a pending-catch-up leader, ownership is granted regardless of
/// catch-up state — at worst the original latency, never a permanent stall. The window resets
/// whenever the node is not a pending-catch-up leader (caught up, or not leader), so a later
/// lag re-opens a fresh bounded window rather than inheriting an exhausted one.
public final class LeaderCatchUpGate {
    /// Default suppression window. Kept comfortably above the typical Rabia catch-up time and
    /// below the management forwarder's overall timeout budget so the bounded retry path
    /// converges within a single management request when catch-up completes quickly, yet the
    /// gate self-releases long before any operation could wedge.
    public static final TimeSpan DEFAULT_SUPPRESSION_WINDOW = TimeSpan.timeSpan(2).seconds();
    private static final long WINDOW_INACTIVE = Long.MIN_VALUE;

    private final NodeId self;
    private final Supplier<Option<NodeId>> leaderSupplier;
    private final BooleanSupplier pendingCatchUp;
    private final long suppressionWindowMillis;
    private final LongSupplier clock;
    private final AtomicLong windowStartMillis = new AtomicLong(WINDOW_INACTIVE);

    private LeaderCatchUpGate(NodeId self,
                              Supplier<Option<NodeId>> leaderSupplier,
                              BooleanSupplier pendingCatchUp,
                              TimeSpan suppressionWindow,
                              LongSupplier clock) {
        this.self = self;
        this.leaderSupplier = leaderSupplier;
        this.pendingCatchUp = pendingCatchUp;
        this.suppressionWindowMillis = suppressionWindow.millis();
        this.clock = clock;
    }

    /// Creates a gate using the system wall clock and [#DEFAULT_SUPPRESSION_WINDOW].
    public static LeaderCatchUpGate leaderCatchUpGate(NodeId self,
                                                      Supplier<Option<NodeId>> leaderSupplier,
                                                      BooleanSupplier pendingCatchUp) {
        return leaderCatchUpGate(self,
                                 leaderSupplier,
                                 pendingCatchUp,
                                 DEFAULT_SUPPRESSION_WINDOW,
                                 System::currentTimeMillis);
    }

    /// Creates a gate with an explicit suppression window and clock (testing seam).
    public static LeaderCatchUpGate leaderCatchUpGate(NodeId self,
                                                      Supplier<Option<NodeId>> leaderSupplier,
                                                      BooleanSupplier pendingCatchUp,
                                                      TimeSpan suppressionWindow,
                                                      LongSupplier clock) {
        return new LeaderCatchUpGate(self, leaderSupplier, pendingCatchUp, suppressionWindow, clock);
    }

    /// Resolves the owner of a leader-pinned task group, applying the time-bounded
    /// pending-catch-up gate. Mirrors the un-gated resolver's contract: success → owner NodeId,
    /// failure → [TaskAssignmentError.NotAssigned] (no committed owner / withheld during catch-up).
    public Result<NodeId> resolve(TaskGroup group) {
        return leaderSupplier.get()
                             .toResult(TaskAssignmentError.notAssigned(group))
                             .flatMap(leader -> gate(group, leader));
    }

    private Result<NodeId> gate(TaskGroup group, NodeId leader) {
        if (!leader.equals(self)) {
            windowStartMillis.set(WINDOW_INACTIVE);

            return Result.success(leader);
        }

        if (!pendingCatchUp.getAsBoolean()) {
            windowStartMillis.set(WINDOW_INACTIVE);

            return Result.success(leader);
        }

        return suppressionWindowExpired()
               ? Result.success(leader)
               : TaskAssignmentError.notAssigned(group).result();
    }

    /// True once the bounded suppression window has elapsed since this node first observed itself
    /// as a pending-catch-up leader. Starts the window on the first call in a lagging-leader
    /// state; subsequent calls compare against that start. Window expiry is the hard backstop
    /// that prevents a permanent no-owner wedge.
    private boolean suppressionWindowExpired() {
        var now = clock.getAsLong();
        var started = windowStartMillis.compareAndExchange(WINDOW_INACTIVE, now);
        var effectiveStart = started == WINDOW_INACTIVE
                             ? now
                             : started;

        return now - effectiveStart >= suppressionWindowMillis;
    }
}
