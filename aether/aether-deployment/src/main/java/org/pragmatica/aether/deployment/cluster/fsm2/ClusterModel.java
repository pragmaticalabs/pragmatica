// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.List;
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
    // -- identity and clocks (R-3.1.3, R-3.1.4) --------------------------------------------------------------------
    long incarnation;
    long incarnationStart;
    Option<LeaderValue> leader = Option.none();
    boolean reconcileQueued;
    boolean flushQueued;
    boolean auditDue;
    long txSeq;
    long genCounter;
    long tickCount;
    Seams seams = Seams.UNWIRED;
    PSet<TimerId> timers = PSet.empty();
    // -- blueprint-side fact indexes (CI-2) ------------------------------------------------------------------------
    PMap<BlueprintId, AppBlueprintValue> blueprints = PMap.empty();
    PMap<Artifact, PSet<BlueprintId>> blueprintsByArtifact = PMap.empty();
    PMap<ArtifactBase, BlueprintId> ownerByBase = PMap.empty();
    PMap<ArtifactBase, SliceTargetValue> targets = PMap.empty();
    PMap<ArtifactBase, VersionRoutingValue> routings = PMap.empty();
    PMap<ArtifactBase, SliceTargetValue> pendingTargetPuts = PMap.empty();
    PMap<Artifact, AwaitedTarget> awaitedTargets = PMap.empty();
    PMap<BlueprintId, Long> redriveAt = PMap.empty();
    PMap<Artifact, PSet<Artifact>> dependencies = PMap.empty();
    PMap<Artifact, PSet<Artifact>> dependents = PMap.empty();
    PMap<String, SchemaVersionValue> schemas = PMap.empty();
    PMap<String, SchemaMigrationLockValue> schemaLocks = PMap.empty();
    PMap<String, PSet<BlueprintId>> blockingSchemasByOwner = PMap.empty();
    PMap<SlicePair, String> holdSignatures = PMap.empty();
    PMap<BlueprintId, DeploymentApplyValue> applies = PMap.empty();
    PMap<BlueprintId, DeploymentOutcomeValue> outcomes = PMap.empty();
    PMap<Artifact, ArtifactJudgmentValue> judgments = PMap.empty();
    // -- slice-side fact and derived indexes (CI-3) ----------------------------------------------------------------
    PMap<SlicePair, SliceDesiredValue> desired = PMap.empty();
    PMap<NodeId, PSet<Artifact>> desiredByNode = PMap.empty();
    PMap<Artifact, PSet<NodeId>> desiredByArtifact = PMap.empty();
    PMap<ArtifactBase, PSet<Artifact>> versionsByBase = PMap.empty();
    PMap<SlicePair, SliceObservedValue> observed = PMap.empty();
    PMap<NodeId, PSet<Artifact>> observedByNode = PMap.empty();
    PMap<Artifact, PSet<NodeId>> observedByArtifact = PMap.empty();
    PSet<SlicePair> stray = PSet.empty();
    PSet<SlicePair> divergent = PSet.empty();
    PSet<SlicePair> failedPairs = PSet.empty();
    PSet<SlicePair> unloadedDesired = PSet.empty();
    PMap<SlicePair, GoalIntent> goalIntents = PMap.empty();
    PMap<Artifact, ArtifactIntent> artifactIntents = PMap.empty();
    PMap<SlicePair, PendingGoal> pendingGoals = PMap.empty();
    PMap<Artifact, PSet<NodeId>> pendingByArtifact = PMap.empty();
    PMap<NodeId, PSet<Artifact>> pendingByNode = PMap.empty();
    PMap<SlicePair, Long> seenAt = PMap.empty();
    PMap<SlicePair, Long> transitionedAt = PMap.empty();
    PMap<SlicePair, ReissueMark> reissues = PMap.empty();
    PMap<SlicePair, Long> retryAt = PMap.empty();
    PMap<Artifact, Integer> imbalancedTicks = PMap.empty();
    PSet<NodeId> drainLoops = PSet.empty();
    // -- membership-side state (CI-4..CI-9, CI-15) -----------------------------------------------------------------
    PMap<NodeId, ActivationDirectiveValue> directives = PMap.empty();
    PMap<SlicePair, NodeRoutesValue> routes = PMap.empty();
    PMap<NodeId, PSet<Artifact>> routesByNode = PMap.empty();
    PMap<NodeId, NodePlacementValue> nodePlacements = PMap.empty();
    PMap<String, CommunityPlacementOperationValue> placementOps = PMap.empty();
    Option<ClusterConfigValue> clusterConfig = Option.none();
    PMap<NodeId, Long> admittedAt = PMap.empty();
    PMap<String, PSet<NodeId>> assignedByCommunity = PMap.empty();
    PMap<String, CommunityValue> communities = PMap.empty();
    PMap<String, GovernorAnnouncementValue> rosters = PMap.empty();
    PMap<NodeId, Integer> rosterCount = PMap.empty();
    PMap<String, Long> announcementSeenAt = PMap.empty();
    PMap<NodeId, Admission> admissions = PMap.empty();
    PMap<NodeId, NodeDepartureValue> records = PMap.empty();
    PSet<NodeId> departed = PSet.empty();
    PMap<NodeId, PendingDeparture> departing = PMap.empty();
    PMap<NodeId, PendingJoin> pendingJoins = PMap.empty();
    PMap<NodeId, PSet<Artifact>> unresponsiveByNode = PMap.empty();
    PMap<NodeId, Long> absentSince = PMap.empty();
    PSet<String> unjudgeableWarned = PSet.empty();
    PSet<String> suppressedWarned = PSet.empty();
    // -- the write side (5.7) --------------------------------------------------------------------------------------
    PMap<String, Submitted> submitted = PMap.empty();
    List<TxDraft> outbox = List.of();

    ClusterModel() {}

    /// A shallow copy: every field is an immutable value, so the copy shares everything with the original.
    ClusterModel copy() {
        var c = new ClusterModel();

        c.incarnation = incarnation;
        c.incarnationStart = incarnationStart;
        c.leader = leader;
        c.reconcileQueued = reconcileQueued;
        c.flushQueued = flushQueued;
        c.auditDue = auditDue;
        c.txSeq = txSeq;
        c.genCounter = genCounter;
        c.tickCount = tickCount;
        c.seams = seams;
        c.timers = timers;
        c.blueprints = blueprints;
        c.blueprintsByArtifact = blueprintsByArtifact;
        c.ownerByBase = ownerByBase;
        c.targets = targets;
        c.routings = routings;
        c.pendingTargetPuts = pendingTargetPuts;
        c.awaitedTargets = awaitedTargets;
        c.redriveAt = redriveAt;
        c.dependencies = dependencies;
        c.dependents = dependents;
        c.schemas = schemas;
        c.schemaLocks = schemaLocks;
        c.blockingSchemasByOwner = blockingSchemasByOwner;
        c.holdSignatures = holdSignatures;
        c.applies = applies;
        c.outcomes = outcomes;
        c.judgments = judgments;
        c.desired = desired;
        c.desiredByNode = desiredByNode;
        c.desiredByArtifact = desiredByArtifact;
        c.versionsByBase = versionsByBase;
        c.observed = observed;
        c.observedByNode = observedByNode;
        c.observedByArtifact = observedByArtifact;
        c.stray = stray;
        c.divergent = divergent;
        c.failedPairs = failedPairs;
        c.unloadedDesired = unloadedDesired;
        c.goalIntents = goalIntents;
        c.artifactIntents = artifactIntents;
        c.pendingGoals = pendingGoals;
        c.pendingByArtifact = pendingByArtifact;
        c.pendingByNode = pendingByNode;
        c.seenAt = seenAt;
        c.transitionedAt = transitionedAt;
        c.reissues = reissues;
        c.retryAt = retryAt;
        c.imbalancedTicks = imbalancedTicks;
        c.drainLoops = drainLoops;
        c.directives = directives;
        c.routes = routes;
        c.routesByNode = routesByNode;
        c.nodePlacements = nodePlacements;
        c.placementOps = placementOps;
        c.clusterConfig = clusterConfig;
        c.admittedAt = admittedAt;
        c.assignedByCommunity = assignedByCommunity;
        c.communities = communities;
        c.rosters = rosters;
        c.rosterCount = rosterCount;
        c.announcementSeenAt = announcementSeenAt;
        c.admissions = admissions;
        c.records = records;
        c.departed = departed;
        c.departing = departing;
        c.pendingJoins = pendingJoins;
        c.unresponsiveByNode = unresponsiveByNode;
        c.absentSince = absentSince;
        c.unjudgeableWarned = unjudgeableWarned;
        c.suppressedWarned = suppressedWarned;
        c.submitted = submitted;
        c.outbox = outbox;

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
                             String why) {}

    /// A goal write that rides an artifact's transaction (a retraction or a failure's UNLOADED, R-5.7.4).
    public record GoalWrite(SlicePair pair, SliceGoal goal, Generation generation, Option<SliceObservedValue> guard) {}

    /// What the handlers of one drain intend for one artifact (R-4.2.2, R-5.7.4): the judgment operations, folded into
    /// one mutation at the flush, the goal writes that ride the same transaction, and extra mutations (the BEST_EFFORT
    /// outcome merge, 4.4) built from the mirror when decided.
    public record ArtifactIntent(List<JudgmentOp> ops,
                                 List<GoalWrite> goals,
                                 List<KVCommand.Mutation<AetherKey, AetherValue>> extras) {
        public ArtifactIntent {
            ops = List.copyOf(ops);
            goals = List.copyOf(goals);
            extras = List.copyOf(extras);
        }

        public static ArtifactIntent none() {
            return new ArtifactIntent(List.of(), List.of(), List.of());
        }

        public ArtifactIntent withOp(JudgmentOp op) {
            var next = new ArrayList<>(ops);

            next.add(op);

            return new ArtifactIntent(next, goals, extras);
        }

        public ArtifactIntent withGoal(GoalWrite goal) {
            var next = new ArrayList<>(goals);

            next.add(goal);

            return new ArtifactIntent(ops, next, extras);
        }

        public ArtifactIntent withExtra(KVCommand.Mutation<AetherKey, AetherValue> extra) {
            var next = new ArrayList<>(extras);

            next.add(extra);

            return new ArtifactIntent(ops, goals, next);
        }
    }

    /// The thresholds of 6.1 already acted on for a pair at a generation (at most once per threshold per pair
    /// and generation).
    public record ReissueMark(Generation generation, int level) {}

    /// A C17 / C17s that was submitted and is not yet resolved (R-4.3.0): the record value it expects, the
    /// admission it names, whether it was inferred, and when it started.
    public record PendingDeparture(Admission admission,
                                   Option<NodeDepartureValue> expected,
                                   Option<DepartureCause> cause,
                                   boolean inferred,
                                   long sinceMono) {}

    /// A join the join table could not place (J7, J9): re-evaluated on the next membership input for the node, on a
    /// record change, and on every tick.
    public record PendingJoin(boolean worker, Admission admission) {}

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
                          List<RoutedEvent> onAccepted) {
        public TxDraft {
            mutations = List.copyOf(mutations);
            guards = List.copyOf(guards);
            pairs = Set.copyOf(pairs);
            onAccepted = List.copyOf(onAccepted);
        }

        public static TxDraft draft(SubmitKind kind,
                                    AetherKey key,
                                    List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
            return new TxDraft(kind, key, mutations, List.of(), Set.of(), Option.none(), 0, List.of());
        }

        public TxDraft withGuards(List<KVCommand.ReadWitness<AetherKey>> value) {
            return new TxDraft(kind, key, mutations, value, pairs, node, attempt, onAccepted);
        }

        public TxDraft withPairs(Set<SlicePair> value) {
            return new TxDraft(kind, key, mutations, guards, value, node, attempt, onAccepted);
        }

        public TxDraft withNode(NodeId value) {
            return new TxDraft(kind, key, mutations, guards, pairs, Option.some(value), attempt, onAccepted);
        }

        public TxDraft withOnAccepted(List<RoutedEvent> value) {
            return new TxDraft(kind, key, mutations, guards, pairs, node, attempt, value);
        }

        public TxDraft nextAttempt() {
            return new TxDraft(kind, key, mutations, guards, pairs, node, attempt + 1, onAccepted);
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
