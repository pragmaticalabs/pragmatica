// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.utils.SharedScheduler;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;


/// Node-side self-drain coordinator (membership-architecture-spec.md §16.1, scenarios
/// S19/S20).
///
/// Watches three independent triggers and, on the first one to fire, kicks off an
/// uninterruptible drain: gate the `InFlightRequestTracker`, await in-flight ≤ 0 for
/// up to `inflightGrace`, then exit the JVM via `Runtime.halt(2)`. The orchestrator's
/// restart policy decides whether to bring the node back (cluster A: auto-restart,
/// cluster B: `restart: "no"`).
///
/// Triggers (any one):
///
///   1. Periodic 1Hz: `connectedPeers().size() + 1 < (topologySize / 2) + 1` for
///      `triggerThreshold` consecutive seconds. Caller wires `onConnectivityChange()`
///      to a `SharedScheduler.scheduleAtFixedRate` tick.
///   2. `onQuorumDisappeared()` — invoked from a `QuorumStateNotification.DISAPPEARED`
///      route handler. Immediate; no debounce (quorum loss is the hard signal Rabia
///      itself emits when it can no longer make progress).
///   3. `onRabiaPaused()` — Rabia emits `EngineState.Paused` on quorum disappearance.
///      Currently re-uses the QuorumStateNotification.DISAPPEARED route (Rabia exposes
///      no separate paused-listener API in RC1). Kept as a distinct entry point so a
///      future Rabia listener can be wired without changing the FSM.
///
/// Phase state machine (single CAS guard per transition):
///
/// ```text
///   ACTIVE  ── initiateDrain() ─────▶  DRAINING  ── performExit() ──▶  EXITED
///                                          │
///                                          └── re-trigger → no-op (uninterruptible)
/// ```
///
/// Invariants:
///
///   * Uninterruptible: once `DRAINING`, no incoming trigger can revert to `ACTIVE`.
///     Quorum restoration mid-drain does NOT abort.
///   * `jvmExit` is invoked exactly once. The `DRAINING → EXITED` CAS guards against
///     the timeout fork racing the tracker-empty fork.
///   * No KV/consensus dependency: this coordinator does NOT import
///     `org.pragmatica.consensus.kvstore`, `org.pragmatica.kvstore`, or
///     `org.pragmatica.consensus.rabia` — a partition victim cannot rely on consensus
///     to drain itself. Asserted by `SelfDrainCoordinatorTest.noConsensusOrKvImports`.
public final class SelfDrainCoordinator {
    private static final Logger log = LoggerFactory.getLogger(SelfDrainCoordinator.class);

    public enum Phase {
        ACTIVE,
        DRAINING,
        EXITED
    }

