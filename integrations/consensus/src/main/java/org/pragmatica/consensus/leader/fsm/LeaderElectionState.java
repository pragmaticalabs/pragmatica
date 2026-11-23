/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.consensus.leader.LeaderNotification;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ConsensusReady;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ElectionTick;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.KvSyncGraceTimeout;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderCommitted;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderDoubtConfirmed;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderOutOfView;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.LeaderSilent;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.PeerRemovedFromView;
import org.pragmatica.consensus.leader.fsm.LeaderElectionEvents.ProposalSettled;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


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
///
/// View application (#3092, #3426) follows three invariants, pinned cell by cell (state x view event x mode) by
/// `ViewApplicationTableTest`; no state keeps a local rule of its own about a view:
/// - I1: an applied view is stored as it is, an empty one included ([LeaderElectionContext#currentTopology()] never
///   returns a view the transport has replaced).
/// - I2: after every view write, every local leader adoption and every `Passive` entry, the leader the state holds
///   (`Led`'s leader, `Passive`'s observed leader) is in the stored eligible view and is not the node a removal just
///   named failed, or it is lost. ONE predicate decides membership (`isInStoredView`; every in-view test reads it),
///   and I2 is decided at two places only: the admission `admittedToView` and the post-change site
///   `holdLeaderToView`, which every state's view arms run through `storeView` / `storeRemoval`, a local `Led` runs on
///   entry (a leader adopted while a concurrent view dropped it is caught after the transition), and `Passive` runs on
///   every entry, whatever the predecessor, before its pull. The admission splits by state (#3581):
///   - the VOTING states adopt a cluster commit even when absent from the view (FIX 1: a consensus decision the node
///     took part in; the next view checks it); only a local commit passes `admittedToView`.
///   - `Passive` is an observer: a committed leader, pushed or pulled, passes the ONE admission
///     `observeCommittedLeader`, which includes `admittedToView`, and every sequenced `Passive` view write re-runs the
///     pull after `holdLeaderToView`, so `Passive` holds a leader iff it is committed AND in the stored view (a refused
///     or lagging push heals on the next view write; a view-free removal only shrinks the view and re-pulls nothing).
/// - I3: a leaderless electing state makes progress on its own ticks in local mode as in cluster mode: a local tick
///   elects the first node of the view, and a local commit is not gated on a sequence local mode never advances.
public sealed interface LeaderElectionState extends FsmState<LeaderElectionState, ClusterFsmEvent> permits LeaderElectionState.Dormant, LeaderElectionState.QuorumWaiting, LeaderElectionState.AwaitingKvSync, LeaderElectionState.Electing, LeaderElectionState.Led, LeaderElectionState.ReElecting, LeaderElectionState.QuorumLost, LeaderElectionState.Stopped, LeaderElectionState.Passive {
    Logger log = LoggerFactory.getLogger(LeaderElectionState.class);

    /// Marker no-op used in `tx.handle(...)` arms where an event is intentionally absorbed
    /// without a transition or side-effect. Distinct from `tx.ignore()` to make the explicit
    /// "handled, but no work to do" cases legible at the call site.
    Runnable NO_ACTION_DORMANT = () -> {};

    LeaderElectionContext ctx();

    /// Delivers an event to this FSM instance in the node's one total order (#3587, [LeaderElectionContext#submit]):
    /// from a handler (already inside a task) it is dispatched at once; from a timer or a proposal completion it is
    /// queued behind every event submitted before it.
    private static void dispatchSelf(LeaderElectionContext ctx, ClusterFsmEvent event) {
        ctx.submit(event);
    }

