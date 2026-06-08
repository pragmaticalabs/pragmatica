// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.ClusterMode;
import org.pragmatica.aether.slice.generation.CommunitySummary;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.PartitionOwner;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot.clusterGenerationSnapshot;


public interface ClusterGenerationProjector {
    ClusterGenerationSnapshot project(ProjectionInput input);

    record ProjectionInput(long rabiaTerm,
                           long localCounter,
                           int desiredCoreSize,
                           GenerationReason reason,
                           HlcTimestamp now,
                           Map<NodeId, MemberLifecycle> lifecycles,
                           Map<String, GovernorAnnouncementValue> governors,
                           Map<String, DhtPartitionOwnershipValue> partitions,
                           Map<NodeId, SpokesmanValue> spokesmen,
                           Map<NodeId, Epoch> lastSeenPerNode,
                           Map<String, Epoch> lastAckPerCommunity,
                           Map<String, SliceTargetValue> sliceTargets,
                           Set<NodeId> nodesWithArtifacts,
                           Map<NodeId, HealthHint> swimHints) {
        public ProjectionInput {
            lifecycles = Map.copyOf(lifecycles);
            governors = Map.copyOf(governors);
            partitions = Map.copyOf(partitions);
            spokesmen = Map.copyOf(spokesmen);
            lastSeenPerNode = Map.copyOf(lastSeenPerNode);
            lastAckPerCommunity = Map.copyOf(lastAckPerCommunity);
            sliceTargets = Map.copyOf(sliceTargets);
            nodesWithArtifacts = nodesWithArtifacts == null
                                 ? Set.of()
                                 : Set.copyOf(nodesWithArtifacts);
            swimHints = swimHints == null
                        ? Map.of()
                        : Map.copyOf(swimHints);
        }

        public static ProjectionInput projectionInput(long rabiaTerm,
                                                      long localCounter,
                                                      int desiredCoreSize,
                                                      GenerationReason reason,
                                                      HlcTimestamp now,
                                                      Map<NodeId, MemberLifecycle> lifecycles,
                                                      Map<String, GovernorAnnouncementValue> governors,
                                                      Map<String, DhtPartitionOwnershipValue> partitions,
                                                      Map<NodeId, SpokesmanValue> spokesmen,
                                                      Map<NodeId, Epoch> lastSeenPerNode,
                                                      Map<String, Epoch> lastAckPerCommunity,
                                                      Map<String, SliceTargetValue> sliceTargets) {
            return projectionInput(rabiaTerm,
                                   localCounter,
                                   desiredCoreSize,
                                   reason,
                                   now,
                                   lifecycles,
                                   governors,
                                   partitions,
                                   spokesmen,
                                   lastSeenPerNode,
                                   lastAckPerCommunity,
                                   sliceTargets,
                                   Set.of());
        }

        public static ProjectionInput projectionInput(long rabiaTerm,
                                                      long localCounter,
                                                      int desiredCoreSize,
                                                      GenerationReason reason,
                                                      HlcTimestamp now,
                                                      Map<NodeId, MemberLifecycle> lifecycles,
                                                      Map<String, GovernorAnnouncementValue> governors,
                                                      Map<String, DhtPartitionOwnershipValue> partitions,
                                                      Map<NodeId, SpokesmanValue> spokesmen,
                                                      Map<NodeId, Epoch> lastSeenPerNode,
                                                      Map<String, Epoch> lastAckPerCommunity,
                                                      Map<String, SliceTargetValue> sliceTargets,
                                                      Set<NodeId> nodesWithArtifacts) {
            return projectionInput(rabiaTerm,
                                   localCounter,
                                   desiredCoreSize,
                                   reason,
                                   now,
                                   lifecycles,
                                   governors,
                                   partitions,
                                   spokesmen,
                                   lastSeenPerNode,
                                   lastAckPerCommunity,
                                   sliceTargets,
                                   nodesWithArtifacts,
                                   Map.of());
        }

        public static ProjectionInput projectionInput(long rabiaTerm,
                                                      long localCounter,
                                                      int desiredCoreSize,
                                                      GenerationReason reason,
                                                      HlcTimestamp now,
                                                      Map<NodeId, MemberLifecycle> lifecycles,
                                                      Map<String, GovernorAnnouncementValue> governors,
                                                      Map<String, DhtPartitionOwnershipValue> partitions,
                                                      Map<NodeId, SpokesmanValue> spokesmen,
                                                      Map<NodeId, Epoch> lastSeenPerNode,
                                                      Map<String, Epoch> lastAckPerCommunity,
                                                      Map<String, SliceTargetValue> sliceTargets,
                                                      Set<NodeId> nodesWithArtifacts,
                                                      Map<NodeId, HealthHint> swimHints) {
            return new ProjectionInput(rabiaTerm,
                                       localCounter,
                                       desiredCoreSize,
                                       reason,
                                       now,
                                       lifecycles,
                                       governors,
                                       partitions,
                                       spokesmen,
                                       lastSeenPerNode,
                                       lastAckPerCommunity,
                                       sliceTargets,
                                       nodesWithArtifacts,
                                       swimHints);
        }
    }

