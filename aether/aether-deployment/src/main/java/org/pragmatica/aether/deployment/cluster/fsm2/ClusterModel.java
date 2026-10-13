// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodePlacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaMigrationLockValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// The Active model of the cluster FSM (spec 3.3): every fact index, every derived index and every piece of
/// leader-local in-memory state.
///
/// **Ownership.** A model is owned by exactly one [ClusterFsm#step] call until that call returns it. `step` takes a
/// shallow [#copy] of the model it is given, mutates the copy's fields, and returns the copy. Every field holds an
/// immutable value ([PMap], [PSet], records, [Option]), so the model that was passed in is never touched and a model
/// that was returned is a stable value: a test may keep it, compare it, or step it twice.
///
/// The fields are package-private and written only by the handlers of this package; the public readers are for tests
/// and for the interpreter.
public final class ClusterModel {
    /// Counts the iterations of this model's maps (NT-78). `copy` gives the copy a meter of its own and rebinds its maps to it, so a
    /// step counts on the copy and never touches the meter the input model reads.
    ScanMeter scans = ScanMeter.scanMeter();
    // -- identity and clocks (R-3.1.3, R-3.1.4) --------------------------------------------------------------------
    long incarnation;
    long incarnationStart;
    Option<LeaderValue> leader = Option.none();
    boolean reconcileQueued;
    boolean flushQueued;
    boolean auditDue;
    boolean joinWarningsDue;
    long txSeq;
    long genCounter;
    long tickCount;
    Seams seams = Seams.UNWIRED;
    PSet<TimerId> timers = PSet.empty(scans);
    // -- blueprint-side fact indexes (CI-2) ------------------------------------------------------------------------
    PMap<BlueprintId, AppBlueprintValue> blueprints = PMap.empty(scans);
    PMap<Artifact, PSet<BlueprintId>> blueprintsByArtifact = PMap.empty(scans);
    PMap<ArtifactBase, BlueprintId> ownerByBase = PMap.empty(scans);
    PMap<ArtifactBase, SliceTargetValue> targets = PMap.empty(scans);
    PMap<ArtifactBase, VersionRoutingValue> routings = PMap.empty(scans);
    PMap<ArtifactBase, SliceTargetValue> pendingTargetPuts = PMap.empty(scans);
    PMap<Artifact, AwaitedTarget> awaitedTargets = PMap.empty(scans);
    PMap<BlueprintId, Long> redriveAt = PMap.empty(scans);
    PMap<Artifact, PSet<Artifact>> dependencies = PMap.empty(scans);
    PMap<Artifact, PSet<Artifact>> dependents = PMap.empty(scans);
    PMap<String, SchemaVersionValue> schemas = PMap.empty(scans);
    PMap<String, SchemaMigrationLockValue> schemaLocks = PMap.empty(scans);
    PMap<String, PSet<BlueprintId>> blockingSchemasByOwner = PMap.empty(scans);
    PMap<SlicePair, String> holdSignatures = PMap.empty(scans);
    PMap<BlueprintId, DeploymentApplyValue> applies = PMap.empty(scans);
    PMap<BlueprintId, DeploymentOutcomeValue> outcomes = PMap.empty(scans);
    PMap<Artifact, ArtifactJudgmentValue> judgments = PMap.empty(scans);
    // -- slice-side fact and derived indexes (CI-3) ----------------------------------------------------------------
    PMap<SlicePair, SliceDesiredValue> desired = PMap.empty(scans);
    PMap<NodeId, PSet<Artifact>> desiredByNode = PMap.empty(scans);
    PMap<Artifact, PSet<NodeId>> desiredByArtifact = PMap.empty(scans);
    PMap<ArtifactBase, PSet<Artifact>> versionsByBase = PMap.empty(scans);
    PMap<SlicePair, SliceObservedValue> observed = PMap.empty(scans);
    PMap<NodeId, PSet<Artifact>> observedByNode = PMap.empty(scans);
    PMap<Artifact, PSet<NodeId>> observedByArtifact = PMap.empty(scans);
    PSet<SlicePair> stray = PSet.empty(scans);
    PSet<SlicePair> divergent = PSet.empty(scans);
    PSet<SlicePair> failedPairs = PSet.empty(scans);
    PSet<SlicePair> unloadedDesired = PSet.empty(scans);
    PMap<SlicePair, GoalIntent> goalIntents = PMap.empty(scans);
    PMap<Artifact, ArtifactIntent> artifactIntents = PMap.empty(scans);
    PMap<SlicePair, PendingGoal> pendingGoals = PMap.empty(scans);
    PMap<Artifact, PSet<NodeId>> pendingByArtifact = PMap.empty(scans);
    PMap<NodeId, PSet<Artifact>> pendingByNode = PMap.empty(scans);
    PMap<SlicePair, Long> seenAt = PMap.empty(scans);
    PMap<SlicePair, Long> transitionedAt = PMap.empty(scans);
    PMap<SlicePair, ReissueMark> reissues = PMap.empty(scans);
    PMap<SlicePair, Long> retryAt = PMap.empty(scans);
    PMap<Artifact, Integer> imbalancedTicks = PMap.empty(scans);
    PSet<NodeId> drainLoops = PSet.empty(scans);
    // -- membership-side state (CI-4..CI-9, CI-15) -----------------------------------------------------------------
    PMap<NodeId, ActivationDirectiveValue> directives = PMap.empty(scans);
    PMap<SlicePair, NodeRoutesValue> routes = PMap.empty(scans);
    PMap<NodeId, PSet<Artifact>> routesByNode = PMap.empty(scans);
    PMap<NodeId, NodePlacementValue> nodePlacements = PMap.empty(scans);
    PMap<String, CommunityPlacementOperationValue> placementOps = PMap.empty(scans);
    Option<ClusterConfigValue> clusterConfig = Option.none();
    PMap<NodeId, Long> admittedAt = PMap.empty(scans);
    PMap<String, PSet<NodeId>> assignedByCommunity = PMap.empty(scans);
    PMap<String, CommunityValue> communities = PMap.empty(scans);
    PMap<String, GovernorAnnouncementValue> rosters = PMap.empty(scans);
    PMap<NodeId, Integer> rosterCount = PMap.empty(scans);
    PMap<String, Long> announcementSeenAt = PMap.empty(scans);
    PMap<NodeId, Admission> admissions = PMap.empty(scans);
    PMap<NodeId, NodeDepartureValue> records = PMap.empty(scans);
    /// R-2.11: ids this leader observed joining **after** its `Rebuild` (a join input, C11f, or core liveness). Empty at `Rebuild`: the
    /// seed never supersedes a committed DEPARTED (D-4 (ii)).
    PSet<NodeId> joined = PSet.empty(scans);
    /// R-2.11 (H-1): the non-STALE DEPARTED records present in the `Rebuild` snapshot; an id leaves the set when its record changes.
    PSet<NodeId> inherited = PSet.empty(scans);
    /// R-2.11 (b): when each core of `coreView` was first listed, in the leader-local monotonic clock; `Liveness`.
    PMap<NodeId, Long> livenessSince = PMap.empty(scans);
    /// R-2.11 (G-4): cores whose current listing a removal input has broken; they accrue liveness only after a sample that does not list them.
    PSet<NodeId> livenessVetoed = PSet.empty(scans);
    /// A `SeamsRefreshed` sample was taken after `Rebuild` (R-2.11): the `localAlive` view of the `Rebuild` snapshot is not C11f evidence.
    boolean sampledAfterRebuild;
    PSet<NodeId> departed = PSet.empty(scans);
    PMap<NodeId, PendingDeparture> departing = PMap.empty(scans);
    PMap<NodeId, PendingJoin> pendingJoins = PMap.empty(scans);
    /// NT-88 J9 test hook: ids whose joins are treated as matching no row. Under MQ-4 no enumerated combination reaches J9, so a test injects
    /// one here. Production code never writes it.
    PSet<NodeId> unforeseenJoins = PSet.empty(scans);
    PMap<NodeId, PSet<Artifact>> unresponsiveByNode = PMap.empty(scans);
    PMap<NodeId, Long> absentSince = PMap.empty(scans);
    PSet<String> unjudgeableWarned = PSet.empty(scans);
    PSet<String> suppressedWarned = PSet.empty(scans);
    /// Datasources whose FAILED record was committed while suspended: announced once on the resume (`Schemas.announceWithheld`).
    PSet<String> withheldBlocked = PSet.empty(scans);
    // -- the write side (5.7) --------------------------------------------------------------------------------------
    PMap<String, Submitted> submitted = PMap.empty(scans);
    List<TxDraft> outbox = List.of();
    List<TxDraft> parked = List.of();
    List<Map.Entry<Artifact, JudgmentOp>> parkedOps = List.of();

