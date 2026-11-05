// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.slice.blueprint.SliceSpec;
import org.pragmatica.aether.controller.ClusterController.ArtifactLoad;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlContext;
import org.pragmatica.aether.controller.CompositeLoadFactor;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.controller.ScalingMetric;
import org.pragmatica.aether.controller.fsm.ScalingDecisionRecord.Guard;
import org.pragmatica.aether.controller.fsm.ScalingDecisionRecord.Outcome;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.SliceRequirements;
import org.pragmatica.aether.slice.kvstore.SliceTargetFence;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.PerSliceMetrics;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.LeaderTransaction;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.utility.react.ReactExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toUnmodifiableMap;


public final class ControlLoopContext {
    private static final Logger log = LoggerFactory.getLogger(ControlLoopContext.class);

    private final Fsm<ControlLoopState, ClusterFsmEvent> fsm;
    private final NodeId self;
    private final ClusterController controller;
    private final ClusterSyncCollector metricsCollector;
    private final Option<InvocationMetricsCollector> invocationMetricsCollector;
    private final ClusterNode<KVCommand<AetherKey>> cluster;
    private final KVStore<AetherKey, AetherValue> kvStore;
    private final TimeSpan interval;
    private final Consumer<ScalingEvent> eventPublisher;
    private final LongSupplier clock;
    private final ControlLoopState.Dormant dormant;
    private final ControlLoopState.Stopped stopped;
    private final AtomicReference<ControllerConfig> configRef;
    private final AtomicReference<Predicate<NodeId>> eligibleProducer = new AtomicReference<>(_ -> false);
    private final AtomicReference<List<NodeId>> topology = new AtomicReference<>(List.of());
    /// The last `SliceTargetValue` observed for each registered slice, keyed by the slice's
    /// artifact at that value's version. This holds the whole durable record and not a projection of
    /// it, because the autoscaler has to write a whole record back: #698, #936 and #937 were all one
    /// consequence of keeping a subset here and rebuilding the rest from factory defaults.
    private final ConcurrentHashMap<Artifact, SliceTargetValue> sliceTargets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SliceNodeKey, SliceState> sliceStates = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Artifact, Long> sliceActivationTimes = new ConcurrentHashMap<>();

    /// D1-react (KVStore dispatcher design §3.5.1 row 39): the executor the slice cooldown request runs on. A node installs
    /// its gated executor once, at assembly; until then (and in a host that installs none) the request runs inline, as
    /// before D1-react.
    private final AtomicReference<ReactExecutor> react = new AtomicReference<>(ReactExecutor.inline("ControlLoop"));

    /// Row 39, U2: the undo of a cooldown is [#resetSliceProtectionState] (Warmup entry, an FSM state change that cannot
    /// move to the executor). It bumps this generation before it clears; a cooldown captured under an older generation
    /// marks nothing. Row 48 (`clearSliceState`) removes only the slice's state, never its cooldown entry, so it is not an
    /// undo of this reaction and bumps nothing.
    private final AtomicLong protectionGeneration = new AtomicLong();

    private final ConcurrentHashMap<String, CommunityMetricsSnapshot> communitySnapshotStore = new ConcurrentHashMap<>();

    // Per-artifact scaling state (#423). Windows are lazily created per artifact and pruned to the
    // registered blueprint set, so state is bounded by artifact count. `perNodeSliceMetrics` holds
    // the latest snapshot each remote node reported per artifact; the leader folds its own
    // InvocationMetricsCollector directly during evaluation.
    private final ConcurrentHashMap<Artifact, CompositeLoadFactor> artifactLoadFactors = new ConcurrentHashMap<>();

    // Per-artifact decision snapshot (#425). One record per artifact per evaluation cycle capturing
    // the terminal outcome, the guard that shaped it, the driving load factor, and the instance
    // arithmetic. Pruned to the registered blueprint set like `artifactLoadFactors`; snapshot-read
    // only via `scalingDecisions()`, so the sole hot-path cost is a map put per evaluation.
    private final ConcurrentHashMap<Artifact, ScalingDecisionRecord> lastDecisions = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Artifact, ConcurrentHashMap<NodeId, PerSliceMetrics>> perNodeSliceMetrics = new ConcurrentHashMap<>();

    private final AtomicLong quorumSequence = new AtomicLong(0);

    public ControlLoopContext(Fsm<ControlLoopState, ClusterFsmEvent> fsm,
                              NodeId self,
                              ClusterController controller,
                              ClusterSyncCollector metricsCollector,
                              Option<InvocationMetricsCollector> invocationMetricsCollector,
                              ClusterNode<KVCommand<AetherKey>> cluster,
                              KVStore<AetherKey, AetherValue> kvStore,
                              TimeSpan interval,
                              ControllerConfig config,
                              Consumer<ScalingEvent> eventPublisher) {
        this(fsm,
             self,
             controller,
             metricsCollector,
             invocationMetricsCollector,
             cluster,
             kvStore,
             interval,
             config,
             eventPublisher,
             System::currentTimeMillis);
    }

