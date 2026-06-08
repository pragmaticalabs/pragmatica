// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.ManagementApiResponses.ClusterGenerationCommunity;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterGenerationCore;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterGenerationHealth;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterGenerationMember;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterGenerationPartition;
import org.pragmatica.aether.api.ManagementApiResponses.ClusterGenerationResponse;
import org.pragmatica.aether.api.ManagementApiResponses.EpochInfo;
import org.pragmatica.aether.deployment.generation.ClusterQuiescenceEvaluator;
import org.pragmatica.aether.deployment.generation.ClusterQuiescenceEvaluator.CommunityResult;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.generation.ClusterMode;
import org.pragmatica.aether.slice.generation.CommunityQuiescence;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DhtPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SpokesmanKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Live assembler for the cluster-generation STATUS view. Builds [`ClusterGenerationResponse`]
/// directly from the per-node [`MembershipFsm`], the KV snapshot and the observed ping epoch —
/// no KV-persisted generation snapshot is read. Replicates the wire-shape semantics of the
/// former generation projector + snapshot-route mappers: members from FSM core set, health
/// from TTL-decayed hints (defaulting HEALTHY), communities/partitions/desiredSize from KV, and
/// cluster/community quiescence from the pure [`ClusterQuiescenceEvaluator`]. Pure w.r.t. inputs.
public sealed interface ClusterGenerationAssembler {
    String MODE_UNKNOWN = "unknown";
    String QUIESCENCE_UNKNOWN = "UNKNOWN";
    String STATUS_PRESENT = "PRESENT";
    String CORE_COMMUNITY_ID = "core";

    static ClusterGenerationResponse assemble(ManageableNode node) {
        var fsm = node.membershipFsm();
        var epoch = node.currentGenerationEpoch();
        var kvStore = node.kvStore();

        return isEmpty(fsm, epoch)
               ? emptyResponse()
               : buildResponse(fsm, epoch, kvStore);
    }

    private static boolean isEmpty(MembershipFsm fsm, Epoch epoch) {
        return fsm.coreMembers().isEmpty() && epoch.equals(Epoch.ZERO);
    }

    private static ClusterGenerationResponse emptyResponse() {
        return new ClusterGenerationResponse(Option.none(),
                                             0L,
                                             MODE_UNKNOWN,
                                             QUIESCENCE_UNKNOWN,
                                             "",
                                             new ClusterGenerationCore(0, List.of()),
                                             List.of(),
                                             List.of());
    }

    private static ClusterGenerationResponse buildResponse(MembershipFsm fsm,
                                                           Epoch epoch,
                                                           KVStore<AetherKey, AetherValue> kvStore) {
        var governors = collectGovernors(kvStore);
        var partitionValues = collectPartitions(kvStore);
        var spokesmanIndex = buildSpokesmanIndex(collectSpokesmen(kvStore));
        var communities = buildCommunities(governors, partitionValues, spokesmanIndex);
        var cluster = evaluateCluster(fsm, governors, spokesmanIndex);

        return new ClusterGenerationResponse(Option.some(toEpochInfo(epoch)),
                                             epoch.rabiaTerm(),
                                             deriveMode(governors.keySet()),
                                             cluster.quiescence().name(),
                                             cluster.detail(),
                                             buildCore(fsm, kvStore),
                                             communities,
                                             buildPartitions(partitionValues));
    }

    /// Narrow LIVE cluster-quiescence computation for the hot poll path (avoids full response assembly).
    /// Reads only the inputs that drive cluster quiescence: FSM health hints, per-community quiescence
    /// states from KV governor announcements, and the count of communities with no assigned spokesman
    /// (pending rebalance). Uses typed KV scans (no full-store copy) — for a core-only cluster with zero
    /// governors this reduces to FSM health + epoch only. Identical semantics to the cluster-quiescence
    /// fields of [`#assemble`].
    static ClusterQuiescenceEvaluator.ClusterResult clusterQuiescence(ManageableNode node) {
        var kvStore = node.kvStore();
        var governors = collectGovernors(kvStore);
        var spokesmanIndex = buildSpokesmanIndex(collectSpokesmen(kvStore));

        return evaluateCluster(node.membershipFsm(), governors, spokesmanIndex);
    }

