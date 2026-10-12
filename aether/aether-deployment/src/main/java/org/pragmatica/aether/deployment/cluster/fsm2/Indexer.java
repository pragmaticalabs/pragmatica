// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentApplyKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.VersionRoutingKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityPlacementOperationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodePlacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaMigrationLockValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.VersionRoutingValue;
import org.pragmatica.consensus.NodeId;


/// The incremental fact and derived indexes of the model (spec R-3.3.1): one `index...` function per KV key family,
/// used both by `Rebuild`, which only indexes, and by the live handlers, which index and then react.
///
/// Every function mutates the working model it is given (see [ClusterModel]) and returns it. Each costs O(log n) for
/// the keys it touches; none iterates a model-wide index (R-7.3.1), except where a loop over the artifacts or rows
/// of one node is the definition ("re-classifies n's rows", R-3.3.1).
public sealed interface Indexer {
    // -- dispatch --------------------------------------------------------------------------------------------------
    /// Index one committed put of any key family the FSM reads.
    static ClusterModel indexPut(ClusterModel m, Ctx ctx, AetherKey key, AetherValue value) {
        return switch (key) {
            case AppBlueprintKey k -> value instanceof AppBlueprintValue v
                                      ? indexBlueprint(m, k.blueprintId(), v)
                                      : m;
            case SliceTargetKey k -> value instanceof SliceTargetValue v
                                     ? indexTarget(m, k, v)
                                     : m;
            case VersionRoutingKey k -> value instanceof VersionRoutingValue v
                                        ? indexRouting(m, k, v)
                                        : m;
            case SliceDesiredKey k -> value instanceof SliceDesiredValue v
                                      ? indexDesired(m,
                                                     ctx,
                                                     SlicePair.slicePair(k.nodeId(), k.artifact()),
                                                     v)
                                      : m;
            case SliceObservedKey k -> value instanceof SliceObservedValue v
                                       ? indexObserved(m,
                                                       ctx,
                                                       SlicePair.slicePair(k.nodeId(), k.artifact()),
                                                       v)
                                       : m;
            case ActivationDirectiveKey k -> value instanceof ActivationDirectiveValue v
                                             ? indexDirective(m, k.nodeId(), v)
                                             : m;
            case CommunityKey k -> value instanceof CommunityValue v
                                   ? indexCommunity(m, k.communityId(), v)
                                   : m;
            case GovernorAnnouncementKey k -> value instanceof GovernorAnnouncementValue v
                                              ? indexAnnouncement(m, ctx, k.communityId(), v)
                                              : m;
            case NodeDepartureKey k -> value instanceof NodeDepartureValue v
                                       ? indexRecord(m, k.nodeId(), v)
                                       : m;
            case ArtifactJudgmentKey k -> value instanceof ArtifactJudgmentValue v
                                          ? indexJudgment(m, k.artifact(), v)
                                          : m;
            case DeploymentApplyKey k -> value instanceof DeploymentApplyValue v
                                         ? indexApply(m, k.blueprintId(), v)
                                         : m;
            case DeploymentOutcomeKey k -> value instanceof DeploymentOutcomeValue v
                                           ? indexOutcome(m, k.blueprintId(), v)
                                           : m;
            case SchemaVersionKey k -> value instanceof SchemaVersionValue v
                                       ? indexSchema(m, k.datasourceName(), v)
                                       : m;
            case AetherKey.SchemaMigrationLockKey k -> value instanceof SchemaMigrationLockValue v
                                                       ? indexLock(m, k.datasourceName(), v)
                                                       : m;
            case AetherKey.NodeRoutesKey k -> value instanceof NodeRoutesValue v
                                              ? indexRoutes(m,
                                                            SlicePair.slicePair(k.nodeId(), k.artifact()),
                                                            v)
                                              : m;
            case AetherKey.NodePlacementKey k -> value instanceof NodePlacementValue v
                                                 ? indexNodePlacement(m, k.nodeId(), v)
                                                 : m;
            case AetherKey.CommunityPlacementOperationKey k -> value instanceof CommunityPlacementOperationValue v
                                                               ? indexPlacementOp(m, k.communityId(), v)
                                                               : m;
            case AetherKey.ClusterConfigKey _ -> value instanceof ClusterConfigValue v
                                                 ? indexClusterConfig(m, v)
                                                 : m;
            default -> m;
        };
    }

