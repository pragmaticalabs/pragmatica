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

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineIO.SubmitCommands;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.concurrent.AtomicHolder;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.rabia.Batch.batch;
import static org.pragmatica.consensus.rabia.Batch.emptyBatch;
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
    private final AtomicHolder<QuorumStateNotification> pendingQuorum = AtomicHolder.atomicHolder();

    // Single-thread executor with DiscardPolicy to silently drop tasks after shutdown
    private final ExecutorService executor = new ThreadPoolExecutor(1,
                                                                    1,
                                                                    0L,
                                                                    TimeUnit.MILLISECONDS,
                                                                    new LinkedBlockingQueue<>(),
                                                                    new ThreadPoolExecutor.DiscardPolicy());
    private final ConcurrentNavigableMap<BatchId, Batch<C>> pendingBatches = new ConcurrentSkipListMap<>();
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
        this(topologyManager, network, stateMachine, config, ConsensusMetrics.noop(), false, RabiaPersistence.inMemory(), DEFAULT_PHASE_STALL_CHECK);
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
        this(topologyManager, network, stateMachine, config, metrics, false, RabiaPersistence.inMemory(), DEFAULT_PHASE_STALL_CHECK);
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
        this(topologyManager, network, stateMachine, config, metrics, activationGated, RabiaPersistence.inMemory(), DEFAULT_PHASE_STALL_CHECK);
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
        this(topologyManager, network, stateMachine, config, metrics, activationGated, persistence, DEFAULT_PHASE_STALL_CHECK);
    }

    /// Creates a new Rabia consensus engine with all parameters including persistence and phase stall check.
    ///
    /// @param topologyManager The topology manager for node communication
    /// @param network         The network implementation
    /// @param stateMachine    The state machine to apply commands to
    /// @param config          Configuration for the consensus engine
    /// @param metrics         Metrics collector for observability
    /// @param activationGated Whether consensus activation requires explicit authorization
    /// @param persistence     The persistence implementation for state backup
    /// @param phaseStallCheck Interval for checking phase stalls
    public RabiaEngine(TopologyManager topologyManager,
                       ClusterNetwork network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config,
                       ConsensusMetrics metrics,
                       boolean activationGated,
                       RabiaPersistence<C> persistence,
                       TimeSpan phaseStallCheck) {
        this.self = topologyManager.self()
                                   .id();
        this.topologyManager = topologyManager;
        this.network = network;
        this.stateMachine = stateMachine;
        this.config = config;
        this.metrics = Option.option(metrics)
                             .or(ConsensusMetrics.noop());
        this.activationGated = activationGated;
        this.activationAuthorized = !activationGated;
        this.persistence = persistence;
        this.phaseStallCheck = phaseStallCheck;
        this.cleanupTask = Option.some(SharedScheduler.scheduleAtFixedRate(this::cleanupOldPhases,
                                                                           config.cleanupInterval()));
    }

    @MessageReceiver
    public void quorumState(QuorumStateNotification quorumStateNotification) {
        if (!quorumStateNotification.advanceSequence(quorumSequence)) {
            log.debug("Ignoring stale QuorumStateNotification: {}", quorumStateNotification);
            return;
        }
        log.trace("Node {} received quorum state {}", self, quorumStateNotification);
        switch (quorumStateNotification.state()) {
            case ESTABLISHED -> handleEstablished(quorumStateNotification);
            case DISAPPEARED -> pauseForQuorumLoss();
        }
    }

    private void handleEstablished(QuorumStateNotification notification) {
        if (activationGated && !activationAuthorized) {
            log.info("Node {}: quorum established but activation gated, storing notification", self);
            pendingQuorum.set(notification);
            return;
        }
        // Membership-architecture-spec §4.5 / §7.3: distinguish quorum-resume (Paused → Idle,
        // no state reset) from cold-start (Stopped → Syncing). The Paused branch keeps the
        // engine's existing currentPhase / phases / pendingBatches / lockedValue intact;
        // any Decisions delivered during the pause have already been applied, so we just
        // re-arm phase processing.
        if (engineState.get().isPaused()) {
            resumeFromPause();
        } else {
            clusterConnected();
        }
    }

    /// Authorize a gated engine to start consensus participation.
    /// If a quorum ESTABLISHED notification was received while gated, it is replayed.
    /// When promoting from observer mode, transitions directly to active without re-sync.
    public void authorizeActivation() {
        log.info("Node {}: consensus activation authorized", self);
        if (observerMode) {
            log.info("Node {}: promoting from observer to full consensus", self);
            observerMode = false;
        }
        activationAuthorized = true;
        var pending = pendingQuorum.getAndClear();
        if (pending.isPresent()) {
            log.info("Node {}: replaying stored quorum notification", self);
            clusterConnected();
        } else if (engineState.get().isObserving()) {
            executor.execute(this::promoteObserverToActive);
        }
    }

    private void promoteObserverToActive() {
        var oldState = engineState.getAndSet(new EngineState.Idle());
        exitState(oldState);
        log.info("Node {} promoted from observer to active in phase {}", self, currentPhase.get());
        executor.execute(this::startPhase);
    }

    /// Authorize a gated engine to enter observer mode.
    /// The node will receive and apply committed Decisions but will not propose or vote.
    /// If a quorum ESTABLISHED notification was received while gated, it is replayed.
    public void authorizeObservation() {
        log.info("Node {}: consensus observation authorized (observer mode)", self);
        activationAuthorized = true;
        observerMode = true;
        pendingQuorum.getAndClear()
                     .onPresent(this::replayQuorumForObserver);
    }

    /// Returns true if the engine is currently in observer mode.
    public boolean isObserving() {
        return engineState.get().isObserving();
    }

    // Side-effect callback for `Option.onPresent` — void inherent. Triggered by
    // `pendingQuorum.getAndClear()` consumer chain in `authorizeObservation()`.
    @Contract
    private void replayQuorumForObserver(QuorumStateNotification notification) {
        log.info("Node {}: replaying stored quorum notification for observer mode", self);
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
        executor.execute(this::doClusterConnected);
    }

    private void doClusterConnected() {
        syncResponses.clear();
        var task = SharedScheduler.schedule(this::synchronize,
                                            config.syncRetryInterval()
                                                  .randomize(SCALE));
        var oldState = engineState.getAndSet(new EngineState.Syncing(task));
        exitState(oldState);
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
        executor.execute(this::doPauseForQuorumLoss);
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
        persistence.save(stateMachine,
                         currentPhase.get(),
                         pendingBatches.values())
                   .onSuccessRun(() -> log.info("Node {} paused (quorum lost). State retained, snapshot persisted. currentPhase={}, pendingBatches={}",
                                                self, currentPhase.get(), pendingBatches.size()))
                   .onFailure(cause -> log.error("Node {} failed to persist state on pause: {}", self, cause));
    }

    /// Membership-architecture-spec §4.5 / §7.3 — quorum-return handler when previously paused.
    ///
    /// Transitions `Paused` → `Idle`, preserving `currentPhase`, `phases`, `pendingBatches`,
    /// `lockedValue`. Re-arms phase processing if pending batches remain. No sync round —
    /// Decisions delivered during the pause have already been applied.
    private void resumeFromPause() {
        executor.execute(this::doResumeFromPause);
    }

    private void doResumeFromPause() {
        var current = engineState.get();
        if (!current.isPaused()) {
            return;
        }
        engineState.set(new EngineState.Idle());
        log.info("Node {} resumed from pause (quorum returned). currentPhase={}, pendingBatches={}",
                 self, currentPhase.get(), pendingBatches.size());
        // Drain any far-future Decisions that were buffered while Paused. Decisions with
        // phase < currentPhase are safely discarded; same/higher-phase ones are committed
        // idempotently (PhaseData.tryMarkDecided makes re-application a no-op).
        drainBufferedDecisions();
        if (!pendingBatches.isEmpty()) {
            executor.execute(this::startPhase);
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
        var promise = Promise.<Unit>promise();
        executor.execute(() -> doReconfigure(newConfig, promise));
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
                 self, newConfig.members(), existing.map(ClusterConfig::members).or(List.of()));
        var oldState = engineState.getAndSet(new EngineState.Stopped());
        exitState(oldState);
        persistence.save(stateMachine, Phase.ZERO, List.of())
                   .onFailure(cause -> log.error("Node {} failed to persist empty state on reconfigure: {}", self, cause));
        phases.clear();
        currentPhase.set(Phase.ZERO);
        lockedValue.set(Option.none());
        stateMachine.reset();
        startPromise.set(Promise.promise());
        pendingBatches.clear();
        bufferedDecisions.clear();
        bufferedDecisionCount.set(0);
        correlationMap.forEach((_, p) -> p.fail(ConsensusError.nodeInactive(self)));
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
        lockedValue.set(Option.none());
        stateMachine.reset();
        startPromise.set(Promise.promise());
        pendingBatches.clear();
        bufferedDecisions.clear();
        bufferedDecisionCount.set(0);
        correlationMap.forEach((_, promise) -> promise.fail(ConsensusError.nodeInactive(self)));
        correlationMap.clear();
    }

    public boolean isActive() {
        return engineState.get().isActive();
    }

    /// Returns true when the engine is in the `Paused` state — quorum is currently
    /// unavailable, in-memory protocol state is retained, and new `apply()` submissions
    /// are rejected with [ConsensusError.QuorumPaused]. Mutually exclusive with `isActive`.
    public boolean isPaused() {
        return engineState.get().isPaused();
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
        var pendingAnswer = Promise.<List<R>>promise();
        return submitCommands(commands,
                              batch -> correlationMap.put(batch.correlationIds()
                                                               .getFirst(),
                                                          pendingAnswer)).async()
                             .flatMap(_ -> pendingAnswer);
    }

    @MessageReceiver
    public void handleSubmit(SubmitCommands<C> submitCommands) {
        submitCommands(submitCommands.commands(),
                       _ -> {});
    }

    private Result<Batch<C>> submitCommands(List<C> commands, Consumer<Batch<C>> onBatchPrepared) {
        if (log.isDebugEnabled()) {
            var caller = Thread.currentThread()
                               .getStackTrace();
            var callerInfo = caller.length > 3
                             ? caller[3].toString()
                             : "unknown";
            log.debug("Node {} submitting {} command(s): {} [caller: {}]", self, commands.size(), commands, callerInfo);
        }
        return validateSubmission(commands).map(_ -> prepareBatch(commands))
                                 .onSuccess(batch -> executor.execute(() -> registerBatch(batch, onBatchPrepared)))
                                 .onSuccess(batch -> executor.execute(() -> broadcastBatch(batch)));
    }

    private Result<List<C>> validateSubmission(List<C> commands) {
        if (commands.isEmpty()) {
            return ConsensusError.commandBatchIsEmpty()
                                 .result();
        }
        var state = engineState.get();
        if (state.isPaused()) {
            return ConsensusError.quorumPaused(self)
                                 .result();
        }
        if (!state.isActive()) {
            return ConsensusError.nodeInactive(self)
                                 .result();
        }
        var pending = pendingBatches.size();
        if (pending >= config.maxPendingBatches()) {
            return ConsensusError.backpressureExceeded(pending, config.maxPendingBatches())
                                 .result();
        }
        return Result.success(commands);
    }

    private Batch<C> prepareBatch(List<C> commands) {
        var batch = batch(commands);
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
            executor.execute(this::startPhase);
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
        // Synchronously fail in-flight promises BEFORE executor.shutdown(); otherwise
        // shutdownAndReset() may execute concurrently with the DiscardPolicy and leave
        // callers (e.g. publisher.runApply) waiting on cluster.apply(...) Promises forever.
        correlationMap.forEach((_, p) -> p.fail(ConsensusError.nodeInactive(self)));
        correlationMap.clear();
        shutdownAndReset();
        executor.shutdown();
        promise.succeed(Unit.unit());
    }

    @MessageReceiver
    public void processPropose(Propose<C> propose) {
        executor.execute(() -> handlePropose(propose));
    }

    @MessageReceiver
    public void processVoteRound1(VoteRound1 voteRound1) {
        executor.execute(() -> handleVoteRound1(voteRound1));
    }

    @MessageReceiver
    public void processVoteRound2(VoteRound2 voteRound2) {
        executor.execute(() -> handleVoteRound2(voteRound2));
    }

    @MessageReceiver
    public void processDecision(Decision<C> decision) {
        executor.execute(() -> handleDecision(decision));
    }

    @MessageReceiver
    public void processSyncResponse(SyncResponse<C> syncResponse) {
        executor.execute(() -> handleSyncResponse(syncResponse));
    }

    @SuppressWarnings("unchecked")
    @MessageReceiver
    public void handleNewBatch(NewBatch<?> newBatch) {
        executor.execute(() -> doHandleNewBatch((Batch<C>) newBatch.batch()));
    }

    private void doHandleNewBatch(Batch<C> incoming) {
        // Use compute() for atomic merge to avoid race conditions
        pendingBatches.compute(incoming.id(),
                               (_, existing) -> {
                                   if (existing == null) {
                                       return incoming;
                                   }
                                   if (existing.commands()
                                               .equals(incoming.commands())) {
                                       // Same content - merge correlationIds
        return existing.mergeWith(incoming);
                                   }
                                   // Hash collision (should never happen) - log and keep existing
        log.error("BatchId collision: {} has different content", incoming.id());
                                   return existing;
                               });
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
        Option.option(pendingBatches.firstEntry())
              .onPresent(batchEntry -> broadcastOwnProposal(phase, phaseData, batchEntry.getValue()));
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
        if (!(current instanceof EngineState.Idle)) {
            return;
        }
        Option.option(pendingBatches.firstEntry())
              .onEmpty(this::reExecuteStartPhaseIfBatchPending)
              .onPresent(batchEntry -> startPhaseWithBatch(current, batchEntry.getValue()));
    }

    private void reExecuteStartPhaseIfBatchPending() {
        // Re-check after — a batch may have been added during the window
        if (!pendingBatches.isEmpty()) {
            executor.execute(this::startPhase);
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
        var phaseData = getOrCreatePhaseData(phase);
        phaseData.registerProposal(self, batch);
        network.broadcast(new Propose<>(self, phase, batch));
        broadcastLockedValueIfPresent(phase, phaseData);
    }

    private void broadcastLockedValueIfPresent(Phase phase, PhaseData<C> phaseData) {
        lockedValue.getAndSet(Option.none())
                   .onPresent(locked -> broadcastLockedVote(phase, phaseData, locked));
    }

    private void broadcastLockedVote(Phase phase, PhaseData<C> phaseData, StateValue locked) {
        var vote = new VoteRound1(self, phase, locked);
        log.trace("Node {} immediately voting locked value {} for phase {}", self, locked, phase);
        network.broadcast(vote);
        phaseData.registerRound1Vote(self, locked);
    }

    /// Synchronizes with other nodes to catch up if needed.
    private void synchronize() {
        executor.execute(this::doSynchronize);
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
                                            config.syncRetryInterval()
                                                  .randomize(SCALE));
        var oldState = engineState.getAndSet(new EngineState.Syncing(task));
        exitState(oldState);
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
        syncResponses.clear();
        // Always carry forward the source's lastCommittedPhase + pendingBatches even when
        // the state-machine snapshot is empty. V0 decisions advance phase without touching
        // the state machine, so a syncing node that ignores the empty-snapshot phase ends
        // up perpetually `MAX_PHASE_AHEAD` behind and burns its retry budget on resyncs.
        if (state.snapshot().length == 0) {
            applyRestoredState(state);
            activate();
            return;
        }
        stateMachine.restoreSnapshot(state.snapshot())
                    .onSuccess(_ -> applyRestoredState(state))
                    .onSuccessRun(this::activate)
                    .onFailure(cause -> log.error("Node {} failed to restore state: {}", self, cause));
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
        state.pendingBatches()
             .forEach(batch -> pendingBatches.put(batch.id(),
                                                  batch));
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
        startPromise.get()
                    .succeed(Unit.unit());
        syncResponses.clear();
        metrics.recordSyncAttempt(self, true);
        log.info("Node {} activated in phase {}", self, currentPhase.get());
        // Drain any Decisions that were buffered while the engine was Stopped/Syncing.
        // Must happen AFTER engineState=Idle so that `handleDecision`'s state guard accepts
        // re-applied Decisions, and AFTER the Idle-restore so phase-filter math is correct.
        drainBufferedDecisions();
        executor.execute(this::startPhase);
    }

    private void activateAsObserver() {
        var oldState = engineState.getAndSet(new EngineState.Observing());
        exitState(oldState);
        startPromise.get()
                    .succeed(Unit.unit());
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

    /// Creates a periodic stall detector that re-broadcasts the node's own proposal
    /// when a phase hasn't advanced within the configured interval.
    private ScheduledFuture<?> createStallDetector() {
        return SharedScheduler.scheduleAtFixedRate(
            () -> executor.execute(this::checkPhaseStall),
            phaseStallCheck);
    }

    /// Checks if the current phase is stalled and re-broadcasts this node's own
    /// protocol messages (Propose, VoteRound1, VoteRound2) so peers that missed the
    /// first send due to transient QUIC reconnect can collect them.
    ///
    /// Why: on a 5-way simultaneous restart, peerLinks flap during the QUIC handshake
    /// storm. `QuicClusterNetwork.broadcast` only sends to peers currently in peerLinks,
    /// so a message sent while a peer is in re-dial gets dropped. Proposals used to be
    /// the only message re-broadcast — votes were one-shot and could be lost, leaving
    /// consensus deadlocked until the 3s proposal timeout retries (and the storm repeats).
    /// Votes are idempotent at the receiver (registerRound1Vote/registerRound2Vote are
    /// keyed on (phase, sender)), so re-broadcasting is safe.
    private void checkPhaseStall() {
        if (!(engineState.get() instanceof EngineState.InPhase)) {
            return;
        }
        var phase = currentPhase.get();
        Option.option(phases.get(phase)).onPresent(phaseData -> checkPhaseStallFor(phase, phaseData));
    }

    private void checkPhaseStallFor(Phase phase, PhaseData<C> phaseData) {
        var quorumSize = topologyManager.quorumSize();
        if (!phaseData.hasQuorumProposals(quorumSize) && phaseData.hasProposal(self)) {
            log.debug("Node {} stall detected in phase {}: {}/{} proposals, re-broadcasting own proposal",
                      self, phase, phaseData.proposalCount(), quorumSize);
            Option.option(phaseData.getProposal(self))
                  .onPresent(batch -> network.broadcast(new Propose<>(self, phase, batch)));
        }
        if (phaseData.hasVotedRound1(self) && !phaseData.hasRound1MajorityVotes(quorumSize)) {
            Option.option(phaseData.getRound1Vote(self))
                  .onPresent(value -> rebroadcastRound1Stall(phase, value));
        }
        if (phaseData.hasVotedRound2(self) && !phaseData.hasRound2MajorityVotes(quorumSize)) {
            Option.option(phaseData.getRound2Vote(self))
                  .onPresent(value -> rebroadcastRound2Stall(phase, value));
        }
    }

    private void rebroadcastRound1Stall(Phase phase, StateValue value) {
        log.debug("Node {} stall detected in phase {}: round1 votes short of quorum, re-broadcasting own R1 vote", self, phase);
        network.broadcast(new VoteRound1(self, phase, value));
    }

    private void rebroadcastRound2Stall(Phase phase, StateValue value) {
        log.debug("Node {} stall detected in phase {}: round2 votes short of quorum, re-broadcasting own R2 vote", self, phase);
        network.broadcast(new VoteRound2(self, phase, value));
    }

    /// Handles a synchronization request from another node.
    @MessageReceiver
    public void handleSyncRequest(SyncRequest request) {
        executor.execute(() -> doHandleSyncRequest(request));
    }

    private void doHandleSyncRequest(SyncRequest request) {
        var state = engineState.get();
        if (state.isActive() || state.isObserving()) {
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
                                              persistence.load()
                                                         .or(SavedState.empty()));
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
        executor.execute(this::doCleanupOldPhases);
    }

    private void doCleanupOldPhases() {
        var state = engineState.get();
        if (!state.isActive() && !state.isObserving()) {
            return;
        }
        var current = currentPhase.get();
        phases.keySet()
              .removeIf(phase -> isExpiredPhase(phase, current));
    }

    private boolean isExpiredPhase(Phase phase, Phase current) {
        return phase.compareTo(current) < 0 && current.value() - phase.value() > config.removeOlderThanPhases();
    }

    /// Handles a Propose message from another node.
    /// NOTE: All nodes MUST process proposals regardless of active/dormant state.
    /// Rabia is leaderless — every node participates in every round.
    private void handlePropose(Propose<C> propose) {
        log.trace("Node {} received proposal from {} for phase {}", self, propose.sender(), propose.phase());
        var currentPhaseValue = currentPhase.get();
        if (isPastPhase(propose.phase(), currentPhaseValue)) {
            log.trace("Node {} ignoring proposal for past phase {}", self, propose.phase());
            return;
        }
        if (isFarFuturePhase(propose.phase(), currentPhaseValue)) {
            log.warn("Node {} behind by {} phases (current: {}, received: {}). Triggering resync.",
                     self,
                     propose.phase()
                            .value() - currentPhaseValue.value(),
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
    private void triggerResync() {
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
        if (!(current instanceof EngineState.Idle) || !engineState.compareAndSet(current, new EngineState.InPhase(stallDetector))) {
            stallDetector.cancel(false);
            return;
        }
        Option.option(pendingBatches.firstEntry())
              .onPresent(batchEntry -> broadcastOwnProposal(proposalPhase, phaseData, batchEntry.getValue()));
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
        return engineState.get().isInPhase() && currentPhase.get()
                                              .equals(phase) && !phaseData.hasVotedRound1(self) && phaseData.hasQuorumProposals(quorumSize);
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
        return engineState.get().isInPhase() && currentPhase.get()
                                              .equals(phase) && !phaseData.isDecided() && !phaseData.hasVotedRound2(self) && superMajorityValue.isPresent();
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
                    : Batch.<C>emptyBatch();
        return new Decision<>(self, phaseData.phase(), agreedValue, batch);
    }

    private boolean canVoteRound2(Phase phase, PhaseData<C> phaseData, int quorumSize) {
        return engineState.get().isInPhase() && currentPhase.get()
                                              .equals(phase) && !phaseData.hasVotedRound2(self) && phaseData.hasRound1MajorityVotes(quorumSize);
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
        return engineState.get().isInPhase() && currentPhase.get()
                                              .equals(phase) && !phaseData.isDecided() && phaseData.hasRound2MajorityVotes(quorumSize);
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
            if (decision.stateValue() == StateValue.V1 && !decision.value()
                                                                   .commands()
                                                                   .isEmpty()) {
                commitChanges(phaseData, decision);
            }
            advancePhase(phaseData.phase(), decision.stateValue(), false);
        }
    }

    @SuppressWarnings("unchecked")
    private void commitChanges(PhaseData<C> phaseData, Decision<C> decision) {
        log.trace("Node {} applies decision {}", self, decision);
        var results = stateMachine.process(decision.value()
                                                   .commands());
        // Get the batch from pendingBatches BEFORE removing - this has all merged correlationIds.
        // The decision.value() may have partial IDs if the proposer hadn't received all batches yet.
        var localBatch = Option.option(pendingBatches.remove(decision.value().id()));
        metrics.updatePendingBatches(self, pendingBatches.size());
        // Use correlationIds from our local pendingBatches (fully merged) rather than
        // from decision.value() (which may have partial IDs from early proposals)
        var correlationIds = localBatch.map(Batch::correlationIds)
                                       .or(() -> decision.value().correlationIds());
        for (var correlationId : correlationIds) {
            Option.option(correlationMap.remove(correlationId))
                  .onPresent(promise -> promise.succeed(results));
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
        var state = engineState.get();
        if (state instanceof EngineState.Stopped || state instanceof EngineState.Syncing) {
            bufferDecisionForReplay(decision);
            return;
        }
        if (isFarFuturePhase(decision.phase(), currentPhase.get())) {
            log.warn("Node {} received Decision {} but currentPhase={}; gap={} > {} — buffering{}",
                     self, decision.phase(), currentPhase.get(),
                     decision.phase().value() - currentPhase.get().value(), MAX_PHASE_AHEAD,
                     state.isPaused() ? " (paused; deferring resync to ESTABLISHED)" : " and resyncing");
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
        var sorted = bufferedDecisions.stream()
                                       .sorted(java.util.Comparator.comparing(Decision::phase))
                                       .toList();
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
                 self, applied, skipped, currentPhase.get());
    }

    /// Advances to the next phase after decision or carry-forward.
    /// In observer mode, advances the phase counter but returns to Observing state
    /// without locking values or starting new phases.
    /// @param fromPhase the phase being completed
    /// @param value the state value (V0 or V1)
    /// @param forceLock if true, always lock the value (for carry-forward per spec)
    private void advancePhase(Phase fromPhase, StateValue value, boolean forceLock) {
        var nextPhase = fromPhase.successor();
        this.currentPhase.updateAndGet(p -> p.compareTo(nextPhase) >= 0 ? p : nextPhase);
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
            executor.execute(this::startPhase);
        }
    }

    private void advancePhaseAsObserver(Phase nextPhase) {
        var oldState = engineState.getAndSet(new EngineState.Observing());
        exitState(oldState);
        log.trace("Node {} (observer) advancing to phase {}", self, nextPhase);
    }

    /// Gets or creates phase data for a specific phase.
    private PhaseData<C> getOrCreatePhaseData(Phase phase) {
        return phases.computeIfAbsent(phase, PhaseData::new);
    }
}