    // --- State records ---
    @Contract
    record Dormant(LeaderElectionContext ctx) implements LeaderElectionState {
        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case LeaderElectionEvents.PassiveDirectory directory -> tx.transitionTo(new Passive(ctx,
                                                                                                    directory.members()));
                case ClusterFsmEvent.QuorumEstablished _ -> tx.transitionTo(ctx.quorumWaiting());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                // ConsensusReady arriving in Dormant is acknowledged but causes no transition —
                // the SSOT for consensus readiness is the consensus engine itself, queried on
                // entry to QuorumWaiting via `ctx.consensusReadySupplier()`.
                case ConsensusReady _ -> tx.handle(NO_ACTION_DORMANT);
                case ClusterFsmEvent.NodeAdded na -> tx.handle(() -> storeView(ctx, na));
                case ClusterFsmEvent.NodeGone ng -> tx.handle(() -> storeView(ctx, ng));
                case PeerRemovedFromView removed -> tx.handle(() -> storeRemoval(ctx, removed));
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
    record QuorumWaiting(LeaderElectionContext ctx, AtomicReference<ScheduledFuture<?>> pollFuture) implements LeaderElectionState {
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
            // below advances the FSM. Synchronous dispatch — the hook runs inside a task of the node's
            // serial executor (#3587), so re-entry into `handle` happens before this method returns.
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
            var future = SharedScheduler.scheduleAtFixedRate(ctx.serialized(() -> pollConsensusAndKv(ctx)),
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
                case LeaderElectionEvents.PassiveDirectory directory -> tx.transitionTo(new Passive(ctx,
                                                                                                    directory.members()));
                case ConsensusReady _ -> tx.transitionTo(ctx.awaitingKvSync());
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.dormant());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case ClusterFsmEvent.NodeAdded na -> storeView(ctx, na);
                case ClusterFsmEvent.NodeGone ng -> storeView(ctx, ng);
                case PeerRemovedFromView removed -> storeRemoval(ctx, removed);
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
    /// The timeout is not a wall-clock licence to elect (#1803). A node that joined a running cluster must
    /// not depose a live leader it has simply not read yet, so at the timeout it first follows any committed
    /// leader it can see (the KV record; on entry also the leader it observed while passive, before it was
    /// admitted as a voter), and while its KV state is still catching up ([`LeaderElectionContext#isKvSyncPending`]) it
    /// waits another grace window, up to [`LeaderElectionContext#kvSyncMaxWait`]. Only a cluster that shows
    /// no leader and no sync in flight — or a sync that never settles — reaches `Electing`.
    ///
    /// On entry the state ALSO consults [`LeaderElectionContext#currentLeaderFromKvSupplier`]
    /// (pull-side) and dispatches a synthetic `LeaderCommitted` if the local KV already records
    /// a leader. This handles the auto-heal path: a single replacement node joining a live
    /// cluster receives the leader via KV-sync before this state's `onEntry` runs, so the
    /// pull-side check fires immediately and the state passes through in a single dispatch tick
    /// without paying any grace latency.
    @Contract
    record AwaitingKvSync(LeaderElectionContext ctx,
                          AtomicReference<ScheduledFuture<?>> graceTimeoutFuture,
                          long enteredAtNanos) implements LeaderElectionState {
        public static AwaitingKvSync fresh(LeaderElectionContext ctx) {
            return new AwaitingKvSync(ctx, new AtomicReference<>(), System.nanoTime());
        }

        @Override
        public void onEntry() {
            // Pull-side fast path — if KV already has a leader (auto-heal: replacement node
            // joined an existing cluster, KVSync already applied LeaderKey before we got here),
            // synthesize LeaderCommitted now. Synchronous dispatch — re-entry into `handle`
            // happens before this method returns and transitions to `Led(leader)`.
            adoptLeaderFromKvIfPresent(ctx);
            // #1803: a leader this node already observed (while passive, before it was admitted as a
            // voter) is a committed leader too. The KV pull above skips a leader equal to the one
            // already known, so without this a joiner never left the wait on the strength of it.
            if (ctx.fsm().current() instanceof AwaitingKvSync) {
                adoptKnownLeader(ctx);
            }
            // If the synchronous dispatch already advanced us out of AwaitingKvSync, skip
            // scheduling the grace timer — this record's CAS reservation is gone, but the new
            // state owns its own timers.
            if (! (ctx.fsm().current() instanceof AwaitingKvSync)) {
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
                case LeaderElectionEvents.PassiveDirectory directory -> tx.transitionTo(new Passive(ctx,
                                                                                                    directory.members()));
                case LeaderCommitted lc -> adoptLeaderUnconditionally(ctx, lc, tx);
                case KvSyncGraceTimeout _ -> graceTimeoutFallthrough(ctx, tx, this);
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.quorumLost());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case ClusterFsmEvent.NodeAdded na -> storeView(ctx, na);
                case ClusterFsmEvent.NodeGone ng -> storeView(ctx, ng);
                case PeerRemovedFromView removed -> storeRemoval(ctx, removed);
                default -> tx.ignore();
            }
        }
    }

    /// The wait's verdict at a grace timeout (#1803). A committed leader in the KV record is adopted even if its
    /// push notification never reached this node — a joiner never elects against a live leader. With none
    /// visible and KV state still catching up, the wait is stretched by another grace window (bounded by
    /// [`LeaderElectionContext#kvSyncMaxWait`]) so the leader can still arrive through KV sync. Only a
    /// cluster that shows no leader and no sync in flight is genuinely leaderless and lets this node elect.
    private static void graceTimeoutFallthrough(LeaderElectionContext ctx,
                                                TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx,
                                                AwaitingKvSync waiting) {
        // The transitioning states keep the sequence-less pull (NO_SEQUENCE, FIX 1); only Passive adopts the record (#3581).
        ctx.currentLeaderFromKvSupplier()
           .get()
           .map(LeaderCommitted::leader)
           .onPresent(leader -> adoptVisibleLeader(ctx, leader, tx))
           .onEmpty(() -> electOrKeepWaiting(ctx, tx, waiting));
    }

    private static void adoptVisibleLeader(LeaderElectionContext ctx,
                                           NodeId leader,
                                           TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        log.info("AwaitingKvSync grace window elapsed with committed leader {} visible — following it, not electing",
                 leader);
        adoptLeaderUnconditionally(ctx, new LeaderCommitted(leader), tx);
    }

    private static void electOrKeepWaiting(LeaderElectionContext ctx,
                                           TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx,
                                           AwaitingKvSync waiting) {
        if (ctx.isKvSyncPending() && waitedLessThanMax(ctx, waiting)) {
            tx.handle(() -> rearmGrace(ctx, waiting));

            return;
        }

        log.info("AwaitingKvSync grace window elapsed without observing a committed leader — proceeding to election");
        if (ctx.hasEverHadLeader()) {
            tx.transitionTo(ctx.reElecting());
        } else {
            tx.transitionTo(ctx.electing());
        }
    }

    private static boolean waitedLessThanMax(LeaderElectionContext ctx, AwaitingKvSync waiting) {
        return System.nanoTime() - waiting.enteredAtNanos() < ctx.kvSyncMaxWait()
                                                                 .nanos();
    }

    private static void rearmGrace(LeaderElectionContext ctx, AwaitingKvSync waiting) {
        log.info("AwaitingKvSync: KV state still catching up and no committed leader visible yet — waiting another {}ms",
                 ctx.kvSyncGraceDelay().millis());
        waiting.graceTimeoutFuture()
               .set(SharedScheduler.schedule(() -> dispatchSelf(ctx,
                                                                new KvSyncGraceTimeout()),
                                             ctx.kvSyncGraceDelay()));
    }

    /// Adopts the leader this node already knows. Synchronous dispatch, like the KV pull: the `LeaderCommitted`
    /// handler moves the FSM to `Led` before this returns.
    private static void adoptKnownLeader(LeaderElectionContext ctx) {
        ctx.currentLeader().onPresent(leader -> dispatchSelf(ctx, new LeaderCommitted(leader)));
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
            var tickFuture = scheduleStaircaseFirstTick(ctx, "Electing", Option.none());
            var observationFuture = SharedScheduler.scheduleAtFixedRate(ctx.serialized(() -> adoptLeaderFromKvIfPresent(ctx)),
                                                                        ctx.peerObservationInterval());

            return new Electing(ctx,
                                entryBaseline,
                                new AtomicReference<>(tickFuture),
                                new AtomicReference<>(),
                                new AtomicReference<>(observationFuture));
        }