    static ClusterGenerationProjector clusterGenerationProjector() {
        return ClusterGenerationProjectorRecord.INSTANCE;
    }
}

record ClusterGenerationProjectorRecord() implements ClusterGenerationProjector {
    static final ClusterGenerationProjectorRecord INSTANCE = new ClusterGenerationProjectorRecord();

    @Override
    public ClusterGenerationSnapshot project(ProjectionInput input) {
        var epoch = Epoch.epoch(input.rabiaTerm(), input.localCounter());
        var coreMembers = projectCoreMembers(input);
        var nodesWithoutSlices = deriveNodesWithoutSlices(coreMembers.keySet(), input.nodesWithArtifacts());
        var spokesmanIndex = buildSpokesmanIndex(input.spokesmen());
        var communities = projectCommunities(input, spokesmanIndex);
        var partitions = projectPartitions(input);
        var mode = deriveMode(communities);
        var pendingRebalanceCount = countPendingSpokesmanRebalance(communities);
        var cluster = deriveClusterQuiescence(coreMembers, communities, pendingRebalanceCount);

        return clusterGenerationSnapshot(epoch,
                                         input.now(),
                                         input.reason(),
                                         input.desiredCoreSize(),
                                         coreMembers,
                                         nodesWithoutSlices,
                                         communities,
                                         partitions,
                                         mode,
                                         cluster.quiescence(),
                                         cluster.detail());
    }