    ClusterModel() {}

    /// A shallow copy: every field is an immutable value, so the copy shares everything with the original; the maps are rebound to the
    /// copy's own scan meter (O(1) each, structure is shared).
    ClusterModel copy() {
        var c = new ClusterModel();
        var meter = c.scans;

        c.incarnation = incarnation;
        c.incarnationStart = incarnationStart;
        c.leader = leader;
        c.reconcileQueued = reconcileQueued;
        c.flushQueued = flushQueued;
        c.auditDue = auditDue;
        c.joinWarningsDue = joinWarningsDue;
        c.txSeq = txSeq;
        c.genCounter = genCounter;
        c.tickCount = tickCount;
        c.seams = seams;
        c.timers = timers.rebound(meter);
        c.blueprints = blueprints.rebound(meter);
        c.blueprintsByArtifact = blueprintsByArtifact.rebound(meter);
        c.ownerByBase = ownerByBase.rebound(meter);
        c.targets = targets.rebound(meter);
        c.routings = routings.rebound(meter);
        c.pendingTargetPuts = pendingTargetPuts.rebound(meter);
        c.awaitedTargets = awaitedTargets.rebound(meter);
        c.redriveAt = redriveAt.rebound(meter);
        c.dependencies = dependencies.rebound(meter);
        c.dependents = dependents.rebound(meter);
        c.schemas = schemas.rebound(meter);
        c.schemaLocks = schemaLocks.rebound(meter);
        c.blockingSchemasByOwner = blockingSchemasByOwner.rebound(meter);
        c.holdSignatures = holdSignatures.rebound(meter);
        c.applies = applies.rebound(meter);
        c.outcomes = outcomes.rebound(meter);
        c.judgments = judgments.rebound(meter);
        c.desired = desired.rebound(meter);
        c.desiredByNode = desiredByNode.rebound(meter);
        c.desiredByArtifact = desiredByArtifact.rebound(meter);
        c.versionsByBase = versionsByBase.rebound(meter);
        c.observed = observed.rebound(meter);
        c.observedByNode = observedByNode.rebound(meter);
        c.observedByArtifact = observedByArtifact.rebound(meter);
        c.stray = stray.rebound(meter);
        c.divergent = divergent.rebound(meter);
        c.failedPairs = failedPairs.rebound(meter);
        c.unloadedDesired = unloadedDesired.rebound(meter);
        c.goalIntents = goalIntents.rebound(meter);
        c.artifactIntents = artifactIntents.rebound(meter);
        c.pendingGoals = pendingGoals.rebound(meter);
        c.pendingByArtifact = pendingByArtifact.rebound(meter);
        c.pendingByNode = pendingByNode.rebound(meter);
        c.seenAt = seenAt.rebound(meter);
        c.transitionedAt = transitionedAt.rebound(meter);
        c.reissues = reissues.rebound(meter);
        c.retryAt = retryAt.rebound(meter);
        c.imbalancedTicks = imbalancedTicks.rebound(meter);
        c.drainLoops = drainLoops.rebound(meter);
        c.directives = directives.rebound(meter);
        c.routes = routes.rebound(meter);
        c.routesByNode = routesByNode.rebound(meter);
        c.nodePlacements = nodePlacements.rebound(meter);
        c.placementOps = placementOps.rebound(meter);
        c.clusterConfig = clusterConfig;
        c.admittedAt = admittedAt.rebound(meter);
        c.assignedByCommunity = assignedByCommunity.rebound(meter);
        c.communities = communities.rebound(meter);
        c.rosters = rosters.rebound(meter);
        c.rosterCount = rosterCount.rebound(meter);
        c.announcementSeenAt = announcementSeenAt.rebound(meter);
        c.admissions = admissions.rebound(meter);
        c.records = records.rebound(meter);
        c.joined = joined.rebound(meter);
        c.inherited = inherited.rebound(meter);
        c.livenessSince = livenessSince.rebound(meter);
        c.livenessVetoed = livenessVetoed.rebound(meter);
        c.sampledAfterRebuild = sampledAfterRebuild;
        c.departed = departed.rebound(meter);
        c.departing = departing.rebound(meter);
        c.pendingJoins = pendingJoins.rebound(meter);
        c.unforeseenJoins = unforeseenJoins.rebound(meter);
        c.unresponsiveByNode = unresponsiveByNode.rebound(meter);
        c.absentSince = absentSince.rebound(meter);
        c.unjudgeableWarned = unjudgeableWarned.rebound(meter);
        c.suppressedWarned = suppressedWarned.rebound(meter);
        c.withheldBlocked = withheldBlocked.rebound(meter);
        c.submitted = submitted.rebound(meter);
        c.outbox = outbox;
        c.parked = parked;
        c.parkedOps = parkedOps;

        return c;
    }

