/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.leader.LeaderManager.LeaderProposalHandler;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.statemachine.Fsm;


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
    /// Grace window after [`LeaderElectionState.QuorumWaiting`] before the FSM gives up on
    /// observing a peer-committed leader and falls through to [`LeaderElectionState.Electing`] /
    /// [`LeaderElectionState.ReElecting`]. Sized to absorb the typical KV-sync propagation
    /// latency across a cold-booted cluster:
    ///
    ///   - QUIC reconnect window: ~1-2s under good conditions
    ///   - Rabia engine activation + decision propagation: ~500ms-1s
    ///   - KV-store snapshot apply + ValuePut notification dispatch: ~100-300ms
    ///
    /// 3s is the smallest value that consistently absorbs all three above on integration test
    /// hardware while still bounding the worst-case "genuinely fresh cluster, all nodes proposing
    /// in parallel" path so it doesn't sit idle waiting for an event that will never arrive.
    /// Production callers can override via the full-arity factory; the default is intentionally
    /// conservative so a misconfiguration doesn't introduce a deadlock-shaped bug.
    public static final TimeSpan DEFAULT_KV_SYNC_GRACE_DELAY = TimeSpan.timeSpan(3).seconds();
    /// Cadence for the independent peer-observation timer scheduled in
    /// [`LeaderElectionState.Electing`] / [`LeaderElectionState.ReElecting`]. Names a real
    /// architectural smell: `Electing.handle` previously conflated "drive my own proposal" with
    /// "observe peers" — once `proposalInFlight=true` the FSM rescheduled neither
    /// `ElectionTick` (the only carrier of `adoptLeaderFromKvIfPresent`) nor any other pull-side
    /// check until the in-flight `proposalTimeout` fired. During that window the FSM was blind
    /// to peer-committed leaders if the push notification was buffered (Rabia decisions held
    /// during the Syncing window) or the listener was wired late. Result: a node that fell
    /// through to `Electing` and submitted a self-proposal could sit ~`proposalTimeout`
    /// (10s default) without observing a leader its peers had already committed.
    ///
    /// Decoupling peer-observation from proposal lifecycle: the new periodic timer calls
    /// `adoptLeaderFromKvIfPresent` regardless of `proposalInFlight`. If KV records a leader
    /// in topology, the helper synthesises a `LeaderCommitted`, the existing handler
    /// transitions to `Led(thatLeader)`, and the in-flight proposal is preempted — its timeout
    /// is cancelled in `Electing.onExit` / `ReElecting.onExit`.
    ///
    /// 500ms is the smallest cadence that doesn't measurably load the KV-store accessor while
    /// keeping the worst-case observation latency well under any meaningful operator timeout.
    public static final TimeSpan DEFAULT_PEER_OBSERVATION_INTERVAL = TimeSpan.timeSpan(500).millis();
    /// Cadence at which [`LeaderElectionState.Led`] re-checks whether the believed leader's
    /// leader-stamped `ClusterSync` ping is still fresh (the leader-acting lease). One check per
    /// interval; [`#LEADER_SILENCE_THRESHOLD_INTERVALS`] consecutive silent checks trip a
    /// `LeaderSilent` → `ReElecting`. 1s matches the typical leader ping cadence, so a check
    /// roughly aligns with one expected ping arrival.
    public static final TimeSpan DEFAULT_LEADER_LEASE_CHECK_INTERVAL = TimeSpan.timeSpan(1).seconds();
    /// Tenure warm-up: initial delay before the FIRST lease check fires in
    /// [`LeaderElectionState.Led`]. A freshly-elected leader must activate consensus, collect its
    /// first pongs, broadcast its readiness view, and have each follower's
    /// `FollowerReadinessCache` refresh before the lease can possibly observe a fresh leader-ping.
    /// On a loaded host that whole pipeline can consume the entire lease budget
    /// (`interval × threshold` ≈ 4s), so WITHOUT a warm-up the lease trips on EVERY new leader
    /// before its first freshness signal arrives — perpetual re-election churn (observed on
    /// Hetzner 02-chaos: 6-16 trips/node in 10 min, leader='' between tenures). The warm-up must
    /// exceed the worst-case activation→first-broadcast→follower-cache-refresh pipeline on loaded
    /// hosts; 15s is comfortably above it. The zero-leader wedge the lease guards against is
    /// PERMANENT, so the extra +15s recovery latency is immaterial.
    public static final TimeSpan DEFAULT_LEADER_LEASE_WARMUP = TimeSpan.timeSpan(15).seconds();
    /// Number of CONSECUTIVE silent lease checks (no fresh ping from the believed leader) a
    /// follower tolerates in [`LeaderElectionState.Led`] before declaring the leader silent and
    /// re-electing. Tradeoff: too LOW and ordinary ping loss / GC pause / scheduling jitter on a
    /// healthy leader triggers a spurious re-election (and the brief leaderless window + epoch bump
    /// that comes with it); too HIGH and the cluster tolerates a genuinely wedged-but-SWIM-alive
    /// leader for longer before self-healing. 4 (≈4s at the default 1s cadence) sits above
    /// transient single-ping loss while keeping the worst-case zero-leader wedge bounded to a few
    /// seconds. The believed leader's freshness predicate itself smooths sub-interval jitter (the
    /// production wiring's `FollowerReadinessCache` already applies a 3-ping TTL), so this counter
    /// only escalates on SUSTAINED silence.
    public static final int LEADER_SILENCE_THRESHOLD_INTERVALS = 4;
    /// Default leader-ping-freshness predicate — reports EVERY believed leader as fresh, so the
    /// lease in [`LeaderElectionState.Led`] never trips. Lease-neutral: callers that do not wire a
    /// real freshness signal (test stubs, local-election mode, legacy overloads) keep the prior
    /// "stay in Led until NodeGone" behaviour unchanged. Production wiring (Aether's `AetherNode`)
    /// injects a predicate backed by the follower's observed leader-ping freshness
    /// (`ClusterSyncCollector`), so a believed leader that stops stamping pings trips the lease.
    public static final Predicate<NodeId> DEFAULT_LEADER_PING_FRESH_FN = _ -> true;

    /// Default jitter source — uniform random in [0.0, 0.5). Captured once per FSM so tests can
    /// inject a deterministic alternative via the full-arity constructor.
    public static final DoubleSupplier DEFAULT_JITTER_SOURCE = () -> ThreadLocalRandom.current().nextDouble(0.5);

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
    private final TimeSpan kvSyncGraceDelay;
    private final TimeSpan peerObservationInterval;
    private final TimeSpan leaderLeaseCheckInterval;
    private final TimeSpan leaderLeaseWarmup;
    private final int stuckElectionThreshold;
    private final DoubleSupplier jitterSource;
    private final Supplier<Long> rabiaTermSupplier;
    private final Supplier<Boolean> consensusReadySupplier;
    private final Supplier<Option<NodeId>> currentLeaderFromKvSupplier;
    private final Predicate<NodeId> leaderPingFreshFn;
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
    /// Highest leader-election `viewSequence` this node has observed COMMITTED (via the
    /// `LeaderKey` commit notification — push path or snapshot-replay re-fire). Baseline for
    /// [#nextViewSequence()]; advanced only by [#observeViewSequence(long)] (max-merge).
    private final AtomicLong viewSequence = new AtomicLong(0);
    /// Sequence at which the CURRENTLY-ADOPTED leader (the live `Led` tenure) was committed. The
    /// `Led` swap fence (FIX 2) rejects a later-arriving LeaderCommitted whose sequence does not
    /// STRICTLY EXCEED this — the crossed-pointer amplifier where a straggling lower-sequence
    /// commit overwrites a newer leader. SEPARATE from [viewSequence] (the observation baseline,
    /// max-merged) because the baseline is seeded BEFORE the commit is dispatched, so it cannot be
    /// the fence reference. Updated only when a leader is actually adopted into `Led`.
    private final AtomicLong adoptedViewSequence = new AtomicLong(0);
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
        this(fsm,
             self,
             proposalHandler,
             expectedCluster,
             router,
             proposalRetryDelay,
             baseElectionDelay,
             perRankDelay,
             proposalTimeout,
             stuckElectionThreshold,
             DEFAULT_JITTER_SOURCE,
             DEFAULT_RABIA_TERM_SUPPLIER,
             DEFAULT_CONSENSUS_READY_SUPPLIER,
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
        this(fsm,
             self,
             proposalHandler,
             expectedCluster,
             router,
             proposalRetryDelay,
             baseElectionDelay,
             perRankDelay,
             proposalTimeout,
             stuckElectionThreshold,
             jitterSource,
             DEFAULT_RABIA_TERM_SUPPLIER,
             DEFAULT_CONSENSUS_READY_SUPPLIER,
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
        this(fsm,
             self,
             proposalHandler,
             expectedCluster,
             router,
             proposalRetryDelay,
             baseElectionDelay,
             perRankDelay,
             proposalTimeout,
             stuckElectionThreshold,
             jitterSource,
             rabiaTermSupplier,
             DEFAULT_CONSENSUS_READY_SUPPLIER,
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
        this(fsm,
             self,
             proposalHandler,
             expectedCluster,
             router,
             proposalRetryDelay,
             baseElectionDelay,
             perRankDelay,
             proposalTimeout,
             stuckElectionThreshold,
             jitterSource,
             rabiaTermSupplier,
             consensusReadySupplier,
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
        this(fsm,
             self,
             proposalHandler,
             expectedCluster,
             router,
             proposalRetryDelay,
             baseElectionDelay,
             perRankDelay,
             proposalTimeout,
             stuckElectionThreshold,
             jitterSource,
             rabiaTermSupplier,
             consensusReadySupplier,
             currentLeaderFromKvSupplier,
             DEFAULT_KV_SYNC_GRACE_DELAY);
    }

    /// Constructor adding the KvSync grace delay — the cold-boot self-proposal gate.
    /// Existing callers stay on the prior overload (which forwards
    /// [`#DEFAULT_KV_SYNC_GRACE_DELAY`]); tests that need to drive the fall-through path
    /// deterministically use this constructor with a short delay (e.g. 50ms). The peer
    /// observation interval defaults to [`#DEFAULT_PEER_OBSERVATION_INTERVAL`]; tests that
    /// drive the observation path deterministically should call the next overload directly.
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
                          Supplier<Option<NodeId>> currentLeaderFromKvSupplier,
                          TimeSpan kvSyncGraceDelay) {
        this(fsm,
             self,
             proposalHandler,
             expectedCluster,
             router,
             proposalRetryDelay,
             baseElectionDelay,
             perRankDelay,
             proposalTimeout,
             stuckElectionThreshold,
             jitterSource,
             rabiaTermSupplier,
             consensusReadySupplier,
             currentLeaderFromKvSupplier,
             kvSyncGraceDelay,
             DEFAULT_PEER_OBSERVATION_INTERVAL);
    }

    /// Full-arity constructor adding the peer-observation interval — the independent
    /// observation cadence used by [`LeaderElectionState.Electing`] /
    /// [`LeaderElectionState.ReElecting`] to pull-check the KV-store leader without coupling
    /// to the proposal lifecycle. Forwards [`#DEFAULT_LEADER_LEASE_CHECK_INTERVAL`] and the
    /// lease-neutral [`#DEFAULT_LEADER_PING_FRESH_FN`] to the lease-aware overload below — callers
    /// that don't wire a leader-ping freshness signal keep the prior "stay in Led until NodeGone"
    /// behaviour. Tests that drive the observation path deterministically use this constructor with
    /// a short interval (e.g. 50ms).
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
                          Supplier<Option<NodeId>> currentLeaderFromKvSupplier,
                          TimeSpan kvSyncGraceDelay,
                          TimeSpan peerObservationInterval) {
        this(fsm,
             self,
             proposalHandler,
             expectedCluster,
             router,
             proposalRetryDelay,
             baseElectionDelay,
             perRankDelay,
             proposalTimeout,
             stuckElectionThreshold,
             jitterSource,
             rabiaTermSupplier,
             consensusReadySupplier,
             currentLeaderFromKvSupplier,
             kvSyncGraceDelay,
             peerObservationInterval,
             DEFAULT_LEADER_LEASE_CHECK_INTERVAL,
             DEFAULT_LEADER_PING_FRESH_FN);
    }

    /// Full-arity constructor adding the leader-acting lease parameters — the lease-check cadence
    /// and the leader-ping freshness predicate consulted by [`LeaderElectionState.Led`]. A node in
    /// `Led(X)` where `X != self` re-checks `leaderPingFreshFn.test(X)` once per
    /// `leaderLeaseCheckInterval`; [`#LEADER_SILENCE_THRESHOLD_INTERVALS`] consecutive silent
    /// checks dispatch a `LeaderSilent` → `ReElecting`. Production wiring (Aether's `AetherNode`)
    /// backs the predicate with the follower's observed leader-ping freshness
    /// (`ClusterSyncCollector`). Tests that drive the lease deterministically use this constructor
    /// with a short interval and a controllable predicate.
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
                          Supplier<Option<NodeId>> currentLeaderFromKvSupplier,
                          TimeSpan kvSyncGraceDelay,
                          TimeSpan peerObservationInterval,
                          TimeSpan leaderLeaseCheckInterval,
                          Predicate<NodeId> leaderPingFreshFn) {
        this(fsm,
             self,
             proposalHandler,
             expectedCluster,
             router,
             proposalRetryDelay,
             baseElectionDelay,
             perRankDelay,
             proposalTimeout,
             stuckElectionThreshold,
             jitterSource,
             rabiaTermSupplier,
             consensusReadySupplier,
             currentLeaderFromKvSupplier,
             kvSyncGraceDelay,
             peerObservationInterval,
             leaderLeaseCheckInterval,
             leaderPingFreshFn,
             DEFAULT_LEADER_LEASE_WARMUP);
    }

    /// Full-arity constructor adding the lease tenure warm-up — the initial delay before the FIRST
    /// lease check fires after entering [`LeaderElectionState.Led`]. Defaults to
    /// [`#DEFAULT_LEADER_LEASE_WARMUP`]; tests that drive the lease deterministically inject a
    /// short warm-up (e.g. 30ms) so the lease can trip quickly without waiting the production 15s.
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
                          Supplier<Option<NodeId>> currentLeaderFromKvSupplier,
                          TimeSpan kvSyncGraceDelay,
                          TimeSpan peerObservationInterval,
                          TimeSpan leaderLeaseCheckInterval,
                          Predicate<NodeId> leaderPingFreshFn,
                          TimeSpan leaderLeaseWarmup) {
        this.fsm = fsm;
        this.self = self;
        this.proposalHandler = proposalHandler;
        this.expectedCluster = List.copyOf(expectedCluster);
        this.router = router;
        this.proposalRetryDelay = proposalRetryDelay;
        this.baseElectionDelay = baseElectionDelay;
        this.perRankDelay = perRankDelay;
        this.proposalTimeout = proposalTimeout;
        this.kvSyncGraceDelay = kvSyncGraceDelay;
        this.peerObservationInterval = peerObservationInterval;
        this.leaderLeaseCheckInterval = leaderLeaseCheckInterval;
        this.leaderLeaseWarmup = leaderLeaseWarmup;
        this.stuckElectionThreshold = stuckElectionThreshold;
        this.jitterSource = jitterSource;
        this.rabiaTermSupplier = rabiaTermSupplier;
        this.consensusReadySupplier = consensusReadySupplier;
        this.currentLeaderFromKvSupplier = currentLeaderFromKvSupplier;
        this.leaderPingFreshFn = leaderPingFreshFn;
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
        return TimeSpan.timeSpan(Math.max(proposalRetryDelay.millis() * 3L, DEFAULT_MIN_PROPOSAL_TIMEOUT.millis())).millis();
    }

    // --- Configuration accessors ---
    public NodeId self() {
        return self;
    }

    public Option<LeaderProposalHandler> proposalHandler() {
        return proposalHandler;
    }

    public List<NodeId> expectedCluster() {
        return expectedCluster;
    }

    public MessageRouter router() {
        return router;
    }

    public TimeSpan proposalRetryDelay() {
        return proposalRetryDelay;
    }

    public TimeSpan baseElectionDelay() {
        return baseElectionDelay;
    }

    public TimeSpan perRankDelay() {
        return perRankDelay;
    }

    public TimeSpan proposalTimeout() {
        return proposalTimeout;
    }

    /// Grace window used by [`LeaderElectionState.AwaitingKvSync`]. Defaults to
    /// [`#DEFAULT_KV_SYNC_GRACE_DELAY`]; tests inject shorter values to drive the timeout
    /// fall-through deterministically.
    public TimeSpan kvSyncGraceDelay() {
        return kvSyncGraceDelay;
    }

    /// Observation cadence for the independent peer-observation timer scheduled in
    /// [`LeaderElectionState.Electing`] / [`LeaderElectionState.ReElecting`]. Defaults to
    /// [`#DEFAULT_PEER_OBSERVATION_INTERVAL`]; tests inject shorter values to drive the
    /// observation path deterministically.
    public TimeSpan peerObservationInterval() {
        return peerObservationInterval;
    }

    /// Cadence for the leader-acting lease re-check in [`LeaderElectionState.Led`]. Defaults to
    /// [`#DEFAULT_LEADER_LEASE_CHECK_INTERVAL`]; tests inject shorter values to drive the lease
    /// expiry deterministically.
    public TimeSpan leaderLeaseCheckInterval() {
        return leaderLeaseCheckInterval;
    }

    /// Tenure warm-up: initial delay before the FIRST lease check in [`LeaderElectionState.Led`].
    /// Defaults to [`#DEFAULT_LEADER_LEASE_WARMUP`]; tests inject a short warm-up to drive the
    /// lease deterministically without waiting the production 15s.
    public TimeSpan leaderLeaseWarmup() {
        return leaderLeaseWarmup;
    }

    /// Predicate consulted by [`LeaderElectionState.Led`]'s lease timer: given the believed
    /// leader, returns `true` while its leader-stamped `ClusterSync` ping is still fresh. A node
    /// in `Led(X)` (X != self) that sees this return `false` for
    /// [`#LEADER_SILENCE_THRESHOLD_INTERVALS`] consecutive checks dispatches a `LeaderSilent`.
    /// Defaults to the lease-neutral [`#DEFAULT_LEADER_PING_FRESH_FN`].
    public Predicate<NodeId> leaderPingFreshFn() {
        return leaderPingFreshFn;
    }

    public int stuckElectionThreshold() {
        return stuckElectionThreshold;
    }

    // --- Mutable state accessors ---
    public Option<NodeId> currentLeader() {
        return currentLeader.get();
    }

    @Contract
    public void setCurrentLeader(Option<NodeId> leader) {
        currentLeader.set(leader);
    }

    /// Dedup helper: returns `true` iff `leader` differs from the last notified leader and
    /// atomically records the new value. Callers that receive `true` are the ones that should
    /// emit the `LeaderChange` message; others skip to avoid duplicate notifications.
    public boolean markNotified(Option<NodeId> leader) {
        var previous = lastNotifiedLeader.getAndSet(leader);

        return ! previous.equals(leader);
    }

    public List<NodeId> currentTopology() {
        return currentTopology.get();
    }

    @Contract
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
    public Supplier<Boolean> consensusReadySupplier() {
        return consensusReadySupplier;
    }

    /// Live accessor for the cluster's current leader as recorded in the KV-Store
    /// (`LeaderKey` → `LeaderValue.leader()`). Consulted by `Electing.onEntry` /
    /// `ReElecting.onEntry` to short-circuit to `Led(leader)` whenever consensus has already
    /// committed a leader. Pull-side complement to the push-based
    /// `KVStoreNotification.ValuePut<LeaderKey>` listener — the FSM never gets stuck waiting
    /// for a notification that already fired (or never will).
    public Supplier<Option<NodeId>> currentLeaderFromKvSupplier() {
        return currentLeaderFromKvSupplier;
    }

    public boolean hasEverHadLeader() {
        return hasEverHadLeader.get();
    }

    @Contract
    public void markHasEverHadLeader() {
        hasEverHadLeader.set(true);
    }

    public boolean tryStartProposal() {
        return proposalInFlight.compareAndSet(false, true);
    }

    @Contract
    public void clearProposalInFlight() {
        proposalInFlight.set(false);
    }

    public boolean proposalInFlight() {
        return proposalInFlight.get();
    }

    public int incrementElectionRetryCount() {
        return electionRetryCount.incrementAndGet();
    }

    @Contract
    public void resetElectionRetryCount() {
        electionRetryCount.set(0);
    }

    public int incrementStuckElectionCount() {
        return stuckElectionCount.incrementAndGet();
    }

    @Contract
    public void resetStuckElectionCount() {
        stuckElectionCount.set(0);
    }

    public int stuckElectionCount() {
        return stuckElectionCount.get();
    }

    /// The viewSequence to carry on the NEXT leader proposal: one above the highest COMMITTED
    /// sequence this node has observed. Deliberately NOT a per-attempt counter (H4 fence,
    /// cluster-topology-overhaul §Wave 8.2):
    ///   - peers proposing off the same observed commit produce IDENTICAL `LeaderValue` commands
    ///     (same BatchId → consensus dedup, the property the cold-boot parallel proposal relies on);
    ///   - a minority-flapped node's election RETRIES cannot inflate the sequence past what the
    ///     majority committed — after the partition heals, its stale write carries a sequence at
    ///     or below the committed one and the KV applier rejects it.
    /// Does not mutate state; the baseline advances only when a commit is observed
    /// ([#observeViewSequence(long)]).
    public long nextViewSequence() {
        return viewSequence.get() + 1;
    }

    /// Record a leader `viewSequence` observed COMMITTED (from the `LeaderKey`
    /// `ValuePut` notification or its snapshot-replay re-fire). Max-merge keeps the baseline
    /// monotone, so an out-of-order or duplicate observation can never regress it.
    @Contract
    public void observeViewSequence(long committed) {
        viewSequence.accumulateAndGet(committed, Math::max);
    }

    /// The highest COMMITTED leader `viewSequence` this node has observed (the baseline
    /// [#nextViewSequence] sits one above). Snapshotted on entry to `Electing` / `ReElecting` as
    /// the adoption fence: WITHIN an election, only a commit whose sequence POSTDATES the decision
    /// to elect (`committedViewSequence() > entryBaseline`) is adopted, so the stale pre-death
    /// commit (≤ baseline) cannot resurrect the corpse and wedge re-election (FIX-1 refinement).
    public long committedViewSequence() {
        return viewSequence.get();
    }

    /// Sequence at which the live `Led` leader was adopted — the fence reference for the `Led` swap
    /// guard (FIX 2). Returns 0 before any leader is adopted.
    public long adoptedViewSequence() {
        return adoptedViewSequence.get();
    }

    /// Records the sequence of a leader just adopted into `Led`. Called from the `Led` swap path so
    /// a subsequent later-arriving lower-sequence commit is fenced out.
    @Contract
    public void setAdoptedViewSequence(long sequence) {
        adoptedViewSequence.set(sequence);
    }

    public long nextProposalEpoch() {
        return proposalEpoch.incrementAndGet();
    }

    public AtomicLong quorumSequence() {
        return quorumSequence;
    }

    // --- Per-FSM state instances ---
    public LeaderElectionState.Dormant dormant() {
        return dormant;
    }

    /// Fresh data-carrying instance per call. Each instance owns its own consensus-readiness
    /// poll `ScheduledFuture` and cancels it on `onExit`/`onCasLost`.
    public LeaderElectionState.QuorumWaiting quorumWaiting() {
        return LeaderElectionState.QuorumWaiting.fresh(this);
    }

    /// Fresh data-carrying instance per call. Each instance owns its own KvSync grace
    /// `ScheduledFuture` and cancels it on `onExit`/`onCasLost`.
    public LeaderElectionState.AwaitingKvSync awaitingKvSync() {
        return LeaderElectionState.AwaitingKvSync.fresh(this);
    }

    /// Fresh data-carrying instance per call. Each instance owns its own pending-tick /
    /// proposal-timeout `ScheduledFuture`s and cancels them on `onExit`/`onCasLost`.
    public LeaderElectionState.Electing electing() {
        return LeaderElectionState.Electing.fresh(this);
    }

    /// Fresh data-carrying instance per call. See [`#electing`].
    public LeaderElectionState.ReElecting reElecting() {
        return LeaderElectionState.ReElecting.fresh(this);
    }

    public LeaderElectionState.QuorumLost quorumLost() {
        return quorumLost;
    }

    public LeaderElectionState.Stopped stopped() {
        return stopped;
    }

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
        var filtered = expectedCluster.stream().filter(topology::contains).toList();

        return filtered.isEmpty()
               ? topology
               : filtered;
    }

    public int rankOfSelf() {
        var pool = expectedCluster.isEmpty()
                   ? currentTopology.get()
                   : expectedCluster;
        var sorted = pool.stream().sorted().toList();
        var rank = sorted.indexOf(self);

        return rank >= 0
               ? rank
               : sorted.size();
    }

    public boolean isLeader() {
        return currentLeader.get()
                            .filter(self::equals)
                            .isPresent();
    }
}