    public ControlLoopContext(Fsm<ControlLoopState, ClusterFsmEvent> fsm,
                              NodeId self,
                              ClusterController controller,
                              ClusterSyncCollector metricsCollector,
                              Option<InvocationMetricsCollector> invocationMetricsCollector,
                              ClusterNode<KVCommand<AetherKey>> cluster,
                              KVStore<AetherKey, AetherValue> kvStore,
                              TimeSpan interval,
                              ControllerConfig config,
                              Consumer<ScalingEvent> eventPublisher,
                              LongSupplier clock) {
        this.fsm = fsm;
        this.self = self;
        this.controller = controller;
        this.metricsCollector = metricsCollector;
        this.invocationMetricsCollector = invocationMetricsCollector;
        this.cluster = cluster;
        this.kvStore = kvStore;
        this.interval = interval;
        this.eventPublisher = eventPublisher;
        this.configRef = new AtomicReference<>(config);
        this.clock = clock;
        this.dormant = new ControlLoopState.Dormant(this);
        this.stopped = new ControlLoopState.Stopped(this);
    }

    public long nowMs() {
        return clock.getAsLong();
    }

    public Unit installReactExecutor(ReactExecutor executor) {
        react.set(executor);

        return Unit.unit();
    }

    public Fsm<ControlLoopState, ClusterFsmEvent> fsm() {
        return fsm;
    }

    @Contract
    public void dispatch(ClusterFsmEvent event) {
        fsm.dispatch(event);
    }

    public ControlLoopState.Dormant dormant() {
        return dormant;
    }

    public ControlLoopState.Stopped stopped() {
        return stopped;
    }

    public NodeId self() {
        return self;
    }

    public TimeSpan interval() {
        return interval;
    }

    public ControllerConfig config() {
        return configRef.get();
    }

    @Contract
    public void setConfig(ControllerConfig config) {
        configRef.set(config);
    }

    public List<NodeId> topology() {
        return topology.get();
    }

    @Contract
    public void setTopology(List<NodeId> newTopology) {
        topology.set(newTopology);
    }

    /// Evict a departed node's contribution to the scaling signal (review MAJOR). Without this the
    /// node's last per-artifact metrics stay frozen in `perNodeSliceMetrics` and are re-summed every
    /// cycle, permanently inflating the composite so a scaled-up slice can never scale back down
    /// after a host leaves. Called on the FSM terminal-departure edge (NodeRemoved/NodeDecommissioned).
    @Contract
    public void onNodeDeparted(NodeId departed, List<NodeId> newTopology) {
        topology.set(newTopology);
        removeNodeMetrics(departed);
    }

    public org.pragmatica.lang.Unit removeNodeMetrics(NodeId departed) {
        perNodeSliceMetrics.values().forEach(byNode -> byNode.remove(departed));
        communitySnapshotStore.remove(departed.id());

        return org.pragmatica.lang.Unit.unit();
    }

    public Map<Artifact, ClusterController.Blueprint> blueprintsSnapshot() {
        return sliceTargets.entrySet()
                           .stream()
                           .collect(toUnmodifiableMap(Map.Entry::getKey,
                                                      entry -> blueprintOf(entry.getKey(),
                                                                           entry.getValue())));
    }

    public Option<ClusterController.Blueprint> blueprint(Artifact artifact) {
        return observedTarget(artifact).map(target -> blueprintOf(artifact, target));
    }

    /// The `SliceTargetValue` last observed for this slice — the record a scaling Put must be
    /// derived from rather than rebuilt from a subset of (#698, #936, #937).
    public Option<SliceTargetValue> observedTarget(Artifact artifact) {
        return Option.option(sliceTargets.get(artifact));
    }

    /// The scaling projection handed to `ClusterController.evaluate`. Derived on read rather than
    /// stored alongside the value it comes from: two copies of the same facts are two things that
    /// can drift apart, and #936 was precisely that drift — the in-memory model kept the operator's
    /// `minInstances` while the durable record was overwritten with the new instance count, and the
    /// durable one won on the next feedback.
    ///
    /// The scale-down floor is never below [SliceSpec#MIN_INSTANCES] (#1495, owner ruling: the floor is a
    /// runtime invariant). The autoscaler CLAMPS rather than refuses: a scale-down that would cross the floor
    /// is held at it and recorded as `HELD` by the `MIN_INSTANCES` guard, exactly as a slice's own
    /// `minAvailable` already holds it. Refusing would drop the whole decision, including the part that
    /// stays at or above the floor.
    private static ClusterController.Blueprint blueprintOf(Artifact artifact, SliceTargetValue target) {
        return new ClusterController.Blueprint(artifact,
                                               target.targetInstances(),
                                               Math.max(target.effectiveMinInstances(), SliceSpec.MIN_INSTANCES),
                                               target.owningBlueprint(),
                                               target.maxInstances(),
                                               target.scaleUpThreshold(),
                                               target.scaleDownThreshold());
    }