    private static Set<NodeId> deriveNodesWithoutSlices(Set<NodeId> coreMemberIds, Set<NodeId> nodesWithArtifacts) {
        return coreMemberIds.stream()
                            .filter(nodeId -> !nodesWithArtifacts.contains(nodeId))
                            .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<NodeId, CoreMember> projectCoreMembers(ProjectionInput input) {
        var result = new LinkedHashMap<NodeId, CoreMember>();
        input.lifecycles().forEach((nodeId, lifecycle) -> result.put(nodeId, toCoreMember(nodeId, lifecycle, input)));

        return Map.copyOf(result);
    }

    private static CoreMember toCoreMember(NodeId nodeId, MemberLifecycle lifecycle, ProjectionInput input) {
        var lastSeen = input.lastSeenPerNode().getOrDefault(nodeId, Epoch.ZERO);
        var healthHint = deriveHealthHint(nodeId, input.swimHints());

        return CoreMember.coreMember(nodeId,
                                     lifecycle.host(),
                                     lifecycle.port(),
                                     healthHint,
                                     Epoch.ZERO,
                                     lastSeen);
    }

    /// Presence-derived health: every member is healthy by construction (presence in the NTT
    /// set means SWIM-healthy). A SWIM hint, when present, can only downgrade the displayed
    /// health, so it wins over the HEALTHY baseline.
    private static HealthHint deriveHealthHint(NodeId nodeId, Map<NodeId, HealthHint> swimHints) {
        var swimHint = swimHints.get(nodeId);

        if (swimHint == null) {return HealthHint.HEALTHY;}

        return swimHint;
    }

    private static Map<String, NodeId> buildSpokesmanIndex(Map<NodeId, SpokesmanValue> spokesmen) {
        return spokesmen.entrySet()
                        .stream()
                        .flatMap(entry -> entry.getValue()
                                               .communities()
                                               .stream()
                                               .map(c -> Map.entry(c,
                                                                   entry.getKey())))
                        .collect(Collectors.toMap(Map.Entry::getKey,
                                                  Map.Entry::getValue,
                                                  (a, b) -> a));
    }

    private static Map<String, CommunitySummary> projectCommunities(ProjectionInput input,
                                                                    Map<String, NodeId> spokesmanIndex) {
        var result = new LinkedHashMap<String, CommunitySummary>();
        input.governors().forEach((communityId, announcement) -> result.put(communityId,
                                                                            toCommunitySummary(communityId,
                                                                                               announcement,
                                                                                               input,
                                                                                               spokesmanIndex)));

        return Map.copyOf(result);
    }

    private static CommunitySummary toCommunitySummary(String communityId,
                                                       GovernorAnnouncementValue announcement,
                                                       ProjectionInput input,
                                                       Map<String, NodeId> spokesmanIndex) {
        var lastAck = input.lastAckPerCommunity().getOrDefault(communityId, Epoch.ZERO);
        var assignedSpokesman = Option.option(spokesmanIndex.get(communityId));
        var partitionsForCommunity = collectPartitionIdsForCommunity(communityId, input.partitions());
        var healthyMembers = announcement.dissolved()
                             ? 0
                             : announcement.memberCount();
        var quiescence = deriveCommunityQuiescence(announcement, lastAck);

        return CommunitySummary.communitySummary(communityId,
                                                 announcement.governorId(),
                                                 announcement.communityTerm(),
                                                 announcement.communityEpoch(),
                                                 announcement.memberCount(),
                                                 healthyMembers,
                                                 0,
                                                 0,
                                                 partitionsForCommunity,
                                                 assignedSpokesman,
                                                 lastAck,
                                                 quiescence.state(),
                                                 quiescence.detail());
    }

    private static Set<String> collectPartitionIdsForCommunity(String communityId,
                                                               Map<String, DhtPartitionOwnershipValue> partitions) {
        return partitions.entrySet()
                         .stream()
                         .filter(entry -> communityId.equals(entry.getValue().ownerCommunityId()))
                         .map(Map.Entry::getKey)
                         .collect(Collectors.toUnmodifiableSet());
    }

    private static ClusterQuiescenceEvaluator.CommunityResult deriveCommunityQuiescence(GovernorAnnouncementValue announcement, Epoch lastAckAtCore) {
        return ClusterQuiescenceEvaluator.evaluateCommunity(announcement, lastAckAtCore);
    }

    private static Map<String, PartitionOwner> projectPartitions(ProjectionInput input) {
        var result = new LinkedHashMap<String, PartitionOwner>();
        input.partitions().forEach((partitionId, value) -> result.put(partitionId,
                                                                      PartitionOwner.partitionOwner(partitionId,
                                                                                                    value.ownerNodeId(),
                                                                                                    value.ownerCommunityId(),
                                                                                                    value.ownerEpoch(),
                                                                                                    value.ownershipTerm())));

        return Map.copyOf(result);
    }

    private static ClusterMode deriveMode(Map<String, CommunitySummary> communities) {
        return communities.keySet()
                          .stream()
                          .anyMatch(id -> !"core".equals(id))
               ? ClusterMode.HIERARCHICAL
               : ClusterMode.CORE_ONLY;
    }

    private static int countPendingSpokesmanRebalance(Map<String, CommunitySummary> communities) {
        return (int) communities.values()
                                .stream()
                                .filter(c -> c.assignedSpokesman()
                                              .isEmpty())
                                .count();
    }

    private static ClusterQuiescenceEvaluator.ClusterResult deriveClusterQuiescence(Map<NodeId, CoreMember> coreMembers,
                                                         Map<String, CommunitySummary> communities,
                                                         int pendingRebalanceCount) {
        var memberHealths = coreMembers.values()
                                       .stream()
                                       .map(CoreMember::healthHint)
                                       .toList();
        var communityStates = communities.values()
                                         .stream()
                                         .map(CommunitySummary::quiescence)
                                         .toList();

        return ClusterQuiescenceEvaluator.evaluateCluster(memberHealths, communityStates, pendingRebalanceCount);
    }
}