    /// Index one committed remove of any key family the FSM reads.
    static ClusterModel indexRemove(ClusterModel m, Ctx ctx, AetherKey key) {
        return switch (key) {
            case AppBlueprintKey k -> removeBlueprint(m, k.blueprintId());
            case SliceTargetKey k -> removeTarget(m, k);
            case VersionRoutingKey k -> removeRouting(m, k);
            case SliceDesiredKey k -> removeDesired(m,
                                                    SlicePair.slicePair(k.nodeId(), k.artifact()));
            case SliceObservedKey k -> removeObserved(m,
                                                      SlicePair.slicePair(k.nodeId(), k.artifact()));
            case ActivationDirectiveKey k -> removeDirective(m, k.nodeId());
            case CommunityKey k -> removeCommunity(m, k.communityId());
            case GovernorAnnouncementKey k -> removeAnnouncement(m, k.communityId());
            case NodeDepartureKey k -> removeRecord(m, k.nodeId());
            case ArtifactJudgmentKey k -> removeJudgment(m, k.artifact());
            case DeploymentApplyKey k -> removeApply(m, k.blueprintId());
            case DeploymentOutcomeKey k -> removeOutcome(m, k.blueprintId());
            case SchemaVersionKey k -> removeSchema(m, k.datasourceName());
            case AetherKey.SchemaMigrationLockKey k -> removeLock(m, k.datasourceName());
            case AetherKey.NodeRoutesKey k -> removeRoutes(m,
                                                           SlicePair.slicePair(k.nodeId(), k.artifact()));
            case AetherKey.NodePlacementKey k -> removeNodePlacement(m, k.nodeId());
            case AetherKey.CommunityPlacementOperationKey k -> removePlacementOp(m, k.communityId());
            default -> m;
        };
    }

    // -- blueprints, targets, routings -----------------------------------------------------------------------------
    static ClusterModel indexBlueprint(ClusterModel m, BlueprintId id, AppBlueprintValue value) {
        removeBlueprint(m, id);
        m.blueprints = m.blueprints.put(id, value);
        value.blueprint().loadOrder().forEach(slice -> indexSlice(m, id, slice));

        return m;
    }

    private static void indexSlice(ClusterModel m, BlueprintId id, ResolvedSlice slice) {
        var artifact = slice.artifact();

        m.blueprintsByArtifact = Idx.add(m.blueprintsByArtifact, artifact, id);
        m.ownerByBase = m.ownerByBase.put(artifact.base(), id);
        m.dependencies = m.dependencies.put(artifact, setOf(slice.dependencies()));
        slice.dependencies().forEach(dependency -> m.dependents = Idx.add(m.dependents, dependency, artifact));
    }

    private static PSet<Artifact> setOf(java.util.Set<Artifact> artifacts) {
        var set = PSet.<Artifact> empty();

        for (var artifact : artifacts) {
            set = set.add(artifact);
        }

        return set;
    }

    static ClusterModel removeBlueprint(ClusterModel m, BlueprintId id) {
        m.blueprints.get(id).onPresent(old -> old.blueprint()
                                                 .loadOrder()
                                                 .forEach(slice -> unindexSlice(m, id, slice)));
        m.blueprints = m.blueprints.remove(id);

        return m;
    }

    private static void unindexSlice(ClusterModel m, BlueprintId id, ResolvedSlice slice) {
        var artifact = slice.artifact();

        m.blueprintsByArtifact = Idx.remove(m.blueprintsByArtifact, artifact, id);
        m.ownerByBase.get(artifact.base())
                     .filter(id::equals)
                     .onPresent(_ -> m.ownerByBase = m.ownerByBase.remove(artifact.base()));
        m.dependencies = m.dependencies.remove(artifact);
        slice.dependencies().forEach(dependency -> m.dependents = Idx.remove(m.dependents, dependency, artifact));
    }

    static ClusterModel indexTarget(ClusterModel m, SliceTargetKey key, SliceTargetValue value) {
        m.targets = m.targets.put(key.artifactBase(), value);

        return m;
    }

    static ClusterModel removeTarget(ClusterModel m, SliceTargetKey key) {
        m.targets = m.targets.remove(key.artifactBase());

        return m;
    }

    static ClusterModel indexRouting(ClusterModel m, VersionRoutingKey key, VersionRoutingValue value) {
        m.routings = m.routings.put(key.artifactBase(), value);

        return m;
    }

    static ClusterModel removeRouting(ClusterModel m, VersionRoutingKey key) {
        m.routings = m.routings.remove(key.artifactBase());

        return m;
    }

