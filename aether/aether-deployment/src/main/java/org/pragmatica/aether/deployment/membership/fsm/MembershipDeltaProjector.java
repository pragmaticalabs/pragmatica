// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.membership.ntt.NttTimerScheduler;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Pure projection of the [`MembershipFsm`] delta edge onto the cluster-canonical
/// `MembershipDecision` stream (cluster-topology-overhaul spec, Wave 4 — the SOLE emitter of
/// `MembershipDecision`, replacing `TopologyObserver.publishMembershipDeltas` and its
/// `previousCoreMembers` baseline diff).
///
/// **Why this closes #245:** the old baseline diff ran only inside
/// `evaluateQuorumState` (five triggers), so a CTM replacement promoted OBSERVED→MEMBER via
/// presence sampling was never baselined as ADDED and its death diffed out to nothing — no
/// `NodeRemoved`, no `NODE_FAILED`. This projector consumes the FSM's own per-edge
/// [`MembershipDeltaEdge`] notifications instead: every first promotion is baselined, every
/// death of a baselined member emits, with no dependence on the quorum evaluator's triggers.
///
/// **§3.1 hard constraint (bisect-proven):** delta emission is fully decoupled from
/// `evaluateQuorumState`. The JOINED edge routes a `NodeJoined` decision and touches the
/// quorum evaluator in no way whatsoever. The REMOVED edge routes `NodeRemoved` and prunes the
/// departed peer via `TopologyObserver.pruneDeparted` → `removeNode` — `removeNode` is one of
/// the evaluator's PRE-EXISTING triggers (it was reached identically by the old
/// `publishCoreMembershipDelta` removal arm), so no NEW path into `evaluateQuorumState` is
/// introduced and the promotion edge can never reach it.
///
/// **Quorum gate / A7 — drainer-gated, self-draining (follower-starvation fix).** The FIFO
/// queue IS the pending store: edges are enqueued unconditionally and [`#inQuorum`] is
/// consulted in EXACTLY ONE place — the single-drainer dequeue gate ([`#drainQueue`]). While
/// non-quorate the drainer simply does not dequeue (nothing is emitted, the announced
/// baseline does not advance — the old suppression semantics); the moment it observes quorum
/// it drains everything in arrival order. Drainage cannot depend on any single signal — three
/// independent paths re-poke the drainer:
/// 1. **Every incoming edge** ([`#onDelta`]) — queued pendings drain FIRST (FIFO order), then
///    the fresh edge.
/// 2. **A deduped one-shot flush retry** ([`#armFlushRetry`], [`#FLUSH_RETRY_DELAY`]) —
///    armed whenever a drain attempt stops with a non-empty queue while non-quorate; re-arms
///    only while undrained work remains, terminates the moment the queue drains. This is
///    what un-wedges a FOLLOWER whose boot edges all complete BEFORE its local quorum flip
///    and whose steady-state FSM then emits no further edges — the live-gate starvation: the
///    one-shot `ClusterStateNotification` ACTIVE echo was the sole remaining flush path and
///    never connected with pendings+quorum on followers, freezing their pendings forever.
/// 3. **The shared-bus `ClusterStateNotification` ACTIVE echo** — kept as belt-and-braces.
///
/// **Suppressed-window replay semantics.** Because the queue preserves the raw edge sequence,
/// a membership episode that both opens and closes inside a non-quorate window is REPLAYED in
/// order on drain (`NodeJoined` then `NodeRemoved`) rather than collapsed to nothing as the
/// old frozen-baseline diff did. The final announced state is identical, the replay is
/// strictly ordered and exactly-once per episode (the FSM's `everJoined` pairing), and it
/// occurs only for episodes fully inside a cluster-wide non-quorate window.
///
/// **Core-scoped baseline (Wave 2 / A8), and the non-core channel (#728).** A JOINED edge whose
/// descriptor role is the explicit literal `worker` does not enter the core baseline — worker joins
/// do not perturb the core delta (blank/unknown role counts as core,
/// [`MemberDescriptor#isCoreRole`]). It is NOT discarded, however: it is routed to the separate
/// [`WorkerJoinDecision`] channel, which is what gives `ClusterDeploymentState.assignNodeRole` its
/// trigger for role assignment and community minting. Before #728 this edge was dropped outright,
/// and since this projector is the sole emitter of `MembershipDecision.NodeJoined`, a labelled
/// worker could never be assigned a role, minted a community, or activated — it reached FSM Member
/// and stopped there. The two channels are deliberately separate types: `NodeJoined` carries a
/// `topology()` core snapshot that consumers assign directly to their topology state, so a worker
/// must never travel on it. REMOVED is keyed on the announced baseline (NOT the role at death), so
/// the join/remove pairing stays symmetric even across a role relabel, and a never-announced
/// member's death emits nothing (the FSM's `everJoined` already filters never-JOINED deaths; this
/// is the projector-side mirror).
///
/// **Threading (single-drainer actor).** [`#onDelta`] is invoked under the per-member FSM
/// monitor and MUST stay cheap: it enqueues into the FIFO and returns. A single CAS-elected
/// drainer processes the queue off-monitor (production: one short-lived virtual thread per
/// drain burst), so routing decisions to subscribers can never deadlock against per-member
/// monitors, and per-member edge order is preserved (edges for one member are enqueued under
/// its monitor, in order). All projection state (the `announced` baseline) is drainer-confined.
public final class MembershipDeltaProjector {
    private static final Logger log = LoggerFactory.getLogger(MembershipDeltaProjector.class);
    /// Flush-retry spacing: one short, fixed delay between suppressed-drain re-checks. Cheap
    /// (one quorum-bit read + one queue check per tick), deduped to a single outstanding
    /// timer, armed only while undrained work exists — a bounded convergence loop, never a
    /// periodic tick.
    private static final TimeSpan FLUSH_RETRY_DELAY = timeSpan(1).seconds();

