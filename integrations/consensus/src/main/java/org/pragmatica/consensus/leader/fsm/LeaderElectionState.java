/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ElectionTick;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ProposalSettled;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/// Sealed state hierarchy for the leader-election FSM. Each state is a record bound to the
/// shared [`LeaderElectionContext`]. Data-free states (Dormant, QuorumLost, Stopped) are
/// instantiated once per FSM and stored on the context (the "per-FSM singleton" pattern).
/// Data-carrying states are instantiated fresh on every entry:
///
/// - `Led(leader)` — `leader` identifies the leadership tenure.
/// - `Electing(tickFuture, proposalTimeoutFuture)` and `ReElecting(...)` — each owns the
///   `ScheduledFuture` references for the eagerly-scheduled election tick and the in-flight
///   proposal-timeout dispatcher. Both futures are cancelled in `onExit` / `onCasLost`, so a
///   stale tick from a prior tenure can never fire after the FSM has moved on.
/// - `QuorumWaiting(pollFuture)` — owns a periodic `ScheduledFuture` that re-queries
///   [`LeaderElectionContext#consensusReadySupplier`] at a 1Hz cadence. The supplier wraps a
///   level signal (e.g. `RabiaEngine::isActive`); the periodic re-check eliminates the
///   dead-time between consensus engine activation and the next external topology event.
///   The future is cancelled in `onExit` / `onCasLost`.
///
/// All states accept [`ClusterFsmEvent`] as their event type; leader-election domain events
/// ([`LeaderElectionEvents`]) also implement `ClusterFsmEvent` so they flow through the same
/// dispatch path.
public sealed interface LeaderElectionState extends FsmState<LeaderElectionState, ClusterFsmEvent>
        permits LeaderElectionState.Dormant,
                LeaderElectionState.QuorumWaiting,
                LeaderElectionState.AwaitingKvSync,
                LeaderElectionState.Electing,
                LeaderElectionState.Led,
                LeaderElectionState.ReElecting,
                LeaderElectionState.QuorumLost,
                LeaderElectionState.Stopped {

    Logger log = LoggerFactory.getLogger(LeaderElectionState.class);

    /// Marker no-op used in `tx.handle(...)` arms where an event is intentionally absorbed
    /// without a transition or side-effect. Distinct from `tx.ignore()` to make the explicit
    /// "handled, but no work to do" cases legible at the call site.
    Runnable NO_ACTION_DORMANT = () -> {};

    LeaderElectionContext ctx();

    /// Dispatches an event to this FSM instance through the Fsm reference stored in the context.
    /// The reference is bound at Context construction time via the constructor-driven initial-state
    /// factory, so it is always present when this method runs.
    private static void dispatchSelf(LeaderElectionContext ctx, ClusterFsmEvent event) {
        ctx.fsm().dispatch(event);
    }

    // --- State records ---

    record Dormant(LeaderElectionContext ctx) implements LeaderElectionState {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case ClusterFsmEvent.QuorumEstablished _ -> tx.transitionTo(ctx.quorumWaiting());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                // ConsensusReady arriving in Dormant is acknowledged but causes no transition —
                // the SSOT for consensus readiness is the consensus engine itself, queried on
                // entry to QuorumWaiting via `ctx.consensusReadySupplier()`.
                case ConsensusReady _ -> tx.handle(NO_ACTION_DORMANT);
                case ClusterFsmEvent.NodeAdded na -> tx.handle(() -> ctx.setCurrentTopology(na.topology()));
                case ClusterFsmEvent.NodeGone ng -> tx.handle(() -> ctx.setCurrentTopology(ng.topology()));
                default -> tx.ignore();
            }
        }
    }

    /// Data-carrying state. Fresh instance per entry; holds an [`AtomicReference`] for a
    /// periodic `ScheduledFuture` that re-queries [`LeaderElectionContext#consensusReadySupplier`]
    /// at a 1Hz cadence. The supplier wraps a level signal (e.g. `RabiaEngine::isActive`) — the
    /// periodic re-check is the only path by which the FSM observes the false→true transition
    /// when no incidental topology event arrives. The future is cancelled in `onExit` /
    /// `onCasLost` to prevent leaks. The synchronous check on entry is preserved so the fast
    /// path doesn't pay any polling latency.
    record QuorumWaiting(LeaderElectionContext ctx,
                         AtomicReference<ScheduledFuture<?>> pollFuture) implements LeaderElectionState {

        /// Polling interval for the consensus-readiness re-check. The FSM bounds retries via
        /// state transitions (any `QuorumDisappeared` / `Shutdown` cancels the timer; any
        /// `NodeAdded` / `NodeGone` keeps the timer alive on the same record).
        public static final TimeSpan POLL_INTERVAL = TimeSpan.timeSpan(1).seconds();

        public static QuorumWaiting fresh(LeaderElectionContext ctx) {
            return new QuorumWaiting(ctx, new AtomicReference<>());
        }

        @Override
        public void onEntry() {
            // R6 always-listen: pull KV for a committed leader on every entry. If a peer has
            // already committed a leader visible in the local KV (e.g. a re-joining node whose
            // snapshot included `LeaderKey`), we must observe it here — without this pull, the
            // FSM would advance to AwaitingKvSync and re-discover it, but the direct path is
            // both shorter and matches the spec's "always-listening KV poll across all states"
            // contract. `adoptLeaderFromKvIfPresent` synthesizes `LeaderCommitted`; this state's
            // handler arm transitions to `Led(thatLeader)` synchronously.
            adoptLeaderFromKvIfPresent(ctx);
            // If the synchronous dispatch already advanced us out of QuorumWaiting (i.e. we're
            // now in Led), skip scheduling the poll — the new state owns its own timers.
            // We compare to `this` rather than asserting `instanceof QuorumWaiting` to keep
            // the `fresh` + manual-`onEntry` test pattern (used elsewhere for `onCasLost`
            // coverage) working without changes — when invoked outside the FSM the current
            // state is whatever the harness left in place, and the "did we transition out"
            // semantics is "is the FSM still pointing at this exact record?".
            if (ctx.fsm().current() != this && ctx.currentLeader().isPresent()) {
                return;
            }
            // Query the consensus engine's readiness state directly (SSOT). If consensus is
            // already active we synthesize a ConsensusReady event so the existing handler arm
            // below advances the FSM. Synchronous dispatch — the FSM is single-threaded on the
            // caller thread, so re-entry into `handle` happens before this method returns.
            if (ctx.consensusReadySupplier().get()) {
                log.info("Consensus engine reports ready on entry to QuorumWaiting — advancing");
                dispatchSelf(ctx, new ConsensusReady());
                return;
            }
            // Otherwise schedule a periodic re-check. The supplier wraps a level signal that may
            // become true asynchronously after entry; without this poll the FSM would sit
            // leaderless until an incidental topology event nudges it. R6: the same poll ALSO
            // pulls KV for a committed leader, so a peer-committed leader observed during this
            // window short-circuits straight to `Led` (via `adoptLeaderFromKvIfPresent`'s
            // synthetic `LeaderCommitted` → handler arm) without waiting for consensus-readiness
            // to flip.
            log.debug("Consensus engine not ready on entry — scheduling periodic re-check at {}ms",
                      POLL_INTERVAL.millis());
            var future = SharedScheduler.scheduleAtFixedRate(() -> pollConsensusAndKv(ctx),
                                                              POLL_INTERVAL);
            pollFuture.set(future);
        }

        @Override
        public void onExit() {
            cancelFuture(pollFuture);
        }

        @Override
        public void onCasLost() {
            cancelFuture(pollFuture);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case ConsensusReady _ -> tx.transitionTo(ctx.awaitingKvSync());
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case ClusterFsmEvent.NodeAdded na -> ctx.setCurrentTopology(na.topology());
                case ClusterFsmEvent.NodeGone ng -> ctx.setCurrentTopology(ng.topology());
                case LeaderCommitted lc -> adoptLeaderIfInTopology(ctx, lc, tx);
                default -> tx.ignore();
            }
        }
    }

    /// Brief grace state inserted between [`QuorumWaiting`] and [`Electing`]/[`ReElecting`] to
    /// prevent a cold-boot self-proposal race. When all cluster nodes boot simultaneously with
    /// empty local KV-stores, the prior path ran:
    ///
    ///   `QuorumWaiting --ConsensusReady--> Electing` → eager tick → propose self
    ///
    /// against the parallel cluster path where 4-of-5 peers were busy committing a leader
    /// through Rabia. The self-proposing node won the local race even though consensus had
    /// already named someone else (or was about to). The new gate is:
    ///
    ///   `QuorumWaiting --ConsensusReady--> AwaitingKvSync ` → wait briefly for either
    ///     - `LeaderCommitted` (push-side: KVSync from a peer applies `LeaderKey`, AetherNode's
    ///       `handleLeaderCommit` dispatches `LeaderCommitted` → transition to `Led(thatLeader)`),
    ///     - `KvSyncGraceTimeout` (no peer has committed a leader within the grace window;
    ///       cluster is genuinely fresh — fall through to `Electing`/`ReElecting`).
    ///
    /// On entry the state ALSO consults [`LeaderElectionContext#currentLeaderFromKvSupplier`]
    /// (pull-side) and dispatches a synthetic `LeaderCommitted` if the local KV already records
    /// a leader. This handles the auto-heal path: a single replacement node joining a live
    /// cluster receives the leader via KV-sync before this state's `onEntry` runs, so the
    /// pull-side check fires immediately and the state passes through in a single dispatch tick
    /// without paying any grace latency.
    record AwaitingKvSync(LeaderElectionContext ctx,
                          AtomicReference<ScheduledFuture<?>> graceTimeoutFuture) implements LeaderElectionState {

        public static AwaitingKvSync fresh(LeaderElectionContext ctx) {
            return new AwaitingKvSync(ctx, new AtomicReference<>());
        }

        @Override
        public void onEntry() {
            // Pull-side fast path — if KV already has a leader (auto-heal: replacement node
            // joined an existing cluster, KVSync already applied LeaderKey before we got here),
            // synthesize LeaderCommitted now. Synchronous dispatch — re-entry into `handle`
            // happens before this method returns and transitions to `Led(leader)`.
            adoptLeaderFromKvIfPresent(ctx);
            // If the synchronous dispatch already advanced us out of AwaitingKvSync, skip
            // scheduling the grace timer — this record's CAS reservation is gone, but the new
            // state owns its own timers.
            if (!(ctx.fsm().current() instanceof AwaitingKvSync)) {
                log.debug("AwaitingKvSync.onEntry: KV-side leader adopted synchronously — skipping grace timer");
                return;
            }
            log.info("Entering AwaitingKvSync: deferring leader-proposal for {}ms to absorb KV-sync from peers",
                     ctx.kvSyncGraceDelay().millis());
            var future = SharedScheduler.schedule(() -> dispatchSelf(ctx, new KvSyncGraceTimeout()),
                                                   ctx.kvSyncGraceDelay());
            graceTimeoutFuture.set(future);
        }

        @Override
        public void onExit() {
            cancelFuture(graceTimeoutFuture);
        }

        @Override
        public void onCasLost() {
            cancelFuture(graceTimeoutFuture);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case LeaderCommitted lc -> adoptLeaderIfInTopology(ctx, lc, tx);
                case KvSyncGraceTimeout _ -> graceTimeoutFallthrough(ctx, tx);
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.quorumLost());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case ClusterFsmEvent.NodeAdded na -> ctx.setCurrentTopology(na.topology());
                case ClusterFsmEvent.NodeGone ng -> ctx.setCurrentTopology(ng.topology());
                default -> tx.ignore();
            }
        }
    }

    private static void graceTimeoutFallthrough(LeaderElectionContext ctx,
                                                TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        log.info("AwaitingKvSync grace window elapsed without observing a committed leader — proceeding to election");
        if (ctx.hasEverHadLeader()) {
            tx.transitionTo(ctx.reElecting());
        } else {
            tx.transitionTo(ctx.electing());
        }
    }

    /// Periodic re-check of (a) consensus readiness AND (b) KV-committed leader. R6: pulls
    /// both signals on every tick so a peer-committed leader observed during the QuorumWaiting
    /// window short-circuits to `Led` without waiting for the consensus-readiness flip.
    /// On a true consensus-readiness flag this dispatches `ConsensusReady` into the FSM so the
    /// handler can advance to `AwaitingKvSync`. The dispatch path is idempotent in non-
    /// `QuorumWaiting` states (Dormant absorbs as `NO_ACTION_DORMANT`; QuorumLost absorbs the
    /// same way; AwaitingKvSync/Electing/ReElecting/Led/Stopped fall through to `tx.ignore()`),
    /// so a tick that races the FSM's own state transition is harmless. The KV pull goes
    /// through `adoptLeaderFromKvIfPresent` which synthesizes a `LeaderCommitted`; if the FSM
    /// is no longer in QuorumWaiting the synthetic event is consumed by whatever state owns
    /// the FSM at dispatch time (or harmlessly ignored).
    private static void pollConsensusAndKv(LeaderElectionContext ctx) {
        adoptLeaderFromKvIfPresent(ctx);
        if (ctx.consensusReadySupplier().get()) {
            log.info("Consensus engine became ready during QuorumWaiting poll — advancing");
            dispatchSelf(ctx, new ConsensusReady());
        }
    }

    /// Data-carrying state. Fresh instance per entry; holds the eagerly-scheduled first-tick
    /// `ScheduledFuture` plus an [`AtomicReference`] for subsequent reschedules (proposal retries,
    /// topology changes), a separate slot for the in-flight proposal-timeout dispatcher, AND an
    /// independent peer-observation `ScheduledFuture` that calls [`#adoptLeaderFromKvIfPresent`]
    /// at [`LeaderElectionContext#peerObservationInterval`] cadence. The observation timer is
    /// independent of `proposalInFlight` — it fires regardless of whether a self-proposal is in
    /// flight, so a peer-committed leader that arrives during the proposal window is observed
    /// promptly (preempting the in-flight proposal) instead of waiting for the proposal timeout
    /// (`proposalTimeout`, default 10s) to fire `ProposalSettled.failure`.
    /// All three futures are cancelled in `onExit` / `onCasLost`.
    record Electing(LeaderElectionContext ctx,
                    AtomicReference<ScheduledFuture<?>> tickFuture,
                    AtomicReference<ScheduledFuture<?>> proposalTimeoutFuture,
                    AtomicReference<ScheduledFuture<?>> observationFuture) implements LeaderElectionState {

        public static Electing fresh(LeaderElectionContext ctx) {
            var tickFuture = scheduleStaircaseFirstTick(ctx, "Electing");
            var observationFuture = SharedScheduler.scheduleAtFixedRate(
                    () -> adoptLeaderFromKvIfPresent(ctx),
                    ctx.peerObservationInterval());
            return new Electing(ctx,
                                new AtomicReference<>(tickFuture),
                                new AtomicReference<>(),
                                new AtomicReference<>(observationFuture));
        }

        @Override
        public void onEntry() {
            ctx.resetElectionRetryCount();
            ctx.resetStuckElectionCount();
            adoptLeaderFromKvIfPresent(ctx);
        }

        @Override
        public void onExit() {
            cancelFuture(tickFuture);
            cancelFuture(proposalTimeoutFuture);
            cancelFuture(observationFuture);
            ctx.clearProposalInFlight();
            ctx.resetElectionRetryCount();
        }

        @Override
        public void onCasLost() {
            cancelFuture(tickFuture);
            cancelFuture(proposalTimeoutFuture);
            cancelFuture(observationFuture);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case ElectionTick _ -> tx.handle(() -> {
                    adoptLeaderFromKvIfPresent(ctx);
                    trySubmitProposal(ctx, this);
                });
                case ProposalSettled ps -> tx.handle(() -> handleProposalSettled(ctx, ps));
                case LeaderCommitted lc -> adoptLeaderIfInTopology(ctx, lc, tx);
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.quorumLost());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case ClusterFsmEvent.NodeAdded na -> handleTopologyChange(ctx, na.topology());
                case ClusterFsmEvent.NodeGone ng -> handleTopologyChange(ctx, ng.topology());
                default -> tx.ignore();
            }
        }
    }

    /// The only data-carrying state — fresh instance per entry, `leader` identifies the tenure.
    record Led(LeaderElectionContext ctx, NodeId leader) implements LeaderElectionState {
        @Override
        public void onEntry() {
            ctx.markHasEverHadLeader();
            ctx.setCurrentLeader(Option.some(leader));
            notifyLeaderChange(ctx);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case ClusterFsmEvent.NodeGone ng -> {
                    ctx.setCurrentTopology(ng.topology());
                    if (ng.node().equals(leader)) {
                        tx.transitionTo(ctx.reElecting());
                    }
                }
                case ClusterFsmEvent.NodeAdded na -> ctx.setCurrentTopology(na.topology());
                case LeaderCommitted lc -> handleLeaderCommittedInLed(ctx, leader, lc, tx);
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.quorumLost());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                // External LeaderChange is informational — canonical leader lives in our Led
                // state. Stale ElectionTick / ProposalSettled arrive from prior Electing entries
                // (harmless). All three are explicit no-ops via tx.ignore().
                case ClusterFsmEvent.LeaderChange _, ElectionTick _, ProposalSettled _ -> tx.ignore();
                default -> tx.ignore();
            }
        }
    }

    private static void handleLeaderCommittedInLed(LeaderElectionContext ctx,
                                                   NodeId currentLeader,
                                                   LeaderCommitted event,
                                                   TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        if (event.leader().equals(currentLeader)) {
            // Idempotent replay — no transition.
            return;
        }
        if (!ctx.currentTopology().contains(event.leader())) {
            log.warn("Rejecting stale LeaderCommitted({}) in Led({}) — leader not in topology {}",
                     event.leader(), currentLeader, ctx.currentTopology());
            return;
        }
        // Valid leader swap (different committed leader, in topology) — transition to a new Led
        // instance. This covers local-mode topology-driven re-election and consensus re-elections
        // that commit directly without passing through ReElecting.
        tx.transitionTo(new Led(ctx, event.leader()));
    }

    /// Data-carrying state. Same lifecycle as [`Electing`]: holds the eagerly-scheduled first-tick
    /// `ScheduledFuture`, a slot for the in-flight proposal-timeout dispatcher, AND the
    /// independent peer-observation timer (see [`Electing`] for the rationale — symmetric fix
    /// applies here so a re-election proposal in flight cannot blind the FSM to a peer-committed
    /// leader). All three futures are cancelled in `onExit` / `onCasLost`.
    record ReElecting(LeaderElectionContext ctx,
                      AtomicReference<ScheduledFuture<?>> tickFuture,
                      AtomicReference<ScheduledFuture<?>> proposalTimeoutFuture,
                      AtomicReference<ScheduledFuture<?>> observationFuture) implements LeaderElectionState {

        public static ReElecting fresh(LeaderElectionContext ctx) {
            // R6: ReElecting uses the same rank-staircase first-tick delay as Electing. Without
            // this the prior path scheduled the first tick at `proposalRetryDelay` (~500ms) on
            // every node — i.e. classic thundering herd on leader loss, every surviving peer
            // proposing in parallel. Staircase pacing means the lowest-rank survivor proposes
            // first; higher ranks observe its commit via the independent peer-observation timer
            // (or the KV pull on entry) and abandon their pending tick before it fires. Retries
            // after a failed proposal continue to use `proposalRetryDelay` via the existing
            // `rescheduleCurrentTick` path — the staircase only governs the FIRST tick after
            // entry to the state, which is exactly when herding would otherwise occur.
            var holder = new AtomicReference<ScheduledFuture<?>>(scheduleStaircaseFirstTick(ctx, "ReElecting"));
            var observationFuture = SharedScheduler.scheduleAtFixedRate(
                    () -> adoptLeaderFromKvIfPresent(ctx),
                    ctx.peerObservationInterval());
            return new ReElecting(ctx, holder, new AtomicReference<>(),
                                  new AtomicReference<>(observationFuture));
        }

        @Override
        public void onEntry() {
            ctx.resetStuckElectionCount();
            clearLeaderAndNotify(ctx);
            adoptLeaderFromKvIfPresent(ctx);
        }

        @Override
        public void onExit() {
            cancelFuture(tickFuture);
            cancelFuture(proposalTimeoutFuture);
            cancelFuture(observationFuture);
            ctx.clearProposalInFlight();
            ctx.resetElectionRetryCount();
        }

        @Override
        public void onCasLost() {
            cancelFuture(tickFuture);
            cancelFuture(proposalTimeoutFuture);
            cancelFuture(observationFuture);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case ElectionTick _ -> tx.handle(() -> {
                    adoptLeaderFromKvIfPresent(ctx);
                    trySubmitProposal(ctx, this);
                });
                case ProposalSettled ps -> tx.handle(() -> handleProposalSettled(ctx, ps));
                case LeaderCommitted lc -> adoptLeaderIfInTopology(ctx, lc, tx);
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.quorumLost());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case ClusterFsmEvent.NodeAdded na -> handleTopologyChange(ctx, na.topology());
                case ClusterFsmEvent.NodeGone ng -> handleTopologyChange(ctx, ng.topology());
                default -> tx.ignore();
            }
        }
    }

    record QuorumLost(LeaderElectionContext ctx) implements LeaderElectionState {
        @Override
        public void onEntry() {
            clearLeaderAndNotify(ctx);
            ctx.clearProposalInFlight();
            log.warn("Entering QuorumLost — leader invalidated, waiting for quorum to re-establish");
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                // QuorumEstablished after a transient quorum loss re-enters the AwaitingKvSync
                // gate. We skip the intermediate QuorumWaiting consensus-readiness re-check
                // because consensus was already active before the quorum dropped, and remains
                // active here (QuorumLost is a topology-only state). The KvSync grace window
                // still absorbs leader-commit propagation across the surviving peers, mirroring
                // the cold-boot fix.
                case ClusterFsmEvent.QuorumEstablished _ -> tx.transitionTo(ctx.awaitingKvSync());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                // ConsensusReady arriving in QuorumLost is acknowledged but causes no
                // transition — consensus-readiness will be re-queried via
                // `ctx.consensusReadySupplier()` on next entry to QuorumWaiting (after
                // QuorumEstablished re-establishes quorum).
                case ConsensusReady _ -> tx.handle(NO_ACTION_DORMANT);
                case ClusterFsmEvent.NodeAdded na -> ctx.setCurrentTopology(na.topology());
                case ClusterFsmEvent.NodeGone ng -> ctx.setCurrentTopology(ng.topology());
                case LeaderCommitted lc -> log.warn("Rejecting stale LeaderCommitted({}) in QuorumLost",
                                                    lc.leader());
                default -> tx.ignore();
            }
        }
    }

    record Stopped(LeaderElectionContext ctx) implements LeaderElectionState {
        @Override
        public void onEntry() {
            clearLeaderAndNotify(ctx);
            ctx.clearProposalInFlight();
            log.info("Entering Stopped");
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            // Terminal — ignore everything.
            tx.ignore();
        }
    }

    // --- Shared action helpers ---

    private static void adoptLeaderIfInTopology(LeaderElectionContext ctx,
                                                LeaderCommitted event,
                                                TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        if (ctx.currentTopology().contains(event.leader())) {
            tx.transitionTo(new Led(ctx, event.leader()));
        } else {
            log.warn("Rejecting stale LeaderCommitted({}) — leader not in topology {}",
                     event.leader(), ctx.currentTopology());
        }
    }

    /// Pull-side complement to the push-based `KVStoreNotification.ValuePut<LeaderKey>` listener.
    /// Reads the cluster's KV-Store via the injected supplier and, if a leader is recorded AND in
    /// the current topology, dispatches a synthetic `LeaderCommitted` to self. The standard
    /// `LeaderCommitted` handler in `Electing` / `ReElecting` then transitions to `Led(leader)`.
    /// This closes the gap where the notification path doesn't fire (e.g. the rejoining node's
    /// snapshot-restored state didn't include `LeaderKey`, or the listener wired after a Decision
    /// for the current leader was already applied). KV is the cluster's source of truth — pulling
    /// it once per state-entry and once per election tick guarantees the FSM cannot stay in
    /// `Electing` while consensus has already named a leader visible to this node's local KV.
    private static void adoptLeaderFromKvIfPresent(LeaderElectionContext ctx) {
        ctx.currentLeaderFromKvSupplier().get().onPresent(leader -> {
            if (!ctx.currentTopology().contains(leader)) {
                return;
            }
            if (ctx.currentLeader().filter(leader::equals).isPresent()) {
                return;
            }
            log.info("Adopting leader from KV-Store: {} (push-notification path missed)", leader);
            dispatchSelf(ctx, new LeaderCommitted(leader));
        });
    }

    private static void notifyLeaderChange(LeaderElectionContext ctx) {
        var leaderOpt = ctx.currentLeader();
        if (!ctx.markNotified(leaderOpt)) {
            return; // Already notified this exact leader — skip duplicate.
        }
        var isSelf = leaderOpt.filter(ctx.self()::equals).isPresent();
        ctx.router().route(LeaderNotification.leaderChange(leaderOpt, isSelf));
    }

    private static void clearLeaderAndNotify(LeaderElectionContext ctx) {
        ctx.setCurrentLeader(Option.none());
        notifyLeaderChange(ctx);
    }

    private static void handleTopologyChange(LeaderElectionContext ctx, List<NodeId> topology) {
        ctx.setCurrentTopology(topology);
        rescheduleCurrentTick(ctx);
    }

    /// Reschedules the election tick on the *current* Electing/ReElecting record, replacing any
    /// prior pending tick on that record's holder. If the FSM is no longer in an electing state,
    /// the schedule is a no-op (a stale `ProposalSettled.failure` arriving after the FSM has moved
    /// on must NOT spawn a new orphan timer).
    private static void rescheduleCurrentTick(LeaderElectionContext ctx) {
        switch (ctx.fsm().current()) {
            case Electing e -> scheduleElectionTickInto(ctx, e.tickFuture());
            case ReElecting r -> scheduleElectionTickInto(ctx, r.tickFuture());
            default -> log.debug("rescheduleCurrentTick called from non-electing state — skipped");
        }
    }

    /// Schedules the FIRST election tick on entry to [`Electing`] / [`ReElecting`] using the
    /// rank-staircase formula `baseElectionDelay + rank * perRankDelay`. Lowest-rank node
    /// (`rank=0`) proposes first; higher ranks delay one `perRankDelay` per position so they
    /// can observe the lowest-rank proposal commit before their own tick fires. Defeats the
    /// thundering-herd election storm structurally: in a 5-node cold boot only `node-1`
    /// (rank 0) ever submits a proposal — peers 2..5 see `LeaderKey=node-1` in KV (push
    /// notification or 500ms peer-observation pull) and short-circuit. If `node-1` is dead,
    /// `node-2` (rank 1) proposes one `perRankDelay` later (default 1s); and so on. Subsequent
    /// retries after a failed proposal use [`#scheduleElectionTickInto`] (with `proposalRetryDelay`
    /// + jitter) — the staircase ONLY governs the first tick on each entry, which is exactly
    /// the window where parallel proposals would otherwise collide.
    private static ScheduledFuture<?> scheduleStaircaseFirstTick(LeaderElectionContext ctx,
                                                                  String stateLabel) {
        var rank = ctx.rankOfSelf();
        var delayMs = ctx.baseElectionDelay().millis() + rank * ctx.perRankDelay().millis();
        log.info("Entering {}: rank={}, first-tick delay={}ms (rank-staircase: base={}ms + rank*{}ms), "
                 + "peer-observation interval={}ms",
                 stateLabel, rank, delayMs,
                 ctx.baseElectionDelay().millis(), ctx.perRankDelay().millis(),
                 ctx.peerObservationInterval().millis());
        return SharedScheduler.schedule(() -> dispatchSelf(ctx, new ElectionTick()),
                                        TimeSpan.timeSpan(delayMs).millis());
    }

    /// Schedules a fresh election tick and stores its `ScheduledFuture` into the supplied holder,
    /// cancelling any prior future the holder owned. Called from [`#rescheduleCurrentTick`]
    /// during retries (after a failed proposal). The first tick on each entry to
    /// [`Electing`] / [`ReElecting`] uses the rank-staircase delay via
    /// [`#scheduleStaircaseFirstTick`].
    private static void scheduleElectionTickInto(LeaderElectionContext ctx,
                                                  AtomicReference<ScheduledFuture<?>> holder) {
        var retry = ctx.incrementElectionRetryCount();
        var jitterMs = (long) (ctx.proposalRetryDelay().millis() * (1.0 + ctx.jitterSource().getAsDouble()));
        log.debug("Scheduling election tick #{} in {}ms", retry, jitterMs);
        var future = SharedScheduler.schedule(() -> dispatchSelf(ctx, new ElectionTick()),
                                              TimeSpan.timeSpan(jitterMs).millis());
        var prior = holder.getAndSet(future);
        if (prior != null) {
            prior.cancel(false);
        }
    }

    private static void trySubmitProposal(LeaderElectionContext ctx,
                                          LeaderElectionState owner) {
        ctx.proposalHandler()
           .onPresent(handler -> submitProposalWith(ctx, owner, handler))
           .onEmpty(() -> {
               log.info("No proposal handler configured — rescheduling tick");
               rescheduleCurrentTick(ctx);
           });
    }

    private static void submitProposalWith(LeaderElectionContext ctx,
                                           LeaderElectionState owner,
                                           LeaderProposalHandler handler) {
        // Each silent early-return path below MUST reschedule the tick, otherwise the FSM
        // gets permanently stuck in Electing/ReElecting: the prior tick fired, no proposal
        // was sent, no `ProposalSettled` will arrive (no proposal in flight), no topology
        // event will trigger `rescheduleCurrentTick`. Concrete production stall observed
        // when a single rejoining node sees ghost SWIM peers (cross-cluster gossip) at
        // tick time — `submitProposalWith` exits, FSM never re-fires.
        if (ctx.currentTopology().isEmpty()) {
            log.info("Topology empty — skipping proposal, rescheduling tick");
            rescheduleCurrentTick(ctx);
            return;
        }
        var pool = ctx.candidatePool().stream().sorted().toList();
        if (pool.isEmpty()) {
            log.info("Candidate pool empty — skipping proposal, rescheduling tick");
            rescheduleCurrentTick(ctx);
            return;
        }
        var candidate = pool.getFirst();
        // All nodes propose in parallel during initial election. Rabia phase resolution
        // is leaderless and handles concurrent proposals natively — only one commits per
        // phase. `candidate` is `pool.getFirst()` after sorting, so every proposer picks
        // the same NodeId, eliminating split-vote risk. Removing the prior single-proposer
        // gate (lex-first only) ensures the cluster does not stall on `node-1` whenever its
        // Rabia engine is the slowest to reach `Idle`/`InPhase` after a cold restart —
        // any peer whose Rabia activates first can drive the proposal forward.
        if (!ctx.tryStartProposal()) {
            // Proposal already in flight — DO NOT reschedule. The proposal-timeout future
            // (set in `sendProposal`) will fire `ProposalSettled.failure`, which triggers
            // `rescheduleCurrentTick` via `handleProposalSettled`. Rescheduling here would
            // burst-tick during a normal in-flight proposal.
            log.info("Proposal already in flight — skipping (timeout will reschedule)");
            return;
        }
        sendProposal(ctx, owner, handler, candidate);
    }

    private static void sendProposal(LeaderElectionContext ctx,
                                     LeaderElectionState owner,
                                     LeaderProposalHandler handler,
                                     NodeId candidate) {
        var epoch = ctx.nextProposalEpoch();
        var viewSeq = ctx.nextViewSequence();
        log.info("Submitting leader proposal: candidate={}, viewSequence={}, epoch={}",
                 candidate, viewSeq, epoch);
        var timeoutFuture = SharedScheduler.schedule(
                () -> dispatchSelf(ctx, new ProposalSettled(candidate, false, "timeout@epoch=" + epoch)),
                ctx.proposalTimeout());
        storeProposalTimeoutFuture(owner, timeoutFuture);
        handler.propose(candidate, viewSeq)
               .onSuccess(_ -> dispatchSelf(ctx, new ProposalSettled(candidate, true,
                                                                     "submitted@epoch=" + epoch)))
               .onFailure(cause -> dispatchSelf(ctx, new ProposalSettled(candidate, false,
                                                                         cause.message() + "@epoch=" + epoch)));
    }

    /// Stores the proposal-timeout future onto the owning Electing/ReElecting record so that
    /// `onExit` / `onCasLost` can cancel it. Replaces any prior timeout future the owner held
    /// (cancels the old one), so a fresh proposal after a retry doesn't leak the prior timer.
    private static void storeProposalTimeoutFuture(LeaderElectionState owner,
                                                    ScheduledFuture<?> future) {
        var holder = switch (owner) {
            case Electing e -> e.proposalTimeoutFuture();
            case ReElecting r -> r.proposalTimeoutFuture();
            default -> null;
        };
        if (holder == null) {
            log.debug("storeProposalTimeoutFuture: owner is not electing — cancelling timeout immediately");
            future.cancel(false);
            return;
        }
        var prior = holder.getAndSet(future);
        if (prior != null) {
            prior.cancel(false);
        }
    }

    private static void handleProposalSettled(LeaderElectionContext ctx, ProposalSettled event) {
        if (!ctx.proposalInFlight()) {
            log.debug("Late ProposalSettled({}, success={}) — inFlight already cleared",
                      event.detail(), event.success());
            return;
        }
        ctx.clearProposalInFlight();
        if (event.success()) {
            log.debug("Proposal submitted ({}) — waiting for LeaderCommitted", event.detail());
            return;
        }
        log.debug("Proposal failed ({}) — retry scheduled", event.detail());
        ctx.incrementStuckElectionCount();
        rescheduleCurrentTick(ctx);
    }

    private static void cancelFuture(AtomicReference<ScheduledFuture<?>> holder) {
        var future = holder.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
    }
}
