/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.leader.fsm;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.StateMachineDefinition;
import org.pragmatica.statemachine.Transition;
import org.pragmatica.statemachine.TransitionContext;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvent.Type.CONSENSUS_READY;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvent.Type.ELECTION_TICK;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvent.Type.LEADER_COMMITTED;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvent.Type.NODE_ADDED;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvent.Type.NODE_GONE;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvent.Type.PROPOSAL_SETTLED;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvent.Type.QUORUM_DISAPPEARED;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvent.Type.QUORUM_ESTABLISHED;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionEvent.Type.SHUTDOWN;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionState.DORMANT;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionState.ELECTING;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionState.LED;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionState.QUORUM_LOST;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionState.QUORUM_WAITING;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionState.RE_ELECTING;
import static org.pragmatica.consensus.leader.fsm.LeaderElectionState.STOPPED;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Leader-election state machine. Owns the declarative transition table, a single-threaded
/// dispatcher, and the side-effect actions. External callers use `dispatch(event)` (blocks
/// the caller until the event is processed) or `dispatchAsync(event)` (fire-and-forget from
/// timer callbacks / Promise continuations).
public final class LeaderElectionFsm {
    private static final Logger log = LoggerFactory.getLogger(LeaderElectionFsm.class);

    private final LeaderElectionContext context;
    private final StateMachineDefinition<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> definition;
    private final AtomicReference<LeaderElectionState> currentState = new AtomicReference<>(DORMANT);
    private final ExecutorService dispatcher;
    private final Thread dispatcherThread;

    private LeaderElectionFsm(LeaderElectionContext context,
                              StateMachineDefinition<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> definition,
                              ExecutorService dispatcher,
                              Thread dispatcherThread) {
        this.context = context;
        this.definition = definition;
        this.dispatcher = dispatcher;
        this.dispatcherThread = dispatcherThread;
    }

    public static LeaderElectionFsm leaderElectionFsm(LeaderElectionContext context) {
        var threadRef = new AtomicReference<Thread>();
        var dispatcher = Executors.newSingleThreadExecutor(runnable -> {
            var thread = new Thread(runnable, "leader-election-fsm-" + context.self().id());
            thread.setDaemon(true);
            threadRef.set(thread);
            return thread;
        });
        // Force thread creation so dispatcherThread is populated before any dispatch.
        var booted = Promise.<Unit>promise();
        dispatcher.execute(() -> booted.succeed(Unit.unit()));
        booted.await();
        var thread = threadRef.get();
        var fsm = new LeaderElectionFsm[1];
        var actions = new Actions(() -> fsm[0]);
        var definition = buildDefinition(actions);
        fsm[0] = new LeaderElectionFsm(context, definition, dispatcher, thread);
        return fsm[0];
    }

    // --- public API ---

    public LeaderElectionState currentState() {
        return currentState.get();
    }

    public LeaderElectionContext context() {
        return context;
    }

    /// Dispatch an event synchronously. If called from the dispatcher thread (re-entry from an
    /// action), runs inline; otherwise submits and waits for completion so callers have the
    /// post-transition view.
    public void dispatch(LeaderElectionEvent event) {
        if (Thread.currentThread() == dispatcherThread) {
            runTransition(event);
            return;
        }
        if (dispatcher.isShutdown()) {
            log.debug("Ignoring {} — dispatcher shut down", event.type());
            return;
        }
        var done = Promise.<Unit>promise();
        submitToDispatcher(() -> {
                               runTransition(event);
                               done.succeed(Unit.unit());
                           },
                           _ -> done.succeed(Unit.unit()));
        done.await();
    }

    /// Dispatch an event without waiting. Used by timer callbacks and Promise continuations
    /// that must not block.
    public void dispatchAsync(LeaderElectionEvent event) {
        if (dispatcher.isShutdown()) {
            log.debug("Ignoring async {} — dispatcher shut down", event.type());
            return;
        }
        submitToDispatcher(() -> runTransition(event),
                           cause -> log.debug("Async dispatch rejected ({}): {}",
                                              event.type(), cause.message()));
    }