    private final BooleanSupplier inQuorum;
    private final LongSupplier logIndexSupplier;
    private final Supplier<HlcTimestamp> hlcSupplier;
    private final Consumer<MembershipDecision> decisionSink;
    private final Consumer<WorkerJoinDecision> workerJoinSink;
    private final Consumer<WorkerLeaveDecision> workerLeaveSink;
    private final Consumer<NodeId> pruneDeparted;
    private final Consumer<Runnable> drainExecutor;
    private final NttTimerScheduler retryScheduler;
    /// The pending store: raw FSM edges in arrival order. Dequeued ONLY by the single
    /// drainer, and ONLY while [`#inQuorum`] reports true.
    private final ConcurrentLinkedQueue<MembershipDeltaEdge> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean draining = new AtomicBoolean(false);
    /// At most one outstanding flush retry (drainage path 2, class doc). Deduped via this ref
    /// (non-null short-circuits arming; the CAS-arm cancels a lost race); cleared when the
    /// retry fires. Self-terminating: re-armed only by a drain attempt that stops with a
    /// non-empty queue while non-quorate.
    private final AtomicReference<ScheduledFuture<?>> flushRetryFutureRef = new AtomicReference<>();
    /// Drainer-confined: core members announced to the bus as `NodeJoined` and not yet removed.
    /// The decision-stream baseline — `topology()` payloads are snapshots of this set.
    private final LinkedHashSet<NodeId> announced = new LinkedHashSet<>();
    /// Drainer-confined, and deliberately SEPARATE from [`#announced`] (#728): workers announced on
    /// the [`WorkerJoinDecision`] channel. Kept out of the core baseline so no `topology()` payload
    /// can ever contain a worker; kept at all so a worker join is emitted exactly once, mirroring
    /// the core arm's idempotence guard.
    private final LinkedHashSet<NodeId> announcedWorkers = new LinkedHashSet<>();
    /// Read-only diagnostic snapshot of [`#announced`] (Wave-1 baseline-trace successor).
    private volatile Set<NodeId> announcedView = Set.of();

    private MembershipDeltaProjector(BooleanSupplier inQuorum,
                                     LongSupplier logIndexSupplier,
                                     Supplier<HlcTimestamp> hlcSupplier,
                                     Consumer<MembershipDecision> decisionSink,
                                     Consumer<WorkerJoinDecision> workerJoinSink,
                                     Consumer<WorkerLeaveDecision> workerLeaveSink,
                                     Consumer<NodeId> pruneDeparted,
                                     Consumer<Runnable> drainExecutor,
                                     NttTimerScheduler retryScheduler) {
        this.inQuorum = inQuorum;
        this.logIndexSupplier = logIndexSupplier;
        this.hlcSupplier = hlcSupplier;
        this.decisionSink = decisionSink;
        this.workerJoinSink = workerJoinSink;
        this.workerLeaveSink = workerLeaveSink;
        this.pruneDeparted = pruneDeparted;
        this.drainExecutor = drainExecutor;
        this.retryScheduler = retryScheduler;
    }

