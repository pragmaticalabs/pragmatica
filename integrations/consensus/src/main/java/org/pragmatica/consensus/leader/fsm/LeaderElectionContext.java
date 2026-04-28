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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/// Shared context for the leader-election FSM. Holds:
/// - Configuration that never changes during the FSM's lifetime (self, expectedCluster, router,
///   delays, proposalHandler).
/// - Mutable bookkeeping that is NOT guard-visible (retry counters, viewSequence, proposal epoch).
/// - Atomic `currentTopology` shared across states — every state reads the latest topology via
///   this reference; `NodeAdded`/`NodeGone` mutate it directly (not as state transitions).
/// - State instances for the per-FSM "singletons" (`dormant`, `quorumLost`, `stopped`) so CAS
///   comparisons against them are stable. Data-carrying states (`electing`, `reElecting`,
///   `quorumWaiting`) are constructed fresh on every entry; each owns its own
///   `ScheduledFuture`(s) cancelled on `onExit` / `onCasLost`.
///
/// Thread safety: atomic fields are thread-safe on their own. The context is referenced by all
/// state instances for a given FSM; states mutate atomic fields directly inside their handlers.
public final class LeaderElectionContext {
    public static final TimeSpan DEFAULT_PROPOSAL_RETRY_DELAY = TimeSpan.timeSpan(500).millis();
    public static final TimeSpan DEFAULT_BASE_ELECTION_DELAY = TimeSpan.timeSpan(2).seconds();
    public static final TimeSpan DEFAULT_PER_RANK_DELAY = TimeSpan.timeSpan(1).seconds();
    /// Floor for leader-proposal completion. Must be >= RabiaNode.DEFAULT_PROPOSAL_TIMEOUT (8s)
    /// so the election layer doesn't conclude "lost election" while Rabia is still resolving.
    public static final TimeSpan DEFAULT_MIN_PROPOSAL_TIMEOUT = TimeSpan.timeSpan(10).seconds();
    public static final int DEFAULT_STUCK_ELECTION_THRESHOLD = 10;

    /// Default jitter source — uniform random in [0.0, 0.5). Captured once per FSM so tests can
    /// inject a deterministic alternative via the full-arity constructor.
    public static final DoubleSupplier DEFAULT_JITTER_SOURCE =
            () -> ThreadLocalRandom.current().nextDouble(0.5);

    /// Default rabia term supplier — returns 0 when not injected. Real consumers (Aether) inject
    /// the cluster-side leader-term counter so [`LeaderManager#currentLeaderEpoch`] returns the
    /// canonical epoch source.
    public static final Supplier<Long> DEFAULT_RABIA_TERM_SUPPLIER = () -> 0L;

    /// Default consensus-ready supplier — returns `false` when not injected. Production wiring
    /// (Aether's `RabiaNode`) supplies the actual consensus-engine readiness accessor
    /// (e.g. `RabiaEngine::isActive`); the FSM consults this supplier on entry to `QuorumWaiting`
    /// to decide whether consensus is already ready, eliminating the need to buffer a
    /// `ConsensusReady` event between FSM states.
    public static final Supplier<Boolean> DEFAULT_CONSENSUS_READY_SUPPLIER = () -> false;

    /// Default current-leader-from-KV supplier — returns `Option.none()` when not injected.
    /// Production wiring reads `LeaderKey` from the cluster KV-Store
    /// (`kvStore.get(LeaderKey.INSTANCE).map(LeaderValue::leader)`). The FSM consults this on
    /// entry to `Electing` / `ReElecting` to short-circuit to `Led(thatLeader)` when consensus
    /// has already committed a leader that this node missed via the notification path (e.g.
    /// a snapshot-restored state didn't re-fire `KVStoreNotification.ValuePut<LeaderKey>` for
    /// the current leader, OR a Decision arrived before the KV-store listener was wired).
    /// KV is the cluster's source of truth — pulling complements the push-notification path.
    public static final Supplier<Option<NodeId>> DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER = Option::none;

    private final NodeId self;
    private final Option<LeaderProposalHandler> proposalHandler;
    private final List<NodeId> expectedCluster;
    private final MessageRouter router;
    private final TimeSpan proposalRetryDelay;
    private final TimeSpan baseElectionDelay;
    private final TimeSpan perRankDelay;
    private final TimeSpan proposalTimeout;
    private final int stuckElectionThreshold;
    private final DoubleSupplier jitterSource;
    private final Supplier<Long> rabiaTermSupplier;
    private final Supplier<Boolean> consensusReadySupplier;
    private final Supplier<Option<NodeId>> currentLeaderFromKvSupplier;