    /// Wrap `dispatcher.execute` in a lift so any `RejectedExecutionException` from a
    /// shutdown race is converted to a Cause and handled by the provided callback.
    private void submitToDispatcher(Runnable task, Consumer<Cause> onRejected) {
        Result.lift(() -> dispatcher.execute(task))
              .onFailure(onRejected::accept);
    }

    /// Stop the FSM. Sends Shutdown (synchronously), then shuts the dispatcher.
    public void stop() {
        if (dispatcher.isShutdown()) {
            return;
        }
        dispatch(new LeaderElectionEvent.Shutdown());
        dispatcher.shutdown();
        Promise.lift(() -> {
                   if (!dispatcher.awaitTermination(5, TimeUnit.SECONDS)) {
                       dispatcher.shutdownNow();
                   }
               })
               .onFailure(_ -> dispatcher.shutdownNow())
               .await();
    }

    // --- dispatch core (dispatcher thread only) ---

    private void runTransition(LeaderElectionEvent event) {
        Result.lift(() -> runTransitionInner(event))
              .onFailure(cause -> log.error("Leader-election FSM transition failed for {}: {}",
                                            event.type(), cause.message()));
    }

    private void runTransitionInner(LeaderElectionEvent event) {
        var state = currentState.get();
        if (state == STOPPED) {
            log.debug("Ignoring {} in STOPPED", event.type());
            return;
        }
        context.setPendingEvent(event);
        findAllowedTransition(state, event)
            .onPresent(this::executeTransition)
            .onEmpty(() -> log.debug("Event {} ignored in state {} (no matching transition)",
                                     event.type(), state));
    }

    private Option<Transition<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext>> findAllowedTransition(
            LeaderElectionState state, LeaderElectionEvent event) {
        var type = event.type();
        for (var t : definition.transitions()) {
            if (!t.fromState().equals(state) || !t.event().equals(type)) {
                continue;
            }
            var txCtx = TransitionContext.transitionContext("leader-election",
                                                            t.fromState(),
                                                            t.toState(),
                                                            type,
                                                            context);
            if (t.isAllowed(txCtx)) {
                return Option.some(t);
            }
        }
        return Option.none();
    }

    private void executeTransition(Transition<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> transition) {
        var txCtx = TransitionContext.transitionContext("leader-election",
                                                        transition.fromState(),
                                                        transition.toState(),
                                                        transition.event(),
                                                        context);
        var isSelfTransition = transition.fromState().equals(transition.toState());
        Function<TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext>, Promise<Unit>> noop =
            _ -> Promise.unitPromise();
        var exitAction = isSelfTransition ? noop : definition.exitAction(transition.fromState()).or(noop);
        var entryAction = isSelfTransition ? noop : definition.entryAction(transition.toState()).or(noop);
        exitAction.apply(txCtx).await();
        transition.executeAction(txCtx).await();
        entryAction.apply(txCtx).await();
        if (!isSelfTransition) {
            log.info("Leader-election FSM: {} → {} on {}",
                     transition.fromState(), transition.toState(), transition.event());
        } else {
            log.debug("Leader-election FSM self-transition in {} on {}",
                      transition.fromState(), transition.event());
        }
        currentState.set(transition.toState());
    }

    // --- state machine definition ---