    // --- Members / core ---

    private static ClusterGenerationCore buildCore(MembershipFsm fsm, KVStore<AetherKey, AetherValue> kvStore) {
        var members = buildMembers(fsm);

        return new ClusterGenerationCore(collectDesiredCoreSize(kvStore).or(members.size()), members);
    }

    private static List<ClusterGenerationMember> buildMembers(MembershipFsm fsm) {
        var descriptors = fsm.memberDescriptors();
        var hints = fsm.healthHints();

        return fsm.coreMembers()
                  .stream()
                  .map(nodeId -> toMember(nodeId, descriptors.get(nodeId), hints))
                  .toList();
    }

    private static ClusterGenerationMember toMember(NodeId nodeId,
                                                    MemberDescriptor descriptor,
                                                    Map<NodeId, HealthHint> hints) {
        var resolved = Option.option(descriptor).or(MemberDescriptor.UNKNOWN);
        var address = resolved.address();

        return new ClusterGenerationMember(nodeId.id(),
                                           address.map(NodeAddress::host).or(""),
                                           address.map(NodeAddress::port).or(0),
                                           STATUS_PRESENT,
                                           deriveHealthHint(nodeId, hints).name(),
                                           toEpochInfo(Epoch.ZERO),
                                           toEpochInfo(Epoch.ZERO));
    }

    private static HealthHint deriveHealthHint(NodeId nodeId, Map<NodeId, HealthHint> hints) {
        return Option.option(hints.get(nodeId)).or(HealthHint.HEALTHY);
    }

    // --- Communities ---

    private static List<ClusterGenerationCommunity> buildCommunities(Map<String, GovernorAnnouncementValue> governors,
                                                                    Map<String, DhtPartitionOwnershipValue> partitions,
                                                                    Map<String, NodeId> spokesmanIndex) {
        return governors.entrySet()
                        .stream()
                        .map(entry -> toCommunity(entry.getKey(), entry.getValue(), partitions, spokesmanIndex))
                        .toList();
    }

    private static ClusterGenerationCommunity toCommunity(String communityId,
                                                          GovernorAnnouncementValue announcement,
                                                          Map<String, DhtPartitionOwnershipValue> partitions,
                                                          Map<String, NodeId> spokesmanIndex) {
        var quiescence = ClusterQuiescenceEvaluator.evaluateCommunity(announcement, Epoch.ZERO);

        return new ClusterGenerationCommunity(communityId,
                                              announcement.governorId().id(),
                                              announcement.communityTerm(),
                                              toEpochInfo(announcement.communityEpoch()),
                                              announcement.memberCount(),
                                              toCommunityHealth(announcement),
                                              collectPartitionIdsForCommunity(communityId, partitions),
                                              toEpochInfo(Epoch.ZERO),
                                              quiescence.state().name(),
                                              quiescence.detail());
    }

    private static ClusterGenerationHealth toCommunityHealth(GovernorAnnouncementValue announcement) {
        return new ClusterGenerationHealth(announcement.dissolved() ? 0 : announcement.memberCount(), 0, 0);
    }

    private static List<String> collectPartitionIdsForCommunity(String communityId,
                                                               Map<String, DhtPartitionOwnershipValue> partitions) {
        return partitions.entrySet()
                         .stream()
                         .filter(entry -> communityId.equals(entry.getValue().ownerCommunityId()))
                         .map(Map.Entry::getKey)
                         .toList();
    }

    // --- Partitions ---

    private static List<ClusterGenerationPartition> buildPartitions(Map<String, DhtPartitionOwnershipValue> partitions) {
        return partitions.entrySet()
                         .stream()
                         .map(entry -> toPartition(entry.getKey(), entry.getValue()))
                         .toList();
    }

