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
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderSilent;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ProposalSettled;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Contract
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
    @Contract
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
                log.debug("Consensus engine reports ready on entry to QuorumWaiting — advancing");
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
                case LeaderCommitted lc -> adoptLeaderUnconditionally(ctx, lc, tx);
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
    @Contract
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
                case LeaderCommitted lc -> adoptLeaderUnconditionally(ctx, lc, tx);
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
    @Contract
    record Electing(LeaderElectionContext ctx,
                    long entryBaseline,
                    AtomicReference<ScheduledFuture<?>> tickFuture,
                    AtomicReference<ScheduledFuture<?>> proposalTimeoutFuture,
                    AtomicReference<ScheduledFuture<?>> observationFuture) implements LeaderElectionState {

        public static Electing fresh(LeaderElectionContext ctx) {
            // FIX-1 refinement: snapshot the highest COMMITTED viewSequence at the instant this
            // node decides to elect. Adoption WITHIN this election requires a commit that POSTDATES
            // the decision (seq > entryBaseline) — the stale pre-death commit (≤ baseline) is never
            // adopted, so a killed leader's corpse in KV cannot resurrect and wedge re-election.
            var entryBaseline = ctx.committedViewSequence();
            var tickFuture = scheduleStaircaseFirstTick(ctx, "Electing");
            var observationFuture = SharedScheduler.scheduleAtFixedRate(
                    () -> adoptLeaderFromKvIfPresent(ctx),
                    ctx.peerObservationInterval());
            return new Electing(ctx,
                                entryBaseline,
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
                case LeaderCommitted lc -> adoptLeaderIfPostElectionCommit(ctx, lc, entryBaseline, "Electing", tx);
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.quorumLost());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case ClusterFsmEvent.NodeAdded na -> handleTopologyChange(ctx, na.topology());
                case ClusterFsmEvent.NodeGone ng -> handleTopologyChange(ctx, ng.topology());
                default -> tx.ignore();
            }
        }
    }

    /// The only data-carrying leadership state — fresh instance per entry, `leader` identifies the
    /// tenure. A FOLLOWER tenure (`leader != self`) also owns a periodic lease `ScheduledFuture`
    /// that re-checks the believed leader's leader-ping freshness and an [`AtomicInteger`] counting
    /// CONSECUTIVE silent checks. After [`LeaderElectionContext#LEADER_SILENCE_THRESHOLD_INTERVALS`]
    /// silent checks the lease trips a `LeaderSilent` → `ReElecting` — the recovery edge for the
    /// crossed-pointer zero-leader wedge (every node `Led(other)`, nobody believes self, no
    /// `NodeGone` for a SWIM-alive leader). A LEADER tenure (`leader == self`) schedules NO timer:
    /// the leader is the one stamping pings, so it cannot observe itself silent. The future is
    /// cancelled in `onExit` / `onCasLost` so a stale lease tick from a prior tenure cannot fire.
    @Contract
    record Led(LeaderElectionContext ctx,
               NodeId leader,
               AtomicReference<ScheduledFuture<?>> leaseFuture,
               AtomicInteger consecutiveSilentChecks) implements LeaderElectionState {

        public static Led fresh(LeaderElectionContext ctx, NodeId leader) {
            return new Led(ctx, leader, new AtomicReference<>(), new AtomicInteger(0));
        }

        @Override
        public void onEntry() {
            ctx.markHasEverHadLeader();
            ctx.setCurrentLeader(Option.some(leader));
            notifyLeaderChange(ctx);
            // Leader-acting lease (follower tenures only). A node leading itself stamps the pings
            // the lease tracks, so it is exempt — scheduling a self-lease would re-elect a healthy
            // leader against its own (necessarily "absent from someone else's view") ping signal.
            if (leader.equals(ctx.self())) {
                return;
            }
            log.debug("Led({}): arming leader-acting lease, warm-up={}ms, check interval={}ms, "
                      + "silence threshold={} intervals",
                      leader, ctx.leaderLeaseWarmup().millis(), ctx.leaderLeaseCheckInterval().millis(),
                      LeaderElectionContext.LEADER_SILENCE_THRESHOLD_INTERVALS);
            // Tenure warm-up: delay the FIRST check by `leaderLeaseWarmup` so a freshly-elected
            // leader has time to activate, broadcast its readiness view, and refresh this
            // follower's freshness cache before the lease can observe it. Without the warm-up the
            // lease trips on every new leader before its first freshness signal can arrive — the
            // perpetual re-election churn seen on loaded hosts.
            var future = SharedScheduler.scheduleAtFixedRate(() -> checkLeaderLease(this),
                                                             ctx.leaderLeaseWarmup(),
                                                             ctx.leaderLeaseCheckInterval());
            leaseFuture.set(future);
        }

        @Override
        public void onExit() {
            cancelFuture(leaseFuture);
        }

        @Override
        public void onCasLost() {
            cancelFuture(leaseFuture);
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
                case LeaderSilent ls -> handleLeaderSilentInLed(ctx, leader, ls, tx);
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

    /// Lease tick body for a follower [`Led`] tenure. Reads the believed leader's ping freshness
    /// via [`LeaderElectionContext#leaderPingFreshFn`]: a fresh ping resets the consecutive-silent
    /// counter to 0; a silent check increments it. Once the counter reaches
    /// [`LeaderElectionContext#LEADER_SILENCE_THRESHOLD_INTERVALS`] a `LeaderSilent(leader)` is
    /// dispatched (the `Led` handler then transitions to `ReElecting`). The dispatch is fenced by
    /// the carried `silentLeader` so a tick that races a leadership swap cannot re-elect away from
    /// the NEW leader. Idempotent against state changes: if the FSM has already left this `Led`
    /// record the synthetic `LeaderSilent` is absorbed (stale-leader guard or `tx.ignore()`).
    private static void checkLeaderLease(Led led) {
        var ctx = led.ctx();
        var leader = led.leader();
        if (ctx.leaderPingFreshFn().test(leader)) {
            led.consecutiveSilentChecks().set(0);
            return;
        }
        var silent = led.consecutiveSilentChecks().incrementAndGet();
        if (silent < LeaderElectionContext.LEADER_SILENCE_THRESHOLD_INTERVALS) {
            log.debug("Led({}): leader ping silent ({}/{} consecutive checks) — lease not yet expired",
                      leader, silent, LeaderElectionContext.LEADER_SILENCE_THRESHOLD_INTERVALS);
            return;
        }
        log.warn("Led({}): leader ping silent for {} consecutive lease checks (threshold {}) — "
                 + "dispatching LeaderSilent to re-elect (recovery edge for SWIM-alive but "
                 + "non-committing leader)",
                 leader, silent, LeaderElectionContext.LEADER_SILENCE_THRESHOLD_INTERVALS);
        dispatchSelf(ctx, new LeaderSilent(leader));
    }

    /// Handles a `LeaderSilent` in [`Led`]. Fenced by `silentLeader`: a `LeaderSilent` naming a
    /// leader other than the one this tenure tracks is STALE (the leader was swapped between the
    /// lease tick and this dispatch) and is discarded at WARN rather than triggering a spurious
    /// re-election. A matching `LeaderSilent` re-elects via the fenced `ReElecting` entry path
    /// (which snapshots the committed-viewSequence baseline, so a stale pre-silence commit cannot
    /// regress the fresh election).
    private static void handleLeaderSilentInLed(LeaderElectionContext ctx,
                                                NodeId currentLeader,
                                                LeaderSilent event,
                                                TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        if (!event.silentLeader().equals(currentLeader)) {
            log.warn("Discarding STALE LeaderSilent({}) in Led({}) — leader already swapped",
                     event.silentLeader(), currentLeader);
            return;
        }
        log.warn("Led({}): leader-acting lease expired — transitioning to ReElecting", currentLeader);
        tx.transitionTo(ctx.reElecting());
    }

    private static void handleLeaderCommittedInLed(LeaderElectionContext ctx,
                                                   NodeId currentLeader,
                                                   LeaderCommitted event,
                                                   TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        if (event.leader().equals(currentLeader)) {
            // Idempotent replay — no transition.
            return;
        }
        // FIX 2 (seq fence, crossed-pointer amplifier): a leader SWAP in Led is applied only when
        // the incoming commit's sequence STRICTLY POSTDATES the sequence of the leader currently
        // adopted. Without the fence a LATER-arriving but LOWER-sequence LeaderCommitted (e.g.
        // node-2 commits seq3 but a straggling seq2 commit lands afterwards) would overwrite the
        // newer leader and amplify a crossed-pointer wedge. A non-positive incoming sequence
        // (legacy sequence-less commit synthesized by KV-pull / local mode) bypasses the fence —
        // those paths carry no sequence and must stay adoptable as before.
        if (!isAdvancingCommit(ctx, event)) {
            log.warn("Discarding STALE LeaderCommitted({}, seq={}) in Led({}) — incoming sequence "
                     + "<= adopted sequence {} (crossed-pointer fence, FIX 2)",
                     event.leader(), event.viewSequence(), currentLeader, ctx.adoptedViewSequence());
            return;
        }
        // FIX 1: a committed leader-SWAP is adopted unconditionally w.r.t. transport — transport
        // presence must not veto a consensus decision. WARN when the new leader is not yet in the
        // transport view for diagnosability; SWIM/FSM death drives correction if it is genuinely gone.
        if (!ctx.currentTopology().contains(event.leader())) {
            log.warn("Adopting leader-swap LeaderCommitted({}) in Led({}) — new leader NOT YET in "
                     + "transport topology {} (consensus decision overrides observed state, FIX 1)",
                     event.leader(), currentLeader, ctx.currentTopology());
        }
        // Valid leader swap (different committed leader, advancing sequence) — record the adopted
        // sequence (fence reference for the NEXT swap) and transition to a new Led instance. This
        // covers local-mode topology-driven re-election and consensus re-elections that commit
        // directly without passing through ReElecting.
        ctx.setAdoptedViewSequence(event.viewSequence());
        tx.transitionTo(Led.fresh(ctx, event.leader()));
    }

    /// Sequence fence for a leader SWAP in [`Led`] (FIX 2). A commit ADVANCES iff it carries a
    /// sequence strictly greater than the sequence of the currently-adopted leader. A non-positive
    /// incoming sequence ([`LeaderCommitted#NO_SEQUENCE`]) marks a legacy sequence-less commit
    /// (KV-pull synthesis / local mode); those bypass the fence so the prior unfenced behaviour is
    /// preserved on the paths that never carried a sequence.
    private static boolean isAdvancingCommit(LeaderElectionContext ctx, LeaderCommitted event) {
        return event.viewSequence() <= LeaderCommitted.NO_SEQUENCE
               || event.viewSequence() > ctx.adoptedViewSequence();
    }

    /// Data-carrying state. Same lifecycle as [`Electing`]: holds the eagerly-scheduled first-tick
    /// `ScheduledFuture`, a slot for the in-flight proposal-timeout dispatcher, AND the
    /// independent peer-observation timer (see [`Electing`] for the rationale — symmetric fix
    /// applies here so a re-election proposal in flight cannot blind the FSM to a peer-committed
    /// leader). All three futures are cancelled in `onExit` / `onCasLost`.
    @Contract
    record ReElecting(LeaderElectionContext ctx,
                      long entryBaseline,
                      AtomicReference<ScheduledFuture<?>> tickFuture,
                      AtomicReference<ScheduledFuture<?>> proposalTimeoutFuture,
                      AtomicReference<ScheduledFuture<?>> observationFuture) implements LeaderElectionState {

        public static ReElecting fresh(LeaderElectionContext ctx) {
            // FIX-1 refinement (see Electing.fresh): snapshot the committed-viewSequence baseline at
            // the decision-to-re-elect. The killed leader's stale commit (seq ≤ baseline) is fenced
            // out of adoption; only a fresh post-death commit (> baseline) is adopted.
            var entryBaseline = ctx.committedViewSequence();
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
            return new ReElecting(ctx, entryBaseline, holder, new AtomicReference<>(),
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
                case LeaderCommitted lc -> adoptLeaderIfPostElectionCommit(ctx, lc, entryBaseline, "ReElecting", tx);
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.quorumLost());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case ClusterFsmEvent.NodeAdded na -> handleTopologyChange(ctx, na.topology());
                case ClusterFsmEvent.NodeGone ng -> handleTopologyChange(ctx, ng.topology());
                default -> tx.ignore();
            }
        }
    }

    @Contract
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

    @Contract
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

    /// Adopt a COMMITTED leader unconditionally (FIX 1, B5-facet-2 600s wedge). A committed
    /// `LeaderKey` is a consensus-validated DECISION; the transport-fed `ctx.currentTopology()`
    /// is OBSERVED state and must NOT veto it. Used by states that have NOT decided to elect
    /// (`QuorumWaiting`, `AwaitingKvSync`, and the `Led` leader-swap) — there, a committed leader
    /// is plain truth and is adopted as-is (this preserves the stuck-joiner fix: a joiner adopts
    /// in `AwaitingKvSync` before ever reaching `Electing`). A transport-absent adoption logs WARN.
    private static void adoptLeaderUnconditionally(LeaderElectionContext ctx,
                                                   LeaderCommitted event,
                                                   TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        if (!ctx.currentTopology().contains(event.leader())) {
            log.warn("Adopting committed LeaderCommitted({}) NOT YET in transport topology {} — "
                     + "consensus decision overrides observed transport state (FIX 1); SWIM/FSM "
                     + "death will drive re-election if the leader is genuinely gone",
                     event.leader(), ctx.currentTopology());
        }
        recordAdoptedSequence(ctx, event);
        tx.transitionTo(Led.fresh(ctx, event.leader()));
    }

    /// Adoption WITHIN an election (`Electing` / `ReElecting`), gated by SEQUENCE (FIX-1
    /// refinement). A node that decided to elect must NOT adopt the commit it was already electing
    /// AWAY from — the killed leader's stale `LeaderKey` (still at its old `viewSequence` N, ≤ the
    /// `entryBaseline` snapshotted on entry) would otherwise resurrect the corpse and wedge
    /// re-election. Only a commit that POSTDATES the decision to elect
    /// (`committedViewSequence() > entryBaseline` — a fresh N+1 from this node's own proposal or a
    /// peer's) is adopted; everyone electing off the same baseline adopts the same winner, so there
    /// is no ping-pong. A stale adoption is skipped at INFO with both sequences. NOTE: gating is on
    /// the OBSERVED committed sequence, not transport/membership — the dead leader stays a
    /// legitimate proposal candidate concern of the live topology view (selection), never of
    /// adoption here.
    private static void adoptLeaderIfPostElectionCommit(LeaderElectionContext ctx,
                                                        LeaderCommitted event,
                                                        long entryBaseline,
                                                        String stateLabel,
                                                        TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        var committed = ctx.committedViewSequence();
        if (committed <= entryBaseline) {
            log.info("{}: skipping STALE LeaderCommitted({}) — committed viewSequence {} <= election "
                     + "entryBaseline {} (pre-election commit; awaiting a fresh post-election commit)",
                     stateLabel, event.leader(), committed, entryBaseline);
            return;
        }
        log.info("{}: adopting post-election LeaderCommitted({}) — committed viewSequence {} > "
                 + "entryBaseline {}", stateLabel, event.leader(), committed, entryBaseline);
        recordAdoptedSequence(ctx, event);
        tx.transitionTo(Led.fresh(ctx, event.leader()));
    }

    /// Records the sequence at which a leader is being adopted into [`Led`], the fence reference
    /// for the next swap (FIX 2). Uses the larger of the commit's own `viewSequence` and the
    /// node's observed committed baseline, so a sequence-less synthetic commit (KV-pull / local
    /// mode, [`LeaderCommitted#NO_SEQUENCE`]) still records the true committed sequence the node
    /// has seen rather than the sentinel.
    private static void recordAdoptedSequence(LeaderElectionContext ctx, LeaderCommitted event) {
        ctx.setAdoptedViewSequence(Math.max(event.viewSequence(), ctx.committedViewSequence()));
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
            if (ctx.currentLeader().filter(leader::equals).isPresent()) {
                return;
            }
            // FIX 1: do NOT gate the KV-pull adoption on transport topology presence. The prior
            // `if (!currentTopology().contains(leader)) return;` silently skipped FOREVER on every
            // 500ms tick when the committed leader was absent from the transport view — the second
            // root of the 600s wedge (one joiner self-healed only by luck, the other never did).
            // The KV leader is a consensus-validated decision; adopt it. A transport-absent leader
            // is logged WARN here; the `adoptLeaderUnconditionally` handler emits the adoption WARN.
            if (!ctx.currentTopology().contains(leader)) {
                log.warn("KV-pull: committed leader {} NOT in transport topology {} — adopting anyway "
                         + "(consensus decision overrides observed state, FIX 1)",
                         leader, ctx.currentTopology());
            } else {
                log.info("Adopting leader from KV-Store: {} (push-notification path missed)", leader);
            }
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
            // GUARD 6 (election-trigger swallow): a reschedule requested from a non-electing state
            // is dropped. Benign when the FSM legitimately moved on (e.g. a leader committed), but
            // WARN-worthy because if it happens during an active election it leaves no pending tick.
            default -> log.warn("triggerElection swallowed by GUARD[non-electing-state]: "
                                + "rescheduleCurrentTick from {} — no tick scheduled",
                                ctx.fsm().current().getClass().getSimpleName());
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
        Option.option(holder.getAndSet(future)).onPresent(prior -> prior.cancel(false));
    }

    private static void trySubmitProposal(LeaderElectionContext ctx,
                                          LeaderElectionState owner) {
        ctx.proposalHandler()
           .onPresent(handler -> submitProposalWith(ctx, owner, handler))
           .onEmpty(() -> {
               // GUARD 1 (election-trigger swallow): no consensus proposal handler wired (local
               // mode / mis-wired). The trigger cannot produce a consensus proposal — reschedule.
               log.warn("triggerElection swallowed by GUARD[no-proposal-handler]: "
                        + "no proposal handler configured — rescheduling tick");
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
        // tick time — `submitProposalWith` exits, FSM never re-fires. Each guard WARNs naming
        // the swallowing condition so a stuck election is diagnosable from logs alone.
        if (ctx.currentTopology().isEmpty()) {
            // GUARD 2 (election-trigger swallow).
            log.warn("triggerElection swallowed by GUARD[empty-topology]: no peers in transport "
                     + "view — skipping proposal, rescheduling tick");
            rescheduleCurrentTick(ctx);
            return;
        }
        var pool = ctx.candidatePool().stream().sorted().toList();
        if (pool.isEmpty()) {
            // GUARD 3 (election-trigger swallow).
            log.warn("triggerElection swallowed by GUARD[empty-candidate-pool]: no eligible "
                     + "candidates — skipping proposal, rescheduling tick");
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
            // GUARD 4 (election-trigger swallow): proposal already in flight — DO NOT reschedule.
            // The proposal-timeout future (set in `sendProposal`) will fire
            // `ProposalSettled.failure`, which triggers `rescheduleCurrentTick` via
            // `handleProposalSettled`. Rescheduling here would burst-tick during a normal
            // in-flight proposal.
            log.warn("triggerElection swallowed by GUARD[proposal-in-flight]: a proposal is "
                     + "already in flight — skipping (timeout will reschedule)");
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
        var holderOpt = Option.option(switch (owner) {
            case Electing e -> e.proposalTimeoutFuture();
            case ReElecting r -> r.proposalTimeoutFuture();
            default -> null;
        });
        holderOpt.onEmpty(() -> cancelImmediatelyForNonElecting(future))
                 .onPresent(holder -> swapAndCancelPrior(holder, future));
    }

    private static void cancelImmediatelyForNonElecting(ScheduledFuture<?> future) {
        log.debug("storeProposalTimeoutFuture: owner is not electing — cancelling timeout immediately");
        future.cancel(false);
    }

    private static void swapAndCancelPrior(AtomicReference<ScheduledFuture<?>> holder, ScheduledFuture<?> future) {
        Option.option(holder.getAndSet(future)).onPresent(prior -> prior.cancel(false));
    }

    private static void handleProposalSettled(LeaderElectionContext ctx, ProposalSettled event) {
        if (!ctx.proposalInFlight()) {
            // GUARD 5 (election-trigger swallow): a ProposalSettled arrived after the in-flight
            // flag was already cleared (a prior settle / state exit handled it). The settle is
            // dropped — no retry is scheduled off it. Benign in the normal duplicate-settle case,
            // but WARN-worthy because a dropped FAILURE settle here is exactly the shape of a
            // stuck election (no retry tick follows).
            log.warn("triggerElection swallowed by GUARD[settle-not-in-flight]: late "
                     + "ProposalSettled({}, success={}) — inFlight already cleared, no retry off it",
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
        Option.option(holder.getAndSet(null)).onPresent(future -> future.cancel(false));
    }
}
