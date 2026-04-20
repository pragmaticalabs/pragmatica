// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.ClusterMode;
import org.pragmatica.aether.slice.generation.ClusterQuiescence;
import org.pragmatica.aether.slice.generation.CommunityQuiescence;
import org.pragmatica.aether.slice.generation.CommunitySummary;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.PartitionOwner;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot.clusterGenerationSnapshot;


/// Stateless projector that builds a `ClusterGenerationSnapshot` from the currently-committed atoms.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §3 / §6 / §15.6.
///
/// Dormant in Commit 2 — constructed but not yet wired into cluster bootstrap.
public interface ClusterGenerationProjector {
    ClusterGenerationSnapshot project(ProjectionInput input);

    record ProjectionInput(long rabiaTerm,
                           long localCounter,
                           int desiredCoreSize,
                           GenerationReason reason,
                           HlcTimestamp now,
                           Map<NodeId, NodeLifecycleValue> lifecycles,
                           Map<String, GovernorAnnouncementValue> governors,
                           Map<String, DhtPartitionOwnershipValue> partitions,
                           Map<NodeId, SpokesmanValue> spokesmen,
                           Map<NodeId, Epoch> lastSeenPerNode,
                           Map<String, Epoch> lastAckPerCommunity,
                           Map<String, SliceTargetValue> sliceTargets,
                           Set<NodeId> nodesWithArtifacts) {
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
        }

        public static ProjectionInput projectionInput(long rabiaTerm,
                                                      long localCounter,
                                                      int desiredCoreSize,
                                                      GenerationReason reason,
                                                      HlcTimestamp now,
                                                      Map<NodeId, NodeLifecycleValue> lifecycles,
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
                                                      Map<NodeId, NodeLifecycleValue> lifecycles,
                                                      Map<String, GovernorAnnouncementValue> governors,
                                                      Map<String, DhtPartitionOwnershipValue> partitions,
                                                      Map<NodeId, SpokesmanValue> spokesmen,
                                                      Map<NodeId, Epoch> lastSeenPerNode,
                                                      Map<String, Epoch> lastAckPerCommunity,
                                                      Map<String, SliceTargetValue> sliceTargets,
                                                      Set<NodeId> nodesWithArtifacts) {
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
                                       nodesWithArtifacts);
        }
    }

    static ClusterGenerationProjector clusterGenerationProjector() {
        return ClusterGenerationProjectorRecord.INSTANCE;
    }
}

/// Pure projection implementation — no state, no side effects.
record ClusterGenerationProjectorRecord() implements ClusterGenerationProjector {
    static final ClusterGenerationProjectorRecord INSTANCE = new ClusterGenerationProjectorRecord();