        @Override
        public void onEntry() {
            ctx.clearProposalInFlight();
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
                case LeaderElectionEvents.PassiveDirectory directory -> tx.transitionTo(new Passive(ctx,
                                                                                                    directory.members()));
                case ElectionTick _ -> tx.handle(() -> {
                    adoptLeaderFromKvIfPresent(ctx);
                    trySubmitProposal(ctx, this);
                });
                case ProposalSettled ps -> tx.handle(() -> handleProposalSettled(ctx, ps));
                case LeaderCommitted lc -> adoptLeaderIfPostElectionCommit(ctx, lc, entryBaseline, "Electing", tx);
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.quorumLost());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case ClusterFsmEvent.NodeAdded na -> handleTopologyChange(ctx, na);
                case ClusterFsmEvent.NodeGone ng -> handleTopologyChange(ctx, ng);
                case PeerRemovedFromView removed -> handleRemoval(ctx, removed);
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
    ///
    /// A follower that loses the leader (`NodeGone(leader)`, or the lease expiring) does not go to
    /// `ReElecting` on that private view when the leader pre-vote is enabled (#1748): it stays in
    /// `Led(leader)` and asks the electorate through [`LeaderPreVote`]; only [`LeaderDoubtConfirmed`] — a majority
    /// affirmatively doubting the leader — takes it to `ReElecting`. Without the pre-vote (local mode, stubs)
    /// the edge is immediate, as before.
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
            if (leftTheViewOnLocalEntry()) {
                return;
            }

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
                      leader,
                      ctx.leaderLeaseWarmup().millis(),
                      ctx.leaderLeaseCheckInterval().millis(),
                      LeaderElectionContext.LEADER_SILENCE_THRESHOLD_INTERVALS);
            // Tenure warm-up: delay the FIRST check by `leaderLeaseWarmup` so a freshly-elected
            // leader has time to activate, broadcast its readiness view, and refresh this
            // follower's freshness cache before the lease can observe it. Without the warm-up the
            // lease trips on every new leader before its first freshness signal can arrive — the
            // perpetual re-election churn seen on loaded hosts.
            var future = SharedScheduler.scheduleAtFixedRate(ctx.serialized(() -> checkLeaderLease(this)),
                                                             ctx.leaderLeaseWarmup(),
                                                             ctx.leaderLeaseCheckInterval());