    // Per-FSM "singletons" — one instance per state class, shared for the lifetime of this FSM.
    // Built in the constructor via the constructor-driven initial-state factory, so the fields
    // are `final` and CAS comparisons against them return the same reference.
    private final LeaderElectionState.Dormant dormant;
    // Electing / ReElecting / QuorumWaiting are data-carrying (each carries one or more
    // `ScheduledFuture`s — election tick / proposal timeout / consensus-ready poll). They MUST
    // be fresh per entry so `onExit` / `onCasLost` cancel a tenure-specific future, not a
    // shared one.
    private final LeaderElectionState.QuorumLost quorumLost;
    private final LeaderElectionState.Stopped stopped;

    // Per-FSM Fsm reference — bound at construction time via the constructor-driven initial-state
    // factory. Replaces the global static FSM_REF.
    private final Fsm<LeaderElectionState, ClusterFsmEvent> fsm;

    private final AtomicReference<Option<NodeId>> currentLeader = new AtomicReference<>(Option.none());
    private final AtomicReference<Option<NodeId>> lastNotifiedLeader = new AtomicReference<>(Option.none());
    private final AtomicReference<List<NodeId>> currentTopology = new AtomicReference<>(List.of());
    private final AtomicBoolean hasEverHadLeader = new AtomicBoolean(false);
    private final AtomicBoolean proposalInFlight = new AtomicBoolean(false);
    private final AtomicInteger electionRetryCount = new AtomicInteger(0);
    private final AtomicInteger stuckElectionCount = new AtomicInteger(0);
    private final AtomicLong viewSequence = new AtomicLong(0);
    private final AtomicLong proposalEpoch = new AtomicLong(0);
    private final AtomicLong quorumSequence = new AtomicLong(0);

    LeaderElectionContext(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                          NodeId self,
                          Option<LeaderProposalHandler> proposalHandler,
                          List<NodeId> expectedCluster,
                          MessageRouter router,
                          TimeSpan proposalRetryDelay,
                          TimeSpan baseElectionDelay,
                          TimeSpan perRankDelay,
                          TimeSpan proposalTimeout,
                          int stuckElectionThreshold) {
        this(fsm, self, proposalHandler, expectedCluster, router, proposalRetryDelay,
             baseElectionDelay, perRankDelay, proposalTimeout, stuckElectionThreshold,
             DEFAULT_JITTER_SOURCE, DEFAULT_RABIA_TERM_SUPPLIER, DEFAULT_CONSENSUS_READY_SUPPLIER,
             DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER);
    }

    /// Full-arity constructor that accepts an injectable [`DoubleSupplier`] jitter source — for
    /// tests that need deterministic scheduling. Production code uses the default constructor.
    LeaderElectionContext(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                          NodeId self,
                          Option<LeaderProposalHandler> proposalHandler,
                          List<NodeId> expectedCluster,
                          MessageRouter router,
                          TimeSpan proposalRetryDelay,
                          TimeSpan baseElectionDelay,
                          TimeSpan perRankDelay,
                          TimeSpan proposalTimeout,
                          int stuckElectionThreshold,
                          DoubleSupplier jitterSource) {
        this(fsm, self, proposalHandler, expectedCluster, router, proposalRetryDelay,
             baseElectionDelay, perRankDelay, proposalTimeout, stuckElectionThreshold,
             jitterSource, DEFAULT_RABIA_TERM_SUPPLIER, DEFAULT_CONSENSUS_READY_SUPPLIER,
             DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER);
    }

    /// Full-arity constructor with jitter source and rabia-term supplier — uses the default
    /// `() -> false` consensus-ready supplier (FSM never auto-advances from `QuorumWaiting`
    /// without an explicit injected accessor).
    LeaderElectionContext(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                          NodeId self,
                          Option<LeaderProposalHandler> proposalHandler,
                          List<NodeId> expectedCluster,
                          MessageRouter router,
                          TimeSpan proposalRetryDelay,
                          TimeSpan baseElectionDelay,
                          TimeSpan perRankDelay,
                          TimeSpan proposalTimeout,
                          int stuckElectionThreshold,
                          DoubleSupplier jitterSource,
                          Supplier<Long> rabiaTermSupplier) {
        this(fsm, self, proposalHandler, expectedCluster, router, proposalRetryDelay,
             baseElectionDelay, perRankDelay, proposalTimeout, stuckElectionThreshold,
             jitterSource, rabiaTermSupplier, DEFAULT_CONSENSUS_READY_SUPPLIER,
             DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER);
    }

