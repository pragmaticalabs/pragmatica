// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.react.ReactExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// #2688: what `AetherNode.stop()` waits for between routing PASSIVE and quiescing the router. The leader-toggled components
/// (`toggle*OnLeaderChange`) deactivate when a `LeaderChange` that leaves this node not the leader is delivered, and the leader
/// FSM routes that change onto the `LeaderManager` react executor, so it runs after `route(passive)` has returned; a quiesced
/// router drops it. This record watches the deliveries on the same route as the toggles and holds the promise of the next
/// deactivation: resolved while the last delivered change left this node not the leader (or none was delivered), pending from
/// the delivery that made it the leader until the delivery that ends that.
///
/// It is keyed to the delivery, not to the queue: a FIFO marker taken after `route(passive)` misses a `LeaderChange` that a
/// concurrent dispatch enqueues after it (the leader FSM's dispatch is caller-thread and CAS-guarded, so the thread whose CAS
/// enters `QuorumLost` raises the change, and it can still be between its CAS and its entry action when the stop's own
/// dispatch, finding `QuorumLost` already current, has returned).
///
/// A `LeaderChange(self)` still queued and undelivered when `stop()` takes the wait leaves [#next] resolved (the node is not yet
/// recorded as the leader), so [#delivered] first drains the executor: that delivers every change queued before the stop, and
/// only then reads [#next], so a leadership delivered by the drain makes the wait pending and its deactivation is awaited
/// even when it is enqueued after the drain marker.
///
/// Not covered: a toggle whose own `deactivate()` re-dispatches onto another executor (ControlLoop, RollbackManager) is
/// stopped explicitly by `stop()` instead, and a `LeaderChange(self)` that the leader FSM has not yet enqueued when the
/// drain marker is taken (its dispatch is between the CAS and the entry action).
///
/// `deadline` arms the bound on the wait: [Promise#timeout] in production, a manual clock in the tests that pin the bound.
public record LeaderDeactivation(AtomicReference<Promise<Unit>> next,
                                 Fn2<Promise<Unit>, Promise<Unit>, TimeSpan> deadline) {
    private static final Logger log = LoggerFactory.getLogger(LeaderDeactivation.class);

    public static LeaderDeactivation leaderDeactivation() {
        return leaderDeactivation(Promise::timeout);
    }

    /// The bound armed by `deadline` instead of the shared timeout scheduler: the seam that lets a test pin the bound without waiting for it.
    public static LeaderDeactivation leaderDeactivation(Fn2<Promise<Unit>, Promise<Unit>, TimeSpan> deadline) {
        return new LeaderDeactivation(new AtomicReference<>(Promise.success(unit())), deadline);
    }

    /// One delivered `LeaderChange`, on the route the toggles share. Deliveries come one at a time, in order, from the
    /// `LeaderManager` react executor, so this is the only writer of [#next].
    public Unit onLeaderChange(LeaderNotification.LeaderChange change) {
        return change.localNodeIsLeader()
               ? leadershipDelivered()
               : deactivationDelivered();
    }

    private Unit leadershipDelivered() {
        next.updateAndGet(LeaderDeactivation::pendingFrom);

        return unit();
    }

    private static Promise<Unit> pendingFrom(Promise<Unit> current) {
        return current.isResolved()
               ? Promise.promise()
               : current;
    }

    private Unit deactivationDelivered() {
        next.get().succeed(unit());

        return unit();
    }

    /// Resolves once the changes queued before the call were delivered, then a deactivation has been delivered to the toggles (at once if this node is not the leader), and then every
    /// reaction queued on `executor` before that point has run: the route that delivered it may still be running the toggles
    /// registered after this record's. Never fails: past `bound` it warns and resolves, so a wedged reaction or a leader that
    /// never steps down cannot hold the stop.
    public Promise<Unit> delivered(ReactExecutor executor, TimeSpan bound) {
        return deadline.apply(executor.drained()
                                      .flatMap(_ -> next.get())
                                      .flatMap(executor::drained),
                              bound)
                       .recover(cause -> undelivered(executor, bound, cause));
    }

    // FER: the stop goes on and quiesces the router; a deactivation still undelivered past the bound is dropped by the quiesce,
    // and the leader-toggled components it would have reached are reported active here rather than holding the stop forever.
    private static Unit undelivered(ReactExecutor executor, TimeSpan bound, Cause cause) {
        log.warn("Leader deactivation not delivered on {} within {}; the leader-toggled components may stay active: {}",
                 executor.component(),
                 bound,
                 cause.message());

        return unit();
    }
}