    /// Production factory. `inQuorum` is `TopologyObserver.inQuorum()` (the SAME
    /// `quorumEstablished` bit the old emission gate read); `logIndexSupplier` is
    /// `GenerationSnapshotSource::observedRabiaTerm` and `hlcSupplier` the node HLC clock
    /// (identical stamping semantics to the old `publishMembershipDeltas`); `decisionSink`
    /// routes onto the node-local shared bus (`MessageRouter::route`); `pruneDeparted` is
    /// `TopologyObserver::pruneDeparted`. `workerJoinSink` routes the non-core
    /// [`WorkerJoinDecision`] channel (#728) onto that same bus, where the cluster deployment FSM
    /// consumes it for role assignment and community minting; `workerLeaveSink` is its symmetric
    /// counterpart (#731), routing [`WorkerLeaveDecision`] so the FSM reclaims a departed worker's
    /// allocation-pool slot and KV footprint. Each drain burst runs on a short-lived virtual
    /// thread, off every FSM per-member monitor; the flush retry rides the shared scheduler.
    public static MembershipDeltaProjector membershipDeltaProjector(BooleanSupplier inQuorum,
                                                                    LongSupplier logIndexSupplier,
                                                                    Supplier<HlcTimestamp> hlcSupplier,
                                                                    Consumer<MembershipDecision> decisionSink,
                                                                    Consumer<WorkerJoinDecision> workerJoinSink,
                                                                    Consumer<WorkerLeaveDecision> workerLeaveSink,
                                                                    Consumer<NodeId> pruneDeparted) {
        return membershipDeltaProjector(inQuorum,
                                        logIndexSupplier,
                                        hlcSupplier,
                                        decisionSink,
                                        workerJoinSink,
                                        workerLeaveSink,
                                        pruneDeparted,
                                        Thread::startVirtualThread,
                                        SharedScheduler::schedule);
    }

    /// Test factory with an injectable drain executor and retry scheduler — pass
    /// `Runnable::run` and a manual scheduler for a fully synchronous, deterministic projector.
    public static MembershipDeltaProjector membershipDeltaProjector(BooleanSupplier inQuorum,
                                                                    LongSupplier logIndexSupplier,
                                                                    Supplier<HlcTimestamp> hlcSupplier,
                                                                    Consumer<MembershipDecision> decisionSink,
                                                                    Consumer<WorkerJoinDecision> workerJoinSink,
                                                                    Consumer<WorkerLeaveDecision> workerLeaveSink,
                                                                    Consumer<NodeId> pruneDeparted,
                                                                    Consumer<Runnable> drainExecutor,
                                                                    NttTimerScheduler retryScheduler) {
        return new MembershipDeltaProjector(inQuorum,
                                            logIndexSupplier,
                                            hlcSupplier,
                                            decisionSink,
                                            workerJoinSink,
                                            workerLeaveSink,
                                            pruneDeparted,
                                            drainExecutor,
                                            retryScheduler);
    }

    /// FSM delta-edge ingress — wired to [`MembershipFsm#onMembershipDelta`]. Called under the
    /// per-member monitor: enqueue + CAS-schedule only, constant-time, non-blocking. Drainage
    /// path 1 — queued pendings drain first (FIFO), then this edge, whenever the drainer
    /// observes quorum.
    @Contract
    public void onDelta(MembershipDeltaEdge edge) {
        queue.add(edge);
        scheduleDrain();
    }

    /// Drainage path 3 (belt-and-braces) — the shared-bus `ClusterStateNotification` ACTIVE
    /// echo (the ConsensusBridge re-emission of engine activation) re-pokes the drainer.
    /// PASSIVE is ignored: the drainer's dequeue gate suppresses on its own by reading
    /// [`#inQuorum`] live.
    @Contract
    public void onClusterStateNotification(ClusterStateNotification notification) {
        if (notification.state() != ClusterStateNotification.State.ACTIVE) {
            return;
        }

        scheduleDrain();
    }

