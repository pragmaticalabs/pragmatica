/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.rabia;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.StateMachine.Batch.Id;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineIO.SubmitCommands;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.rabia.ConsensusEvent.ConsensusActive;
import org.pragmatica.consensus.rabia.ConsensusEvent.ConsensusPassive;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.concurrent.AtomicHolder;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.rabia.RabiaPersistence.SavedState.savedState;
import static org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;


/// Implementation of the Rabia consensus protocol.
///
/// Rabia is a crash-fault-tolerant (CFT) consensus algorithm that provides:
///
///   - No persistent event log required
///   - Batch-based command processing
///   - Automatic state synchronization
///   - Deterministic decision-making with coin-flip fallback
///
/// @param <C> Command type
public class RabiaEngine<C extends Command> {
    private static final Logger log = LoggerFactory.getLogger(RabiaEngine.class);
    /// Light jitter scale: ±20% around the configured sync retry interval.
    /// Smaller than the default (±50%) to avoid disrupting protocol timing while still
    /// breaking thundering-herd patterns when many nodes resync after a quorum hiccup.
    private static final double SCALE = 0.2d;
    /// Default phase stall check interval.
    public static final TimeSpan DEFAULT_PHASE_STALL_CHECK = TimeSpan.timeSpan(500).millis();
    /// Apply-task duration probe threshold (5 ms). Diagnostic only — tasks at or above this on
    /// the single consensus apply worker are logged as `SLOW-APPLY` with the executor queue depth.
    private static final long SLOW_APPLY_THRESHOLD_NANOS = 5_000_000L;

    private final NodeId self;
    private final TopologyManager topologyManager;
    private final ClusterNetwork network;
    private final StateMachine<C> stateMachine;
    private final ProtocolConfig config;
    private final ConsensusMetrics metrics;
    private final boolean activationGated;
    private final TimeSpan phaseStallCheck;
    private volatile boolean activationAuthorized;
    private volatile boolean observerMode;
    private final AtomicHolder<ClusterStateNotification> pendingQuorum = AtomicHolder.atomicHolder();
    /// Sink for engine-level `ConsensusEvent` emissions. The default no-op consumer keeps
    /// existing test constructors working; production wiring (`RabiaNode`) injects
    /// `ConsensusBridge.consensusBridge(router)` so the consensus engine becomes the
    /// authoritative source for `ClusterStateNotification` (E2 Phase 2c.0, 2026-05-28).
    private final Consumer<ConsensusEvent> consensusEventListener;

    /// Single-fire-per-transition gate for `ConsensusActive` / `ConsensusPassive`. Tracks the
    /// last published "active" sense; the consumer fires only on true edges
    /// (false → true emits `ConsensusActive`, true → false emits `ConsensusPassive`).
    /// Initialized `false` because every engine starts in `Stopped` (not active).
    private final java.util.concurrent.atomic.AtomicBoolean lastPublishedActive = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Single-thread executor with DiscardPolicy to silently drop tasks after shutdown
    private final ExecutorService executor = new ThreadPoolExecutor(1,
                                                                    1,
                                                                    0L,
                                                                    TimeUnit.MILLISECONDS,
                                                                    new LinkedBlockingQueue<>(),
                                                                    new ThreadPoolExecutor.DiscardPolicy());

    private final ConcurrentNavigableMap<Id, Batch<C>> pendingBatches = new ConcurrentSkipListMap<>();
    private final Map<NodeId, SavedState<C>> syncResponses = new ConcurrentHashMap<>();
    private final RabiaPersistence<C> persistence;

    @SuppressWarnings("rawtypes")
    private final Map<CorrelationId, Promise> correlationMap = new ConcurrentHashMap<>();

    /// Decisions delivered to a node whose engine is `Stopped` / `Syncing` are buffered here
    /// instead of applied immediately. Applying them in those states causes asymmetric apply:
    /// the Decision mutates KV state via `commitDecision → advancePhase`, then the imminent
    /// `restoreSnapshot` wipes the mutation, and `applyRestoredState` regresses
    /// `currentPhase`. Net effect: KV writes from the live phase silently disappear from the
    /// rejoiner's local state machine. Buffer is drained at the tail of `activate()` once the
    /// engine is `Idle`, in phase-ascending order, filtered to phases at or above the
    /// post-restore `currentPhase` (older Decisions are safely discarded — they're already
    /// captured in the restored snapshot).
    private static final int MAX_BUFFERED_DECISIONS = 256;

    private final java.util.concurrent.ConcurrentLinkedDeque<Decision<C>> bufferedDecisions = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private final java.util.concurrent.atomic.AtomicInteger bufferedDecisionCount = new java.util.concurrent.atomic.AtomicInteger();

    //--------------------------------- Node State Start
    private final Map<Phase, PhaseData<C>> phases = new ConcurrentHashMap<>();
    private final AtomicReference<Phase> currentPhase = new AtomicReference<>(Phase.ZERO);
    /// Highest Rabia phase this node has ever observed the cluster reference, across any inbound
    /// peer message (Propose / VoteRound1 / VoteRound2 / Decision) and any sync candidate's
    /// `lastCommittedPhase`. Advance-only (never regresses). This is the engine's view of the
    /// cluster's committed frontier; `currentPhase` is the locally-applied frontier. When the
    /// former exceeds the latter the node is committed-ahead-of-applied — i.e. lagging — which
    /// [#isPendingCatchUp] reports so leadership-pinned ownership can be withheld from a
    /// not-yet-caught-up replacement leader (#329).
    private final AtomicReference<Phase> highestObservedClusterPhase = new AtomicReference<>(Phase.ZERO);
    private final AtomicReference<EngineState> engineState = new AtomicReference<>(new EngineState.Stopped());
    private final AtomicReference<Promise<Unit>> startPromise = new AtomicReference<>(Promise.promise());
    // Per Rabia spec: after a decision, the next phase inherits this value for round 1 vote
    private final AtomicReference<Option<StateValue>> lockedValue = new AtomicReference<>(Option.none());
    private final Option<ScheduledFuture<?>> cleanupTask;
    private final AtomicLong quorumSequence = new AtomicLong();

    /// Current cluster membership (consensus-level view).
    /// Tracked locally so [#reconfigure] can detect a true membership change vs a no-op
    /// replay of the same config. `none` until the first explicit reconfigure (or the
    /// first quorum activation observed against a known TopologyManager.topology()).
    private final AtomicReference<Option<ClusterConfig>> currentConfig = new AtomicReference<>(Option.none());

    /// Wave-1 §6.4 detect-only boot future-history check (cluster-topology-overhaul spec):
    /// one-shot gate so the persisted-vs-cluster phase comparison runs exactly once per process,
    /// at the FIRST sync restore (the moment the node first learns the cluster's reported state).
    private final AtomicBoolean bootFutureHistoryChecked = new AtomicBoolean(false);

    /// Listener invoked (persistedPhaseValue, clusterReportedPhaseValue) when the §6.4
    /// future-history hazard is detected — this node's persisted Rabia phase EXCEEDS what the
    /// joined cluster reports (mixed-wipe / `down -v` hazard). Detect-only: the engine logs WARN
    /// and notifies; recovery design is explicitly deferred (RC2). Default no-op; AetherNode
    /// wires the per-node transition journal.
    private volatile BiConsumer<Long, Long> onBootFutureHistory = (persisted, cluster) -> {};

    /// Listeners invoked after EVERY successful sync restore, once the restored state has been
    /// applied and the state machine content is queryable (and the engine re-activated).
    /// Late-joiner seam: state distributed via live notifications (e.g. KV `ValuePut`-derived
    /// state such as the cluster gossip-encryption key) is re-readable from the restored store
    /// at this point. Widened from a single slot to a list (Wave 8.6 / M5) so the gossip-key
    /// replay and the post-activation action-log drain can both register; invoked in
    /// registration order. See [#onStateRestored(Runnable)].
    private final List<Runnable> onStateRestored = new CopyOnWriteArrayList<>();