    // -- the slice pair: desired, observed, derived (R-3.3.1, R-3.3.11) --------------------------------------------
    static ClusterModel indexDesired(ClusterModel m, Ctx ctx, SlicePair pair, SliceDesiredValue value) {
        m.desired = m.desired.put(pair, value);
        m.desiredByNode = Idx.add(m.desiredByNode, pair.node(), pair.artifact());
        m.desiredByArtifact = Idx.add(m.desiredByArtifact, pair.artifact(), pair.node());
        m.seenAt = m.seenAt.put(pair, ctx.monoNs());
        clearPending(m, pair);
        noteGeneration(m, value.generation());
        trackVersion(m, pair);

        return refreshPair(m, pair);
    }

    static ClusterModel removeDesired(ClusterModel m, SlicePair pair) {
        m.desired = m.desired.remove(pair);
        m.desiredByNode = Idx.remove(m.desiredByNode, pair.node(), pair.artifact());
        m.desiredByArtifact = Idx.remove(m.desiredByArtifact, pair.artifact(), pair.node());
        clearPending(m, pair);
        m.reissues = m.reissues.remove(pair);
        trackVersion(m, pair);

        return refreshPair(m, pair);
    }

    static ClusterModel indexObserved(ClusterModel m, Ctx ctx, SlicePair pair, SliceObservedValue value) {
        m.observed = m.observed.put(pair, value);
        m.observedByNode = Idx.add(m.observedByNode, pair.node(), pair.artifact());
        m.observedByArtifact = Idx.add(m.observedByArtifact, pair.artifact(), pair.node());
        m.seenAt = m.seenAt.put(pair, ctx.monoNs());
        m.transitionedAt = m.transitionedAt.put(pair, value.transitionedAt());
        noteGeneration(m, value.forGeneration());
        trackVersion(m, pair);

        return refreshPair(m, pair);
    }

    static ClusterModel removeObserved(ClusterModel m, SlicePair pair) {
        m.observed = m.observed.remove(pair);
        m.observedByNode = Idx.remove(m.observedByNode, pair.node(), pair.artifact());
        m.observedByArtifact = Idx.remove(m.observedByArtifact, pair.artifact(), pair.node());
        m.transitionedAt = m.transitionedAt.remove(pair);
        trackVersion(m, pair);

        return refreshPair(m, pair);
    }

    /// `versionsByBase[base]` holds every artifact (version) of the base that has a desired or observed key.
    private static void trackVersion(ClusterModel m, SlicePair pair) {
        var artifact = pair.artifact();
        var present = m.desiredByArtifact.containsKey(artifact) || m.observedByArtifact.containsKey(artifact);

        m.versionsByBase = present
                           ? Idx.add(m.versionsByBase, artifact.base(), artifact)
                           : Idx.remove(m.versionsByBase, artifact.base(), artifact);
    }

    /// A goal in flight is answered by the desired key that carries it (C11b), and is dropped when the desired key goes.
    static ClusterModel clearPending(ClusterModel m, SlicePair pair) {
        m.pendingGoals = m.pendingGoals.remove(pair);
        m.pendingByArtifact = Idx.remove(m.pendingByArtifact, pair.artifact(), pair.node());
        m.pendingByNode = Idx.remove(m.pendingByNode, pair.node(), pair.artifact());

        return m;
    }

    static ClusterModel putPending(ClusterModel m, SlicePair pair, ClusterModel.PendingGoal pending) {
        m.pendingGoals = m.pendingGoals.put(pair, pending);
        m.pendingByArtifact = Idx.add(m.pendingByArtifact, pair.artifact(), pair.node());
        m.pendingByNode = Idx.add(m.pendingByNode, pair.node(), pair.artifact());

        return m;
    }

    /// The generation counter never falls behind any generation the model has seen (NT-114): a fresh generation is
    /// strictly greater than every observation, after GC and after a leader change.
    static ClusterModel noteGeneration(ClusterModel m, AetherValue.Generation seen) {
        var leaderSequence = m.leader.map(leader -> leader.viewSequence()).or(0L);

        if (seen.viewSequence() == leaderSequence && seen.counter() >= m.genCounter) {
            m.genCounter = seen.counter() + 1;
        }

        return m;
    }

    /// Recompute the three derived memberships of one pair: `stray`, `divergent`, `unloadedDesired`.
    static ClusterModel refreshPair(ClusterModel m, SlicePair pair) {
        var goal = m.desired.get(pair);
        var current = Rows.current(m, pair);
        var isStray = goal.isEmpty() && current.map(value -> value.state() != SliceObservedState.UNLOADED).or(false);

        m.stray = isStray
                  ? m.stray.add(pair)
                  : m.stray.remove(pair);
        m.unloadedDesired = goal.map(value -> value.goal() == SliceGoal.UNLOADED).or(false)
                            ? m.unloadedDesired.add(pair)
                            : m.unloadedDesired.remove(pair);
        m.failedPairs = goal.flatMap(value -> current.filter(row -> awaitsFailureHandling(value, row))).isPresent()
                        ? m.failedPairs.add(pair)
                        : m.failedPairs.remove(pair);
        m.divergent = goal.map(value -> !Rows.isSettled(value, current)).or(false)
                      ? m.divergent.add(pair)
                      : m.divergent.remove(pair);

        return m;
    }