    private static StateMachineDefinition<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> buildDefinition(Actions a) {
        var builder = StateMachineDefinition.<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext>builder("leader-election")
                .initialState(DORMANT)
                .finalState(STOPPED);

        // --- DORMANT ---
        builder.transition(DORMANT, QUORUM_ESTABLISHED, QUORUM_WAITING);
        builder.transition(Transition.transition(DORMANT, NODE_ADDED, DORMANT, a::updateTopology));
        builder.transition(Transition.transition(DORMANT, NODE_GONE, DORMANT, a::updateTopology));
        // Buffer an early triggerElection (ConsensusReady) so it replays once quorum establishes.
        builder.transition(Transition.transition(DORMANT, CONSENSUS_READY, DORMANT, a::bufferConsensusReady));

        // --- QUORUM_WAITING ---
        // Buffered triggerElection replay: if pending, immediately transition on entry.
        builder.transition(Transition.transitionGuarded(QUORUM_WAITING, CONSENSUS_READY, ELECTING,
                                                        ctx -> !ctx.userContext().hasEverHadLeader()));
        builder.transition(QUORUM_WAITING, CONSENSUS_READY, RE_ELECTING);
        builder.transition(QUORUM_WAITING, QUORUM_DISAPPEARED, QUORUM_LOST);
        builder.transition(Transition.transition(QUORUM_WAITING, NODE_ADDED, QUORUM_WAITING, a::updateTopology));
        builder.transition(Transition.transition(QUORUM_WAITING, NODE_GONE, QUORUM_WAITING, a::updateTopology));
        // Adoption: newly joined node picks up already-committed leader before ConsensusReady.
        builder.transition(Transition.transitionFull(QUORUM_WAITING, LEADER_COMMITTED, LED,
                                                     a::adoptLeader, a::leaderInTopology));
        builder.transition(Transition.transition(QUORUM_WAITING, LEADER_COMMITTED, QUORUM_WAITING, a::warnStaleCommit));

        // --- ELECTING ---
        builder.transition(Transition.transition(ELECTING, ELECTION_TICK, ELECTING, a::submitProposal));
        builder.transition(Transition.transition(ELECTING, PROPOSAL_SETTLED, ELECTING, a::handleProposalSettled));
        builder.transition(Transition.transitionFull(ELECTING, LEADER_COMMITTED, LED,
                                                     a::adoptLeader, a::leaderInTopology));
        builder.transition(Transition.transition(ELECTING, LEADER_COMMITTED, ELECTING, a::warnStaleCommit));
        builder.transition(ELECTING, QUORUM_DISAPPEARED, QUORUM_LOST);
        builder.transition(Transition.transition(ELECTING, NODE_ADDED, ELECTING, a::updateTopologyAndTick));
        builder.transition(Transition.transition(ELECTING, NODE_GONE, ELECTING, a::updateTopologyAndTick));

        // --- LED ---
        builder.transition(Transition.transitionFull(LED, NODE_GONE, RE_ELECTING,
                                                     a::leaderGone, a::goneIsLeader));
        builder.transition(Transition.transition(LED, NODE_GONE, LED, a::updateTopology));
        builder.transition(Transition.transition(LED, NODE_ADDED, LED, a::updateTopology));
        // Idempotent replay: same leader re-committed. No notification.
        builder.transition(Transition.transitionGuarded(LED, LEADER_COMMITTED, LED, a::sameLeader));
        // Different leader in topology: swap + notify.
        builder.transition(Transition.transitionFull(LED, LEADER_COMMITTED, LED,
                                                     a::swapLeader, a::differentLeaderInTopology));
        // Stale: not in topology. Drop with warning.
        builder.transition(Transition.transition(LED, LEADER_COMMITTED, LED, a::warnStaleCommit));
        builder.transition(LED, QUORUM_DISAPPEARED, QUORUM_LOST);
        // Spurious ELECTION_TICK in LED (already-scheduled timer firing after commit). Ignore.
        builder.transition(LED, ELECTION_TICK, LED);
        builder.transition(LED, PROPOSAL_SETTLED, LED);

        // --- RE_ELECTING ---
        builder.transition(Transition.transition(RE_ELECTING, ELECTION_TICK, RE_ELECTING, a::submitProposal));
        builder.transition(Transition.transition(RE_ELECTING, PROPOSAL_SETTLED, RE_ELECTING, a::handleProposalSettled));
        builder.transition(Transition.transitionFull(RE_ELECTING, LEADER_COMMITTED, LED,
                                                     a::adoptLeader, a::leaderInTopology));
        builder.transition(Transition.transition(RE_ELECTING, LEADER_COMMITTED, RE_ELECTING, a::warnStaleCommit));
        builder.transition(RE_ELECTING, QUORUM_DISAPPEARED, QUORUM_LOST);
        builder.transition(Transition.transition(RE_ELECTING, NODE_ADDED, RE_ELECTING, a::updateTopologyAndTick));
        builder.transition(Transition.transition(RE_ELECTING, NODE_GONE, RE_ELECTING, a::updateTopologyAndTick));

        // --- QUORUM_LOST ---
        builder.transition(Transition.transitionGuarded(QUORUM_LOST, QUORUM_ESTABLISHED, RE_ELECTING,
                                                        ctx -> ctx.userContext().hasEverHadLeader()));
        builder.transition(QUORUM_LOST, QUORUM_ESTABLISHED, ELECTING);
        // Late triggerElection during quorum loss — buffer and replay when quorum re-establishes.
        builder.transition(Transition.transition(QUORUM_LOST, CONSENSUS_READY, QUORUM_LOST, a::bufferConsensusReady));
        builder.transition(Transition.transition(QUORUM_LOST, NODE_ADDED, QUORUM_LOST, a::updateTopology));
        builder.transition(Transition.transition(QUORUM_LOST, NODE_GONE, QUORUM_LOST, a::updateTopology));
        // Stale commits during quorum loss — drop.
        builder.transition(Transition.transition(QUORUM_LOST, LEADER_COMMITTED, QUORUM_LOST, a::warnStaleCommit));
        // Late proposal settlements during quorum loss — swallow.
        builder.transition(QUORUM_LOST, PROPOSAL_SETTLED, QUORUM_LOST);
        builder.transition(QUORUM_LOST, ELECTION_TICK, QUORUM_LOST);

        // --- Universal SHUTDOWN ---
        for (var s : LeaderElectionState.values()) {
            if (s != STOPPED) {
                builder.transition(s, SHUTDOWN, STOPPED);
            }
        }

        // --- Entry actions ---
        builder.onEntry(QUORUM_WAITING, a::onEnterQuorumWaiting);
        builder.onEntry(ELECTING, a::onEnterElecting);
        builder.onEntry(RE_ELECTING, a::onEnterReElecting);
        builder.onEntry(LED, a::onEnterLed);
        builder.onEntry(QUORUM_LOST, a::onEnterQuorumLost);
        builder.onEntry(STOPPED, a::onEnterStopped);

        // --- Exit actions ---
        builder.onExit(ELECTING, a::onExitElection);
        builder.onExit(RE_ELECTING, a::onExitElection);
        builder.onExit(LED, a::onExitLed);

        return builder.build().unwrap();
    }