    //--------------------------------- Node State End
    /// Creates a new Rabia consensus engine without metrics or activation gating.
    ///
    /// @param topologyManager The topology manager for node communication
    /// @param network         The network implementation
    /// @param stateMachine    The state machine to apply commands to
    /// @param config          Configuration for the consensus engine
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config) {
        this(topologyManager,
             network,
             stateMachine,
             config,
             ConsensusMetrics.noop(),
             false,
             RabiaPersistence.inMemory(),
             DEFAULT_PHASE_STALL_CHECK);
    }

    /// Creates a new Rabia consensus engine with metrics but without activation gating.
    ///
    /// @param topologyManager The topology manager for node communication
    /// @param network         The network implementation
    /// @param stateMachine    The state machine to apply commands to
    /// @param config          Configuration for the consensus engine
    /// @param metrics         Metrics collector for observability
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config,
                       ConsensusMetrics metrics) {
        this(topologyManager,
             network,
             stateMachine,
             config,
             metrics,
             false,
             RabiaPersistence.inMemory(),
             DEFAULT_PHASE_STALL_CHECK);
    }

    /// Creates a new Rabia consensus engine with metrics and activation gating but in-memory persistence.
    ///
    /// When `activationGated` is true, the engine will not start consensus on quorum ESTABLISHED
    /// until `authorizeActivation()` is called. This allows the CDM to decide whether a joining
    /// node should participate in consensus or become a worker.
    ///
    /// @param topologyManager The topology manager for node communication
    /// @param network         The network implementation
    /// @param stateMachine    The state machine to apply commands to
    /// @param config          Configuration for the consensus engine
    /// @param metrics         Metrics collector for observability
    /// @param activationGated Whether consensus activation requires explicit authorization
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config,
                       ConsensusMetrics metrics,
                       boolean activationGated) {
        this(topologyManager,
             network,
             stateMachine,
             config,
             metrics,
             activationGated,
             RabiaPersistence.inMemory(),
             DEFAULT_PHASE_STALL_CHECK);
    }

    /// Creates a new Rabia consensus engine with all parameters except phaseStallCheck.
    /// Uses the default phase stall check interval.
    ///
    /// @param topologyManager The topology manager for node communication
    /// @param network         The network implementation
    /// @param stateMachine    The state machine to apply commands to
    /// @param config          Configuration for the consensus engine
    /// @param metrics         Metrics collector for observability
    /// @param activationGated Whether consensus activation requires explicit authorization
    /// @param persistence     The persistence implementation for state backup
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config,
                       ConsensusMetrics metrics,
                       boolean activationGated,
                       RabiaPersistence<C> persistence) {
        this(topologyManager,
             network,
             stateMachine,
             config,
             metrics,
             activationGated,
             persistence,
             DEFAULT_PHASE_STALL_CHECK);
    }

    /// Creates a new Rabia consensus engine with all parameters including persistence and phase stall check.
    /// Equivalent to the full-arity constructor with a no-op `consensusEventListener`.
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config,
                       ConsensusMetrics metrics,
                       boolean activationGated,
                       RabiaPersistence<C> persistence,
                       TimeSpan phaseStallCheck) {
        this(topologyManager,
             network,
             stateMachine,
             config,
             metrics,
             activationGated,
             persistence,
             phaseStallCheck,
             RabiaEngine::ignoreConsensusEvent);
    }

    /// Default no-op consumer used by legacy constructors that do not wire a
    /// `consensusEventListener`. Marked `@Contract` because the void return is intentional
    /// (sink semantics).
    @Contract
    private static void ignoreConsensusEvent(ConsensusEvent event) {}

    /// Creates a new Rabia consensus engine with all parameters including persistence,
    /// phase stall check, and a `consensusEventListener` for engine-level state transitions.
    ///
    /// @param topologyManager          The topology manager for node communication
    /// @param network                  The network implementation
    /// @param stateMachine             The state machine to apply commands to
    /// @param config                   Configuration for the consensus engine
    /// @param metrics                  Metrics collector for observability
    /// @param activationGated          Whether consensus activation requires explicit authorization
    /// @param persistence              The persistence implementation for state backup
    /// @param phaseStallCheck          Interval for checking phase stalls
    /// @param consensusEventListener   Sink for `ConsensusActive` / `ConsensusPassive` events;
    ///                                 typically `ConsensusBridge.consensusBridge(router)` in
    ///                                 production wiring
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config,
                       ConsensusMetrics metrics,
                       boolean activationGated,
                       RabiaPersistence<C> persistence,
                       TimeSpan phaseStallCheck,
                       Consumer<ConsensusEvent> consensusEventListener) {
        this.self = topologyManager.self().id();
        this.topologyManager = topologyManager;
        this.network = network;
        this.stateMachine = stateMachine;
        this.config = config;
        this.metrics = Option.option(metrics).or(ConsensusMetrics.noop());
        this.activationGated = activationGated;
        this.activationAuthorized = !activationGated;
        this.persistence = persistence;
        this.phaseStallCheck = phaseStallCheck;
        this.consensusEventListener = Option.option(consensusEventListener).or(RabiaEngine::ignoreConsensusEvent);
        this.cleanupTask = Option.some(SharedScheduler.scheduleAtFixedRate(this::cleanupOldPhases,
                                                                           config.cleanupInterval()));
    }

    @Contract
    @MessageReceiver
    public void clusterState(ClusterStateNotification clusterStateNotification) {
        if (!clusterStateNotification.advanceSequence(quorumSequence)) {
            log.debug("Ignoring stale ClusterStateNotification: {}", clusterStateNotification);

            return;
        }

        log.trace("Node {} received cluster state {}", self, clusterStateNotification);
        switch (clusterStateNotification.state()) {
            case ACTIVE -> handleClusterActive(clusterStateNotification);
            case PASSIVE -> pauseForQuorumLoss();
        }
    }

    private void handleClusterActive(ClusterStateNotification notification) {
        if (activationGated && !activationAuthorized) {
            log.info("Node {}: cluster active but activation gated, storing notification", self);
            pendingQuorum.set(notification);

            return;
        }
        // Membership-architecture-spec §4.5 / §7.3: distinguish quorum-resume (Paused → Idle,
        // no state reset) from cold-start (Stopped → Syncing). The Paused branch keeps the
        // engine's existing currentPhase / phases / pendingBatches / lockedValue intact;
        // any Decisions delivered during the pause have already been applied, so we just
        // re-arm phase processing.
        var current = engineState.get();

        if (current.isPaused()) {
            resumeFromPause();
        } else if (current.isActive() || current instanceof EngineState.Syncing) {
            // Redundant ACTIVE while already active/syncing — this is the ConsensusBridge echo
            // of our own ConsensusActive (ClusterStateNotification is engine-derived in steady
            // state). Ignore it: re-running clusterConnected() here would force a spurious
            // re-sync and, via the Active→Syncing transition, emit ConsensusPassive and flap.
            // TopologyObserver is the cold-start/resume originator; RabiaEngine owns every
            // steady-state transition.
            log.debug("Node {}: ignoring redundant cluster ACTIVE while {}", self, current);
        } else {
            // Stopped (cold start) or Observing — begin a sync round to catch up, then Active.
            clusterConnected();
        }
    }

    /// Authorize a gated engine to start consensus participation.
    /// If a quorum ESTABLISHED notification was received while gated, it is replayed.
    /// When promoting from observer mode, transitions directly to active without re-sync.
    @Contract
    public void authorizeActivation() {
        log.info("Node {}: consensus activation authorized", self);
        if (observerMode) {
            log.info("Node {}: promoting from observer to full consensus", self);
            observerMode = false;
        }

        activationAuthorized = true;
        var pending = pendingQuorum.getAndClear();

        if (pending.isPresent()) {
            log.info("Node {}: replaying stored cluster-state notification", self);
            clusterConnected();
        } else if (engineState.get().isObserving()) {
            safeExecute(this::promoteObserverToActive);
        }
    }

    private void promoteObserverToActive() {
        var oldState = engineState.getAndSet(new EngineState.Idle());

        exitState(oldState);
        notifyConsensusStateTransition();
        log.info("Node {} promoted from observer to active in phase {}", self, currentPhase.get());
        safeExecute(this::startPhase);
    }

    /// Authorize a gated engine to enter observer mode.
    /// The node will receive and apply committed Decisions but will not propose or vote.
    /// If a quorum ESTABLISHED notification was received while gated, it is replayed.
    @Contract
    public void authorizeObservation() {
        log.info("Node {}: consensus observation authorized (observer mode)", self);
        activationAuthorized = true;
        observerMode = true;
        pendingQuorum.getAndClear().onPresent(this::replayQuorumForObserver);
    }

    /// Returns true if the engine is currently in observer mode.
    public boolean isObserving() {
        return engineState.get()
                          .isObserving();
    }

    // Side-effect callback for `Option.onPresent` — void inherent. Triggered by
    // `pendingQuorum.getAndClear()` consumer chain in `authorizeObservation()`.
    @Contract
    private void replayQuorumForObserver(ClusterStateNotification notification) {
        log.info("Node {}: replaying stored cluster-state notification for observer mode", self);
        clusterConnected();
    }

    /// Cold-start path: engine is `Stopped`, quorum has just been established for the
    /// first time (or after a full reset via [#reconfigure]). Initiates a sync round to
    /// catch up state from peers, then transitions to `Active`.
    ///
    /// This is NOT used for quorum-return after a transient pause — that path goes through
    /// [#resumeFromPause] which preserves all in-memory state.
    private void clusterConnected() {
        log.info("Node {}: quorum connected. Starting synchronization attempts", self);
        safeExecute(this::doClusterConnected);
    }

    private void doClusterConnected() {
        syncResponses.clear();
        // Catch-up race fix: broadcast the first SyncRequest IMMEDIATELY instead of waiting a full
        // syncRetryInterval for the timer below. A replacement that joins a cluster hundreds of
        // phases ahead must start its snapshot-install round at once — otherwise it sits silently in
        // Syncing (triggerResync is a no-op while Syncing) and can be drained/killed as a not-ready
        // node before the first request ever goes out. The scheduled `synchronize` remains the retry;
        // doSynchronize processes accumulated responses (>= quorum) or re-broadcasts.
        network.broadcast(new SyncRequest(self));
        var task = SharedScheduler.schedule(this::synchronize,
                                            config.syncRetryInterval().randomize(SCALE));
        var oldState = engineState.getAndSet(new EngineState.Syncing(task));

        exitState(oldState);
        notifyConsensusStateTransition();
    }

    /// Membership-architecture-spec §4.5 / §7.3 — quorum-loss handler.
    ///
    /// Transitions Active (Idle/InPhase) or Observing engines to `Paused`, retaining ALL
    /// in-memory protocol state: `phases`, `currentPhase`, `pendingBatches`, `lockedValue`,
    /// `correlationMap`, `bufferedDecisions`. The state machine is NOT reset.
    ///
    /// On the subsequent quorum `ESTABLISHED` notification, [#resumeFromPause] re-arms
    /// phase processing without a sync round — Decisions delivered during the pause are
    /// applied directly by [#handleDecision], keeping the engine current.
    ///
    /// In-flight stall/sync timers are cancelled. State is also persisted to durable
    /// storage so that a crash during the pause leaves a recoverable snapshot.
    private void pauseForQuorumLoss() {
        safeExecute(this::doPauseForQuorumLoss);
    }

    private void doPauseForQuorumLoss() {
        var current = engineState.get();

        if (!current.isActive() && !current.isObserving() && !current.isPaused() && !(current instanceof EngineState.Syncing)) {
            // Stopped engines stay Stopped — nothing to pause.
            return;
        }

        if (current.isPaused()) {
            return;
        }

        var oldState = engineState.getAndSet(new EngineState.Paused());

        exitState(oldState);
        notifyConsensusStateTransition();
        persistence.save(stateMachine,
                         currentPhase.get(),
                         pendingBatches.values())
                   .onSuccessRun(() -> log.info("Node {} paused (quorum lost). State retained, snapshot persisted. currentPhase={}, pendingBatches={}",
                                                self,
                                                currentPhase.get(),
                                                pendingBatches.size()))
                   .onFailure(cause -> log.error("Node {} failed to persist state on pause: {}", self, cause));
    }

    /// Membership-architecture-spec §4.5 / §7.3 — quorum-return handler when previously paused.
    ///
    /// Transitions `Paused` → `Idle`, preserving `currentPhase`, `phases`, `pendingBatches`,
    /// `lockedValue`. Re-arms phase processing if pending batches remain. No sync round —
    /// Decisions delivered during the pause have already been applied.
    private void resumeFromPause() {
        safeExecute(this::doResumeFromPause);
    }

    private void doResumeFromPause() {
        var current = engineState.get();

        if (!current.isPaused()) {
            return;
        }

        engineState.set(new EngineState.Idle());
        notifyConsensusStateTransition();
        log.info("Node {} resumed from pause (quorum returned). currentPhase={}, pendingBatches={}",
                 self,
                 currentPhase.get(),
                 pendingBatches.size());
        // Drain any far-future Decisions that were buffered while Paused. Decisions with
        // phase < currentPhase are safely discarded; same/higher-phase ones are committed
        // idempotently (PhaseData.tryMarkDecided makes re-application a no-op).
        drainBufferedDecisions();
        if (!pendingBatches.isEmpty()) {
            safeExecute(this::startPhase);
        }
    }

    /// Membership-architecture-spec §4.5 — full reset, the ONLY path that wipes proposal state.
    ///
    /// Applying a `ClusterConfig` whose membership differs from the engine's current view
    /// drains in-flight proposals (failing them with [ConsensusError.NodeInactive]), clears
    /// all phase data, resets `currentPhase` to ZERO, resets the state machine, and persists
    /// an empty snapshot. The engine transitions to `Stopped`; the next quorum `ESTABLISHED`
    /// notification will trigger a fresh sync round against the new membership.
    ///
    /// Replaying the same membership is a no-op — no state is wiped if `newConfig` already
    /// matches the engine's current view. Returns success on no-op too.
    public Promise<Unit> reconfigure(ClusterConfig newConfig) {
        var promise = Promise.<Unit> promise();

        safeExecute(() -> doReconfigure(newConfig, promise));

        return promise;
    }

    private void doReconfigure(ClusterConfig newConfig, Promise<Unit> promise) {
        var existing = currentConfig.get();

        if (existing.map(c -> c.sameMembership(newConfig)).or(false)) {
            log.info("Node {}: reconfigure called with identical membership, no-op", self);
            currentConfig.set(Option.some(newConfig));
            promise.succeed(Unit.unit());

            return;
        }

        log.info("Node {}: reconfigure to new membership {} (was {})",
                 self,
                 newConfig.members(),
                 existing.map(ClusterConfig::members).or(List.of()));
        var oldState = engineState.getAndSet(new EngineState.Stopped());

        exitState(oldState);
        notifyConsensusStateTransition();
        persistence.save(stateMachine,
                         Phase.ZERO,
                         List.of())
                   .onFailure(cause -> log.error("Node {} failed to persist empty state on reconfigure: {}", self, cause));
        phases.clear();
        currentPhase.set(Phase.ZERO);
        highestObservedClusterPhase.set(Phase.ZERO);
        lockedValue.set(Option.none());
        stateMachine.reset();
        startPromise.set(Promise.promise());
        pendingBatches.clear();
        bufferedDecisions.clear();
        bufferedDecisionCount.set(0);
        correlationMap.forEach((_, p) -> p.fail(new ConsensusError.NodeInactive(self)));
        correlationMap.clear();
        currentConfig.set(Option.some(newConfig));
        log.info("Node {}: reconfigure complete; awaiting quorum to start sync against new membership", self);
        promise.succeed(Unit.unit());
    }

    /// Hard-shutdown path used only by [#stop]. Performs the full state-clearing reset that
    /// `clusterDisconnected()` used to do under quorum-loss; in the new design, quorum-loss
    /// goes through [#pauseForQuorumLoss] and only `stop()` (and [#reconfigure]) reset state.
    private void shutdownAndReset() {
        var current = engineState.get();

        if (current instanceof EngineState.Stopped) {
        // Already stopped via performStop's pre-set; just clear state.
        }

        persistence.save(stateMachine,
                         currentPhase.get(),
                         pendingBatches.values())
                   .onSuccessRun(() -> log.info("Node {} stopped. State persisted", self))
                   .onFailure(cause -> log.error("Node {} failed to persist state on stop: {}", self, cause));
        phases.clear();
        currentPhase.set(Phase.ZERO);
        highestObservedClusterPhase.set(Phase.ZERO);
        lockedValue.set(Option.none());
        stateMachine.reset();
        startPromise.set(Promise.promise());
        pendingBatches.clear();
        bufferedDecisions.clear();
        bufferedDecisionCount.set(0);
        correlationMap.forEach((_, promise) -> promise.fail(new ConsensusError.NodeInactive(self)));
        correlationMap.clear();
    }

    public boolean isActive() {
        return engineState.get()
                          .isActive();
    }

    /// Returns true when the engine is in the `Paused` state — quorum is currently
    /// unavailable, in-memory protocol state is retained, and new `apply()` submissions
    /// are rejected with [ConsensusError.QuorumPaused]. Mutually exclusive with `isActive`.
    public boolean isPaused() {
        return engineState.get()
                          .isPaused();
    }

    /// Returns true when this node's locally-applied frontier trails the cluster's observed
    /// committed frontier — i.e. the engine is still draining the consensus log and has NOT
    /// caught up. Distinct from [#isActive], which only reports protocol participation
    /// (Idle/InPhase) and stays true for a freshly-elected replacement leader whose log is
    /// still draining.
    ///
    /// Pending-catch-up is true when either:
    ///   - the engine is mid-resync (`Syncing`) — by construction it is behind and pulling state, or
    ///   - the highest cluster phase observed from any peer message exceeds the locally-applied
    ///     `currentPhase` (the committed-ahead-of-applied gap).
    ///
    /// A genuinely caught-up active node (no higher cluster phase seen, not syncing) reports
    /// false. Used by the leader-pinned task-group owner resolver (#329) to withhold ownership
    /// from a lagging replacement leader so synchronous leader-local commits do not stall and
    /// 503; the resolver bounds the suppression in time so this can never wedge ownership.
    public boolean isPendingCatchUp() {
        var state = engineState.get();

        if (state instanceof EngineState.Syncing) {
            return true;
        }

        return highestObservedClusterPhase.get()
                                          .compareTo(currentPhase.get()) > 0;
    }

    /// Advance-only update of the cluster's observed committed frontier. Records the highest
    /// phase any inbound peer message has referenced so [#isPendingCatchUp] can compare it to
    /// the locally-applied `currentPhase`. Never regresses.
    private void observeClusterPhase(Phase phase) {
        highestObservedClusterPhase.updateAndGet(existing -> existing.compareTo(phase) >= 0
                                                             ? existing
                                                             : phase);
    }

    /// Package-private test hook: highest observed cluster phase (the committed frontier).
    Phase highestObservedClusterPhaseForTesting() {
        return highestObservedClusterPhase.get();
    }

    /// Package-private test hook: current Rabia phase.
    /// Used by R1 unit tests to verify state retention across pause/resume.
    Phase currentPhaseForTesting() {
        return currentPhase.get();
    }

    /// Package-private test hook: number of pending batches awaiting consensus.
    /// Used by R1 unit tests to verify state retention across pause/resume.
    int pendingBatchCountForTesting() {
        return pendingBatches.size();
    }

    /// Package-private test hook: current cluster config (set by [#reconfigure]).
    Option<ClusterConfig> currentConfigForTesting() {
        return currentConfig.get();
    }

    public <R> Promise<List<R>> apply(List<C> commands) {
        var pendingAnswer = Promise.<List<R>> promise();

        return submitCommands(commands,
                              batch -> correlationMap.put(batch.correlationIds().getFirst(),
                                                          pendingAnswer)).async()
                             .flatMap(_ -> pendingAnswer.timeout(config.applyTimeout())
                                                        .mapError(this::toApplyTimeout));
    }

    /// Replaces the generic `CoreError.Timeout` produced by `Promise.timeout` with the
    /// domain-specific `ConsensusError.ApplyTimeout`, leaving every other cause untouched.
    private Cause toApplyTimeout(Cause cause) {
        return cause instanceof CoreError.Timeout
               ? new ConsensusError.ApplyTimeout(config.applyTimeout().millis())
               : cause;
    }

    @Contract
    @MessageReceiver
    public void handleSubmit(SubmitCommands<C> submitCommands) {
        submitCommands(submitCommands.commands(),
                       _ -> {});
    }

    private Result<Batch<C>> submitCommands(List<C> commands, Consumer<Batch<C>> onBatchPrepared) {
        if (log.isDebugEnabled()) {
            var caller = Thread.currentThread().getStackTrace();
            var callerInfo = caller.length > 3
                             ? caller[3].toString()
                             : "unknown";

            log.debug("Node {} submitting {} command(s): {} [caller: {}]", self, commands.size(), commands, callerInfo);
        }

        return validateSubmission(commands).map(_ -> prepareBatch(commands))
                                 .onSuccess(batch -> safeExecute(() -> registerBatch(batch, onBatchPrepared)))
                                 .onSuccess(batch -> safeExecute(() -> broadcastBatch(batch)));
    }

    private Result<List<C>> validateSubmission(List<C> commands) {
        if (commands.isEmpty()) {
            return new ConsensusError.CommandBatchIsEmpty().result();
        }

        var state = engineState.get();

        if (state.isPaused()) {
            return new ConsensusError.QuorumPaused(self).result();
        }

        if (!state.isActive()) {
            return new ConsensusError.NodeInactive(self).result();
        }

        var pending = pendingBatches.size();

        if (pending >= config.maxPendingBatches()) {
            return new ConsensusError.BackpressureExceeded(pending, config.maxPendingBatches()).result();
        }

        return Result.success(commands);
    }

    private Batch<C> prepareBatch(List<C> commands) {
        var batch = stateMachine.createBatch(commands);

        log.trace("Node {}: client submitted {} command(s). Prepared batch: {}", self, commands.size(), batch);

        return batch;
    }

    private void registerBatch(Batch<C> batch, Consumer<Batch<C>> onBatchPrepared) {
        pendingBatches.put(batch.id(), batch);
        metrics.updatePendingBatches(self, pendingBatches.size());
        onBatchPrepared.accept(batch);
        triggerPhaseIfNeeded();
    }

    private void broadcastBatch(Batch<C> batch) {
        network.broadcast(new NewBatch<>(self, batch));
    }

    private void triggerPhaseIfNeeded() {
        if (engineState.get() instanceof EngineState.Idle) {
            safeExecute(this::startPhase);
        }
    }

    public Promise<Unit> start() {
        return startPromise.get();
    }

    public Promise<Unit> stop() {
        return Promise.promise(this::performStop);
    }

    private void performStop(Promise<Unit> promise) {
        cleanupTask.onPresent(task -> task.cancel(false));
        var oldState = engineState.getAndSet(new EngineState.Stopped());

        exitState(oldState);
        notifyConsensusStateTransition();
        // Synchronously fail in-flight promises BEFORE executor.shutdown(); otherwise
        // shutdownAndReset() may execute concurrently with the DiscardPolicy and leave
        // callers (e.g. publisher.runApply) waiting on cluster.apply(...) Promises forever.
        correlationMap.forEach((_, p) -> p.fail(new ConsensusError.NodeInactive(self)));
        correlationMap.clear();
        shutdownAndReset();
        executor.shutdown();
        promise.succeed(Unit.unit());
    }

    /// Containment boundary for the single consensus apply worker (7c). Every task submitted to
    /// {@link #executor} runs through here so a handler that throws — on the live-apply path OR the
    /// snapshot/resync restore path — is caught and logged instead of escaping the worker's run(),
    /// terminating the thread, dropping the in-flight consensus round, and leaving the node
    /// permanently un-converged (topology never applied -> stuck behind -> infinite resync). Mirrors
    /// the swallow semantics the live KV dispatch already has (MessageRouter.dispatchOne): the worker
    /// survives to process subsequent rounds; the failed round is abandoned and re-driven by the
    /// sender's retry. Errors (non-RuntimeException Throwable) are intentionally left to propagate.
    private void safeExecute(Runnable task) {
        executor.execute(() -> {
            var start = System.nanoTime();

            try {
                task.run();
                var elapsed = System.nanoTime() - start;

                if (elapsed >= SLOW_APPLY_THRESHOLD_NANOS) {
                    log.warn("SLOW-APPLY ms={} queueDepth={}", elapsed / 1_000_000, applyQueueDepth());
                }
            } catch (RuntimeException e) {
                log.error("Consensus apply task threw {} — contained; worker preserved, round not applied",
                          e.getClass().getSimpleName(),
                          e);
            }
        });
    }

    /// Diagnostic helper: backlog of the single-thread apply executor. The executor is always a
    /// `ThreadPoolExecutor` (see field construction); returns -1 if that ever changes so the probe
    /// never throws.
    private int applyQueueDepth() {
        return executor instanceof ThreadPoolExecutor pool
               ? pool.getQueue()
                     .size()
               : -1;
    }

    @Contract
    @MessageReceiver
    public void processPropose(Propose<C> propose) {
        safeExecute(() -> handlePropose(propose));
    }

    @Contract
    @MessageReceiver
    public void processVoteRound1(VoteRound1 voteRound1) {
        safeExecute(() -> handleVoteRound1(voteRound1));
    }

    @Contract
    @MessageReceiver
    public void processVoteRound2(VoteRound2 voteRound2) {
        safeExecute(() -> handleVoteRound2(voteRound2));
    }

    @Contract
    @MessageReceiver
    public void processDecision(Decision<C> decision) {
        safeExecute(() -> handleDecision(decision));
    }

    @Contract
    @MessageReceiver
    public void processSyncResponse(SyncResponse<C> syncResponse) {
        safeExecute(() -> handleSyncResponse(syncResponse));
    }

    @Contract
    @SuppressWarnings("unchecked")
    @MessageReceiver
    public void handleNewBatch(NewBatch<?> newBatch) {
        safeExecute(() -> doHandleNewBatch((Batch<C>) newBatch.batch()));
    }

    private void doHandleNewBatch(Batch<C> incoming) {
        // Use compute() for atomic merge to avoid race conditions. The compute
        // lambda routes through `Option.option(existing)` so the absent case is
        // expressed via `fold` rather than a raw `existing == null` sentinel.
        // Same id ⟹ same commands (content-derived id), so we merge correlationIds
        // directly via the state machine.
        pendingBatches.compute(incoming.id(),
                               (_, existing) -> Option.option(existing).fold(() -> incoming,
                                                                             current -> stateMachine.merge(current,
                                                                                                           incoming)));
        if (engineState.get().isInPhase()) {
            // Already in phase - broadcast our proposal for this batch if not already proposed
            broadcastOwnProposalIfNeeded();
        } else {
            triggerPhaseIfNeeded();
        }
    }

    /// Broadcasts own proposal for pending batch if not already proposed in current phase.
    private void broadcastOwnProposalIfNeeded() {
        var phase = currentPhase.get();
        var phaseData = getOrCreatePhaseData(phase);

        if (phaseData.hasProposal(self)) {
            return;
        }

        Option.option(pendingBatches.firstEntry()).onPresent(batchEntry -> broadcastOwnProposal(phase,
                                                                                                phaseData,
                                                                                                batchEntry.getValue()));
    }

    /// Starts a new phase with pending commands.
    /// Dormant nodes must not enter phases -- they accumulate batches in pendingBatches
    /// and process them after activation. Without this guard, dormant nodes would broadcast
    /// Propose messages but ignore incoming votes, creating an unrecoverable phase deadlock.
    private void startPhase() {
        var current = engineState.get();

        if (!current.isActive()) {
            return;
        }

        if (! (current instanceof EngineState.Idle)) {
            return;
        }

        Option.option(pendingBatches.firstEntry())
              .onEmpty(this::reExecuteStartPhaseIfBatchPending)
              .onPresent(batchEntry -> startPhaseWithBatch(current,
                                                           batchEntry.getValue()));
    }

    private void reExecuteStartPhaseIfBatchPending() {
        // Re-check after — a batch may have been added during the window
        if (!pendingBatches.isEmpty()) {
            safeExecute(this::startPhase);
        }
    }

    private void startPhaseWithBatch(EngineState current, Batch<C> batch) {
        var phase = currentPhase.get();

        log.trace("Node {} starting phase {} with batch {}", self, phase, batch.id());
        var stallDetector = createStallDetector();

        if (!engineState.compareAndSet(current, new EngineState.InPhase(stallDetector))) {
            stallDetector.cancel(false);

            return;
        }

        notifyConsensusStateTransition();
        var phaseData = getOrCreatePhaseData(phase);

        phaseData.registerProposal(self, batch);
        network.broadcast(new Propose<>(self, phase, batch));
        broadcastLockedValueIfPresent(phase, phaseData);
    }

    private void broadcastLockedValueIfPresent(Phase phase, PhaseData<C> phaseData) {
        lockedValue.getAndSet(Option.none()).onPresent(locked -> broadcastLockedVote(phase, phaseData, locked));
    }

    private void broadcastLockedVote(Phase phase, PhaseData<C> phaseData, StateValue locked) {
        var vote = new VoteRound1(self, phase, locked);

        log.trace("Node {} immediately voting locked value {} for phase {}", self, locked, phase);
        network.broadcast(vote);
        phaseData.registerRound1Vote(self, locked);
    }

    /// Synchronizes with other nodes to catch up if needed.
    private void synchronize() {
        safeExecute(this::doSynchronize);
    }

    private void doSynchronize() {
        if (engineState.get().isActive()) {
            return;
        }
        // Check if we already have enough responses from previous attempt
        if (syncResponses.size() >= syncQuorumSize()) {
            // Process immediately instead of clearing
            processAccumulatedSyncResponses();

            return;
        }
        // Only clear and restart if we don't have enough responses
        syncResponses.clear();
        var request = new SyncRequest(self);

        log.trace("Node {}: requesting phase synchronization {}", self, request);
        network.broadcast(request);
        var task = SharedScheduler.schedule(this::synchronize,
                                            config.syncRetryInterval().randomize(SCALE));
        var oldState = engineState.getAndSet(new EngineState.Syncing(task));

        exitState(oldState);
        notifyConsensusStateTransition();
    }

    private void processAccumulatedSyncResponses() {
        var responses = syncResponses.values()
                                     .stream()
                                     .sorted(Comparator.comparing(SavedState::lastCommittedPhase))
                                     .toList();

        if (responses.isEmpty()) {
            log.warn("Node {} has no sync responses to process", self);

            return;
        }

        var candidate = responses.getLast();

        log.trace("Node {} uses {} as synchronization candidate out of {}", self, candidate, syncResponses.size());
        restoreState(candidate);
    }

    /// Handles a synchronization response from another node.
    private void handleSyncResponse(SyncResponse<C> response) {
        if (engineState.get().isActive()) {
            log.trace("Node {} ignoring synchronization response {}. Node is active", self, response);

            return;
        }

        syncResponses.put(response.sender(), response.state());
        if (syncResponses.size() < syncQuorumSize()) {
            log.trace("Node {} received {} responses {}, not enough to proceed (quorum size = {})",
                      self,
                      syncResponses.size(),
                      syncResponses.keySet(),
                      syncQuorumSize());

            return;
        }

        log.trace("Node {} received {} responses, collected: {}", self, syncResponses.size(), syncResponses);
        // Use the latest known state among received responses
        var candidate = syncResponses.values()
                                     .stream()
                                     .sorted(Comparator.comparing(SavedState::lastCommittedPhase))
                                     .toList()
                                     .getLast();

        log.trace("Node {} uses {} as synchronization candidate out of {}", self, candidate, syncResponses.size());
        restoreState(candidate);
    }

    private void restoreState(SavedState<C> state) {
        detectBootFutureHistory(state);
        observeClusterPhase(state.lastCommittedPhase());
        syncResponses.clear();
        // Always carry forward the source's lastCommittedPhase + pendingBatches even when
        // the state-machine snapshot is empty. V0 decisions advance phase without touching
        // the state machine, so a syncing node that ignores the empty-snapshot phase ends
        // up perpetually `MAX_PHASE_AHEAD` behind and burns its retry budget on resyncs.
        if (state.snapshot().length == 0) {
            applyRestoredState(state);
            activate();
            replayStateNotifications();
            notifyStateRestored();

            return;
        }
        // sync → activate → replay (cluster-topology-overhaul §5.8, AMENDED 2026-06-11):
        // restoreSnapshot installs the synced state SILENTLY (no notifications); activate() flips
        // the engine ACTIVE; replayStateNotifications() then fires the synthetic notification burst
        // as the FIRST work after activation, so a KV notification structurally implies ACTIVE.
        stateMachine.restoreSnapshot(state.snapshot())
                    .onSuccess(_ -> applyRestoredState(state))
                    .onSuccessRun(this::activate)
                    .onSuccessRun(this::replayStateNotifications)
                    .onSuccessRun(this::notifyStateRestored)
                    .onFailure(cause -> log.error("Node {} failed to restore state: {}", self, cause));
    }

    /// Fire the state machine's deferred notification burst (cluster-topology-overhaul §5.8,
    /// AMENDED 2026-06-11). Called AFTER [#activate()] on the apply thread, so the synthetic puts
    /// land before any live apply queued behind this restore — consumers observe a totally-ordered
    /// "world-as-of-N, then N+1 onward" stream. Mutation-free notification synthesis only.
    @Contract
    private void replayStateNotifications() {
        stateMachine.replayNotifications();
    }

    /// Wave-1 §6.4 boot-time future-history sanity check (DETECT-ONLY, ratified D9). Runs ONCE
    /// per process, at the first sync restore — the moment this node first learns the cluster's
    /// reported Rabia phase (`candidate.lastCommittedPhase()`, the max among the quorum of sync
    /// responses). Compares it against this node's OWN persisted phase (`persistence.load()`).
    /// Persisted phase EXCEEDING the cluster-reported phase means "I have a future this cluster
    /// never saw" — the mixed-wipe / `down -v` hazard. Emits WARN + notifies the
    /// [#onBootFutureHistory] listener (journal feed); changes NO control flow — restore
    /// proceeds exactly as before. Rabia is leaderless: the phase counter is the log index and
    /// there is no separate persisted term, so the phase pair is precisely what is comparable.
    /// With the in-memory persistence (no snapshot survives restart) `load()` is empty and the
    /// check is trivially silent.
    @Contract
    private void detectBootFutureHistory(SavedState<C> candidate) {
        if (!bootFutureHistoryChecked.compareAndSet(false, true)) {
            return;
        }

        persistence.load()
                   .map(SavedState::lastCommittedPhase)
                   .filter(persisted -> persisted.compareTo(candidate.lastCommittedPhase()) > 0)
                   .onPresent(persisted -> warnBootFutureHistory(persisted,
                                                                 candidate.lastCommittedPhase()));
    }

    @Contract
    private void warnBootFutureHistory(Phase persisted, Phase clusterReported) {
        log.warn("Node {} BOOT FUTURE-HISTORY detected (§6.4, detect-only): persisted Rabia phase {} exceeds "
                + "cluster-reported sync phase {} — this node carries history the joined cluster never saw "
                + "(mixed-wipe / down -v hazard). Recovery is NOT attempted (RC2).",
                 self,
                 persisted,
                 clusterReported);
        onBootFutureHistory.accept(persisted.value(), clusterReported.value());
    }

    /// Install the Wave-1 §6.4 boot future-history listener (invoked with
    /// `(persistedPhaseValue, clusterReportedPhaseValue)` when [#detectBootFutureHistory] trips).
    /// Diagnostic-only; default no-op. A `null` argument resets to the no-op.
    @Contract
    public void onBootFutureHistory(BiConsumer<Long, Long> listener) {
        this.onBootFutureHistory = listener == null
                                   ? (persisted, cluster) -> {}
                                   : listener;
    }

    /// Register a post-restore listener, invoked after EVERY successful sync restore once the
    /// restored state has been applied, the engine re-activated, AND the §5.8 notification replay
    /// has fired. Restore can run more than once per process (initial join sync, later re-syncs),
    /// so listeners must be idempotent. Fires on the thread `restoreState` completes on (the
    /// consensus apply executor); listeners must be cheap and thread-safe. Listeners ACCUMULATE and
    /// run in registration order. A `null` argument clears all registered listeners.
    ///
    /// SEAM RETAINED (Wave 8 M5 rework, 2026-06-11): the §5.8 amendment removed the production
    /// listeners (gossip-key `replayFromStore` and the action-log drain) — KV-derived boot state
    /// now arrives via the engine-level replay on the normal subscription, not via this hook. The
    /// seam stays as a general post-restore lifecycle notification (exercised by `RabiaEngineTest`
    /// and available for non-KV restore-completion needs); it is NOT narrowed because it is a
    /// legitimate engine lifecycle event independent of the KV notification path.
    @Contract
    public void onStateRestored(Runnable listener) {
        if (listener == null) {
            onStateRestored.clear();

            return;
        }

        onStateRestored.add(listener);
    }

    /// Invoke the post-restore listeners in registration order. Runs on the consensus apply thread.
    @Contract
    private void notifyStateRestored() {
        onStateRestored.forEach(Runnable::run);
    }

    private void applyRestoredState(SavedState<C> state) {
        // Advance-only: never regress currentPhase below where it already is. A live Decision
        // applied during the Stopped/Syncing window (now buffered via `handleDecision`'s state
        // guard) could have advanced the counter past the candidate snapshot's phase; an
        // unconditional `set` would drop the rejoiner back behind the cluster and cause
        // `commitDecision` to ignore subsequent same-phase Decisions as duplicates.
        currentPhase.updateAndGet(existing -> existing.compareTo(state.lastCommittedPhase()) >= 0
                                              ? existing
                                              : state.lastCommittedPhase());
        state.pendingBatches().forEach(batch -> pendingBatches.put(batch.id(), batch));
        persistence.save(stateMachine, currentPhase.get(), pendingBatches.values());
        log.info("Node {} restored state from persistence. Current phase {}", self, currentPhase.get());
    }

    /// Activate node and adjust phase, if necessary.
    /// In observer mode, transitions to Observing state instead of Idle and does not start phases.
    private void activate() {
        if (observerMode) {
            activateAsObserver();

            return;
        }

        var oldState = engineState.getAndSet(new EngineState.Idle());

        exitState(oldState);
        notifyConsensusStateTransition();
        startPromise.get().succeed(Unit.unit());
        syncResponses.clear();
        metrics.recordSyncAttempt(self, true);
        log.info("Node {} activated in phase {}", self, currentPhase.get());
        // Drain any Decisions that were buffered while the engine was Stopped/Syncing.
        // Must happen AFTER engineState=Idle so that `handleDecision`'s state guard accepts
        // re-applied Decisions, and AFTER the Idle-restore so phase-filter math is correct.
        drainBufferedDecisions();
        safeExecute(this::startPhase);
    }

    private void activateAsObserver() {
        var oldState = engineState.getAndSet(new EngineState.Observing());

        exitState(oldState);
        notifyConsensusStateTransition();
        startPromise.get().succeed(Unit.unit());
        syncResponses.clear();
        metrics.recordSyncAttempt(self, true);
        log.info("Node {} activated in observer mode at phase {}", self, currentPhase.get());
    }

    /// Cancels any timers owned by the old state during a transition.
    private void exitState(EngineState oldState) {
        switch (oldState) {
            case EngineState.InPhase(var stallDetector) -> stallDetector.cancel(false);
            case EngineState.Syncing(var syncTask) -> syncTask.cancel(false);
            default -> {}
        }
    }

    /// Re-evaluates whether the engine is currently in an `Active` phase (`Idle | InPhase`)
    /// and emits a `ConsensusEvent` ONLY on edge transitions. Idempotent within an
    /// active-/passive-phase window: the same edge will not fire twice. Called from every
    /// state-mutation site.
    ///
    /// E2 Phase 2c.0 (2026-05-28): `RabiaEngine` is the authoritative source for "consensus
    /// engine is genuinely operational" semantics. The bridge translates these into
    /// `ClusterStateNotification.ACTIVE` / `PASSIVE` on the cluster `MessageRouter`.
    @Contract
    private void notifyConsensusStateTransition() {
        var nowActive = engineState.get().isActive();
        var prev = lastPublishedActive.get();

        if (nowActive == prev) {
            return;
        }

        if (!lastPublishedActive.compareAndSet(prev, nowActive)) {
            return;
        }

        if (nowActive) {
            // Single authority for downstream ClusterStateNotification: TopologyObserver feeds the
            // quorum edge to this engine privately, and this emission (via ConsensusBridge) is now
            // the sole shared-bus producer of Rabia's active status. CAS-latch above stays as
            // belt-and-suspenders: notifyConsensusStateTransition runs off several state-mutation
            // sites that are not all on the single apply executor, so the edge guard is load-bearing.
            log.info("Node {}: emitting ConsensusActive (cluster active)", self);
            consensusEventListener.accept(new ConsensusActive(self));
        } else {
            log.info("Node {}: emitting ConsensusPassive (cluster passive)", self);
            consensusEventListener.accept(new ConsensusPassive(self));
        }
    }

    /// Creates a periodic stall detector that re-broadcasts the held proposal set and this
    /// node's own votes when a phase hasn't advanced within the configured interval.
    private ScheduledFuture<?> createStallDetector() {
        return SharedScheduler.scheduleAtFixedRate(() -> safeExecute(this::checkPhaseStall), phaseStallCheck);
    }

    /// Checks if the current phase is stalled and re-broadcasts protocol messages so peers that
    /// missed the first send (transient QUIC reconnect) or that joined the phase after the
    /// original contributors died can collect them.
    ///
    /// Re-broadcasts, while short of quorum/majority:
    ///   - the FULL proposal SET this node holds (not just its own) — see #258 below;
    ///   - this node's own Round 1 / Round 2 votes.
    ///
    /// Why re-broadcast votes: on a 5-way simultaneous restart, peerLinks flap during the QUIC
    /// handshake storm. `QuicClusterNetwork.broadcast` only sends to peers currently in peerLinks,
    /// so a message sent while a peer is in re-dial gets dropped. Votes are idempotent at the
    /// receiver (registerRound1Vote/registerRound2Vote are keyed on (phase, sender)), so
    /// re-broadcasting is safe.
    ///
    /// Why re-broadcast the whole proposal set (#258): the detector used to re-broadcast only the
    /// node's OWN proposal. If the voters that contributed the quorum proposals die mid-phase,
    /// surviving/fresh voters can never satisfy `hasQuorumProposals` and can never vote — an
    /// unrecoverable phase deadlock. The holder of a dead node's proposal now re-broadcasts it
    /// (under the original sender's id; `registerProposal` is idempotent and keyed on sender), so
    /// a fresh voter synced to the stalled phase can reach quorum proposals and make progress.
    private void checkPhaseStall() {
        if (! (engineState.get() instanceof EngineState.InPhase)) {
            return;
        }

        var phase = currentPhase.get();

        Option.option(phases.get(phase)).onPresent(phaseData -> checkPhaseStallFor(phase, phaseData));
    }

    private void checkPhaseStallFor(Phase phase, PhaseData<C> phaseData) {
        var quorumSize = topologyManager.quorumSize();

        if (!phaseData.hasQuorumProposals(quorumSize) && phaseData.proposalCount() > 0) {
            log.debug("Node {} stall detected in phase {}: {}/{} proposals, re-broadcasting full proposal set",
                      self,
                      phase,
                      phaseData.proposalCount(),
                      quorumSize);
            rebroadcastProposalSet(phase, phaseData);
        }

        if (phaseData.hasVotedRound1(self) && !phaseData.hasRound1MajorityVotes(quorumSize)) {
            Option.option(phaseData.getRound1Vote(self)).onPresent(value -> rebroadcastRound1Stall(phase, value));
        }

        if (phaseData.hasVotedRound2(self) && !phaseData.hasRound2MajorityVotes(quorumSize)) {
            Option.option(phaseData.getRound2Vote(self)).onPresent(value -> rebroadcastRound2Stall(phase, value));
        }
    }

    /// Re-broadcasts every proposal this node holds for the stalled phase, each under its
    /// original contributing node's id. Idempotent at the receiver; bounded by the periodic
    /// stall-check cadence and by the `!hasQuorumProposals` guard at the call site.
    private void rebroadcastProposalSet(Phase phase, PhaseData<C> phaseData) {
        phaseData.proposals().forEach((proposer, batch) -> network.broadcast(new Propose<>(proposer, phase, batch)));
    }

    private void rebroadcastRound1Stall(Phase phase, StateValue value) {
        log.debug("Node {} stall detected in phase {}: round1 votes short of quorum, re-broadcasting own R1 vote",
                  self,
                  phase);
        network.broadcast(new VoteRound1(self, phase, value));
    }

    private void rebroadcastRound2Stall(Phase phase, StateValue value) {
        log.debug("Node {} stall detected in phase {}: round2 votes short of quorum, re-broadcasting own R2 vote",
                  self,
                  phase);
        network.broadcast(new VoteRound2(self, phase, value));
    }

    /// Handles a synchronization request from another node.
    @Contract
    @MessageReceiver
    public void handleSyncRequest(SyncRequest request) {
        safeExecute(() -> doHandleSyncRequest(request));
    }

    private void doHandleSyncRequest(SyncRequest request) {
        var state = engineState.get();
        // A Paused responder retains its full in-memory protocol state (stateMachine,
        // currentPhase, pendingBatches) so it can serve the SAME live-equivalent payload an
        // active/observing node would. Only genuinely stateless engines (Stopped/Syncing) fall
        // back to the persisted/empty snapshot, which omits pendingBatches and would otherwise
        // leave a joiner unable to re-propose an in-flight batch.
        if (state.isActive() || state.isObserving() || state.isPaused()) {
            stateMachine.makeSnapshot()
                        .map(snapshot -> new SyncResponse<>(self,
                                                            savedState(snapshot,
                                                                       currentPhase.get(),
                                                                       pendingBatches.values())))
                        .onSuccess(response -> network.send(request.sender(),
                                                            response))
                        .onFailure(cause -> log.error("Node {} failed to create snapshot: {}", self, cause));
        } else {
            log.trace("Node {} is inactive, trying to share saved (or empty) state for request: {}", self, request);
            var response = new SyncResponse<>(self,
                                              persistence.load().or(SavedState.empty()));

            network.send(request.sender(), response);
        }
    }

    /// Calculates quorum size for sync based on currently connected peers.
    /// Unlike consensus quorum (fixed cluster size), sync quorum adapts to actual connectivity.
    /// Uses minimum of connected count and expected cluster size for robustness.
    private int syncQuorumSize() {
        var connectedCount = network.connectedNodeCount();
        var clusterSize = topologyManager.clusterSize();
        var effectiveSize = Math.min(connectedCount, clusterSize);

        return effectiveSize / 2 + 1;
    }

    /// Cleans up old phase data to prevent memory leaks.
    private void cleanupOldPhases() {
        safeExecute(this::doCleanupOldPhases);
    }

    private void doCleanupOldPhases() {
        var state = engineState.get();

        if (!state.isActive() && !state.isObserving()) {
            return;
        }

        var current = currentPhase.get();

        phases.keySet().removeIf(phase -> isExpiredPhase(phase, current));
    }

    private boolean isExpiredPhase(Phase phase, Phase current) {
        return phase.compareTo(current) < 0 && current.value() - phase.value() > config.removeOlderThanPhases();
    }

    /// Handles a Propose message from another node.
    /// NOTE: All nodes MUST process proposals regardless of active/dormant state.
    /// Rabia is leaderless — every node participates in every round.
    private void handlePropose(Propose<C> propose) {
        log.trace("Node {} received proposal from {} for phase {}", self, propose.sender(), propose.phase());
        observeClusterPhase(propose.phase());
        var currentPhaseValue = currentPhase.get();

        if (isPastPhase(propose.phase(), currentPhaseValue)) {
            log.trace("Node {} ignoring proposal for past phase {}", self, propose.phase());

            return;
        }

        if (isFarFuturePhase(propose.phase(), currentPhaseValue)) {
            log.warn("Node {} behind by {} phases (current: {}, received: {}). Triggering resync.",
                     self,
                     propose.phase().value() - currentPhaseValue.value(),
                     currentPhaseValue,
                     propose.phase());
            triggerResync();

            return;
        }

        var phaseData = getOrCreatePhaseData(propose.phase());

        enterPhaseIfNeeded(propose.phase(), currentPhaseValue, phaseData);
        registerProposal(propose, phaseData);
        tryBroadcastRound1Vote(propose.phase(), phaseData);
    }

    private static final long MAX_PHASE_AHEAD = 100;

    private boolean isFarFuturePhase(Phase proposalPhase, Phase current) {
        return proposalPhase.value() - current.value() > MAX_PHASE_AHEAD;
    }

    /// Triggers a resync when the node detects it's significantly behind.
    /// No-op when already syncing — doSynchronize() handles retries internally.
    private void triggerResync() {
        if (engineState.get() instanceof EngineState.Syncing) {
            return;
        }

        doClusterConnected();
    }

    private boolean isPastPhase(Phase proposalPhase, Phase current) {
        return proposalPhase.compareTo(current) < 0;
    }

    private void enterPhaseIfNeeded(Phase proposalPhase, Phase currentPhaseValue, PhaseData<C> phaseData) {
        if (!proposalPhase.equals(currentPhaseValue) || engineState.get().isInPhase()) {
            return;
        }

        log.trace("Node {} entering phase {} triggered by external proposal", self, proposalPhase);
        var stallDetector = createStallDetector();
        var current = engineState.get();

        if (! (current instanceof EngineState.Idle) || !engineState.compareAndSet(current,
                                                                                  new EngineState.InPhase(stallDetector))) {
            stallDetector.cancel(false);

            return;
        }

        notifyConsensusStateTransition();
        Option.option(pendingBatches.firstEntry()).onPresent(batchEntry -> broadcastOwnProposal(proposalPhase,
                                                                                                phaseData,
                                                                                                batchEntry.getValue()));
        // Broadcast locked value if present (same as startPhase does)
        broadcastLockedValueIfPresent(proposalPhase, phaseData);
    }

    private void broadcastOwnProposal(Phase phase, PhaseData<C> phaseData, Batch<C> batch) {
        phaseData.registerProposal(self, batch);
        network.broadcast(new Propose<>(self, phase, batch));
    }

    private void registerProposal(Propose<C> propose, PhaseData<C> phaseData) {
        phaseData.registerProposal(propose.sender(), propose.value());
        metrics.recordProposal(propose.sender(), propose.phase());
    }

    private void tryBroadcastRound1Vote(Phase phase, PhaseData<C> phaseData) {
        var quorumSize = topologyManager.quorumSize();

        if (canVoteRound1(phase, phaseData, quorumSize)) {
            broadcastRound1Vote(phase, phaseData, quorumSize);
        } else {
            logRound1VoteConditionsNotMet(phase, phaseData, quorumSize);
        }
    }

    private boolean canVoteRound1(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        return engineState.get()
                          .isInPhase()
               && currentPhase.get()
                              .equals(phase)
               && !phaseData.hasVotedRound1(self)
               && phaseData.hasQuorumProposals(quorumSize);
    }

    private void broadcastRound1Vote(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        var vote = phaseData.evaluateInitialVote(self, quorumSize);

        log.trace("Node {} broadcasting R1 vote {} for phase {} after collecting quorum proposals", self, vote, phase);
        network.broadcast(vote);
        phaseData.registerRound1Vote(self, vote.stateValue());
    }

    private void logRound1VoteConditionsNotMet(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        log.trace("Node {} conditions not met to vote R1 for phase {}. InPhase: {}, CurrentPhase: {}, HasVotedR1: {}, ProposalCount: {}/{}",
                  self,
                  phase,
                  engineState.get().isInPhase(),
                  currentPhase.get(),
                  phaseData.hasVotedRound1(self),
                  phaseData.proposalCount(),
                  quorumSize);
    }

    /// Handles a round 1 vote from another node.
    private void handleVoteRound1(VoteRound1 vote) {
        log.trace("Node {} received round 1 vote from {} for phase {} with value {}",
                  self,
                  vote.sender(),
                  vote.phase(),
                  vote.stateValue());
        observeClusterPhase(vote.phase());
        var phaseData = getOrCreatePhaseData(vote.phase());

        registerRound1Vote(vote, phaseData);
        tryBroadcastRound2Vote(vote.phase(), phaseData);
    }

    private void registerRound1Vote(VoteRound1 vote, PhaseData<C> phaseData) {
        phaseData.registerRound1Vote(vote.sender(), vote.stateValue());
        metrics.recordVoteRound1(vote.sender(), vote.phase(), vote.stateValue());
    }

    private void tryBroadcastRound2Vote(Phase phase, PhaseData<C> phaseData) {
        var quorumSize = topologyManager.quorumSize();
        var superMajoritySize = topologyManager.superMajoritySize();
        // Check for fast path: if n-f nodes agree in Round 1, skip Round 2
        var superMajorityValue = phaseData.getSuperMajorityRound1Value(superMajoritySize);

        if (canUseFastPath(phase, phaseData, superMajorityValue)) {
            useFastPath(phase, phaseData, superMajorityValue, quorumSize);

            return;
        }
        // Normal path: proceed with Round 2 voting
        if (canVoteRound2(phase, phaseData, quorumSize)) {
            broadcastRound2Vote(phase, phaseData, quorumSize);
        }
    }

    private boolean canUseFastPath(Phase phase, PhaseData<C> phaseData, Option<StateValue> superMajorityValue) {
        return engineState.get()
                          .isInPhase()
               && currentPhase.get()
                              .equals(phase)
               && !phaseData.isDecided()
               && !phaseData.hasVotedRound2(self)
               && superMajorityValue.isPresent();
    }

    private void useFastPath(Phase phase,
                             PhaseData<C> phaseData,
                             Option<StateValue> superMajorityValue,
                             int quorumSize) {
        superMajorityValue.onPresent(agreedValue -> {
            log.debug("Node {} using fast path for phase {} with value {} (super-majority agreement)",
                      self,
                      phase,
                      agreedValue);
            metrics.recordFastPath(self, phase, agreedValue);
            var decision = buildDecision(phaseData, agreedValue, quorumSize);

            network.broadcast(decision);
            processDecision(decision);
        });
    }

    private Decision<C> buildDecision(PhaseData<C> phaseData, StateValue agreedValue, int quorumSize) {
        var batch = agreedValue == StateValue.V1
                    ? phaseData.findAgreedProposal(quorumSize)
                    : StateMachine.Batch.<C> emptyBatch();

        return new Decision<>(self, phaseData.phase(), agreedValue, batch);
    }

    private boolean canVoteRound2(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        return engineState.get()
                          .isInPhase()
               && currentPhase.get()
                              .equals(phase)
               && !phaseData.hasVotedRound2(self)
               && phaseData.hasRound1MajorityVotes(quorumSize);
    }

    private void broadcastRound2Vote(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        var round2Vote = phaseData.evaluateRound2Vote(quorumSize);

        log.trace("Node {} votes in round 2 {}", self, round2Vote);
        network.broadcast(new VoteRound2(self, phase, round2Vote));
        phaseData.registerRound2Vote(self, round2Vote);
    }

    /// Handles a round 2 vote from another node.
    private void handleVoteRound2(VoteRound2 vote) {
        log.trace("Node {} received round 2 vote from {} for phase {} with value {}",
                  self,
                  vote.sender(),
                  vote.phase(),
                  vote.stateValue());
        observeClusterPhase(vote.phase());
        var phaseData = getOrCreatePhaseData(vote.phase());

        registerRound2Vote(vote, phaseData);
        tryMakeDecision(vote.phase(), phaseData);
    }

    private void registerRound2Vote(VoteRound2 vote, PhaseData<C> phaseData) {
        phaseData.registerRound2Vote(vote.sender(), vote.stateValue());
        metrics.recordVoteRound2(vote.sender(), vote.phase(), vote.stateValue());
    }

    private void tryMakeDecision(Phase phase, PhaseData<C> phaseData) {
        var quorumSize = topologyManager.quorumSize();

        if (canMakeDecision(phase, phaseData, quorumSize)) {
            makeAndBroadcastDecision(phaseData, quorumSize);
        }
    }

    private boolean canMakeDecision(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        return engineState.get()
                          .isInPhase()
               && currentPhase.get()
                              .equals(phase)
               && !phaseData.isDecided()
               && phaseData.hasRound2MajorityVotes(quorumSize);
    }

    private void makeAndBroadcastDecision(PhaseData<C> phaseData, int quorumSize) {
        var outcome = phaseData.processRound2Completion(self, topologyManager.fPlusOne(), quorumSize);

        switch (outcome) {
            case Round2Outcome.Decided<C> decided -> {
                network.broadcast(decided.decision());
                processDecision(decided.decision());
            }
            case Round2Outcome.CarryForward<C> carryForward -> {
                if (phaseData.tryMarkDecided()) {
                    advancePhase(phaseData.phase(), carryForward.value(), true);
                }
            }
        }
    }

    private void commitDecision(PhaseData<C> phaseData, Decision<C> decision) {
        if (phaseData.tryMarkDecided()) {
            metrics.recordDecision(self, phaseData.phase(), decision.stateValue(), 0L);
            // Apply commands to state machine ONLY if it was a V1 decision with a non-empty batch
            if (decision.stateValue() == StateValue.V1 && !decision.value().commands().isEmpty()) {
                commitChanges(phaseData, decision);
            }

            advancePhase(phaseData.phase(), decision.stateValue(), false);
        }
    }

    @SuppressWarnings("unchecked")
    private void commitChanges(PhaseData<C> phaseData, Decision<C> decision) {
        log.trace("Node {} applies decision {}", self, decision);
        var results = stateMachine.process(decision.value());
        // Get the batch from pendingBatches BEFORE removing - this has all merged correlationIds.
        // The decision.value() may have partial IDs if the proposer hadn't received all batches yet.
        var localBatch = Option.option(pendingBatches.remove(decision.value().id()));

        metrics.updatePendingBatches(self, pendingBatches.size());
        // Use correlationIds from our local pendingBatches (fully merged) rather than
        // from decision.value() (which may have partial IDs from early proposals)
        var correlationIds = localBatch.map(Batch::correlationIds).or(() -> decision.value()
                                                                                    .correlationIds());

        for (var correlationId : correlationIds) {
            Option.option(correlationMap.remove(correlationId)).onPresent(promise -> promise.succeed(results));
        }
    }

    /// Handles a decision message from another node.
    /// Observers also process decisions to keep their state machine in sync.
    ///
    /// Engine-state guard: Decisions delivered while the engine is `Stopped` / `Syncing` are
    /// buffered for replay after `activate()`. Applying them eagerly causes asymmetric apply
    /// — the Decision mutates state via `commitDecision → advancePhase`, then the imminent
    /// `stateMachine.restoreSnapshot` wipes the mutation and `applyRestoredState` regresses
    /// `currentPhase`. Live KV writes from the cluster's current phase silently disappear
    /// from the rejoiner's local state machine, leaving it stuck (its FSM proposes against a
    /// phantom phase counter and never commits because the cluster is at a different phase).
    ///
    /// Membership-architecture-spec §4.5: while `Paused` (transient quorum loss), Decisions
    /// MUST be applied directly so the engine catches up transparently when quorum returns.
    /// Buffering on Paused would defeat the purpose — state would silently drift and require
    /// a full sync round on resume, which the new design explicitly avoids.
    private void handleDecision(Decision<C> decision) {
        log.trace("Node {} received decision {}", self, decision);
        observeClusterPhase(decision.phase());
        var state = engineState.get();

        if (state instanceof EngineState.Stopped || state instanceof EngineState.Syncing) {
            bufferDecisionForReplay(decision);

            return;
        }

        if (isFarFuturePhase(decision.phase(), currentPhase.get())) {
            log.warn("Node {} received Decision {} but currentPhase={}; gap={} > {} — buffering{}",
                     self,
                     decision.phase(),
                     currentPhase.get(),
                     decision.phase().value() - currentPhase.get().value(),
                     MAX_PHASE_AHEAD,
                     state.isPaused()
                     ? " (paused; deferring resync to ESTABLISHED)"
                     : " and resyncing");
            bufferDecisionForReplay(decision);
            // While Paused, do not flip to Syncing on far-future decisions — quorum is by
            // definition unavailable, so a sync round cannot succeed. The next ESTABLISHED
            // will drive the resume; the buffered decision will be drained then.
            if (!state.isPaused()) {
                triggerResync();
            }

            return;
        }

        commitDecision(getOrCreatePhaseData(decision.phase()), decision);
    }

    private void bufferDecisionForReplay(Decision<C> decision) {
        bufferedDecisions.offer(decision);
        if (bufferedDecisionCount.incrementAndGet() > MAX_BUFFERED_DECISIONS) {
            bufferedDecisions.pollFirst();
            bufferedDecisionCount.decrementAndGet();
        }
    }

    /// Drains the buffered Decisions queue after `activate()` has transitioned the engine
    /// to `Idle`. Decisions are applied in phase-ascending order, filtered to phases at or
    /// above the post-restore `currentPhase` — older Decisions are safely discarded because
    /// they're already captured in the restored snapshot's KV state. Idempotent: applying
    /// the same Decision twice is a no-op (`PhaseData.tryMarkDecided` returns false on the
    /// second call). Runs on the executor thread, so concurrent commits are serialized.
    private void drainBufferedDecisions() {
        if (bufferedDecisions.isEmpty()) {
            return;
        }

        var sorted = bufferedDecisions.stream().sorted(java.util.Comparator.comparing(Decision::phase)).toList();

        bufferedDecisions.clear();
        bufferedDecisionCount.set(0);
        var minPhase = currentPhase.get();
        var applied = 0;
        var skipped = 0;

        for (var decision : sorted) {
            if (decision.phase().compareTo(minPhase) < 0) {
                skipped++;
                continue;
            }

            commitDecision(getOrCreatePhaseData(decision.phase()), decision);
            applied++;
        }

        log.info("Node {} drained buffered decisions: applied={}, skipped={}, post-currentPhase={}",
                 self,
                 applied,
                 skipped,
                 currentPhase.get());
    }

    /// Advances to the next phase after decision or carry-forward.
    /// In observer mode, advances the phase counter but returns to Observing state
    /// without locking values or starting new phases.
    /// @param fromPhase the phase being completed
    /// @param value the state value (V0 or V1)
    /// @param forceLock if true, always lock the value (for carry-forward per spec)
    private void advancePhase(Phase fromPhase, StateValue value, boolean forceLock) {
        var nextPhase = fromPhase.successor();

        this.currentPhase.updateAndGet(p -> p.compareTo(nextPhase) >= 0
                                            ? p
                                            : nextPhase);
        if (observerMode) {
            advancePhaseAsObserver(nextPhase);

            return;
        }
        // Only transition InPhase → Idle. Preserve Syncing/Stopped states so that
        // live Decisions received during synchronization don't cancel the sync task.
        var oldState = engineState.get();

        if (oldState instanceof EngineState.InPhase) {
            engineState.set(new EngineState.Idle());
            exitState(oldState);
            notifyConsensusStateTransition();
        }
        // Lock policy: always lock V1 (critical for liveness), always lock carry-forward (spec),
        // lock V0 only when no pending batches (prevents self-reinforcing deadlock).
        if (forceLock || value == StateValue.V1 || pendingBatches.isEmpty()) {
            lockedValue.set(Option.some(value));
        } else {
            lockedValue.set(Option.none());
        }

        log.trace("Node {} advancing to phase {} with value {} (forceLock={})", self, nextPhase, value, forceLock);
        if (!pendingBatches.isEmpty()) {
            safeExecute(this::startPhase);
        }
    }

    private void advancePhaseAsObserver(Phase nextPhase) {
        var oldState = engineState.getAndSet(new EngineState.Observing());

        exitState(oldState);
        notifyConsensusStateTransition();
        log.trace("Node {} (observer) advancing to phase {}", self, nextPhase);
    }

    /// Gets or creates phase data for a specific phase.
    private PhaseData<C> getOrCreatePhaseData(Phase phase) {
        return phases.computeIfAbsent(phase, PhaseData::new);
    }
}