    // -- readers ---------------------------------------------------------------------------------------------------
    public long incarnation() {
        return incarnation;
    }

    public Option<LeaderValue> leader() {
        return leader;
    }

    public Seams seams() {
        return seams;
    }

    public Option<SliceDesiredValue> desired(SlicePair pair) {
        return desired.get(pair);
    }

    public Option<SliceObservedValue> observed(SlicePair pair) {
        return observed.get(pair);
    }

    public Option<NodeDepartureValue> record(NodeId node) {
        return records.get(node);
    }

    public boolean isDeparted(NodeId node) {
        return departed.contains(node);
    }

    public boolean isDeparting(NodeId node) {
        return departing.containsKey(node);
    }

    public Option<ArtifactJudgmentValue> judgment(Artifact artifact) {
        return judgments.get(artifact);
    }

    public Option<Admission> admission(NodeId node) {
        return admissions.get(node);
    }

    public Option<Long> absentSince(NodeId node) {
        return absentSince.get(node);
    }

    public Option<DeploymentApplyValue> apply(BlueprintId id) {
        return applies.get(id);
    }

    public boolean hasStray(SlicePair pair) {
        return stray.contains(pair);
    }

    public boolean hasPendingGoal(SlicePair pair) {
        return pendingGoals.containsKey(pair);
    }

