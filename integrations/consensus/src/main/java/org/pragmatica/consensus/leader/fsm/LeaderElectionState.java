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
            // leaderless until an incidental topology event nudges it.
            log.debug("Consensus engine not ready on entry — scheduling periodic re-check at {}ms",
                      POLL_INTERVAL.millis());
            var future = SharedScheduler.scheduleAtFixedRate(() -> pollConsensusReady(ctx),
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
                case ConsensusReady _ -> {
                    if (ctx.hasEverHadLeader()) {
                        tx.transitionTo(ctx.reElecting());
                    } else {
                        tx.transitionTo(ctx.electing());
                    }
                }
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case ClusterFsmEvent.NodeAdded na -> ctx.setCurrentTopology(na.topology());
                case ClusterFsmEvent.NodeGone ng -> ctx.setCurrentTopology(ng.topology());
                case LeaderCommitted lc -> adoptLeaderIfInTopology(ctx, lc, tx);
                default -> tx.ignore();
            }
        }
    }

    /// Periodic re-check of consensus readiness. Re-queries the supplier; on `true` it
    /// dispatches `ConsensusReady` into the FSM so the handler can advance to
    /// `Electing` / `ReElecting`. The dispatch path is idempotent in non-`QuorumWaiting`
    /// states (Dormant absorbs as `NO_ACTION_DORMANT`; QuorumLost absorbs the same way;
    /// Electing/ReElecting/Led/Stopped fall through to `tx.ignore()`), so a tick that races
    /// the FSM's own state transition is harmless.
    private static void pollConsensusReady(LeaderElectionContext ctx) {
        if (ctx.consensusReadySupplier().get()) {
            log.info("Consensus engine became ready during QuorumWaiting poll — advancing");
            dispatchSelf(ctx, new ConsensusReady());
        }
    }

    /// Data-carrying state. Fresh instance per entry; holds the eagerly-scheduled first-tick
    /// `ScheduledFuture` plus an [`AtomicReference`] for subsequent reschedules (proposal retries,
    /// topology changes) and a separate slot for the in-flight proposal-timeout dispatcher. Both
    /// futures are cancelled in `onExit` / `onCasLost` to prevent stale ticks from firing after
    /// the FSM has moved on.
    record Electing(LeaderElectionContext ctx,
                    AtomicReference<ScheduledFuture<?>> tickFuture,
                    AtomicReference<ScheduledFuture<?>> proposalTimeoutFuture) implements LeaderElectionState {

        public static Electing fresh(LeaderElectionContext ctx) {
            var rank = ctx.rankOfSelf();
            var delay = ctx.baseElectionDelay().millis() + rank * ctx.perRankDelay().millis();
            log.info("Entering Electing: rank={}, first-tick delay={}ms", rank, delay);
            var future = SharedScheduler.schedule(() -> dispatchSelf(ctx, new ElectionTick()),
                                                  TimeSpan.timeSpan(delay).millis());
            return new Electing(ctx, new AtomicReference<>(future), new AtomicReference<>());
        }

        @Override
        public void onEntry() {
            ctx.resetElectionRetryCount();
            ctx.resetStuckElectionCount();
        }

        @Override
        public void onExit() {
            cancelFuture(tickFuture);
            cancelFuture(proposalTimeoutFuture);
            ctx.clearProposalInFlight();
            ctx.resetElectionRetryCount();
        }

        @Override
        public void onCasLost() {
            cancelFuture(tickFuture);
            cancelFuture(proposalTimeoutFuture);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case ElectionTick _ -> tx.handle(() -> trySubmitProposal(ctx, this));
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
    /// `ScheduledFuture` and a slot for the in-flight proposal-timeout dispatcher; both are
    /// cancelled in `onExit` / `onCasLost`.
    record ReElecting(LeaderElectionContext ctx,
                      AtomicReference<ScheduledFuture<?>> tickFuture,
                      AtomicReference<ScheduledFuture<?>> proposalTimeoutFuture) implements LeaderElectionState {

        public static ReElecting fresh(LeaderElectionContext ctx) {
            log.info("Entering ReElecting: scheduling tick in {}ms", ctx.proposalRetryDelay().millis());
            var holder = new AtomicReference<ScheduledFuture<?>>();
            scheduleElectionTickInto(ctx, holder);
            return new ReElecting(ctx, holder, new AtomicReference<>());
        }

        @Override
        public void onEntry() {
            ctx.resetStuckElectionCount();
            clearLeaderAndNotify(ctx);
        }

        @Override
        public void onExit() {
            cancelFuture(tickFuture);
            cancelFuture(proposalTimeoutFuture);
            ctx.clearProposalInFlight();
            ctx.resetElectionRetryCount();
        }

        @Override
        public void onCasLost() {
            cancelFuture(tickFuture);
            cancelFuture(proposalTimeoutFuture);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case ElectionTick _ -> tx.handle(() -> trySubmitProposal(ctx, this));
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
                case ClusterFsmEvent.QuorumEstablished _ -> {
                    if (ctx.hasEverHadLeader()) {
                        tx.transitionTo(ctx.reElecting());
                    } else {
                        tx.transitionTo(ctx.electing());
                    }
                }
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

    /// Schedules a fresh election tick and stores its `ScheduledFuture` into the supplied holder,
    /// cancelling any prior future the holder owned. Called from both [`Electing.fresh`] /
    /// [`ReElecting.fresh`] entry paths AND from [`#rescheduleCurrentTick`] during retries.
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