            leaseFuture.set(future);
        }

        /// #3092 I2 after a local leader adoption: a local commit names a node of the view it was elected from, but a
        /// view written by an earlier event may have dropped that node between the commit's admission and this
        /// transition. The I2 predicate is read here, BEFORE the leader is published, so such a tenure announces
        /// nothing; the hook does not dispatch (`FsmState`, #3587): the loss is queued as `LeaderOutOfView`, which
        /// this tenure handles as the next event in order, as the I2 site's own event ([#holdLeaderToView]).
        /// Cluster mode is exempt: a consensus commit is adopted when absent from the view (FIX 1), and the next view
        /// checks it.
        private boolean leftTheViewOnLocalEntry() {
            if (!ctx.isLocalMode() || isInStoredView(ctx, leader)) {
                return false;
            }

            ctx.enqueue(new LeaderOutOfView(leader));

            return true;
        }

        @Override
        public void onExit() {
            cancelFuture(leaseFuture);
            ctx.preVote().onPresent(preVote -> preVote.cancel(leader));
        }

        @Override
        public void onCasLost() {
            cancelFuture(leaseFuture);
        }

        /// Whether this follower has lost the leader on its own evidence: the leader is gone from its transport
        /// view, or its leader ping has been silent for the full lease threshold. Read by the pre-vote both to
        /// keep retrying while it holds and to answer other followers' questions about this leader.
        public boolean isLeaderSuspected() {
            return ! isInStoredView(ctx, leader) || consecutiveSilentChecks.get() >= LeaderElectionContext.LEADER_SILENCE_THRESHOLD_INTERVALS;
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case LeaderElectionEvents.PassiveDirectory directory -> tx.transitionTo(new Passive(ctx,
                                                                                                    directory.members()));
                case ClusterFsmEvent.NodeGone ng -> storeView(ctx, ng);
                case PeerRemovedFromView removed -> storeRemoval(ctx, removed);
                case ClusterFsmEvent.NodeAdded na -> storeView(ctx, na);
                case LeaderOutOfView out -> handleLeaderOutOfViewInLed(ctx, this, out, tx);
                case LeaderCommitted lc -> handleLeaderCommittedInLed(ctx, leader, lc, tx);
                case LeaderSilent ls -> handleLeaderSilentInLed(ctx, this, ls, tx);
                case LeaderDoubtConfirmed dc -> handleLeaderDoubtConfirmedInLed(ctx, this, dc, tx);
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
                      leader,
                      silent,
                      LeaderElectionContext.LEADER_SILENCE_THRESHOLD_INTERVALS);

            return;
        }

        log.warn("Led({}): leader ping silent for {} consecutive lease checks (threshold {}) — "
                + "dispatching LeaderSilent to re-elect (recovery edge for SWIM-alive but "
                + "non-committing leader)",
                 leader,
                 silent,
                 LeaderElectionContext.LEADER_SILENCE_THRESHOLD_INTERVALS);
        dispatchSelf(ctx, new LeaderSilent(leader));
    }

    /// Handles a `LeaderSilent` in [`Led`]. Fenced by `silentLeader`: a `LeaderSilent` naming a
    /// leader other than the one this tenure tracks is STALE (the leader was swapped between the
    /// lease tick and this dispatch) and is discarded at WARN rather than triggering a spurious
    /// re-election. A matching `LeaderSilent` re-elects via the fenced `ReElecting` entry path
    /// (which snapshots the committed-viewSequence baseline, so a stale pre-silence commit cannot
    /// regress the fresh election).
    private static void handleLeaderSilentInLed(LeaderElectionContext ctx,
                                                Led led,
                                                LeaderSilent event,
                                                TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        var currentLeader = led.leader();

        if (!event.silentLeader().equals(currentLeader)) {
            log.warn("Discarding STALE LeaderSilent({}) in Led({}) — leader already swapped",
                     event.silentLeader(),
                     currentLeader);

            return;
        }

        log.warn("Led({}): leader-acting lease expired — leader lost", currentLeader);
        loseLeader(ctx,
                   led,
                   Option.some(led.leader()),
                   tx);
    }

    /// The one #3092/#3426 I2 site: the leader the current state holds (`Led`'s leader, `Passive`'s observed leader;
    /// every other state holds none) must be in the stored eligible view, otherwise a [LeaderOutOfView] is dispatched
    /// and the state loses it (`Led` through [#loseLeader], with the pre-vote asking first when enabled, #1748;
    /// `Passive` drops it). It reads the STORED view after the write, whether or not the event's own view applied: a
    /// stale event (#2971) writes nothing, so the view it checks is the newer one, and a view that `LeaderManager`'s
    /// local paths applied ahead of the dispatch (#3044) is checked here too. Runs after every view write
    /// ([#storeView], [#storeRemoval]), on a local `Led` entry and on every `Passive` entry; no other path checks the
    /// leader against the view.
    /// `namedFailed` is the node a view-free removal named (#3044): SWIM evidence that it failed, so a held leader it
    /// names is lost even where the storage KEPT it (it is this node, or the last node left), the one case the stored
    /// view cannot express. A view write names none.
    private static void holdLeaderToView(LeaderElectionContext ctx, Option<NodeId> namedFailed) {
        heldLeader(ctx.fsm().current()).filter(leader -> !isInStoredView(ctx, leader) || namedFailed.filter(leader::equals)
                                                                                                    .isPresent())
                  .onPresent(leader -> dispatchSelf(ctx,
                                                    new LeaderOutOfView(leader)));
    }

    /// The one #3092 I2 membership predicate: `node` is in the stored eligible view. Every in-view test in this file
    /// reads it; the I2 decisions are the admission [#admittedToView] and [#holdLeaderToView].
    private static boolean isInStoredView(LeaderElectionContext ctx, NodeId node) {
        return ctx.currentTopology()
                  .contains(node);
    }

    private static Option<NodeId> heldLeader(LeaderElectionState state) {
        return switch (state) {
            case Led led -> Option.some(led.leader());
            case Passive passive -> passive.ctx().storedLeader();
            default -> Option.none();
        };
    }

    /// I1 then I2 for a sequenced view event, in every state: store the event's view (dropped when stale), then hold
    /// the leader to the stored view. Returns `true` iff the event's view was applied.
    private static boolean storeView(LeaderElectionContext ctx, ClusterFsmEvent.TopologyChange event) {
        var applied = ctx.applyTopology(event);

        holdLeaderToView(ctx, Option.none());

        return applied;
    }

    /// I2 for a view-free removal, in every state: remove the node (the storage keeps an absent node, self and the last
    /// node), then hold the leader to the stored view with the removed node named failed, so a held leader it names is
    /// lost whatever the storage kept. Returns `true` iff the node was removed.
    private static boolean storeRemoval(LeaderElectionContext ctx, PeerRemovedFromView event) {
        var removed = ctx.removeFromView(event.node());

        holdLeaderToView(ctx,
                         Option.some(event.node()));

        return removed;
    }

    /// The I2 site found this tenure's leader absent from the stored view, or named failed by a removal. Fenced by the carried leader, like
    /// [LeaderSilent]: a check made for an earlier tenure is discarded. No suspect (#2869): a leader absent from the
    /// view is already out of the transport topology (and a removal names it failed).
    private static void handleLeaderOutOfViewInLed(LeaderElectionContext ctx,
                                                   Led led,
                                                   LeaderOutOfView event,
                                                   TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        if (event.leader().equals(led.leader())) {
            loseLeader(ctx, led, Option.none(), tx);
        }
    }

    /// This follower lost its leader on its own evidence. With the pre-vote enabled it asks the electorate
    /// first and stays in `Led` (#1748); the pre-vote does not apply to a leader that is this node itself or
    /// no longer eligible (voted out — nothing for the electorate to vouch for), or when this node is the whole electorate (nobody to ask; the verdict would dispatch re-entrantly from inside this handler), and is absent in local mode.
    /// Without the pre-vote, `suspect` becomes the [`ReElecting`] suspect (#2869): the lease-trip path names the
    /// silent leader, which may still sit in the transport topology; the NodeGone path passes none.
    private static void loseLeader(LeaderElectionContext ctx,
                                   Led led,
                                   Option<NodeId> suspect,
                                   TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        ctx.preVote()
           .filter(_ -> !led.leader()
                            .equals(ctx.self())
                        && ctx.isEligible(led.leader())
                        && ctx.electorate()
                              .size() > 1)
           .onPresent(preVote -> tx.handle(() -> preVote.suspect(led.leader())))
           .onEmpty(() -> tx.transitionTo(ctx.reElecting(suspect)));
    }

    /// A majority of the electorate doubts the leader. Honoured only for the tenure it was asked about and
    /// only while the follower still suspects that leader on its own evidence (the leader may have come back
    /// while the round ran).
    private static void handleLeaderDoubtConfirmedInLed(LeaderElectionContext ctx,
                                                        Led led,
                                                        LeaderDoubtConfirmed event,
                                                        TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        if (!event.leader().equals(led.leader()) || !led.isLeaderSuspected()) {
            log.info("Led({}): discarding leader-doubt verdict for {} — tenure changed or the leader is back",
                     led.leader(),
                     event.leader());

            return;
        }

        log.warn("Led({}): a majority of the electorate doubts the leader — transitioning to ReElecting", led.leader());
        tx.transitionTo(ctx.reElecting(Option.some(led.leader())));
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
                     event.leader(),
                     event.viewSequence(),
                     currentLeader,
                     ctx.adoptedViewSequence());

            return;
        }
        // FIX 1: a committed leader-SWAP is adopted unconditionally w.r.t. transport — transport
        // presence must not veto a consensus decision. WARN when the new leader is not yet in the
        // transport view for diagnosability; SWIM/FSM death drives correction if it is genuinely gone.
        if (!isInStoredView(ctx, event.leader())) {
            log.warn("Adopting leader-swap LeaderCommitted({}) in Led({}) — new leader NOT YET in "
                    + "transport topology {} (consensus decision overrides observed state, FIX 1)",
                     event.leader(),
                     currentLeader,
                     ctx.currentTopology());
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
        return event.viewSequence() <= LeaderCommitted.NO_SEQUENCE || event.viewSequence() > ctx.adoptedViewSequence();
    }

    /// Data-carrying state. Same lifecycle as [`Electing`]: holds the eagerly-scheduled first-tick
    /// `ScheduledFuture`, a slot for the in-flight proposal-timeout dispatcher, AND the
    /// independent peer-observation timer (see [`Electing`] for the rationale — symmetric fix
    /// applies here so a re-election proposal in flight cannot blind the FSM to a peer-committed
    /// leader). All three futures are cancelled in `onExit` / `onCasLost`.
    ///
    /// `suspect` (#2869) is the leader this node doubted when it decided to re-elect (lease trip or a
    /// majority-confirmed pre-vote doubt). It lives for this instance only — the next entry starts from
    /// whatever its own trigger names — and only steers candidate selection and the staircase rank:
    /// a commit naming the suspect is still adopted, because rejecting a consensus decision would split views.
    @Contract
    record ReElecting(LeaderElectionContext ctx,
                      long entryBaseline,
                      Option<NodeId> suspect,
                      AtomicReference<ScheduledFuture<?>> tickFuture,
                      AtomicReference<ScheduledFuture<?>> proposalTimeoutFuture,
                      AtomicReference<ScheduledFuture<?>> observationFuture) implements LeaderElectionState {
        public static ReElecting fresh(LeaderElectionContext ctx) {
            return fresh(ctx, Option.none());
        }

        public static ReElecting fresh(LeaderElectionContext ctx, Option<NodeId> suspect) {
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
            var holder = new AtomicReference<ScheduledFuture<?>>(scheduleStaircaseFirstTick(ctx, "ReElecting", suspect));
            var observationFuture = SharedScheduler.scheduleAtFixedRate(ctx.serialized(() -> adoptLeaderFromKvIfPresent(ctx)),
                                                                        ctx.peerObservationInterval());

            return new ReElecting(ctx,
                                  entryBaseline,
                                  suspect,
                                  holder,
                                  new AtomicReference<>(),
                                  new AtomicReference<>(observationFuture));
        }

        @Override
        public void onEntry() {
            ctx.clearProposalInFlight();
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
                case LeaderElectionEvents.PassiveDirectory directory -> tx.transitionTo(new Passive(ctx,
                                                                                                    directory.members()));
                case ElectionTick _ -> tx.handle(() -> {
                    adoptLeaderFromKvIfPresent(ctx);
                    trySubmitProposal(ctx, this);
                });
                case ProposalSettled ps -> tx.handle(() -> handleProposalSettled(ctx, ps));
                case LeaderCommitted lc -> adoptLeaderIfPostElectionCommit(ctx, lc, entryBaseline, "ReElecting", tx);
                case ClusterFsmEvent.QuorumDisappeared _ -> tx.transitionTo(ctx.quorumLost());
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                case ClusterFsmEvent.NodeAdded na -> handleTopologyChange(ctx, na);
                case ClusterFsmEvent.NodeGone ng -> handleTopologyChange(ctx, ng);
                case PeerRemovedFromView removed -> handleRemoval(ctx, removed);
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
                case LeaderElectionEvents.PassiveDirectory directory -> tx.transitionTo(new Passive(ctx,
                                                                                                    directory.members()));
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
                case ClusterFsmEvent.NodeAdded na -> storeView(ctx, na);
                case ClusterFsmEvent.NodeGone ng -> storeView(ctx, ng);
                case PeerRemovedFromView removed -> storeRemoval(ctx, removed);
                case LeaderCommitted lc -> log.warn("Rejecting stale LeaderCommitted({}) in QuorumLost", lc.leader());
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
            // Terminal: no transition. A view is still stored (#3092 I1, the same in local and cluster mode).
            switch (event) {
                case ClusterFsmEvent.NodeAdded na -> tx.handle(() -> storeView(ctx, na));
                case ClusterFsmEvent.NodeGone ng -> tx.handle(() -> storeView(ctx, ng));
                case PeerRemovedFromView removed -> tx.handle(() -> storeRemoval(ctx, removed));
                default -> tx.ignore();
            }
        }
    }

    /// A worker observes committed leadership without entering any core election or quorum state.
    @Contract
    record Passive(LeaderElectionContext ctx, java.util.List<NodeId> members) implements LeaderElectionState {
        @Override
        public void onEntry() {
            ctx.installVoters(members);
            // #3581 I2 at every entry, whatever the predecessor: the stored leader handed over (a voting state's, which
            // FIX 1 may hold out of view; or one the entered members make ineligible) is held to the stored eligible
            // view and its loss announced, BEFORE the pull, so no later clear undoes the pull. The hook does not
            // dispatch (`FsmState`, #3587): it drops the leader itself, exactly as `Passive`'s `LeaderOutOfView` arm.
            dropLeaderOutOfViewOnEntry(ctx);
            // #3414: Passive can be entered after the committed leader's notification passed (Dormant drops it), so the
            // entry reads the committed leader key, as the other entry states do.
            observeCommittedLeaderFromKv(ctx);
        }

        @Override
        public void handle(ClusterFsmEvent event, TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
            switch (event) {
                case LeaderElectionEvents.PassiveDirectory directory -> tx.transitionTo(new Passive(ctx,
                                                                                                    directory.members()));
                case LeaderElectionEvents.VoterReadmitted _ -> tx.transitionTo(ctx.quorumWaiting());
                case LeaderCommitted committed -> tx.handle(() -> observeCommittedLeader(ctx, committed));
                case LeaderOutOfView out -> tx.handle(() -> dropObservedLeader(ctx, out));
                case ClusterFsmEvent.Shutdown _ -> tx.transitionTo(ctx.stopped());
                // #3092 I1: a readmitted worker elects from the view the transport last applied, not a replaced one.
                case ClusterFsmEvent.NodeAdded na -> tx.handle(() -> storePassiveView(ctx, na));
                case ClusterFsmEvent.NodeGone ng -> tx.handle(() -> storePassiveView(ctx, ng));
                // No re-pull after a view-free removal: it only shrinks the view, so it cannot admit a leader the view
                // refused before it, and a re-pull would re-admit the held leader it named failed where the storage
                // kept it (I2's second half, [#holdLeaderToView]).
                case PeerRemovedFromView removed -> tx.handle(() -> storeRemoval(ctx, removed));
                default -> tx.ignore();
            }
        }
    }

    /// `Passive`'s sequenced view write (#3581): I1 then I2 as in every state ([#storeView]), then the pull re-runs, so
    /// the committed leader is admitted on the view write that brings it into the stored view.
    private static void storePassiveView(LeaderElectionContext ctx, ClusterFsmEvent.TopologyChange event) {
        storeView(ctx, event);
        observeCommittedLeaderFromKv(ctx);
    }

    /// Passive's one admission (#3581), for the commit `LeaderManager` pushes and the record the entry pulls alike: the
    /// leader is eligible and not this node, the sequence is a real commit not older than the adopted one (an equal one
    /// only for the leader adopted at it), and the leader is in the stored view ([#admittedToView], I2's admission).
    private static void observeCommittedLeader(LeaderElectionContext ctx, LeaderCommitted committed) {
        if (!ctx.isEligible(committed.leader()) || committed.leader().equals(ctx.self()) || committed.viewSequence() <= LeaderCommitted.NO_SEQUENCE || committed.viewSequence() < ctx.adoptedViewSequence() || (committed.viewSequence() == ctx.adoptedViewSequence() && ctx.lastAdoptedLeader()
                                                                                                                                                                                                                                                                            .filter(committed.leader()::equals)
                                                                                                                                                                                                                                                                            .isEmpty())) {
            return;
        }

        if (!admittedToView(ctx, committed, "Passive")) {
            return;
        }

        ctx.setAdoptedViewSequence(committed.viewSequence());
        ctx.setCurrentLeader(Option.some(committed.leader()));
        notifyLeaderChange(ctx);
    }

    /// `Passive.onEntry`'s I2 check (#3587): the predicate of [#holdLeaderToView] with no named node, applied without an
    /// event: the stored leader absent from the stored view is dropped and its loss announced ([#dropObservedLeader]).
    private static void dropLeaderOutOfViewOnEntry(LeaderElectionContext ctx) {
        ctx.storedLeader().filter(leader -> !isInStoredView(ctx, leader)).onPresent(_ -> clearLeaderAndNotify(ctx));
    }

    /// `Passive` side of the I2 site: the observed leader the site found absent from the stored view is dropped
    /// (F-a4), unless a newer commit replaced it meanwhile.
    private static void dropObservedLeader(LeaderElectionContext ctx, LeaderOutOfView event) {
        ctx.storedLeader().filter(event.leader()::equals).onPresent(_ -> clearLeaderAndNotify(ctx));
    }

    /// Passive's pull of the committed `LeaderKey` (#3414), the counterpart of [#adoptLeaderFromKvIfPresent] for a
    /// state that observes leadership instead of transitioning on it. It adopts exactly the committed record, the
    /// leader AND its own `viewSequence` (#3581), through [#observeCommittedLeader], the one admission the push takes:
    /// the pull is the push of the record the KV-Store holds. Run on every entry and after every sequenced view write,
    /// it adopts and announces nothing new on a repeat, never resurrects a leader the I2 site dropped while the view
    /// still lacks it, and admits the committed leader on the view write that brings it into view.
    private static void observeCommittedLeaderFromKv(LeaderElectionContext ctx) {
        ctx.currentLeaderFromKvSupplier().get().onPresent(committed -> observeCommittedLeader(ctx, committed));
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
        if (!isInStoredView(ctx, event.leader())) {
            log.warn("Adopting committed LeaderCommitted({}) NOT YET in transport topology {} — "
                    + "consensus decision overrides observed transport state (FIX 1); SWIM/FSM "
                    + "death will drive re-election if the leader is genuinely gone",
                     event.leader(),
                     ctx.currentTopology());
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
        if (ctx.isLocalMode()) {
            adoptLocalCommit(ctx, event, stateLabel, tx);

            return;
        }

        var committed = ctx.committedViewSequence();

        if (committed <= entryBaseline) {
            log.info("{}: skipping STALE LeaderCommitted({}) — committed viewSequence {} <= election "
                    + "entryBaseline {} (pre-election commit; awaiting a fresh post-election commit)",
                     stateLabel,
                     event.leader(),
                     committed,
                     entryBaseline);

            return;
        }

        log.info("{}: adopting post-election LeaderCommitted({}) — committed viewSequence {} > " + "entryBaseline {}",
                 stateLabel,
                 event.leader(),
                 committed,
                 entryBaseline);
        recordAdoptedSequence(ctx, event);
        tx.transitionTo(Led.fresh(ctx, event.leader()));
    }

    /// #3092 I3: local mode never advances the committed sequence, so the sequence gate would skip every local commit
    /// and leave an electing node leaderless. A local commit is adopted iff its leader is in the applied eligible view:
    /// every local election names the first node of that view (I1), and a KV-pull commit of a leader the view no
    /// longer holds (the evicted leader's stale `LeaderKey`) is skipped. This is I2's admission half (the post-change
    /// half is [#holdLeaderToView]; both read [#isInStoredView]): without it the post-change site would still drop such
    /// a tenure on `Led` entry, but `ReElecting`'s entry re-pulls the same stale `LeaderKey`, so admit -> drop ->
    /// re-pull would recurse on the dispatching thread.
    private static void adoptLocalCommit(LeaderElectionContext ctx,
                                         LeaderCommitted event,
                                         String stateLabel,
                                         TransitionRequest<LeaderElectionState, ClusterFsmEvent> tx) {
        if (!admittedToView(ctx, event, stateLabel)) {
            return;
        }

        log.info("{}: adopting local-mode LeaderCommitted({}) — no commit sequence exists in local mode",
                 stateLabel,
                 event.leader());
        recordAdoptedSequence(ctx, event);
        tx.transitionTo(Led.fresh(ctx, event.leader()));
    }

    /// I2's admission half, the one in-view test a commit passes before it is adopted where I2 decides at admission
    /// (a local commit, [#adoptLocalCommit]; a passive observation, [#observeCommittedLeader], #3581): a leader absent
    /// from the stored view is skipped, so a later re-read of the same commit cannot re-admit what the post-change site
    /// [#holdLeaderToView] dropped. Returns `true` iff the leader is in the stored view.
    private static boolean admittedToView(LeaderElectionContext ctx, LeaderCommitted event, String stateLabel) {
        if (isInStoredView(ctx, event.leader())) {
            return true;
        }

        log.info("{}: skipping LeaderCommitted({}) — absent from the applied view {}",
                 stateLabel,
                 event.leader(),
                 ctx.currentTopology());

        return false;
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
        // The transitioning states keep the sequence-less pull (NO_SEQUENCE, FIX 1); only Passive adopts the record (#3581).
        ctx.currentLeaderFromKvSupplier()
           .get()
           .map(LeaderCommitted::leader)
           .onPresent(leader -> {
                          if (ctx.currentLeader()
                                 .filter(leader::equals)
                                 .isPresent()) {
                              return;
                          }
                          // FIX 1: do NOT gate the KV-pull adoption on transport topology presence. The prior
                          // `if (!currentTopology().contains(leader)) return;` silently skipped FOREVER on every
                          // 500ms tick when the committed leader was absent from the transport view — the second
                          // root of the 600s wedge (one joiner self-healed only by luck, the other never did).
                          // The KV leader is a consensus-validated decision; adopt it. A transport-absent leader
                          // is logged WARN here; the `adoptLeaderUnconditionally` handler emits the adoption WARN.
                          if (!isInStoredView(ctx, leader)) {
                              log.warn("KV-pull: committed leader {} NOT in transport topology {} — adopting anyway "
                                      + "(consensus decision overrides observed state, FIX 1)",
                                       leader,
                                       ctx.currentTopology());
                          } else {
                              log.info("Adopting leader from KV-Store: {} (push-notification path missed)",
                                       leader);
                          }

                          dispatchSelf(ctx,
                                       new LeaderCommitted(leader));
                      });
    }

    private static void notifyLeaderChange(LeaderElectionContext ctx) {
        var leaderOpt = ctx.currentLeader();

        if (!ctx.markNotified(leaderOpt)) {
            return;  // Already notified this exact leader — skip duplicate.
        }

        var isSelf = leaderOpt.filter(ctx.self()::equals).isPresent();
        var change = LeaderNotification.leaderChange(leaderOpt, isSelf, ctx.replaying());

        routeLeaderChange(ctx, change);
    }

    /// D1-react (KVStore dispatcher design §3.5.1 row 107, R5.8): the FSM step and `markNotified` stay in absorb; only the
    /// route moves, onto the LeaderManager react executor, with the leader and the replay bit captured here (each
    /// `LeaderChange` carries its own leader and `replayed`, R3). Every route takes the one executor, so FIFO keeps the order absorb gave them. A change that makes this
    /// node the leader re-activates leader-pinned managers: a side effect, behind the engine-alive gate. Any other change
    /// (another leader, or none) deactivates them: an undo, never gated (U1), so an engine stop cannot drop the
    /// deactivation and leave a manager active.
    private static void routeLeaderChange(LeaderElectionContext ctx, LeaderNotification.LeaderChange change) {
        if (change.localNodeIsLeader()) {
            ctx.reactExecutor().react("LeaderChange(self)",
                                      () -> ctx.router()
                                               .route(change));
        } else {
            ctx.reactExecutor().undo("LeaderChange(other)",
                                     () -> ctx.router()
                                              .route(change));
        }
    }

    private static void clearLeaderAndNotify(LeaderElectionContext ctx) {
        ctx.setCurrentLeader(Option.none());
        notifyLeaderChange(ctx);
    }

    private static void handleTopologyChange(LeaderElectionContext ctx, ClusterFsmEvent.TopologyChange event) {
        if (storeView(ctx, event)) {
            rescheduleCurrentTick(ctx);
        }
    }

    /// #3044: a view-free removal reschedules the election tick only when the storage removed the node, as a dropped
    /// stale topology event does not (#3040).
    private static void handleRemoval(LeaderElectionContext ctx, PeerRemovedFromView event) {
        if (storeRemoval(ctx, event)) {
            rescheduleCurrentTick(ctx);
        }
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
            default -> log.warn("triggerElection swallowed by GUARD[non-electing-state]: " + "rescheduleCurrentTick from {} — no tick scheduled",
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
    /// the window where parallel proposals would otherwise collide. A [`ReElecting`] suspect is left out
    /// of the ranking (#2869), so the lowest non-suspect node proposes at the base delay.
    private static ScheduledFuture<?> scheduleStaircaseFirstTick(LeaderElectionContext ctx,
                                                                 String stateLabel,
                                                                 Option<NodeId> suspect) {
        var rank = ctx.rankOfSelf(suspect);
        var delayMs = ctx.baseElectionDelay().millis() + rank * ctx.perRankDelay().millis();

        log.info("Entering {}: rank={}, first-tick delay={}ms (rank-staircase: base={}ms + rank*{}ms), "
                + "peer-observation interval={}ms",
                 stateLabel,
                 rank,
                 delayMs,
                 ctx.baseElectionDelay().millis(),
                 ctx.perRankDelay().millis(),
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
        var jitterMs = (long)(ctx.proposalRetryDelay().millis() * (1.0 + ctx.jitterSource().getAsDouble()));

        log.debug("Scheduling election tick #{} in {}ms", retry, jitterMs);
        var future = SharedScheduler.schedule(() -> dispatchSelf(ctx, new ElectionTick()),
                                              TimeSpan.timeSpan(jitterMs).millis());

        Option.option(holder.getAndSet(future)).onPresent(prior -> prior.cancel(false));
    }

    private static void trySubmitProposal(LeaderElectionContext ctx, LeaderElectionState owner) {
        ctx.proposalHandler()
           .onPresent(handler -> submitProposalWith(ctx, owner, handler))
           .onEmpty(() -> electLocallyOnTick(ctx, owner));
    }

    /// #3092 I3: in local mode (no proposal handler) a tick elects the way every local path does, by committing the
    /// first node of the applied eligible view ([LeaderElectionContext#localLeaderCandidate()]), so a leaderless
    /// electing state makes progress on its own ticks. A tick whose KV pull already moved the FSM elects nothing.
    private static void electLocallyOnTick(LeaderElectionContext ctx, LeaderElectionState owner) {
        if (ctx.fsm().current() != owner) {
            return;
        }

        ctx.localLeaderCandidate()
           .onPresent(leader -> dispatchSelf(ctx,
                                             new LeaderCommitted(leader)))
           .onEmpty(() -> rescheduleOnEmptyLocalView(ctx));
    }

    /// GUARD 1 (election-trigger swallow): local mode with an empty eligible view has no node to elect — reschedule.
    private static void rescheduleOnEmptyLocalView(LeaderElectionContext ctx) {
        log.warn("triggerElection swallowed by GUARD[local-empty-view]: local mode with an empty eligible view — "
                + "rescheduling tick");
        rescheduleCurrentTick(ctx);
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

        var pool = excludeSuspect(ctx.candidatePool().stream().sorted().toList(),
                                  suspectOf(owner));

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
        // phase. `candidate` is `pool.getFirst()` after sorting, but a ReElecting suspect (#2869)
        // is local to the node that observed the silence, so proposers can now differ. One leader
        // per view is guaranteed by Rabia's total log order plus the LeaderValue fence, which
        // refuses a second write at the same viewSequence (KVStore.java:714, doc :656-658).
        // Removing the prior single-proposer
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

        if (ctx.fsm().current() != owner) {
            // #1797 F1: a tick's own KV-pull adoption moved the FSM out of `owner` before this point;
            // `owner.onExit` already cleared the guard, `tryStartProposal` just re-set it, and a proposal
            // sent from the left state would store its timeout on a dead record (so nothing ever
            // releases the guard) and double-propose the round the commit already settled.
            ctx.clearProposalInFlight();

            return;
        }

        sendProposal(ctx, owner, handler, candidate);
    }

    /// #2869: drops the [`ReElecting`] suspect from the sorted pool so a silent ex-leader still present in
    /// the transport topology is not proposed again. Falls back to the unfiltered pool when the suspect is
    /// the only candidate, so the empty-pool guard and the 1-2 node behaviour stay as they were.
    private static List<NodeId> excludeSuspect(List<NodeId> sortedPool, Option<NodeId> suspect) {
        var filtered = suspect.map(node -> withoutNode(sortedPool, node)).or(sortedPool);

        return filtered.isEmpty()
               ? sortedPool
               : filtered;
    }

    private static List<NodeId> withoutNode(List<NodeId> pool, NodeId node) {
        return pool.stream()
                   .filter(candidate -> !candidate.equals(node))
                   .toList();
    }

    private static Option<NodeId> suspectOf(LeaderElectionState owner) {
        return switch (owner) {
            case ReElecting r -> r.suspect();
            default -> Option.none();
        };
    }

    private static void sendProposal(LeaderElectionContext ctx,
                                     LeaderElectionState owner,
                                     LeaderProposalHandler handler,
                                     NodeId candidate) {
        var epoch = ctx.nextProposalEpoch();
        var viewSeq = ctx.nextViewSequence();

        log.info("Submitting leader proposal: candidate={}, viewSequence={}, epoch={}", candidate, viewSeq, epoch);
        var timeoutFuture = SharedScheduler.schedule(() -> dispatchSelf(ctx,
                                                                        new ProposalSettled(candidate,
                                                                                            false,
                                                                                            "timeout@epoch=" + epoch)),
                                                     ctx.proposalTimeout());

        storeProposalTimeoutFuture(owner, timeoutFuture);
        handler.propose(candidate, viewSeq)
               .onSuccess(_ -> dispatchSelf(ctx,
                                            new ProposalSettled(candidate, true, "submitted@epoch=" + epoch)))
               .onFailure(cause -> dispatchSelf(ctx,
                                                new ProposalSettled(candidate,
                                                                    false,
                                                                    cause.message() + "@epoch=" + epoch)));
    }

    /// Stores the proposal-timeout future onto the owning Electing/ReElecting record so that
    /// `onExit` / `onCasLost` can cancel it. Replaces any prior timeout future the owner held
    /// (cancels the old one), so a fresh proposal after a retry doesn't leak the prior timer.
    private static void storeProposalTimeoutFuture(LeaderElectionState owner, ScheduledFuture<?> future) {
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
                     event.detail(),
                     event.success());

            return;
        }

        if (event.success()) {
            // #1797: "submitted" is NOT "committed". The guard stays held until the commit moves the FSM
            // out of Electing/ReElecting (their `onExit` clears it) or the proposal timeout settles as a
            // failure (below) — clearing here let a tick or topology reschedule in the submit-to-commit
            // gap propose a second time, advancing the committed viewSequence twice for one round.
            log.debug("Proposal submitted ({}) — guard held until LeaderCommitted or timeout", event.detail());

            return;
        }

        ctx.clearProposalInFlight();
        log.debug("Proposal failed ({}) — retry scheduled", event.detail());
        ctx.incrementStuckElectionCount();
        rescheduleCurrentTick(ctx);
    }

    private static void cancelFuture(AtomicReference<ScheduledFuture<?>> holder) {
        Option.option(holder.getAndSet(null)).onPresent(future -> future.cancel(false));
    }
}