    public int submittedCount() {
        return submitted.size();
    }

    /// The goal a pair is heading to: the in-flight goal while one is pending, else the committed one (R-2.6).
    public Option<SliceGoal> effectiveGoal(SlicePair pair) {
        return pendingGoals.get(pair)
                           .map(PendingGoal::goal)
                           .orElse(() -> desired.get(pair)
                                                .map(SliceDesiredValue::goal));
    }

    // -- value types of the model ----------------------------------------------------------------------------------
    /// A goal submitted and not yet answered (the model's `pendingGoals`, M-3): it counts as placed until its
    /// transaction settles or its desired key arrives.
    public record PendingGoal(SliceGoal goal, Generation generation) {}

    /// How a goal intent is written (R-1.6.1).
    public enum GoalMode {
        /// A fresh generation, greater than any seen.
        FRESH,
        /// An adoption of an observed row: the generation is the row's own `forGeneration` (R-3.3.11).
        ADOPT,
        /// An identical re-issue: the same value, expecting itself (C2).
        REISSUE
    }

    /// A goal the handlers intend to write at the end of the drain, with the generation allocated for it at decision
    /// time. `guard` is the observed value the decision was derived from (R-5.7.1 (d)); `why` names the cause for audit.
    public record GoalIntent(SliceGoal goal,
                             GoalMode mode,
                             Generation generation,
                             Option<SliceObservedValue> guard,
                             String why,
                             List<RoutedEvent> routed) {
        public GoalIntent {
            routed = List.copyOf(routed);
        }
    }

    /// A goal write that rides an artifact's transaction (a retraction or a failure's UNLOADED, R-5.7.4).
    public record GoalWrite(SlicePair pair, SliceGoal goal, Generation generation, Option<SliceObservedValue> guard) {}