    // --- Actions: side-effect methods keyed off context state + pending event ---

    private static final class Actions {
        private final Supplier<LeaderElectionFsm> fsmRef;

        Actions(Supplier<LeaderElectionFsm> fsmRef) {
            this.fsmRef = fsmRef;
        }

        private LeaderElectionFsm fsm() { return fsmRef.get(); }

        // --- Guards ---

        boolean leaderInTopology(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var event = (LeaderElectionEvent.LeaderCommitted) tx.userContext().pendingEvent();
            return tx.userContext().currentTopology().contains(event.leader());
        }

        boolean goneIsLeader(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var event = (LeaderElectionEvent.NodeGone) tx.userContext().pendingEvent();
            return tx.userContext().currentLeader().filter(event.nodeId()::equals).isPresent();
        }

        boolean sameLeader(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var event = (LeaderElectionEvent.LeaderCommitted) tx.userContext().pendingEvent();
            return tx.userContext().currentLeader().filter(event.leader()::equals).isPresent();
        }

        boolean differentLeaderInTopology(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            return !sameLeader(tx) && leaderInTopology(tx);
        }

        // --- Topology updates ---

        Promise<Unit> updateTopology(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            applyTopology(tx.userContext());
            return Promise.unitPromise();
        }

        /// Remember that CONSENSUS_READY arrived while we weren't able to act on it (DORMANT or
        /// QUORUM_LOST). Replay happens inside `onEnterQuorumWaiting` / `onEnterReElecting`.
        Promise<Unit> bufferConsensusReady(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            tx.userContext().markConsensusReadyPending();
            log.debug("Buffered CONSENSUS_READY in state {} — will replay when actionable", tx.fromState());
            return Promise.unitPromise();
        }

        Promise<Unit> updateTopologyAndTick(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            applyTopology(tx.userContext());
            scheduleTick(tx.userContext());
            return Promise.unitPromise();
        }

        private void applyTopology(LeaderElectionContext ctx) {
            var event = ctx.pendingEvent();
            List<NodeId> topology;
            if (event instanceof LeaderElectionEvent.NodeAdded na) {
                topology = na.topology();
            } else if (event instanceof LeaderElectionEvent.NodeGone ng) {
                topology = ng.topology();
            } else {
                return;
            }
            if (topology.isEmpty()) {
                log.debug("Topology update ignored — empty topology from {}", event.type());
                return;
            }
            ctx.setCurrentTopology(topology);
        }