    /// Diagnostic read-only snapshot of the announced core-member baseline (the successor of
    /// the deleted `TopologyObserver.previousCoreMembersSnapshot` Wave-1 trace surface).
    public Set<NodeId> announcedCoreMembers() {
        return announcedView;
    }

    /// Elect a single drainer via CAS; everyone else's work is picked up by the active one.
    @Contract
    private void scheduleDrain() {
        if (draining.compareAndSet(false, true)) {
            drainExecutor.accept(this::drainLoop);
        }
    }

    /// Single-drainer loop. [`#drainQueue`] dequeues only while quorate; after releasing the
    /// drain flag: an empty queue terminates; undrained work while NON-quorate arms the
    /// deduped flush retry (which owns the wakeup — looping here would spin hot); undrained
    /// work while quorate re-elects (standard lost-wakeup guard). All projection-state
    /// mutation happens here, strictly single-threaded, off every FSM per-member monitor.
    @Contract
    private void drainLoop() {
        while (true) {
            drainQueue();
            draining.set(false);
            if (queue.isEmpty()) {
                return;
            }

            if (!inQuorum.getAsBoolean()) {
                armFlushRetry();

                return;
            }

            if (!draining.compareAndSet(false, true)) {
                return;
            }
        }
    }

    /// The ONLY place [`#inQuorum`] gates anything: dequeue-while-quorate. A mid-drain quorum
    /// loss stops dequeuing immediately; the remaining edges stay queued for the retry.
    @Contract
    private void drainQueue() {
        while (inQuorum.getAsBoolean()) {
            var edge = queue.poll();

            if (edge == null) {
                return;
            }

            process(edge);
        }
    }

    /// Arm the deduped one-shot flush retry (drainage path 2). Hot-loop safe: a non-null
    /// [`#flushRetryFutureRef`] short-circuits (at most one outstanding), the CAS-arm cancels
    /// a lost race, the delay is the fixed [`#FLUSH_RETRY_DELAY`] floor, and the only re-arm
    /// site is a drain attempt that still sees undrained suppressed work — so the retry stops
    /// being armed the moment the queue drains.
    @Contract
    private void armFlushRetry() {
        if (flushRetryFutureRef.get() != null) {
            return;
        }

        var future = retryScheduler.schedule(this::runFlushRetry, FLUSH_RETRY_DELAY);

        if (!flushRetryFutureRef.compareAndSet(null, future)) {
            future.cancel(false);
        }
    }

    /// Flush-retry tick: clear the ref, then re-poke the drainer iff undrained work remains.
    /// If the node is STILL non-quorate the drain attempt re-arms the retry ([`#drainLoop`]);
    /// once quorum is observed the queue drains fully, in order, and no further retry is armed.
    // JBCT-RET-08: AtomicReference clear — null is the JDK sentinel, not Option-wrappable
    @SuppressWarnings("JBCT-RET-08")
    @Contract
    private void runFlushRetry() {
        flushRetryFutureRef.set(null);
        if (queue.isEmpty()) {
            return;
        }

        scheduleDrain();
    }

    @Contract
    private void process(MembershipDeltaEdge edge) {
        switch (edge.kind()) {
            case JOINED -> processJoined(edge);
            case REMOVED -> processRemoved(edge.node());
        }
    }

    /// JOINED: core-role edges advance the core baseline and emit `NodeJoined`; non-core (worker)
    /// edges are routed to the separate [`WorkerJoinDecision`] channel instead (#728) and never
    /// touch the core baseline. An already-announced member is idempotently skipped on whichever
    /// channel owns it (the FSM's `everJoined` makes duplicates structurally impossible — these
    /// guards are the projector-side mirror).
    @Contract
    private void processJoined(MembershipDeltaEdge edge) {
        if (!MemberDescriptor.isCoreRole(edge.role())) {
            emitWorkerJoin(edge);

            return;
        }

        if (announced.contains(edge.node())) {
            return;
        }

        emitJoin(edge.node());
    }

