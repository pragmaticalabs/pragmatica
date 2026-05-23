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
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.CommunitySummary;
import org.pragmatica.aether.slice.generation.CoreMember;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.PartitionOwner;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;


public final class ClusterGenerationRoutes implements RouteSource {
    private static final String MODE_UNKNOWN = "unknown";
    private static final String QUIESCENCE_UNKNOWN = "UNKNOWN";

    private final Supplier<ManageableNode> nodeSupplier;

    private ClusterGenerationRoutes(Supplier<ManageableNode> nodeSupplier) {
        this.nodeSupplier = nodeSupplier;
    }

    public static ClusterGenerationRoutes clusterGenerationRoutes(Supplier<ManageableNode> nodeSupplier) {
        return new ClusterGenerationRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ClusterGenerationResponse> route(ManagementRoute.CLUSTER_GENERATION).toJson(this::buildGenerationResponse));
    }

    private ClusterGenerationResponse buildGenerationResponse() {
        return nodeSupplier.get()
                           .currentGenerationSnapshot()
                           .map(ClusterGenerationRoutes::toResponse)
                           .or(ClusterGenerationRoutes::emptyResponse);
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

    private static ClusterGenerationResponse toResponse(ClusterGenerationSnapshot snapshot) {
        return new ClusterGenerationResponse(Option.some(toEpochInfo(snapshot.epoch())),
                                             snapshot.rabiaTerm(),
                                             snapshot.derivedMode().name(),
                                             snapshot.quiescence().name(),
                                             snapshot.quiescenceDetail(),
                                             toCore(snapshot),
                                             toCommunities(snapshot),
                                             toPartitions(snapshot));
    }

    private static ClusterGenerationCore toCore(ClusterGenerationSnapshot snapshot) {
        return new ClusterGenerationCore(snapshot.desiredCoreSize(), toMembers(snapshot));
    }

    private static List<ClusterGenerationMember> toMembers(ClusterGenerationSnapshot snapshot) {
        return snapshot.coreMembers()
                       .values()
                       .stream()
                       .map(ClusterGenerationRoutes::toMember)
                       .toList();
    }

    private static ClusterGenerationMember toMember(CoreMember member) {
        return new ClusterGenerationMember(member.nodeId().id(),
                                           member.host(),
                                           member.port(),
                                           member.lifecycle().name(),
                                           member.healthHint().name(),
                                           toEpochInfo(member.joinedEpoch()),
                                           toEpochInfo(member.lastSeenEpoch()));
    }

    private static List<ClusterGenerationCommunity> toCommunities(ClusterGenerationSnapshot snapshot) {
        return snapshot.communities()
                       .values()
                       .stream()
                       .map(ClusterGenerationRoutes::toCommunity)
                       .toList();
    }

    private static ClusterGenerationCommunity toCommunity(CommunitySummary summary) {
        return new ClusterGenerationCommunity(summary.communityId(),
                                              summary.governorNodeId().id(),
                                              summary.communityTerm(),
                                              toEpochInfo(summary.communityEpoch()),
                                              summary.memberCount(),
                                              new ClusterGenerationHealth(summary.healthyMembers(),
                                                                          summary.suspectedMembers(),
                                                                          summary.faultyMembers()),
                                              List.copyOf(summary.partitions()),
                                              toEpochInfo(summary.lastAckAtCore()),
                                              summary.quiescence().name(),
                                              summary.quiescenceDetail());
    }

    private static List<ClusterGenerationPartition> toPartitions(ClusterGenerationSnapshot snapshot) {
        return snapshot.partitions()
                       .values()
                       .stream()
                       .map(ClusterGenerationRoutes::toPartition)
                       .toList();
    }

    private static ClusterGenerationPartition toPartition(PartitionOwner owner) {
        return new ClusterGenerationPartition(owner.partitionId(),
                                              owner.ownerNodeId().id(),
                                              owner.ownerCommunityId(),
                                              toEpochInfo(owner.ownerEpoch()),
                                              owner.ownershipTerm());
    }

    private static EpochInfo toEpochInfo(Epoch epoch) {
        return new EpochInfo(epoch.rabiaTerm(), epoch.localCounter());
    }
}
