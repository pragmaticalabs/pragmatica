// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.aether.deployment.membership.MembershipConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.lang.utils.TimeSource;
import org.pragmatica.swim.SwimObservation;
import org.pragmatica.swim.SwimObservation.DepartedObserved;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// Node Topology Tracker (membership v2 spec §6, I12). Per-peer one-shot timer component
/// that subscribes to SWIM-converged departure observations and emits a local
/// [`TopologyUnhealthyEvent`] when the timer expires without a QUIC reconnect.
///
/// **Mechanism only.** NTT is universal — it runs on every node and observes regardless of
/// leader status. The observation-only feature gate ([`NttObservationFlag`]) is enforced
/// upstream by the wiring layer; NTT itself does not consult the flag. The leader's CTM is
/// the sole consumer of fired events (see spec §6.3).
///
/// **Inputs.**
/// - [`#onSwimObservation`] — filters for `DepartedObserved`, schedules a per-peer timer
///   (idempotent: a duplicate departure for an already-tracked peer is a no-op; the deadline
///   is NOT re-stamped, matching spec §6.2 "first-departure-wins").
/// - [`#onQuicReconnect`] — cancels any pending timer for that peer AND removes any
///   unclaimed fired event (cleanup when a peer transitions Departed → reconnected after the
///   timer already fired).
///
/// **Outputs.**
/// - [`#claim`] — atomic remove-and-return of any fired event for `peer`. Returns `none()`
///   if no event is pending. Single-shot per fire (the entry is removed on claim).
/// - [`#drainAllFiredEvents`] — atomic snapshot+clear of all currently-fired events, used by
///   the leader's reconciliation tick when it activates to absorb the accumulated map.
///
/// **Observability.** [`#pendingTimerCount`] / [`#firedEventCount`] expose the in-memory map
/// sizes. Stage 6 will adapt these into metrics; Stage 5 wires divergence-logging.
///
/// **Concurrency.** All state is held in a single `ConcurrentHashMap<NodeId, NttPendingEntry>`.
/// `onSwimObservation`, `onQuicReconnect`, `claim`, `drainAllFiredEvents`, and the scheduler-
/// thread fire-callback can run concurrently — every state transition is expressed via the
/// atomic `compute`/`computeIfPresent`/`remove` primitives so no synchronized block or lock is
/// required. The single-map design (entry carries both the `ScheduledFuture` and an
/// `Option<TopologyUnhealthyEvent>`) lets the QUIC-reconnect cleanup path be a single
/// `remove` call regardless of whether the timer has fired yet.
///
/// **QUIC-reconnect event source.** Stage 6 will adapt this method's `onQuicReconnect(NodeId)`
/// from `org.pragmatica.consensus.net.quic.PeerConnectivityReporter#onPeerConnected`
/// (`integrations/consensus/.../PeerConnectivityReporter.java:40`) — the connect-up edge the
/// transport fires on `PeerState.Phase.CONNECTED` (initial handshake completion OR reconnection).
/// NTT consumes a single-arg `NodeId` form because the term/counter epoch fields are leader-
/// aggregator concerns; NTT only needs the identity that came up.
public final class NodeTopologyTracker {
    private static final Consumer<TopologyUnhealthyEvent> NOOP_FIRE_LISTENER = event -> {};

    private final MembershipConfig config;
    private final TimeSource timeSource;
    private final NttTimerScheduler scheduler;
    private final Map<NodeId, NttPendingEntry> entries = new ConcurrentHashMap<>();
    private volatile Consumer<TopologyUnhealthyEvent> onTimerFireListener = NOOP_FIRE_LISTENER;

    private NodeTopologyTracker(MembershipConfig config, TimeSource timeSource, NttTimerScheduler scheduler) {
        this.config = config;
        this.timeSource = timeSource;
        this.scheduler = scheduler;
    }

    /// Production factory bound to the process-wide [`SharedScheduler`] and the system clock.
    public static NodeTopologyTracker nodeTopologyTracker(MembershipConfig config) {
        return new NodeTopologyTracker(config, TimeSource.system(), SharedScheduler::schedule);
    }

    /// Production factory with an explicit [`TimeSource`] (e.g., a shared clock injected from
    /// the node-wide HLC physical source).
    public static NodeTopologyTracker nodeTopologyTracker(MembershipConfig config, TimeSource timeSource) {
        return new NodeTopologyTracker(config, timeSource, SharedScheduler::schedule);
    }

    /// Test factory accepting an explicit scheduler — required for deterministic timer-fire
    /// without wall-clock advancement.
    public static NodeTopologyTracker nodeTopologyTracker(MembershipConfig config,
                                                          TimeSource timeSource,
                                                          NttTimerScheduler scheduler) {
        return new NodeTopologyTracker(config, timeSource, scheduler);
    }