    /// Records the value observed for `artifact`. Sole writer of the registration map; its only
    /// callers are `ControlLoop.onSliceTargetPut` and `applyScaling`'s own write-through, so what
    /// the autoscaler holds in memory is always a value the KV store either holds or is being asked
    /// to take.
    @Contract
    public void putBlueprint(Artifact artifact, SliceTargetValue target) {
        sliceTargets.put(artifact, target);
    }

    @Contract
    public void removeBlueprint(Artifact artifact) {
        sliceTargets.remove(artifact);
    }

    @Contract
    public void removeBlueprintMatching(SliceTargetKey key) {
        var artifactBase = key.artifactBase();

        Option.from(sliceTargets.keySet().stream().filter(artifactBase::matches).findFirst()).onPresent(sliceTargets::remove);
    }

    public boolean blueprintsEmpty() {
        return sliceTargets.isEmpty();
    }

    @Contract
    public void recordSliceState(SliceNodeKey key, SliceState newState) {
        var previous = sliceStates.put(key, newState);

        if (newState == SliceState.ACTIVE && previous != SliceState.ACTIVE) {
            requestSliceCooldown(key.artifact());
        }

        if (newState.isInProgress()) {
            log.debug("Slice {} in progress state: {}", key, newState);
        }
    }

    @Contract
    public void clearSliceState(SliceNodeKey key) {
        sliceStates.remove(key);
        log.debug("Removed slice state tracking for NodeArtifactKey {}", key);
    }

    public boolean anySliceInProgress() {
        return sliceStates.values()
                          .stream()
                          .anyMatch(SliceState::isInProgress);
    }

    public Option<String> describeInProgressSlice() {
        return Option.from(sliceStates.entrySet()
                                      .stream()
                                      .filter(e -> e.getValue()
                                                    .isInProgress())
                                      .findFirst()
                                      .map(e -> "Slice " + e.getKey()
                                                            .artifact() + " in progress state: " + e.getValue()));
    }

    private Option<Artifact> inProgressArtifact() {
        return Option.from(sliceStates.entrySet()
                                      .stream()
                                      .filter(e -> e.getValue()
                                                    .isInProgress())
                                      .findFirst()
                                      .map(e -> e.getKey()
                                                 .artifact()));
    }

    public Map<Artifact, Long> sliceCooldownsSnapshot() {
        return Map.copyOf(sliceActivationTimes);
    }

    @Contract
    public void resetSliceProtectionState() {
        protectionGeneration.incrementAndGet();
        sliceStates.clear();
        sliceActivationTimes.clear();
    }

    @Contract
    public void cleanupExpiredCooldowns(long now) {
        sliceActivationTimes.entrySet()
                            .removeIf(entry -> (now - entry.getValue()) >= configRef.get()
                                                                                    .sliceCooldown()
                                                                                    .millis());
    }

    public boolean allCooldownsExpired(long now) {
        var cooldownMs = configRef.get().sliceCooldown().millis();

        return sliceActivationTimes.values()
                                   .stream()
                                   .noneMatch(ts -> (now - ts) < cooldownMs);
    }

    public Unit setMetricsProducerEligibility(Predicate<NodeId> isEligible) {
        eligibleProducer.set(isEligible);

        return Unit.unit();
    }

    @Contract
    public void storeCommunitySnapshot(CommunityMetricsSnapshot snapshot) {
        if (snapshot.memberCount() != 1 || !isFreshSource(snapshot)) {
            return;
        }

        communitySnapshotStore.compute(snapshot.governorId().id(),
                                       (_, previous) -> acceptSourceSnapshot(snapshot, Option.option(previous)));
    }

    private CommunityMetricsSnapshot acceptSourceSnapshot(CommunityMetricsSnapshot incoming,
                                                          Option<CommunityMetricsSnapshot> previous) {
        return previous.filter(prior -> !isNewerSource(incoming, prior))
                       .or(() -> recordSourceSnapshot(incoming));
    }

    /// `incarnation` is the source's boot token (equality only). The first token pins the source; a
    /// different token is another process claiming the NodeId (terminal removal) and never supersedes.
    private boolean isNewerSource(CommunityMetricsSnapshot incoming, CommunityMetricsSnapshot previous) {
        return incoming.incarnation() == previous.incarnation() && incoming.sequence() > previous.sequence();
    }

    private CommunityMetricsSnapshot recordSourceSnapshot(CommunityMetricsSnapshot incoming) {
        perNodeSliceMetrics.values().forEach(byNode -> byNode.remove(incoming.governorId()));
        ingestSliceMetrics(incoming);

        return incoming;
    }

    private boolean isFreshSource(CommunityMetricsSnapshot snapshot) {
        return eligibleProducer.get()
                               .test(snapshot.governorId()) && MetricObservation.isTimestampFresh(snapshot.timestampMs(),
                                                                                                  nowMs());
    }

    boolean hasMetricCoverage(Artifact artifact) {
        return sliceStates.entrySet()
                          .stream()
                          .filter(entry -> entry.getKey()
                                                .artifact()
                                                .equals(artifact) && entry.getValue() == SliceState.ACTIVE)
                          .allMatch(entry -> hasFreshSource(entry.getKey().nodeId()));
    }