    /// Constructor with rabia-term + consensus-readiness suppliers; defaults the KV-leader
    /// supplier to `Option.none()`. Existing callers (and tests) that don't need the
    /// pull-from-KV path stay on this overload.
    LeaderElectionContext(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                          NodeId self,
                          Option<LeaderProposalHandler> proposalHandler,
                          List<NodeId> expectedCluster,
                          MessageRouter router,
                          TimeSpan proposalRetryDelay,
                          TimeSpan baseElectionDelay,
                          TimeSpan perRankDelay,
                          TimeSpan proposalTimeout,
                          int stuckElectionThreshold,
                          DoubleSupplier jitterSource,
                          Supplier<Long> rabiaTermSupplier,
                          Supplier<Boolean> consensusReadySupplier) {
        this(fsm, self, proposalHandler, expectedCluster, router, proposalRetryDelay,
             baseElectionDelay, perRankDelay, proposalTimeout, stuckElectionThreshold,
             jitterSource, rabiaTermSupplier, consensusReadySupplier,
             DEFAULT_CURRENT_LEADER_FROM_KV_SUPPLIER);
    }

    /// Full-arity constructor with all injectable suppliers — production wiring (Aether's
    /// `RabiaNode`) calls this overload to plumb the rabia-term counter, the consensus-engine
    /// readiness accessor (e.g. `RabiaEngine::isActive`), AND the KV-store leader accessor
    /// (`kvStore.get(LeaderKey).map(LeaderValue::leader)`). The KV-store accessor is the
    /// pull-side complement to the push-based `KVStoreNotification.ValuePut<LeaderKey>` path:
    /// `Electing` / `ReElecting` consult it on entry to short-circuit to `Led(leader)` when
    /// consensus has already committed a leader that this node missed via the notification path
    /// (e.g. snapshot-restored state didn't re-fire the notification, or a Decision arrived
    /// before the listener was wired). KV is the cluster's source of truth.
    LeaderElectionContext(Fsm<LeaderElectionState, ClusterFsmEvent> fsm,
                          NodeId self,
                          Option<LeaderProposalHandler> proposalHandler,
                          List<NodeId> expectedCluster,
                          MessageRouter router,
                          TimeSpan proposalRetryDelay,
                          TimeSpan baseElectionDelay,
                          TimeSpan perRankDelay,
                          TimeSpan proposalTimeout,
                          int stuckElectionThreshold,
                          DoubleSupplier jitterSource,
                          Supplier<Long> rabiaTermSupplier,
                          Supplier<Boolean> consensusReadySupplier,
                          Supplier<Option<NodeId>> currentLeaderFromKvSupplier) {
        this.fsm = fsm;
        this.self = self;
        this.proposalHandler = proposalHandler;
        this.expectedCluster = List.copyOf(expectedCluster);
        this.router = router;
        this.proposalRetryDelay = proposalRetryDelay;
        this.baseElectionDelay = baseElectionDelay;
        this.perRankDelay = perRankDelay;
        this.proposalTimeout = proposalTimeout;
        this.stuckElectionThreshold = stuckElectionThreshold;
        this.jitterSource = jitterSource;
        this.rabiaTermSupplier = rabiaTermSupplier;
        this.consensusReadySupplier = consensusReadySupplier;
        this.currentLeaderFromKvSupplier = currentLeaderFromKvSupplier;
        this.dormant = new LeaderElectionState.Dormant(this);
        this.quorumLost = new LeaderElectionState.QuorumLost(this);
        this.stopped = new LeaderElectionState.Stopped(this);
    }

    /// Returns the jitter source used by [`LeaderElectionState#scheduleElectionTick`]. Emits a
    /// `double` in the range [0.0, 0.5) scaled over the retry delay.
    public DoubleSupplier jitterSource() {
        return jitterSource;
    }

    /// Live read of the cluster-side rabia term. Source for
    /// [`LeaderManager#currentLeaderEpoch`]; the rabia term is the canonical leader-tenure
    /// identifier exposed to consumers (e.g. Aether's `HealthReconciler`).
    public Supplier<Long> rabiaTermSupplier() {
        return rabiaTermSupplier;
    }

    static TimeSpan proposalTimeoutFor(TimeSpan proposalRetryDelay) {
        return TimeSpan.timeSpan(Math.max(proposalRetryDelay.millis() * 3L,
                                          DEFAULT_MIN_PROPOSAL_TIMEOUT.millis())).millis();
    }

    // --- Configuration accessors ---

    public NodeId self() { return self; }
    public Option<LeaderProposalHandler> proposalHandler() { return proposalHandler; }
    public List<NodeId> expectedCluster() { return expectedCluster; }
    public MessageRouter router() { return router; }
    public TimeSpan proposalRetryDelay() { return proposalRetryDelay; }
    public TimeSpan baseElectionDelay() { return baseElectionDelay; }
    public TimeSpan perRankDelay() { return perRankDelay; }
    public TimeSpan proposalTimeout() { return proposalTimeout; }
    public int stuckElectionThreshold() { return stuckElectionThreshold; }

    // --- Mutable state accessors ---

    public Option<NodeId> currentLeader() { return currentLeader.get(); }
    public void setCurrentLeader(Option<NodeId> leader) { currentLeader.set(leader); }