    @Override public ClusterGenerationSnapshot project(ProjectionInput input) {
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
        return coreMemberIds.stream().filter(nodeId -> !nodesWithArtifacts.contains(nodeId))
                                   .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<NodeId, CoreMember> projectCoreMembers(ProjectionInput input) {
        var result = new LinkedHashMap<NodeId, CoreMember>();
        input.lifecycles().forEach((nodeId, lifecycle) -> result.put(nodeId, toCoreMember(nodeId, lifecycle, input)));
        return Map.copyOf(result);
    }

    private static CoreMember toCoreMember(NodeId nodeId, NodeLifecycleValue lifecycle, ProjectionInput input) {
        var lastSeen = input.lastSeenPerNode().getOrDefault(nodeId, Epoch.ZERO);
        var healthHint = deriveHealthHint(lifecycle);
        return CoreMember.coreMember(nodeId,
                                     lifecycle.host(),
                                     lifecycle.port(),
                                     lifecycle.state(),
                                     healthHint,
                                     lifecycle.observedCoreEpoch(),
                                     lastSeen,
                                     lifecycle.provisioningSource());
    }

    private static HealthHint deriveHealthHint(NodeLifecycleValue lifecycle) {
        return switch (lifecycle.state()){
            case DECOMMISSIONED, SHUTTING_DOWN -> HealthHint.FAULTY;
            case DRAINING -> HealthHint.SUSPECTED;
            case JOINING, ON_DUTY -> HealthHint.HEALTHY;
        };
    }

    private static Map<String, NodeId> buildSpokesmanIndex(Map<NodeId, SpokesmanValue> spokesmen) {
        return spokesmen.entrySet().stream()
                                 .flatMap(entry -> entry.getValue().communities()
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
        input.governors()
                       .forEach((communityId, announcement) -> result.put(communityId,
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
        var quiescence = deriveCommunityQuiescence(announcement, lastAck, 0, 0);
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
        return partitions.entrySet().stream()
                                  .filter(entry -> communityId.equals(entry.getValue().ownerCommunityId()))
                                  .map(Map.Entry::getKey)
                                  .collect(Collectors.toUnmodifiableSet());
    }

    private static CommunityResult deriveCommunityQuiescence(GovernorAnnouncementValue announcement,
                                                             Epoch lastAckAtCore,
                                                             int suspected,
                                                             int faulty) {
        if (announcement.dissolved()) {return new CommunityResult(CommunityQuiescence.DISSOLVING, "community dissolved");}
        if (suspected > 0 || faulty > 0) {return new CommunityResult(CommunityQuiescence.DEGRADED,
                                                                     suspected + " suspected, " + faulty + " faulty");}
        if (lastAckAtCore.compareTo(announcement.communityEpoch()) <0) {return new CommunityResult(CommunityQuiescence.CONVERGING,
                                                                                                   "governor has not acked epoch " + announcement.communityEpoch());}
        return new CommunityResult(CommunityQuiescence.QUIESCED, "");
    }

    private static Map<String, PartitionOwner> projectPartitions(ProjectionInput input) {
        var result = new LinkedHashMap<String, PartitionOwner>();
        input.partitions()
                        .forEach((partitionId, value) -> result.put(partitionId,
                                                                    PartitionOwner.partitionOwner(partitionId,
                                                                                                  value.ownerNodeId(),
                                                                                                  value.ownerCommunityId(),
                                                                                                  value.ownerEpoch(),
                                                                                                  value.ownershipTerm())));
        return Map.copyOf(result);
    }

    private static ClusterMode deriveMode(Map<String, CommunitySummary> communities) {
        return communities.keySet().stream()
                                 .anyMatch(id -> !"core".equals(id))
              ? ClusterMode.HIERARCHICAL
              : ClusterMode.CORE_ONLY;
    }

    private static int countPendingSpokesmanRebalance(Map<String, CommunitySummary> communities) {
        return (int) communities.values().stream()
                                       .filter(c -> c.assignedSpokesman().isEmpty())
                                       .count();
    }

    private static ClusterResult deriveClusterQuiescence(Map<NodeId, CoreMember> coreMembers,
                                                         Map<String, CommunitySummary> communities,
                                                         int pendingRebalanceCount) {
        var memberSnapshot = summarizeMembers(coreMembers);
        var communitySnapshot = summarizeCommunities(communities);
        if (memberSnapshot.hasFaulty() || memberSnapshot.hasSuspected() || communitySnapshot.hasDegraded()) {return new ClusterResult(ClusterQuiescence.DEGRADED,
                                                                                                                                      buildDegradedDetail(memberSnapshot,
                                                                                                                                                          communitySnapshot));}
        if (communitySnapshot.hasConverging() || pendingRebalanceCount > 0) {return new ClusterResult(ClusterQuiescence.CONVERGING,
                                                                                                      buildConvergingDetail(communitySnapshot,
                                                                                                                            pendingRebalanceCount));}
        return new ClusterResult(ClusterQuiescence.QUIESCED, "");
    }

    private static MemberSnapshot summarizeMembers(Map<NodeId, CoreMember> coreMembers) {
        var faulty = 0;
        var suspected = 0;
        for (var member : coreMembers.values()) {
            if (member.healthHint() == HealthHint.FAULTY) {faulty++;}
            if (member.healthHint() == HealthHint.SUSPECTED) {suspected++;}
        }
        return new MemberSnapshot(faulty, suspected);
    }

    private static CommunityStatusSnapshot summarizeCommunities(Map<String, CommunitySummary> communities) {
        var degraded = 0;
        var converging = 0;
        var dissolving = 0;
        for (var community : communities.values()) {switch (community.quiescence()){
            case DEGRADED -> degraded++;
            case CONVERGING -> converging++;
            case DISSOLVING -> dissolving++;
            case QUIESCED -> {}
        }}
        return new CommunityStatusSnapshot(degraded, converging, dissolving);
    }

    private static String buildDegradedDetail(MemberSnapshot members, CommunityStatusSnapshot communities) {
        var parts = new ArrayList<String>();
        if (members.faulty() > 0) {parts.add(members.faulty() + " members FAULTY");}
        if (members.suspected() > 0) {parts.add(members.suspected() + " members SUSPECTED");}
        if (communities.degraded() > 0) {parts.add(communities.degraded() + " communities DEGRADED");}
        if (communities.dissolving() > 0) {parts.add(communities.dissolving() + " communities DISSOLVING");}
        return String.join("; ", parts);
    }

    private static String buildConvergingDetail(CommunityStatusSnapshot communities, int pendingRebalance) {
        var parts = new ArrayList<String>();
        if (communities.converging() > 0) {parts.add(communities.converging() + " communities CONVERGING");}
        if (pendingRebalance > 0) {parts.add(pendingRebalance + " communities awaiting spokesman");}
        return String.join("; ", parts);
    }

    private record CommunityResult(CommunityQuiescence state, String detail){}

    private record ClusterResult(ClusterQuiescence quiescence, String detail){}

    private record MemberSnapshot(int faulty, int suspected) {
        boolean hasFaulty() {
            return faulty > 0;
        }

        boolean hasSuspected() {
            return suspected > 0;
        }
    }

    private record CommunityStatusSnapshot(int degraded, int converging, int dissolving) {
        boolean hasDegraded() {
            return degraded > 0;
        }

        boolean hasConverging() {
            return converging > 0 || dissolving > 0;
        }
    }
}