    private final NodeId self;
    private final Supplier<Set<NodeId>> connectedPeers;
    private final IntSupplier topologySize;
    private final InFlightRequestTracker tracker;
    private final SelfDrainConfig config;
    private final Runnable jvmExit;
    private final SelfDrainEventPublisher eventPublisher;
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.ACTIVE);
    private final AtomicLong firstBelowQuorumMs = new AtomicLong(-1L);

    private SelfDrainCoordinator(NodeId self,
                                 Supplier<Set<NodeId>> connectedPeers,
                                 IntSupplier topologySize,
                                 InFlightRequestTracker tracker,
                                 SelfDrainConfig config,
                                 Runnable jvmExit,
                                 SelfDrainEventPublisher eventPublisher) {
        this.self = self;
        this.connectedPeers = connectedPeers;
        this.topologySize = topologySize;
        this.tracker = tracker;
        this.config = config;
        this.jvmExit = jvmExit;
        this.eventPublisher = eventPublisher;
    }

    /// Canonical factory. Caller supplies `jvmExit` explicitly — production passes
    /// `() -> Runtime.getRuntime().halt(2)`, Forge / single-JVM test runtimes supply a
    /// hook that signals the supervising driver instead (so a SelfDrain doesn't take down
    /// the entire test JVM along with all other in-process nodes).
    ///
    /// `eventPublisher` is the sink for the `SELF_DRAIN_INITIATED` cluster event emitted
    /// at the `ACTIVE → DRAINING` transition. The drain decision is made by the draining
    /// node itself; the event is NOT leader-gated upstream (the leader cannot publish
    /// self-drain on behalf of a partition victim — see `SelfDrainEventPublisher`).
    /// Tests that don't care about the event surface should pass
    /// `SelfDrainEventPublisher.NO_OP`.
    public static SelfDrainCoordinator selfDrainCoordinator(NodeId self,
                                                            Supplier<Set<NodeId>> connectedPeers,
                                                            IntSupplier topologySize,
                                                            InFlightRequestTracker tracker,
                                                            SelfDrainConfig config,
                                                            Runnable jvmExit,
                                                            SelfDrainEventPublisher eventPublisher) {
        return new SelfDrainCoordinator(self, connectedPeers, topologySize, tracker, config, jvmExit, eventPublisher);
    }

    /// Periodic 1Hz check: caller schedules this. Computes
    /// `threshold = (topologySize / 2) + 1` and `visible = connectedPeers + 1` (self
    /// counts). When `visible < threshold` for `triggerThreshold` consecutive
    /// observations, trips `initiateDrain()`. Resets on recovery.
    @Contract
    public void onConnectivityChange() {
        if (phase.get() != Phase.ACTIVE) {return;}

        var threshold = quorumThreshold();
        var visible = connectedPeers.get().size() + 1;
        routeOnVisibility(visible, threshold);
    }

    private void routeOnVisibility(int visible, int threshold) {
        if (visible <threshold) {
            recordBelowQuorum(visible, threshold);
        } else {
            recoverAboveQuorum();
        }
    }

    /// Hard trigger: `QuorumStateNotification.DISAPPEARED` from the local topology
    /// observer. No debounce — the topology layer has already concluded quorum is
    /// lost.
    @Contract
    public void onQuorumDisappeared() {
        if (phase.get() != Phase.ACTIVE) {return;}

        log.warn("Self-drain: QuorumStateNotification.DISAPPEARED received on {} — initiating drain", self.id());
        initiateDrain("quorum-disappeared");
    }

    /// Hard trigger: Rabia engine reported `Paused`. In RC1 this is wired to the same
    /// QuorumStateNotification route, but the entry point is kept distinct so a future
    /// Rabia-direct listener can be added without touching this class.
    @Contract
    public void onRabiaPaused() {
        if (phase.get() != Phase.ACTIVE) {return;}

        log.warn("Self-drain: Rabia paused on {} — initiating drain", self.id());
        initiateDrain("rabia-paused");
    }

    /// Current phase. Exposed for diagnostics (`/api/status` projection, tests).
    public Phase phase() {
        return phase.get();
    }

    private int quorumThreshold() {
        return (topologySize.getAsInt() / 2) + 1;
    }

    private void recordBelowQuorum(int visible, int threshold) {
        var nowMs = System.currentTimeMillis();
        var prev = firstBelowQuorumMs.get();

        if (prev <0) {
            firstBelowQuorumMs.compareAndSet(prev, nowMs);
            log.info("Self-drain: visible={} below quorum={} on {} — starting debounce window {}ms",
                     visible,
                     threshold,
                     self.id(),
                     config.triggerThreshold().millis());

            return;
        }

        var elapsedMs = nowMs - prev;

        if (elapsedMs >= config.triggerThreshold().millis()) {
            log.warn("Self-drain: visible={} below quorum={} on {} for {}ms — initiating drain",
                     visible,
                     threshold,
                     self.id(),
                     elapsedMs);
            initiateDrain("sustained-below-quorum");
        }
    }

    private void recoverAboveQuorum() {
        var prev = firstBelowQuorumMs.getAndSet(-1L);
        if (prev >= 0) {
            log.info("Self-drain: quorum visibility restored on {} — debounce window cleared", self.id());
        }
    }

    /// Package-private for direct test invocation of the CAS-guarded entry point.
    @Contract
    void initiateDrain(String reason) {
        if (!phase.compareAndSet(Phase.ACTIVE, Phase.DRAINING)) {return;}

        log.warn("Self-drain: DRAINING on {} (reason={}) — closing tracker gate, grace={}ms",
                 self.id(),
                 reason,
                 config.inflightGrace().millis());
        publishSelfDrainEvent(reason);
        tracker.setAcceptingNewWork(false);
        tracker.onAllDrained(this::onTrackerDrained);
        SharedScheduler.schedule(this::onGraceExpired, config.inflightGrace());
    }

    /// Surface the `SELF_DRAIN_INITIATED` cluster event. Best-effort: an exception from
    /// the publisher MUST NOT interrupt the drain sequence — the node is about to halt
    /// either way. The event is intentionally not leader-gated; the draining node itself
    /// is the only authoritative source for "I'm self-draining" (membership-architecture-
    /// spec.md §16.1).
    private void publishSelfDrainEvent(String reason) {
        try {
            eventPublisher.publish(reason,
                                   Map.of("nodeId",
                                          self.id(),
                                          "reason",
                                          reason,
                                          "graceMs",
                                          String.valueOf(config.inflightGrace().millis())));
        } catch (Throwable t) {
            log.warn("Self-drain: SELF_DRAIN_INITIATED publish failed on {} (reason={}): {} — drain proceeds regardless",
                     self.id(),
                     reason,
                     t.getMessage());
        }
    }

    private void onTrackerDrained() {
        log.warn("Self-drain: in-flight tracker drained on {} — exiting", self.id());
        performExit();
    }

    private void onGraceExpired() {
        if (phase.get() == Phase.DRAINING) {
            log.warn("Self-drain: grace expired on {} with in-flight={} — forcing exit", self.id(), tracker.count());
        }
        performExit();
    }

    private void performExit() {
        if (!phase.compareAndSet(Phase.DRAINING, Phase.EXITED)) {return;}
        jvmExit.run();
    }
}