    /// Dedup helper: returns `true` iff `leader` differs from the last notified leader and
    /// atomically records the new value. Callers that receive `true` are the ones that should
    /// emit the `LeaderChange` message; others skip to avoid duplicate notifications.
    public boolean markNotified(Option<NodeId> leader) {
        var previous = lastNotifiedLeader.getAndSet(leader);
        return !previous.equals(leader);
    }

    public List<NodeId> currentTopology() { return currentTopology.get(); }
    public void setCurrentTopology(List<NodeId> topology) {
        if (topology.isEmpty()) {
            return;
        }
        var sorted = new ArrayList<>(topology);
        Collections.sort(sorted);
        currentTopology.set(List.copyOf(sorted));
    }

    /// Live accessor for the consensus-engine readiness state — the SSOT consulted by
    /// [`LeaderElectionState.QuorumWaiting#onEntry`]. When `true`, the FSM self-dispatches a
    /// [`LeaderElectionEvents.ConsensusReady`] to immediately advance to `Electing`/`ReElecting`.
    public Supplier<Boolean> consensusReadySupplier() { return consensusReadySupplier; }

    /// Live accessor for the cluster's current leader as recorded in the KV-Store
    /// (`LeaderKey` → `LeaderValue.leader()`). Consulted by `Electing.onEntry` /
    /// `ReElecting.onEntry` to short-circuit to `Led(leader)` whenever consensus has already
    /// committed a leader. Pull-side complement to the push-based
    /// `KVStoreNotification.ValuePut<LeaderKey>` listener — the FSM never gets stuck waiting
    /// for a notification that already fired (or never will).
    public Supplier<Option<NodeId>> currentLeaderFromKvSupplier() { return currentLeaderFromKvSupplier; }

    public boolean hasEverHadLeader() { return hasEverHadLeader.get(); }
    public void markHasEverHadLeader() { hasEverHadLeader.set(true); }

    public boolean tryStartProposal() { return proposalInFlight.compareAndSet(false, true); }
    public void clearProposalInFlight() { proposalInFlight.set(false); }
    public boolean proposalInFlight() { return proposalInFlight.get(); }

    public int incrementElectionRetryCount() { return electionRetryCount.incrementAndGet(); }
    public void resetElectionRetryCount() { electionRetryCount.set(0); }

    public int incrementStuckElectionCount() { return stuckElectionCount.incrementAndGet(); }
    public void resetStuckElectionCount() { stuckElectionCount.set(0); }
    public int stuckElectionCount() { return stuckElectionCount.get(); }

    public long nextViewSequence() { return viewSequence.incrementAndGet(); }
    public long nextProposalEpoch() { return proposalEpoch.incrementAndGet(); }

    public AtomicLong quorumSequence() { return quorumSequence; }

    // --- Per-FSM state instances ---

    public LeaderElectionState.Dormant dormant() { return dormant; }
    /// Fresh data-carrying instance per call. Each instance owns its own consensus-readiness
    /// poll `ScheduledFuture` and cancels it on `onExit`/`onCasLost`.
    public LeaderElectionState.QuorumWaiting quorumWaiting() { return LeaderElectionState.QuorumWaiting.fresh(this); }
    /// Fresh data-carrying instance per call. Each instance owns its own pending-tick /
    /// proposal-timeout `ScheduledFuture`s and cancels them on `onExit`/`onCasLost`.
    public LeaderElectionState.Electing electing() { return LeaderElectionState.Electing.fresh(this); }
    /// Fresh data-carrying instance per call. See [`#electing`].
    public LeaderElectionState.ReElecting reElecting() { return LeaderElectionState.ReElecting.fresh(this); }
    public LeaderElectionState.QuorumLost quorumLost() { return quorumLost; }
    public LeaderElectionState.Stopped stopped() { return stopped; }

    public Fsm<LeaderElectionState, ClusterFsmEvent> fsm() {
        return fsm;
    }

    // --- Derived helpers ---

    /// Candidate pool for leader election.
    public List<NodeId> candidatePool() {
        if (expectedCluster.isEmpty() || stuckElectionCount.get() >= stuckElectionThreshold) {
            return currentTopology.get();
        }
        if (!hasEverHadLeader.get()) {
            return expectedCluster;
        }
        var topology = currentTopology.get();
        var filtered = expectedCluster.stream()
                                      .filter(topology::contains)
                                      .toList();
        return filtered.isEmpty() ? topology : filtered;
    }

    public int rankOfSelf() {
        var pool = expectedCluster.isEmpty() ? currentTopology.get() : expectedCluster;
        var sorted = pool.stream().sorted().toList();
        var rank = sorted.indexOf(self);
        return rank >= 0 ? rank : sorted.size();
    }

    public boolean isLeader() {
        return currentLeader.get().filter(self::equals).isPresent();
    }
}