        // --- Leader transitions ---

        Promise<Unit> adoptLeader(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var event = (LeaderElectionEvent.LeaderCommitted) tx.userContext().pendingEvent();
            var ctx = tx.userContext();
            ctx.setCurrentLeader(Option.some(event.leader()));
            ctx.markHasEverHadLeader();
            ctx.setProposalInFlight(false);
            ctx.resetElectionRetryCount();
            ctx.resetStuckElectionCount();
            notifyLeaderChange(ctx);
            return Promise.unitPromise();
        }

        Promise<Unit> swapLeader(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var event = (LeaderElectionEvent.LeaderCommitted) tx.userContext().pendingEvent();
            var ctx = tx.userContext();
            ctx.setCurrentLeader(Option.some(event.leader()));
            notifyLeaderChange(ctx);
            return Promise.unitPromise();
        }

        Promise<Unit> leaderGone(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            applyTopology(tx.userContext());
            tx.userContext().setCurrentLeader(Option.none());
            notifyNoLeader(tx.userContext());
            return Promise.unitPromise();
        }

        Promise<Unit> warnStaleCommit(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var event = (LeaderElectionEvent.LeaderCommitted) tx.userContext().pendingEvent();
            log.warn("Rejecting stale LeaderCommitted({}) in state {} — leader not in topology {}",
                     event.leader(), tx.fromState(), tx.userContext().currentTopology());
            return Promise.unitPromise();
        }

        // --- Proposals ---

        Promise<Unit> submitProposal(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var ctx = tx.userContext();
            ctx.proposalHandler().onPresent(handler -> trySubmitProposal(ctx, handler));
            return Promise.unitPromise();
        }

        private void trySubmitProposal(LeaderElectionContext ctx, LeaderProposalHandler handler) {
            if (ctx.proposalInFlight()) {
                log.debug("Proposal already in flight — skipping tick");
                return;
            }
            if (ctx.currentTopology().isEmpty()) {
                log.debug("Topology empty — skipping proposal");
                return;
            }
            var sortedPool = ctx.candidatePool().stream().sorted().toList();
            if (sortedPool.isEmpty()) {
                log.debug("Candidate pool empty — skipping proposal");
                return;
            }
            var candidate = sortedPool.getFirst();
            // Initial election: only lowest-ranked candidate submits (avoids livelock).
            if (!ctx.hasEverHadLeader() && !ctx.self().equals(candidate)) {
                log.debug("Not initial-election candidate (self={}, candidate={}) — skipping",
                          ctx.self(), candidate);
                return;
            }
            ctx.setProposalInFlight(true);
            var epoch = ctx.nextProposalEpoch();
            var viewSeq = ctx.incrementViewSequence();
            log.info("Submitting leader proposal: candidate={}, viewSequence={}, epoch={}",
                     candidate, viewSeq, epoch);
            SharedScheduler.schedule(() ->
                fsm().dispatchAsync(new LeaderElectionEvent.ProposalSettled(candidate, false,
                                                                            "timeout@epoch=" + epoch)),
                ctx.proposalTimeout());
            handler.propose(candidate, viewSeq)
                   .onSuccess(_ -> fsm().dispatchAsync(new LeaderElectionEvent.ProposalSettled(candidate, true,
                                                                                                "submitted@epoch=" + epoch)))
                   .onFailure(cause -> fsm().dispatchAsync(new LeaderElectionEvent.ProposalSettled(candidate, false,
                                                                                                    cause.message() + "@epoch=" + epoch)));
        }

        Promise<Unit> handleProposalSettled(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var ctx = tx.userContext();
            var event = (LeaderElectionEvent.ProposalSettled) ctx.pendingEvent();
            if (!ctx.proposalInFlight()) {
                log.debug("Late ProposalSettled({}, success={}) — inFlight already cleared, ignoring",
                          event.detail(), event.success());
                return Promise.unitPromise();
            }
            ctx.setProposalInFlight(false);
            if (event.success()) {
                log.debug("Proposal submitted successfully ({}) — waiting for LeaderCommitted", event.detail());
                return Promise.unitPromise();
            }
            log.debug("Proposal failed ({}) — retry scheduled", event.detail());
            ctx.incrementStuckElectionCount();
            scheduleTick(ctx);
            return Promise.unitPromise();
        }