    /// SWIM observation entry point. Only [`DepartedObserved`] starts a timer; all other
    /// observation kinds are ignored. Re-arming on duplicate departure is a no-op
    /// (`computeIfAbsent`-style guard).
    @Contract
    public void onSwimObservation(SwimObservation observation) {
        if (observation instanceof DepartedObserved departed) {
            scheduleIfAbsent(departed.peer());
        }
    }

    /// QUIC reconnect entry point. Cancels any pending timer AND clears any unclaimed fired
    /// event for `peer`. Atomic remove — a single map removal handles both states.
    @Contract
    public void onQuicReconnect(NodeId peerId) {
        var removed = entries.remove(peerId);

        option(removed).onPresent(NttPendingEntry::cancelTimer);
    }

    /// Atomic claim of any pending fired event for `peer`. Returns `some(event)` if a fired
    /// event was waiting (and removes it); `none()` otherwise. A still-pending timer is left
    /// in place — claim is single-shot per fire.
    public Option<TopologyUnhealthyEvent> claim(NodeId peerId) {
        var holder = new EventHolder();

        entries.computeIfPresent(peerId, (key, entry) -> consumeFiredOrKeep(entry, holder));

        return holder.take();
    }

    private static NttPendingEntry consumeFiredOrKeep(NttPendingEntry entry, EventHolder holder) {
        var fired = entry.firedEvent();

        if (fired.isEmpty()) {
            return entry;
        }
        fired.onPresent(holder::set);

        return null;
    }

    /// Atomic snapshot+clear of all currently-fired events. Pending timers are left in place.
    /// Used by the leader's reconciliation tick when it activates to absorb the accumulated
    /// map (spec §6.3). The drain is per-entry atomic (each `computeIfPresent` is atomic),
    /// not whole-map atomic — a fire racing the drain may land in either this batch or the
    /// next; that's the contract callers expect from a "drain all currently fired" operation
    /// against a live mutating map.
    public List<TopologyUnhealthyEvent> drainAllFiredEvents() {
        var drained = new ArrayList<TopologyUnhealthyEvent>();

        for (var peer : List.copyOf(entries.keySet())) {
            entries.computeIfPresent(peer, (key, current) -> drainFiredOrKeep(current, drained));
        }

        return List.copyOf(drained);
    }

    private static NttPendingEntry drainFiredOrKeep(NttPendingEntry current, List<TopologyUnhealthyEvent> drained) {
        var fired = current.firedEvent();

        if (fired.isEmpty()) {
            return current;
        }
        fired.onPresent(drained::add);

        return null;
    }

    /// Count of currently-tracked entries with a not-yet-fired timer.
    public int pendingTimerCount() {
        return (int) entries.values().stream().filter(entry -> entry.firedEvent().isEmpty()).count();
    }

    /// Count of currently-tracked entries with a fired but not-yet-claimed event.
    public int firedEventCount() {
        return (int) entries.values().stream().filter(entry -> entry.firedEvent().isPresent()).count();
    }

    /// Stage 6 wiring hook — register a consumer fired once per timer expiry, alongside the
    /// internal map-put of the [`TopologyUnhealthyEvent`]. Used by [`LeaderReconciler`] to
    /// trigger an immediate reconcile pass without polling [`#claim`]. Non-blocking — the
    /// listener runs on the scheduler thread that fired the timer.
    @Contract
    public void setOnTimerFireListener(Consumer<TopologyUnhealthyEvent> listener) {
        onTimerFireListener = listener;
    }

    private void scheduleIfAbsent(NodeId peerId) {
        entries.computeIfAbsent(peerId, this::armEntry);
    }

    private NttPendingEntry armEntry(NodeId peerId) {
        var future = scheduler.schedule(() -> onTimerFire(peerId), config.nttDepartureTimeout());

        return new NttPendingEntry(future, none());
    }

    private void onTimerFire(NodeId peerId) {
        var firedAt = timeSource.nanoTime();
        var event = new TopologyUnhealthyEvent(peerId, firedAt);

        entries.computeIfPresent(peerId, (key, entry) -> entry.markFired(event));
        onTimerFireListener.accept(event);
    }

    /// Per-peer state held in the entries map. Immutable record — every state transition
    /// produces a new entry (idiomatic with `ConcurrentHashMap.compute*`).
    private record NttPendingEntry(ScheduledFuture<?> future, Option<TopologyUnhealthyEvent> firedEvent) {
        NttPendingEntry markFired(TopologyUnhealthyEvent event) {
            return new NttPendingEntry(future, some(event));
        }

        @Contract
        void cancelTimer() {
            future.cancel(false);
        }
    }

    /// Single-shot holder used by [`#claim`] to ferry a value out of the
    /// `computeIfPresent` lambda without `[]`-array trickery and without breaking the
    /// "no nulls" rule on the return path.
    private static final class EventHolder {
        private Option<TopologyUnhealthyEvent> value = none();

        @Contract
        void set(TopologyUnhealthyEvent event) {
            value = some(event);
        }

        Option<TopologyUnhealthyEvent> take() {
            return value;
        }
    }
}