    /// A FAILED row at the goal's current generation under a goal other than UNLOADED: the failure handling (C10c) owns it.
    private static boolean awaitsFailureHandling(SliceDesiredValue goal, SliceObservedValue row) {
        return row.state() == SliceObservedState.FAILED
               && goal.goal() != SliceGoal.UNLOADED
               && row.forGeneration()
                     .equals(goal.generation());
    }

    /// Re-classify every row of `node` (R-3.3.1): O(rows of node).
    static ClusterModel refreshNodeRows(ClusterModel m, NodeId node) {
        var artifacts = Idx.members(m.observedByNode, node).toList();

        for (var artifact : artifacts) {
            refreshPair(m, SlicePair.slicePair(node, artifact));
        }

        return m;
    }

    // -- directives, communities, announcements --------------------------------------------------------------------
    static ClusterModel indexDirective(ClusterModel m, NodeId node, ActivationDirectiveValue value) {
        removeDirective(m, node);
        m.directives = m.directives.put(node, value);
        if (!value.communityId().isEmpty() && !m.departed.contains(node)) {
            m.assignedByCommunity = Idx.add(m.assignedByCommunity, value.communityId(), node);
        }

        return m;
    }

    static ClusterModel removeDirective(ClusterModel m, NodeId node) {
        m.directives.get(node)
                    .filter(old -> !old.communityId()
                                       .isEmpty())
                    .onPresent(old -> m.assignedByCommunity = Idx.remove(m.assignedByCommunity,
                                                                         old.communityId(),
                                                                         node));
        m.directives = m.directives.remove(node);

        return m;
    }

    static ClusterModel indexCommunity(ClusterModel m, String id, CommunityValue value) {
        m.communities = m.communities.put(id, value);

        return m;
    }

    static ClusterModel removeCommunity(ClusterModel m, String id) {
        m.communities = m.communities.remove(id);

        return m;
    }

    /// Index a committed roster and stamp the leader-local time of this Put (R-2.14c). `rosterCount` counts, per node,
    /// the non-dissolved rosters that list it: `rosterUnion` (`M`) is its key set.
    static ClusterModel indexAnnouncement(ClusterModel m, Ctx ctx, String community, GovernorAnnouncementValue value) {
        removeRoster(m, community);
        m.rosters = m.rosters.put(community, value);
        if (!value.dissolved()) {
            value.members()
                 .forEach(member -> m.rosterCount = m.rosterCount.put(member,
                                                                      m.rosterCount.get(member).or(0) + 1));
        }

        m.announcementSeenAt = m.announcementSeenAt.put(community, ctx.monoNs());

        return m;
    }

    static ClusterModel removeAnnouncement(ClusterModel m, String community) {
        removeRoster(m, community);
        m.announcementSeenAt = m.announcementSeenAt.remove(community);

        return m;
    }

    private static void removeRoster(ClusterModel m, String community) {
        m.rosters.get(community)
                 .filter(old -> !old.dissolved())
                 .onPresent(old -> old.members()
                                      .forEach(member -> decrementRoster(m, member)));
        m.rosters = m.rosters.remove(community);
    }

    private static void decrementRoster(ClusterModel m, NodeId member) {
        var count = m.rosterCount.get(member).or(0) - 1;

        m.rosterCount = count > 0
                        ? m.rosterCount.put(member, count)
                        : m.rosterCount.remove(member);
    }

    // -- departure records (R-4.3.0) -------------------------------------------------------------------------------
    static ClusterModel indexRecord(ClusterModel m, NodeId node, NodeDepartureValue value) {
        m.records = m.records.put(node, value);

        return refreshDeparted(m, node);
    }

    static ClusterModel removeRecord(ClusterModel m, NodeId node) {
        m.records = m.records.remove(node);

        return refreshDeparted(m, node);
    }