    private static ClusterGenerationPartition toPartition(String partitionId, DhtPartitionOwnershipValue value) {
        return new ClusterGenerationPartition(partitionId,
                                              value.ownerNodeId().id(),
                                              value.ownerCommunityId(),
                                              toEpochInfo(value.ownerEpoch()),
                                              value.ownershipTerm());
    }

    // --- Cluster quiescence / mode ---

    private static ClusterQuiescenceEvaluator.ClusterResult evaluateCluster(MembershipFsm fsm,
                                                                           Map<String, GovernorAnnouncementValue> governors,
                                                                           Map<String, NodeId> spokesmanIndex) {
        return ClusterQuiescenceEvaluator.evaluateCluster(fsm.healthHints().values(),
                                                          communityStates(governors),
                                                          countPendingSpokesmanRebalance(governors, spokesmanIndex));
    }

    private static List<CommunityQuiescence> communityStates(Map<String, GovernorAnnouncementValue> governors) {
        return governors.values()
                        .stream()
                        .map(ClusterGenerationAssembler::communityState)
                        .toList();
    }

    private static CommunityQuiescence communityState(GovernorAnnouncementValue announcement) {
        return communityResult(announcement).state();
    }

    private static CommunityResult communityResult(GovernorAnnouncementValue announcement) {
        return ClusterQuiescenceEvaluator.evaluateCommunity(announcement, Epoch.ZERO);
    }

    private static int countPendingSpokesmanRebalance(Map<String, GovernorAnnouncementValue> governors,
                                                     Map<String, NodeId> spokesmanIndex) {
        return (int) governors.keySet()
                              .stream()
                              .filter(communityId -> !spokesmanIndex.containsKey(communityId))
                              .count();
    }

    private static String deriveMode(Set<String> communityIds) {
        return communityIds.stream().anyMatch(id -> !CORE_COMMUNITY_ID.equals(id))
               ? ClusterMode.HIERARCHICAL.name()
               : ClusterMode.CORE_ONLY.name();
    }

    // --- KV enumeration (typed scans, no full-store copy) ---

    private static Map<String, GovernorAnnouncementValue> collectGovernors(KVStore<AetherKey, AetherValue> kvStore) {
        var result = new LinkedHashMap<String, GovernorAnnouncementValue>();

        kvStore.forEach(GovernorAnnouncementKey.class,
                        GovernorAnnouncementValue.class,
                        (key, value) -> result.put(key.communityId(), value));
        return result;
    }

    private static Map<String, DhtPartitionOwnershipValue> collectPartitions(KVStore<AetherKey, AetherValue> kvStore) {
        var result = new LinkedHashMap<String, DhtPartitionOwnershipValue>();

        kvStore.forEach(DhtPartitionOwnershipKey.class,
                        DhtPartitionOwnershipValue.class,
                        (key, value) -> result.put(key.partitionId(), value));
        return result;
    }

    private static Map<NodeId, SpokesmanValue> collectSpokesmen(KVStore<AetherKey, AetherValue> kvStore) {
        var result = new LinkedHashMap<NodeId, SpokesmanValue>();

        kvStore.forEach(SpokesmanKey.class, SpokesmanValue.class, (key, value) -> result.put(key.coreNodeId(), value));
        return result;
    }

    private static Map<String, NodeId> buildSpokesmanIndex(Map<NodeId, SpokesmanValue> spokesmen) {
        return spokesmen.entrySet()
                        .stream()
                        .flatMap(entry -> communityEntries(entry.getKey(), entry.getValue()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
    }

    private static Stream<Map.Entry<String, NodeId>> communityEntries(NodeId nodeId, SpokesmanValue value) {
        return value.communities().stream().map(community -> Map.entry(community, nodeId));
    }

    private static Option<Integer> collectDesiredCoreSize(KVStore<AetherKey, AetherValue> kvStore) {
        return kvStore.getTyped(ClusterConfigKey.CURRENT, ClusterConfigValue.class).map(ClusterConfigValue::coreCount);
    }

    private static EpochInfo toEpochInfo(Epoch epoch) {
        return new EpochInfo(epoch.rabiaTerm(), epoch.localCounter());
    }

    record unused() implements ClusterGenerationAssembler {}
}