        // --- Entry / Exit actions ---

        Promise<Unit> onEnterQuorumWaiting(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            if (tx.userContext().consumeConsensusReadyPending()) {
                log.info("Replaying buffered CONSENSUS_READY on entry to QUORUM_WAITING");
                fsm().dispatchAsync(new LeaderElectionEvent.ConsensusReady());
            }
            return Promise.unitPromise();
        }

        Promise<Unit> onEnterElecting(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var ctx = tx.userContext();
            ctx.resetElectionRetryCount();
            ctx.resetStuckElectionCount();
            var rank = ctx.rankOfSelf();
            var total = ctx.baseElectionDelay().millis() + rank * ctx.perRankDelay().millis();
            log.info("Entering ELECTING: rank={}, first-tick delay={}ms (base={}+rank*{})",
                     rank, total, ctx.baseElectionDelay().millis(), ctx.perRankDelay().millis());
            SharedScheduler.schedule(() -> fsm().dispatchAsync(new LeaderElectionEvent.ElectionTick()),
                                     timeSpan(total).millis());
            return Promise.unitPromise();
        }

        Promise<Unit> onEnterReElecting(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var ctx = tx.userContext();
            ctx.resetStuckElectionCount();
            log.info("Entering RE_ELECTING: scheduling tick in {}ms", ctx.proposalRetryDelay().millis());
            scheduleTick(ctx);
            return Promise.unitPromise();
        }

        Promise<Unit> onEnterLed(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            // Leader change notification already sent by the transition action (adoptLeader).
            // This entry action is a defensive marker in case future transitions into LED miss
            // the explicit notify — ensures watchers see a consistent leader.
            log.info("Entering LED: leader={}", tx.userContext().currentLeader());
            return Promise.unitPromise();
        }

        Promise<Unit> onEnterQuorumLost(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var ctx = tx.userContext();
            ctx.setCurrentLeader(Option.none());
            ctx.setProposalInFlight(false);
            notifyNoLeader(ctx);
            log.warn("Entering QUORUM_LOST — leader invalidated, waiting for quorum to re-establish");
            return Promise.unitPromise();
        }

        Promise<Unit> onEnterStopped(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var ctx = tx.userContext();
            ctx.setCurrentLeader(Option.none());
            ctx.setProposalInFlight(false);
            notifyNoLeader(ctx);
            log.info("Entering STOPPED");
            return Promise.unitPromise();
        }

        Promise<Unit> onExitElection(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            var ctx = tx.userContext();
            ctx.setProposalInFlight(false);
            ctx.resetElectionRetryCount();
            return Promise.unitPromise();
        }

        Promise<Unit> onExitLed(TransitionContext<LeaderElectionState, LeaderElectionEvent.Type, LeaderElectionContext> tx) {
            // Leader cleared by transition actions (leaderGone / onEnterQuorumLost). Nothing more to do here.
            return Promise.unitPromise();
        }

        // --- Helpers ---

        private void scheduleTick(LeaderElectionContext ctx) {
            var retry = ctx.incrementElectionRetryCount();
            var jitterMs = (long) (ctx.proposalRetryDelay().millis() * (1.0 + Math.random() * 0.5));
            log.debug("Scheduling election tick #{} in {}ms", retry, jitterMs);
            SharedScheduler.schedule(() -> fsm().dispatchAsync(new LeaderElectionEvent.ElectionTick()),
                                     timeSpan(jitterMs).millis());
        }

        private void notifyLeaderChange(LeaderElectionContext ctx) {
            var leaderOpt = ctx.currentLeader();
            var isSelf = leaderOpt.filter(ctx.self()::equals).isPresent();
            var notification = LeaderNotification.leaderChange(leaderOpt, isSelf);
            routeNotification(ctx.router(), notification);
        }

        private void notifyNoLeader(LeaderElectionContext ctx) {
            routeNotification(ctx.router(), LeaderNotification.leaderChange(Option.none(), false));
        }

        private void routeNotification(MessageRouter router, LeaderNotification notification) {
            router.route(notification);
        }
    }
}
