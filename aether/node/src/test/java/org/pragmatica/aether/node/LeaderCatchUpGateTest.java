// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.delegation.TaskAssignmentError;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #329 — the leader-pinned task-group owner resolver must withhold ownership from a lagging
/// replacement leader (consensus log still draining) so synchronous leader-local commits do not
/// stall and 503, and the suppression must be time-bounded so it can never wedge ownership.
class LeaderCatchUpGateTest {
    private static final NodeId SELF = nodeId("self").unwrap();
    private static final NodeId OTHER = nodeId("other").unwrap();
    private static final TimeSpan WINDOW = TimeSpan.timeSpan(2).seconds();

    private final AtomicReference<Option<NodeId>> leader = new AtomicReference<>(Option.some(SELF));
    private final AtomicBoolean pendingCatchUp = new AtomicBoolean(false);
    private final AtomicLong clockMillis = new AtomicLong(0L);

    private LeaderCatchUpGate gate() {
        Supplier<Option<NodeId>> leaderSupplier = leader::get;
        LongSupplier clock = clockMillis::get;
        return LeaderCatchUpGate.leaderCatchUpGate(SELF, leaderSupplier, pendingCatchUp::get, WINDOW, clock);
    }

    @Test
    void resolve_returns_leader_when_caught_up_leader_is_self() {
        leader.set(Option.some(SELF));
        pendingCatchUp.set(false);

        var result = gate().resolve(TaskGroup.STREAMING);

        assertThat(result.isSuccess()).as("caught-up self-leader must own the group").isTrue();
        result.onSuccess(owner -> assertThat(owner).isEqualTo(SELF));
    }

    @Test
    void resolve_withholds_ownership_when_self_is_pending_catch_up_leader() {
        leader.set(Option.some(SELF));
        pendingCatchUp.set(true);

        var result = gate().resolve(TaskGroup.STREAMING);

        assertThat(result.isFailure()).as("lagging self-leader must be withheld ownership").isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(TaskAssignmentError.NotAssigned.class));
    }

    @Test
    void resolve_returns_remote_leader_even_when_self_is_pending_catch_up() {
        // The gate only concerns leader-LOCAL commits. When another node is leader, ownership
        // resolves to it unconditionally so forwarding proceeds normally.
        leader.set(Option.some(OTHER));
        pendingCatchUp.set(true);

        var result = gate().resolve(TaskGroup.DEPLOYMENT);

        assertThat(result.isSuccess()).as("remote leader resolves regardless of local catch-up").isTrue();
        result.onSuccess(owner -> assertThat(owner).isEqualTo(OTHER));
    }

    @Test
    void resolve_fails_notAssigned_when_no_leader_committed() {
        leader.set(Option.none());
        pendingCatchUp.set(false);

        var result = gate().resolve(TaskGroup.SCALING);

        assertThat(result.isFailure()).as("no committed leader yields notAssigned").isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(TaskAssignmentError.NotAssigned.class));
    }

    @Test
    void resolve_grants_ownership_after_suppression_window_expires_even_while_lagging() {
        // CRITICAL: the gate must never become a permanent no-owner wedge. After the bounded
        // window elapses, a still-lagging self-leader is granted ownership anyway.
        var gate = gate();
        leader.set(Option.some(SELF));
        pendingCatchUp.set(true);
        clockMillis.set(0L);

        // First resolution opens the window and withholds ownership.
        assertThat(gate.resolve(TaskGroup.STREAMING).isFailure())
            .as("ownership withheld at window open").isTrue();

        // Still inside the window: still withheld.
        clockMillis.set(WINDOW.millis() - 1);
        assertThat(gate.resolve(TaskGroup.STREAMING).isFailure())
            .as("ownership still withheld inside window").isTrue();

        // Window elapsed: ownership granted despite still pending catch-up (backstop).
        clockMillis.set(WINDOW.millis());
        var afterWindow = gate.resolve(TaskGroup.STREAMING);
        assertThat(afterWindow.isSuccess())
            .as("ownership granted after window expiry even while still lagging").isTrue();
        afterWindow.onSuccess(owner -> assertThat(owner).isEqualTo(SELF));
    }

    @Test
    void resolve_resets_window_when_catch_up_completes_so_a_later_lag_reopens_a_fresh_window() {
        var gate = gate();
        leader.set(Option.some(SELF));

        // Open the window while lagging.
        pendingCatchUp.set(true);
        clockMillis.set(0L);
        assertThat(gate.resolve(TaskGroup.STREAMING).isFailure()).isTrue();

        // Catch up: ownership granted and window reset.
        pendingCatchUp.set(false);
        clockMillis.set(500L);
        assertThat(gate.resolve(TaskGroup.STREAMING).isSuccess())
            .as("caught-up leader owns and window resets").isTrue();

        // Lag again later: a fresh bounded window opens, not an already-exhausted one.
        pendingCatchUp.set(true);
        clockMillis.set(600L);
        assertThat(gate.resolve(TaskGroup.STREAMING).isFailure())
            .as("re-lag must withhold again within the fresh window").isTrue();

        clockMillis.set(600L + WINDOW.millis() - 1);
        assertThat(gate.resolve(TaskGroup.STREAMING).isFailure())
            .as("fresh window measured from the re-lag, not the original open").isTrue();
    }
}