    /// What the handlers of one drain intend for one artifact (R-4.2.2, R-5.7.4): the judgment operations, folded into
    /// one mutation at the flush, the goal writes that ride the same transaction, and extra mutations (the BEST_EFFORT
    /// outcome merge, 4.4) built from the mirror when decided.
    public record ArtifactIntent(List<JudgmentOp> ops,
                                 List<GoalWrite> goals,
                                 List<KVCommand.Mutation<AetherKey, AetherValue>> extras,
                                 List<RoutedEvent> routed) {
        public ArtifactIntent {
            ops = List.copyOf(ops);
            goals = List.copyOf(goals);
            extras = List.copyOf(extras);
            routed = List.copyOf(routed);
        }

        public ArtifactIntent(List<JudgmentOp> ops,
                              List<GoalWrite> goals,
                              List<KVCommand.Mutation<AetherKey, AetherValue>> extras) {
            this(ops, goals, extras, List.of());
        }

        public static ArtifactIntent none() {
            return new ArtifactIntent(List.of(), List.of(), List.of());
        }

        public ArtifactIntent withRouted(RoutedEvent event) {
            var next = new ArrayList<>(routed);

            next.add(event);

            return new ArtifactIntent(ops, goals, extras, next);
        }

        public ArtifactIntent withOp(JudgmentOp op) {
            var next = new ArrayList<>(ops);

            next.add(op);

            return new ArtifactIntent(next, goals, extras, routed);
        }

        public ArtifactIntent withGoal(GoalWrite goal) {
            var next = new ArrayList<>(goals);

            next.add(goal);

            return new ArtifactIntent(ops, next, extras, routed);
        }

        public ArtifactIntent withoutGoalsOf(NodeId node) {
            return new ArtifactIntent(ops,
                                      goals.stream().filter(goal -> !goal.pair()
                                                                         .node()
                                                                         .equals(node)).toList(),
                                      extras,
                                      routed);
        }

        public ArtifactIntent withExtra(KVCommand.Mutation<AetherKey, AetherValue> extra) {
            var next = new ArrayList<>(extras);

            next.add(extra);

            return new ArtifactIntent(ops, goals, next, routed);
        }
    }

    /// The thresholds of 6.1 already acted on for a pair at a generation (at most once per threshold per pair
    /// and generation).
    public record ReissueMark(Generation generation, int level) {}

    /// A C17 / C17s that was submitted and is not yet resolved (R-4.3.0): the record value it expects, the
    /// admission it names, whether it was inferred, and when it started. `inFlightEpoch` is the epoch of an admission write that was in
    /// flight when the departure was decided (0 when none): the removal is ordered after that join, so an ADMITTED record of that epoch is
    /// the admission this departure ends, never one that makes it obsolete `[guess: the mailbox order of a join and a later removal,
    /// R-4.3.0 "within one leader incarnation", applied to a join whose write has not committed yet]`.
    public record PendingDeparture(Admission admission,
                                   Option<NodeDepartureValue> expected,
                                   Option<DepartureCause> cause,
                                   boolean inferred,
                                   long sinceMono,
                                   long inFlightEpoch) {
        /// The epoch this departure ends (R-4.3.0): `epochOf(n)` at the decision, or that of the admission in flight then.
        public long epoch() {
            return Math.max(Departures.epochOf(expected), inFlightEpoch);
        }
    }

    /// A join the join table could not place (J7, J9): re-evaluated on the next membership input for the node, on a
    /// record change, and on every tick.
    ///
    /// `warned`: the `join-unmatched` WARN (J9) of the current unmatched episode was emitted. The episode ends with the pending entry
    /// (a match, a departure, a new join input); the tick re-arms it (`Joins.retryPending`).
    public record PendingJoin(boolean worker, Admission admission, boolean warned) {
        public PendingJoin(boolean worker, Admission admission) {
            this(worker, admission, false);
        }

        PendingJoin unwarned() {
            return new PendingJoin(worker, admission, false);
        }

        PendingJoin asWarned() {
            return new PendingJoin(worker, admission, true);
        }
    }

    /// The state of the awaited-target watch of one slice (#1109, B-036).
    public record AwaitedTarget(long sinceMs, boolean warned, long lastResubmitMs) {}