    private boolean hasFreshSource(NodeId producer) {
        return producer.equals(self)
               ? invocationMetricsCollector.isPresent()
               : Option.option(communitySnapshotStore.get(producer.id()))
                       .filter(this::isFreshSource)
                       .isPresent();
    }

    private void ingestSliceMetrics(CommunityMetricsSnapshot snapshot) {
        var source = snapshot.governorId();

        if (source.equals(self)) {
            return;
        }

        snapshot.sliceMetrics().forEach(metrics -> recordRemoteSliceMetrics(source, metrics));
    }

    private void recordRemoteSliceMetrics(NodeId source, PerSliceMetrics metrics) {
        perNodeSliceMetrics.computeIfAbsent(metrics.artifact(), _ -> new ConcurrentHashMap<>()).put(source, metrics);
    }

    public Map<String, CommunityMetricsSnapshot> communitySnapshots() {
        return communitySnapshotStore.entrySet()
                                     .stream()
                                     .filter(entry -> isFreshSource(entry.getValue()))
                                     .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                                            Map.Entry::getValue));
    }

    public AtomicLong quorumSequence() {
        return quorumSequence;
    }

    @Contract
    public void runEvaluationCycle() {
        if (sliceTargets.isEmpty()) {
            log.trace("No blueprints registered, skipping evaluation");

            return;
        }

        cleanupExpiredCooldowns(nowMs());
        var sliceInProgress = describeInProgressSlice();

        if (sliceInProgress.isPresent()) {
            sliceInProgress.onPresent(reason -> log.debug("Auto-scaling paused: {}", reason));
            inProgressArtifact().onPresent(this::recordSliceInProgress);

            return;
        }

        var loads = computeArtifactLoads();
        var context = new ControlContext(loads, blueprintsSnapshot(), topology.get());

        controller.evaluate(context)
                  .onSuccess(this::applyDecisions)
                  .onFailure(cause -> log.error("Failed to evaluate controller: {}",
                                                cause.message()));
    }

    @Contract
    public void publishScalingEvent(BlueprintChange change,
                                    Artifact artifact,
                                    int previousInstances,
                                    int newInstances) {
        var event = switch (change) {
            case BlueprintChange.ScaleUp _ -> ScalingEvent.ScaledUp.scaledUp(artifact, previousInstances, newInstances);
            case BlueprintChange.ScaleDown _ -> ScalingEvent.ScaledDown.scaledDown(artifact,
                                                                                   previousInstances,
                                                                                   newInstances);
        };

        eventPublisher.accept(event);
    }

    /// Row 39 absorb: the slice state stays in [#recordSliceState]; the timestamp and the protection generation are captured
    /// here. Reaction (behind the engine-alive gate): the whole cooldown mark, map put and FSM dispatch together, because the
    /// FSM transition arms the evaluation and expiry timers and cannot be separated from the dispatch.
    @Contract
    private void requestSliceCooldown(Artifact artifact) {
        var timestamp = nowMs();
        var generation = protectionGeneration.get();

        react.get().react("ControlLoop slice cooldown", () -> markSliceCooldown(artifact, timestamp, generation));
    }

    /// U2: the put runs inside the slice's `compute` only if no protection reset ran since absorb. The reset bumps the generation
    /// before it clears, so whichever runs first the later one sees the other: no cooldown survives a reset it predates. The
    /// dispatch runs after the `compute` (an FSM transition must not run under a map bin lock) and only when the put landed; a
    /// reset between the two leaves a `CooldownRequested` that Warmup and Dormant ignore.
    @Contract
    private void markSliceCooldown(Artifact artifact, long timestamp, long generation) {
        var marked = sliceActivationTimes.compute(artifact,
                                                  (_, current) -> cooldownUnderGuard(timestamp, generation, current));

        if (Option.option(marked).filter(value -> value == timestamp).isPresent()) {
            log.debug("Slice {} reached ACTIVE, cooldown started", artifact);
            fsm.dispatch(new ControlLoopEvents.CooldownRequested(artifact, timestamp));
        }
    }

    @NullReturn
    private Long cooldownUnderGuard(long timestamp, long generation, Long current) {
        if (protectionGeneration.get() == generation) {
            return timestamp;
        }

        return current;
    }

    private Map<Artifact, ArtifactLoad> computeArtifactLoads() {
        artifactLoadFactors.keySet().retainAll(sliceTargets.keySet());
        perNodeSliceMetrics.keySet().retainAll(sliceTargets.keySet());
        lastDecisions.keySet().retainAll(sliceTargets.keySet());
        var loads = new HashMap<Artifact, ArtifactLoad>();

        sliceTargets.keySet().forEach(artifact -> loads.put(artifact, computeArtifactLoad(artifact)));

        return loads;
    }

    private ArtifactLoad computeArtifactLoad(Artifact artifact) {
        var sample = sampleArtifactMetrics(artifact);
        var loadFactor = artifactLoadFactors.computeIfAbsent(artifact, _ -> newLoadFactor());
        var covered = hasMetricCoverage(artifact);

        if (covered) {
            sample.forEach(loadFactor::recordSample);
        }

        var result = loadFactor.computeWithCurrentValues(sample);

        recordBaseline(artifact, result.compositeScore(), result.canScale(), loadFactor.isErrorRateHigh());
        if (!covered) {
            var instances = artifactInstances(artifact);

            recordDecision(artifact,
                           Outcome.HELD,
                           Guard.METRICS_INCOMPLETE,
                           result.compositeScore(),
                           instances,
                           instances,
                           instances);
        }

        return ArtifactLoad.artifactLoad(result.compositeScore(),
                                         result.canScale() && covered,
                                         loadFactor.isErrorRateHigh(),
                                         result.components());
    }

    private CompositeLoadFactor newLoadFactor() {
        return CompositeLoadFactor.compositeLoadFactor(configRef.get().scalingConfig());
    }

    private Map<ScalingMetric, Double> sampleArtifactMetrics(Artifact artifact) {
        var sources = collectSliceSources(artifact);
        var metrics = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);

        metrics.put(ScalingMetric.CPU, 0.0);
        metrics.put(ScalingMetric.ACTIVE_INVOCATIONS,
                    (double) sources.stream().mapToLong(PerSliceMetrics::activeInvocations).sum());
        metrics.put(ScalingMetric.P95_LATENCY,
                    sources.stream().mapToDouble(PerSliceMetrics::p95LatencyMs).max().orElse(0.0));
        metrics.put(ScalingMetric.ERROR_RATE,
                    sources.stream().mapToDouble(PerSliceMetrics::errorRate).max().orElse(0.0));

        return metrics;
    }

    private List<PerSliceMetrics> collectSliceSources(Artifact artifact) {
        var sources = new ArrayList<>(ownSliceMetrics(artifact));

        Option.option(perNodeSliceMetrics.get(artifact)).onPresent(remote -> remote.entrySet()
                                                                                   .stream()
                                                                                   .filter(entry -> hasFreshSource(entry.getKey()))
                                                                                   .map(Map.Entry::getValue)
                                                                                   .forEach(sources::add));

        return sources;
    }

    private List<PerSliceMetrics> ownSliceMetrics(Artifact artifact) {
        return invocationMetricsCollector.map(InvocationMetricsCollector::collectPerSliceMetrics)
                                         .or(List.<PerSliceMetrics> of())
                                         .stream()
                                         .filter(metrics -> metrics.artifact()
                                                                   .equals(artifact))
                                         .toList();
    }

    private void applyDecisions(ClusterController.ControlDecisions decisions) {
        if (decisions.changes().isEmpty()) {
            log.trace("No scaling decisions");

            return;
        }

        decisions.changes().forEach(change -> prepareGuardedChange(change).filter(this::changesRequirement)
                                                                          .onPresent(this::submitScaling));
    }

    /// Whether the decision differs from the committed `REACTIVE` requirement: while an operator floor holds the target above the
    /// decision, every evaluation re-decides the same count, and re-writing an equal requirement would commit a write per cycle.
    private boolean changesRequirement(Scaling scaling) {
        return kvStore.getTyped(SliceRequirementKey.sliceRequirementKey(scaling.artifact().base(),
                                                                        RequirementKind.REACTIVE),
                                SliceRequirementValue.class)
                      .map(committed -> committed.instances() != scaling.newInstances())
                      .or(true);
    }

    /// One scaling decision's write: the instance count chosen for `artifact`, derived from `observed`, and why.
    private record Scaling(Artifact artifact, SliceTargetValue observed, int newInstances, String reason) {}

    /// rc6 §3.1: the autoscaler writes its `REACTIVE` requirement, never the target; the leader's requirement combiner derives the
    /// target from it and from every other requirement source, so an operator's floor and the autoscaler compose instead of racing.
    ///
    /// The write is fenced as the #2662 target write was: ONE leader transaction putting the requirement and witnessing the target at
    /// the observed value the decision was made from, so a write racing a blueprint delete (the target removed) or a republish or
    /// another source's combination (the target moved) is refused. The instance count is derived from the observed target (its count,
    /// cap and floor), so it is never carried onto a moved target: a refused write is resubmitted only while the committed target still
    /// equals the observed one; otherwise it is dropped ([SliceTargetFence.SliceTargetFenceError.TargetMoved]) and the next evaluation
    /// decides from the committed target. A target that is gone is not written for.
    @Contract
    private void submitScaling(Scaling scaling) {
        SliceTargetFence.submit("autoscale " + scaling.artifact(),
                                cluster::apply,
                                () -> scalingTransaction(scaling,
                                                         scaling.observed()),
                                () -> committedScalingTransaction(scaling))
                        .onFailure(cause -> scalingNotWritten(scaling.artifact(),
                                                              cause));
    }

    private Result<LeaderTransaction<AetherKey, AetherValue>> committedScalingTransaction(Scaling scaling) {
        return SliceTargetFence.presentTarget(kvStore,
                                              SliceTargetKey.sliceTargetKey(scaling.artifact().base()))
                               .flatMap(read -> sameDecisionInput(scaling, read));
    }

    private Result<LeaderTransaction<AetherKey, AetherValue>> sameDecisionInput(Scaling scaling,
                                                                                SliceTargetValue read) {
        return read.equals(scaling.observed())
               ? scalingTransaction(scaling, read)
               : new SliceTargetFence.SliceTargetFenceError.TargetMoved(scaling.artifact().base()).result();
    }

    private Result<LeaderTransaction<AetherKey, AetherValue>> scalingTransaction(Scaling scaling, SliceTargetValue read) {
        var key = SliceRequirementKey.sliceRequirementKey(scaling.artifact().base(),
                                                          RequirementKind.REACTIVE);
        var requirement = SliceRequirementValue.sliceRequirementValue(scaling.newInstances(),
                                                                      scaling.reason(),
                                                                      Option.none(),
                                                                      kvStore.committedRevision());

        return SliceRequirements.requirementTransaction(kvStore, read, key, Option.some(requirement));
    }

    /// FER (degrade forward): a scaling decision that was not written is reported, and the registration is re-read from the committed
    /// target, so the autoscaler never holds a count that did not commit. Guarantee earned: the next evaluation decides from committed
    /// state. Mechanism: the registration is replaced by the committed target of this version, or dropped when none is committed.
    @Contract
    private void scalingNotWritten(Artifact artifact, Cause cause) {
        logNotWritten(artifact, cause);
        SliceTargetFence.committedTarget(kvStore,
                                         SliceTargetKey.sliceTargetKey(artifact.base()))
                        .filter(committed -> committed.currentVersion()
                                                      .equals(artifact.version()))
                        .onPresent(committed -> putBlueprint(artifact, committed))
                        .onEmpty(() -> removeBlueprint(artifact));
    }

    private void logNotWritten(Artifact artifact, Cause cause) {
        if (cause instanceof SliceTargetFence.SliceTargetFenceError.TargetMoved) {
            log.info("Scaling decision for {} dropped: {}", artifact, cause.message());
        } else {
            log.warn("Scaling decision for {} not written: {}", artifact, cause.message());
        }
    }

    private Option<Scaling> prepareGuardedChange(BlueprintChange change) {
        var artifact = change.artifact();

        if (isArtifactInCooldown(artifact)) {
            log.debug("Scaling decision for {} blocked: cooldown active", artifact);
            recordBlocked(artifact);

            return Option.none();
        }

        return prepareChange(change);
    }

    private boolean isArtifactInCooldown(Artifact artifact) {
        return Option.option(sliceActivationTimes.get(artifact))
                     .map(activation -> (nowMs() - activation) < configRef.get()
                                                                          .sliceCooldown()
                                                                          .millis())
                     .or(false);
    }

    private Option<Scaling> prepareChange(BlueprintChange change) {
        var artifact = change.artifact();

        return observedTarget(artifact).flatMap(observed -> prepareChangeToTarget(change, artifact, observed));
    }

    private Option<Scaling> prepareChangeToTarget(BlueprintChange change,
                                                  Artifact artifact,
                                                  SliceTargetValue observed) {
        var currentBlueprint = blueprintOf(artifact, observed);
        var requestedInstances = computeRequestedInstances(change, currentBlueprint);
        var clusterSize = effectiveClusterSize();
        var capResult = applyCap(requestedInstances, currentBlueprint.maxInstances(), clusterSize);
        var newInstances = capResult.value();
        var capped = capResult.reason().isPresent();

        capResult.reason()
                 .onPresent(reason -> emitCapped(artifact, currentBlueprint, requestedInstances, newInstances, reason));
        if (newInstances == currentBlueprint.instances()) {
            recordFlooredScaleDown(change, artifact, currentBlueprint);

            return Option.none();
        }

        return applyScaling(change, artifact, observed, requestedInstances, newInstances, capped);
    }

    /// #936: a scale-down the minimum-instances floor reduced to a no-op is a different state from
    /// an evaluation with nothing to do, and the two were indistinguishable — no command, no event,
    /// no log, and a decision snapshot still carrying the cycle's `HELD`/`NONE` baseline. An
    /// operator saw an autoscaler that appeared idle. Recording the floor as the guard puts the
    /// declined scale-down on the #425 management surface, where the cap already reports itself.
    private void recordFlooredScaleDown(BlueprintChange change,
                                        Artifact artifact,
                                        ClusterController.Blueprint currentBlueprint) {
        flooredRequest(change, currentBlueprint).onPresent(requested -> recordFloored(artifact,
                                                                                      currentBlueprint,
                                                                                      requested));
    }

    /// The count a `ScaleDown` actually asked for, present only when the floor is what stopped it.
    /// A change asking for the count the slice already runs was never going to move anything, and
    /// reporting that as a floor would be a diagnostic firing when nothing happened.
    ///
    /// The pre-floor count is what is reported, not `computeRequestedInstances`'s already-floored
    /// result — the latter is the current count again and says nothing about what was wanted.
    private static Option<Integer> flooredRequest(BlueprintChange change,
                                                  ClusterController.Blueprint currentBlueprint) {
        return switch (change) {
            case BlueprintChange.ScaleDown(_, int reduceBy) when(currentBlueprint.instances() - reduceBy) < currentBlueprint.minInstances() -> Option.some(currentBlueprint.instances() - reduceBy);
            case BlueprintChange.ScaleDown _ -> Option.none();
            case BlueprintChange.ScaleUp _ -> Option.none();
        };
    }

    private void recordFloored(Artifact artifact,
                               ClusterController.Blueprint currentBlueprint,
                               int requestedInstances) {
        log.info("Scaling {} held at {} instances: minimum-instances floor of {} blocked a scale-down to {}",
                 artifact,
                 currentBlueprint.instances(),
                 currentBlueprint.minInstances(),
                 requestedInstances);
        recordDecision(artifact,
                       Outcome.HELD,
                       Guard.MIN_INSTANCES,
                       priorLoadFactor(artifact),
                       currentBlueprint.instances(),
                       requestedInstances,
                       currentBlueprint.instances());
    }

    /// Decides the autoscaler's `REACTIVE` requirement from the value last observed for this slice (rc6 §3.1). The autoscaler
    /// decides one number and writes one number, and since rc6 that number is its requirement, not the target: the target is the
    /// combiner's, and every component of it the autoscaler has no opinion about (placement, floor, owner, overrides) is untouched
    /// by construction (#698, #936, #937).
    ///
    /// The in-memory registration is NOT advanced to the decided count. It is fed only by the committed target
    /// (`ControlLoop.onSliceTargetPut`), because the combined target can differ from the reactive requirement (an operator floor holds
    /// it higher), and a registration ahead of the committed target would make the next decision derive from a count that never
    /// committed. A write that does not commit re-reads the registration from the committed target ([#scalingNotWritten]).
    ///
    /// No store read happens here. The observed value is carried in the registration map rather than re-read, deliberately: a
    /// re-read would add a read-then-write on the autoscaler's hot path, the shape flagged by #906 and #805. An artifact with no
    /// observed value has no registration, so `prepareChange` returns `none()` and this method is never reached for it.
    private Option<Scaling> applyScaling(BlueprintChange change,
                                         Artifact artifact,
                                         SliceTargetValue observed,
                                         int requestedInstances,
                                         int newInstances,
                                         boolean capped) {
        var currentBlueprint = blueprintOf(artifact, observed);

        log.info("Applying scaling decision: {} from {} to {} instances (REACTIVE requirement)",
                 artifact,
                 currentBlueprint.instances(),
                 newInstances);
        publishScalingEvent(change, artifact, currentBlueprint.instances(), newInstances);
        recordScaled(change, artifact, currentBlueprint, requestedInstances, newInstances, capped);

        return Option.some(new Scaling(artifact, observed, newInstances, reactiveReason(change, currentBlueprint.instances())));
    }

    private static String reactiveReason(BlueprintChange change, int fromInstances) {
        return switch (change) {
            case BlueprintChange.ScaleUp _ -> "autoscaler scale-up from " + fromInstances;
            case BlueprintChange.ScaleDown _ -> "autoscaler scale-down from " + fromInstances;
        };
    }

    private static int computeRequestedInstances(BlueprintChange change, ClusterController.Blueprint currentBlueprint) {
        return switch (change) {
            case BlueprintChange.ScaleUp(_, int additional) -> currentBlueprint.instances() + additional;
            case BlueprintChange.ScaleDown(_, int reduceBy) -> Math.max(currentBlueprint.minInstances(),
                                                                        currentBlueprint.instances() - reduceBy);
        };
    }

    /// The cluster size the autoscaler caps a scale-up at, and the combiner's placeable capacity (rc6 §3.1): the committed core
    /// topology, never below 1.
    public int effectiveClusterSize() {
        var size = topology.get().size();

        return size == 0
               ? 1
               : size;
    }

    /// Cap the requested instance count to the tighter of the blueprint's `maxInstances` bound and
    /// the cluster size. The result value never exceeds `requestedInstances` (the arithmetic matches
    /// #424). `reason` is present only when a real reduction happened: `CLUSTER_CAP` when the cluster
    /// size is the strictly-tighter bound, otherwise `MAX_INSTANCES` (a tie is attributed to the
    /// explicitly-configured max).
    private static CapResult applyCap(int requestedInstances, Option<Integer> maxInstances, int clusterSize) {
        var maxBound = maxInstances.map(max -> Math.min(requestedInstances, max)).or(requestedInstances);
        var value = Math.min(maxBound, clusterSize);

        if (value >= requestedInstances) {
            return new CapResult(requestedInstances, Option.none());
        }

        var reason = clusterSize < maxBound
                     ? CapReason.CLUSTER_CAP
                     : CapReason.MAX_INSTANCES;

        return new CapResult(value, Option.some(reason));
    }

    private record CapResult(int value, Option<CapReason> reason) {}

    private enum CapReason {
        MAX_INSTANCES("max-instances", Guard.MAX_INSTANCES),
        CLUSTER_CAP("cluster-cap", Guard.CLUSTER_CAP);
        private final String label;
        private final Guard guard;
        CapReason(String label, Guard guard) {
            this.label = label;
            this.guard = guard;
        }
        String label() {
            return label;
        }
        Guard guard() {
            return guard;
        }
    }

    private void emitCapped(Artifact artifact,
                            ClusterController.Blueprint currentBlueprint,
                            int requestedInstances,
                            int cappedInstances,
                            CapReason reason) {
        log.debug("Scaling {} capped ({}) from requested {} to {}",
                  artifact,
                  reason.label(),
                  requestedInstances,
                  cappedInstances);
        eventPublisher.accept(ScalingEvent.ScaleCapped.scaleCapped(artifact,
                                                                   requestedInstances,
                                                                   cappedInstances,
                                                                   reason.label()));
        recordDecision(artifact,
                       Outcome.CAPPED,
                       reason.guard(),
                       priorLoadFactor(artifact),
                       currentBlueprint.instances(),
                       requestedInstances,
                       cappedInstances);
    }

    private void recordScaled(BlueprintChange change,
                              Artifact artifact,
                              ClusterController.Blueprint currentBlueprint,
                              int requestedInstances,
                              int newInstances,
                              boolean capped) {
        if (capped) {
            return;
        }

        recordDecision(artifact,
                       scaledOutcome(change),
                       Guard.NONE,
                       priorLoadFactor(artifact),
                       currentBlueprint.instances(),
                       requestedInstances,
                       newInstances);
    }

    private static Outcome scaledOutcome(BlueprintChange change) {
        return switch (change) {
            case BlueprintChange.ScaleUp _ -> Outcome.SCALED_UP;
            case BlueprintChange.ScaleDown _ -> Outcome.SCALED_DOWN;
        };
    }

    private void recordBaseline(Artifact artifact, double compositeScore, boolean canScale, boolean errorRateHigh) {
        var instances = artifactInstances(artifact);

        recordDecision(artifact,
                       Outcome.HELD,
                       baselineGuard(canScale, errorRateHigh),
                       compositeScore,
                       instances,
                       instances,
                       instances);
    }

    private static Guard baselineGuard(boolean canScale, boolean errorRateHigh) {
        if (!canScale) {
            return Guard.WINDOW_NOT_FULL;
        }

        return errorRateHigh
               ? Guard.ERROR_BLOCK
               : Guard.NONE;
    }

    private void recordSliceInProgress(Artifact artifact) {
        var instances = artifactInstances(artifact);

        recordDecision(artifact, Outcome.HELD, Guard.SLICE_IN_PROGRESS, 0.0, instances, instances, instances);
    }

    private void recordBlocked(Artifact artifact) {
        var instances = artifactInstances(artifact);

        recordDecision(artifact,
                       Outcome.BLOCKED,
                       Guard.COOLDOWN,
                       priorLoadFactor(artifact),
                       instances,
                       instances,
                       instances);
    }

    private void recordDecision(Artifact artifact,
                                Outcome outcome,
                                Guard guard,
                                double loadFactor,
                                int currentInstances,
                                int requestedInstances,
                                int cappedInstances) {
        lastDecisions.put(artifact,
                          ScalingDecisionRecord.scalingDecisionRecord(artifact,
                                                                      outcome,
                                                                      guard,
                                                                      loadFactor,
                                                                      currentInstances,
                                                                      requestedInstances,
                                                                      cappedInstances,
                                                                      nowMs()));
    }

    private int artifactInstances(Artifact artifact) {
        return blueprint(artifact).map(ClusterController.Blueprint::instances)
                        .or(0);
    }

    private double priorLoadFactor(Artifact artifact) {
        return Option.option(lastDecisions.get(artifact))
                     .map(ScalingDecisionRecord::loadFactor)
                     .or(0.0);
    }

    /// Snapshot of the latest per-artifact decision, bounded by the registered blueprint set.
    public Map<Artifact, ScalingDecisionRecord> scalingDecisions() {
        return Map.copyOf(lastDecisions);
    }

    /// Cluster-average CPU usage, surfaced alongside the decision snapshot as honest node-capacity
    /// context (never acted on by the autoscaler — the per-artifact composite load is the sole
    /// scaling driver). Averages the `cpu.usage` metric across every node the metrics collector
    /// currently sees; `0.0` when no node reports CPU.
    public double clusterCpuContext() {
        return metricsCollector.allMetrics()
                               .values()
                               .stream()
                               .filter(metrics -> metrics.containsKey(ClusterSyncCollector.CPU_USAGE))
                               .mapToDouble(metrics -> metrics.get(ClusterSyncCollector.CPU_USAGE))
                               .average()
                               .orElse(0.0);
    }
}