    /// REMOVED: a never-announced, never-worker member emits nothing (never JOINED at all — the
    /// tangential consideration's projector-side mirror); a departed worker emits
    /// [`WorkerLeaveDecision`] (#731, symmetric to [`#emitWorkerJoin`]); an announced core member
    /// emits `NodeRemoved` + prunes.
    ///
    /// The worker baseline is consulted (and pruned) unconditionally first (#728, extended #731).
    /// It MUST be, independently of whether anything is emitted: `announcedWorkers` is a once-only
    /// guard, so a worker that departed while still recorded there would be silently swallowed on
    /// rejoin and never re-assigned a role — the same class of silent non-participation #728 itself
    /// was. Removal is keyed on the baselines rather than on the role at death, so this stays
    /// correct across a role relabel, and pruning a node that was never a worker is a no-op. A node
    /// is a member of at most one baseline (`processJoined` branches strictly on
    /// `MemberDescriptor.isCoreRole`), so the two arms below are mutually exclusive.
    @Contract
    private void processRemoved(NodeId node) {
        var wasWorker = announcedWorkers.remove(node);

        if (announced.contains(node)) {
            emitRemoval(node);

            return;
        }

        if (wasWorker) {
            emitWorkerLeave(node);
        }
    }

    /// The non-core arm of [`#processJoined`] (#728). Deliberately does NOT touch [`#announced`]
    /// or `announcedView`: the core baseline stays worker-free, so no `topology()` payload can
    /// ever carry a worker. Emitted from the drain path like every other decision, so it inherits
    /// the quorum gate and FIFO ordering — which matters, because the consumer answers this by
    /// submitting KV commands into consensus.
    @Contract
    private void emitWorkerJoin(MembershipDeltaEdge edge) {
        if (!announcedWorkers.add(edge.node())) {
            return;
        }

        var stampedAt = hlcSupplier.get();

        log.debug("Membership delta: WorkerJoined {} (role={}, stampedAt={})", edge.node(), edge.role(), stampedAt);
        workerJoinSink.accept(WorkerJoinDecision.workerJoinDecision(edge.node(), edge.role(), stampedAt));
    }

    /// The non-core arm of [`#processRemoved`] (#731), symmetric to [`#emitWorkerJoin`].
    /// Deliberately does NOT touch [`#announced`] or `announcedView`: the core baseline was never
    /// touched by this worker's presence either, so its absence must not touch it. No idempotence
    /// guard is needed here the way [`#emitWorkerJoin`] needs `announcedWorkers.add` — the caller
    /// already consumed the once-only `announcedWorkers.remove` guard before calling this, so a
    /// second REMOVED edge for the same node finds `wasWorker` false and never reaches here.
    @Contract
    private void emitWorkerLeave(NodeId node) {
        var stampedAt = hlcSupplier.get();

        log.debug("Membership delta: WorkerLeft {} (stampedAt={})", node, stampedAt);
        workerLeaveSink.accept(WorkerLeaveDecision.workerLeaveDecision(node, stampedAt));
    }

    @Contract
    private void emitJoin(NodeId node) {
        announced.add(node);
        announcedView = Set.copyOf(announced);
        var topology = List.copyOf(announced);
        var logIndex = logIndexSupplier.getAsLong();
        var stampedAt = hlcSupplier.get();

        log.debug("Membership delta: NodeJoined {} (logIndex={}, stampedAt={})", node, logIndex, stampedAt);
        decisionSink.accept(MembershipDecision.nodeJoined(node, topology, logIndex, stampedAt));
    }

    /// Emission + prune mirror the old `publishCoreMembershipDelta` removal arm exactly:
    /// route `NodeRemoved`, then prune via the observer's existing `removeNode` path
    /// (`DisconnectNode` + the evaluator's PRE-EXISTING `removeNode` trigger).
    @Contract
    private void emitRemoval(NodeId node) {
        announced.remove(node);
        announcedView = Set.copyOf(announced);
        var topology = List.copyOf(announced);
        var logIndex = logIndexSupplier.getAsLong();
        var stampedAt = hlcSupplier.get();

        log.debug("Membership delta: NodeRemoved {} (logIndex={}, stampedAt={})", node, logIndex, stampedAt);
        decisionSink.accept(MembershipDecision.nodeRemoved(node, topology, logIndex, stampedAt));
        pruneDeparted.accept(node);
    }
}