    /// What a transaction in flight was, so that its settlement can be handled by kind (5.7, 6.2).
    public enum SubmitKind {
        GOALS,
        FAILURE,
        TARGETS,
        BLUEPRINT_REMOVAL,
        DEPARTURE,
        INFERRED_DEPARTURE,
        ADMISSION,
        CLEANUP,
        DIRECTIVE_REMOVAL,
        SUCCEEDED,
        ROLLBACK,
        STRAY,
        GC,
        DIRECTIVE,
        COMMUNITY,
        SCHEMA,
        OUTCOME,
        ORPHAN,
        DIVERGENCE
    }

    /// One transaction the handlers intend to submit at the end of the drain (R-5.7.4): its kind, its primary key,
    /// its mutations, its read guards, the pairs and node it concerns (for the per-pair re-derivation of R-5.7.2), the
    /// number of times it has been submitted, and the events to route when it is accepted.
    public record TxDraft(SubmitKind kind,
                          AetherKey key,
                          List<KVCommand.Mutation<AetherKey, AetherValue>> mutations,
                          List<KVCommand.ReadWitness<AetherKey>> guards,
                          Set<SlicePair> pairs,
                          Option<NodeId> node,
                          int attempt,
                          List<RoutedEvent> onAccepted,
                          List<JudgmentOp> eventOps) {
        public TxDraft {
            mutations = List.copyOf(mutations);
            guards = List.copyOf(guards);
            pairs = Set.copyOf(pairs);
            onAccepted = List.copyOf(onAccepted);
            eventOps = List.copyOf(eventOps);
        }

        public TxDraft(SubmitKind kind,
                       AetherKey key,
                       List<KVCommand.Mutation<AetherKey, AetherValue>> mutations,
                       List<KVCommand.ReadWitness<AetherKey>> guards,
                       Set<SlicePair> pairs,
                       Option<NodeId> node,
                       int attempt,
                       List<RoutedEvent> onAccepted) {
            this(kind, key, mutations, guards, pairs, node, attempt, onAccepted, List.of());
        }

        public static TxDraft draft(SubmitKind kind,
                                    AetherKey key,
                                    List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
            return new TxDraft(kind, key, mutations, List.of(), Set.of(), Option.none(), 0, List.of());
        }

        public TxDraft withGuards(List<KVCommand.ReadWitness<AetherKey>> value) {
            return new TxDraft(kind, key, mutations, value, pairs, node, attempt, onAccepted, eventOps);
        }

        public TxDraft withPairs(Set<SlicePair> value) {
            return new TxDraft(kind, key, mutations, guards, value, node, attempt, onAccepted, eventOps);
        }

        public TxDraft withNode(NodeId value) {
            return new TxDraft(kind, key, mutations, guards, pairs, Option.some(value), attempt, onAccepted, eventOps);
        }

        public TxDraft withOnAccepted(List<RoutedEvent> value) {
            return new TxDraft(kind, key, mutations, guards, pairs, node, attempt, value, eventOps);
        }

        /// The judgment operations this transaction carries that answer an event (see [JudgmentOp#eventOnly]): if the write is lost,
        /// failed or its judgment moved, they are decided again from here, since no sweep derives them.
        public TxDraft withEventOps(List<JudgmentOp> value) {
            return new TxDraft(kind, key, mutations, guards, pairs, node, attempt, onAccepted, value);
        }

        public TxDraft nextAttempt() {
            return new TxDraft(kind, key, mutations, guards, pairs, node, attempt + 1, onAccepted, eventOps);
        }
    }

    /// A submission in flight, by correlation id: the leader value it carried, so that a refusal can tell a moved
    /// witness from a moved key.
    public record Submitted(String correlationId,
                            SubmitKind kind,
                            List<TxDraft> drafts,
                            long incarnation,
                            LeaderValue leader,
                            long atMono) {
        public Submitted {
            drafts = List.copyOf(drafts);
        }
    }

    /// Needed by tests that stamp rows: the process of a node as the model last admitted it.
    public Option<ProcessIncarnation> processOf(NodeId node) {
        return admissions.get(node)
                         .map(Admission::process);
    }
}
