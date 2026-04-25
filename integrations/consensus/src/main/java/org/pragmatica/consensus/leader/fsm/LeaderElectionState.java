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

/// Sealed state hierarchy for the leader-election FSM. Each state is a record bound to the
/// shared [`LeaderElectionContext`]. Data-free states (Dormant, QuorumWaiting, Electing,
/// ReElecting, QuorumLost, Stopped) are instantiated once per FSM and stored on the context
/// (the "per-FSM singleton" pattern). The single data-carrying state — `Led(leader)` — is
/// instantiated fresh on every entry; each instance uniquely identifies a leadership tenure.
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
                case ClusterFsmEvent.NodeAdded na -> ctx.setCurrentTopology(na.topology());
                case ClusterFsmEvent.NodeGone ng -> ctx.setCurrentTopology(ng.topology());
                default -> tx.ignore();
            }
        }
    }

    record QuorumWaiting(LeaderElectionContext ctx) implements LeaderElectionState {
        @Override
        public void onEntry() {
            // Query the consensus engine's readiness state directly (SSOT). If consensus is
            // already active we synthesize a ConsensusReady event so the existing handler arm
            // below advances the FSM. Synchronous dispatch — the FSM is single-threaded on the
            // caller thread, so re-entry into `handle` happens before this method returns.
            if (ctx.consensusReadySupplier().get()) {
                log.info("Consensus engine reports ready on entry to QuorumWaiting — advancing");
                dispatchSelf(ctx, new ConsensusReady());
            }
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

    record Electing(LeaderElectionContext ctx) implements LeaderElectionState {
        @Override
        public void onEntry() {
            ctx.resetElectionRetryCount();
            ctx.resetStuckElectionCount();
            var rank = ctx.rankOfSelf();
            var delay = ctx.baseElectionDelay().millis() + rank * ctx.perRankDelay().millis();
            log.info("Entering Electing: rank={}, first-tick delay={}ms", rank, delay);
            SharedScheduler.schedule(() -> dispatchSelf(ctx, new ElectionTick()),
                                     TimeSpan.timeSpan(delay).millis());
        }

        @Override
        public void onExit() {
            ctx.clearProposalInFlight();
            ctx.resetElectionRetryCount();
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case ElectionTick _ -> trySubmitProposal(ctx);
                case ProposalSettled ps -> handleProposalSettled(ctx, ps);
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

    record ReElecting(LeaderElectionContext ctx) implements LeaderElectionState {
        @Override
        public void onEntry() {
            ctx.resetStuckElectionCount();
            clearLeaderAndNotify(ctx);
            log.info("Entering ReElecting: scheduling tick in {}ms", ctx.proposalRetryDelay().millis());
            scheduleElectionTick(ctx);
        }

        @Override
        public void onExit() {
            ctx.clearProposalInFlight();
            ctx.resetElectionRetryCount();
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case ElectionTick _ -> trySubmitProposal(ctx);
                case ProposalSettled ps -> handleProposalSettled(ctx, ps);
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
        scheduleElectionTick(ctx);
    }

    private static void scheduleElectionTick(LeaderElectionContext ctx) {
        var retry = ctx.incrementElectionRetryCount();
        var jitterMs = (long) (ctx.proposalRetryDelay().millis() * (1.0 + ctx.jitterSource().getAsDouble()));
        log.debug("Scheduling election tick #{} in {}ms", retry, jitterMs);
        SharedScheduler.schedule(() -> dispatchSelf(ctx, new ElectionTick()),
                                 TimeSpan.timeSpan(jitterMs).millis());
    }

    private static void trySubmitProposal(LeaderElectionContext ctx) {
        ctx.proposalHandler().onPresent(handler -> submitProposalWith(ctx, handler));
    }

    private static void submitProposalWith(LeaderElectionContext ctx, LeaderProposalHandler handler) {
        if (ctx.currentTopology().isEmpty()) {
            log.debug("Topology empty — skipping proposal");
            return;
        }
        var pool = ctx.candidatePool().stream().sorted().toList();
        if (pool.isEmpty()) {
            log.debug("Candidate pool empty — skipping proposal");
            return;
        }
        var candidate = pool.getFirst();
        if (!ctx.hasEverHadLeader() && !ctx.self().equals(candidate)) {
            log.debug("Not initial-election candidate (self={}, candidate={})",
                      ctx.self(), candidate);
            return;
        }
        if (!ctx.tryStartProposal()) {
            log.debug("Proposal already in flight — skipping");
            return;
        }
        sendProposal(ctx, handler, candidate);
    }

    private static void sendProposal(LeaderElectionContext ctx,
                                     LeaderProposalHandler handler,
                                     NodeId candidate) {
        var epoch = ctx.nextProposalEpoch();
        var viewSeq = ctx.nextViewSequence();
        log.info("Submitting leader proposal: candidate={}, viewSequence={}, epoch={}",
                 candidate, viewSeq, epoch);
        SharedScheduler.schedule(() -> dispatchSelf(ctx, new ProposalSettled(candidate, false,
                                                                             "timeout@epoch=" + epoch)),
                                 ctx.proposalTimeout());
        handler.propose(candidate, viewSeq)
               .onSuccess(_ -> dispatchSelf(ctx, new ProposalSettled(candidate, true,
                                                                     "submitted@epoch=" + epoch)))
               .onFailure(cause -> dispatchSelf(ctx, new ProposalSettled(candidate, false,
                                                                         cause.message() + "@epoch=" + epoch)));
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
        scheduleElectionTick(ctx);
    }
}