    /// `departed` = {n : records[n] is DEPARTED and not ignored on read} (R-4.3.0). Recomputed for one node whenever its
    /// record or its admission changes, together with that node's assignment accounting and row classification.
    static ClusterModel refreshDeparted(ClusterModel m, NodeId node) {
        var departed = m.records.get(node)
                                .map(record -> record.state() == DepartureState.DEPARTED && !Departures.isIgnored(record,
                                                                                                                  m.admissions.get(node)))
                                .or(false);

        m.departed = departed
                     ? m.departed.add(node)
                     : m.departed.remove(node);
        m.directives.get(node)
                    .filter(directive -> !directive.communityId()
                                                   .isEmpty())
                    .onPresent(directive -> m.assignedByCommunity = departed
                                                                    ? Idx.remove(m.assignedByCommunity,
                                                                                 directive.communityId(),
                                                                                 node)
                                                                    : Idx.add(m.assignedByCommunity,
                                                                              directive.communityId(),
                                                                              node));

        return m;
    }

    // -- judgments, applies, outcomes, schemas ---------------------------------------------------------------------
    static ClusterModel indexJudgment(ClusterModel m, Artifact artifact, ArtifactJudgmentValue value) {
        unindexExclusions(m, artifact);
        m.judgments = m.judgments.put(artifact, value);
        value.unresponsive()
             .keySet()
             .forEach(node -> m.unresponsiveByNode = Idx.add(m.unresponsiveByNode, node, artifact));

        return m;
    }

    static ClusterModel removeJudgment(ClusterModel m, Artifact artifact) {
        unindexExclusions(m, artifact);
        m.judgments = m.judgments.remove(artifact);

        return m;
    }

    private static void unindexExclusions(ClusterModel m, Artifact artifact) {
        m.judgments.get(artifact)
                   .onPresent(old -> old.unresponsive()
                                        .keySet()
                                        .forEach(node -> m.unresponsiveByNode = Idx.remove(m.unresponsiveByNode,
                                                                                           node,
                                                                                           artifact)));
    }

    static ClusterModel indexApply(ClusterModel m, BlueprintId id, DeploymentApplyValue value) {
        m.applies = m.applies.put(id, value);

        return m;
    }

    static ClusterModel removeApply(ClusterModel m, BlueprintId id) {
        m.applies = m.applies.remove(id);

        return m;
    }

    static ClusterModel indexOutcome(ClusterModel m, BlueprintId id, DeploymentOutcomeValue value) {
        m.outcomes = m.outcomes.put(id, value);

        return m;
    }

    static ClusterModel removeOutcome(ClusterModel m, BlueprintId id) {
        m.outcomes = m.outcomes.remove(id);

        return m;
    }

    static ClusterModel indexSchema(ClusterModel m, String datasource, SchemaVersionValue value) {
        m.schemas = m.schemas.put(datasource, value);

        return m;
    }

    static ClusterModel indexRoutes(ClusterModel m, SlicePair pair, NodeRoutesValue value) {
        m.routes = m.routes.put(pair, value);
        m.routesByNode = Idx.add(m.routesByNode, pair.node(), pair.artifact());

        return m;
    }

    static ClusterModel removeRoutes(ClusterModel m, SlicePair pair) {
        m.routes = m.routes.remove(pair);
        m.routesByNode = Idx.remove(m.routesByNode, pair.node(), pair.artifact());

        return m;
    }

    static ClusterModel indexNodePlacement(ClusterModel m, NodeId node, NodePlacementValue value) {
        m.nodePlacements = m.nodePlacements.put(node, value);

        return m;
    }

    static ClusterModel removeNodePlacement(ClusterModel m, NodeId node) {
        m.nodePlacements = m.nodePlacements.remove(node);

        return m;
    }

    static ClusterModel indexPlacementOp(ClusterModel m, String community, CommunityPlacementOperationValue value) {
        m.placementOps = m.placementOps.put(community, value);

        return m;
    }

    static ClusterModel removePlacementOp(ClusterModel m, String community) {
        m.placementOps = m.placementOps.remove(community);

        return m;
    }

    static ClusterModel indexClusterConfig(ClusterModel m, ClusterConfigValue value) {
        m.clusterConfig = org.pragmatica.lang.Option.some(value);

        return m;
    }

    static ClusterModel indexLock(ClusterModel m, String datasource, SchemaMigrationLockValue value) {
        m.schemaLocks = m.schemaLocks.put(datasource, value);

        return m;
    }

    static ClusterModel removeLock(ClusterModel m, String datasource) {
        m.schemaLocks = m.schemaLocks.remove(datasource);

        return m;
    }

    static ClusterModel removeSchema(ClusterModel m, String datasource) {
        m.schemas = m.schemas.remove(datasource);

        return m;
    }

    record unused() implements Indexer {}
}
