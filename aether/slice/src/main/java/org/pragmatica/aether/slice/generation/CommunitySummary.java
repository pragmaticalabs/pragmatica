// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import java.util.Set;


@Codec
public record CommunitySummary(String communityId,
                               NodeId governorNodeId,
                               long communityTerm,
                               Epoch communityEpoch,
                               int memberCount,
                               int healthyMembers,
                               int suspectedMembers,
                               int faultyMembers,
                               Set<String> partitions,
                               Option<NodeId> assignedSpokesman,
                               Epoch lastAckAtCore,
                               CommunityQuiescence quiescence,
                               String quiescenceDetail) {
    public CommunitySummary {
        partitions = Set.copyOf(partitions);
    }

    public static CommunitySummary communitySummary(String communityId,
                                                    NodeId governorNodeId,
                                                    long communityTerm,
                                                    Epoch communityEpoch,
                                                    int memberCount,
                                                    int healthyMembers,
                                                    int suspectedMembers,
                                                    int faultyMembers,
                                                    Set<String> partitions,
                                                    Option<NodeId> assignedSpokesman,
                                                    Epoch lastAckAtCore,
                                                    CommunityQuiescence quiescence,
                                                    String quiescenceDetail) {
        return new CommunitySummary(communityId,
                                    governorNodeId,
                                    communityTerm,
                                    communityEpoch,
                                    memberCount,
                                    healthyMembers,
                                    suspectedMembers,
                                    faultyMembers,
                                    partitions,
                                    assignedSpokesman,
                                    lastAckAtCore,
                                    quiescence,
                                    quiescenceDetail);
    }

    public CommunitySummary withAssignedSpokesman(Option<NodeId> newAssignedSpokesman) {
        return new CommunitySummary(communityId,
                                    governorNodeId,
                                    communityTerm,
                                    communityEpoch,
                                    memberCount,
                                    healthyMembers,
                                    suspectedMembers,
                                    faultyMembers,
                                    partitions,
                                    newAssignedSpokesman,
                                    lastAckAtCore,
                                    quiescence,
                                    quiescenceDetail);
    }

    public CommunitySummary withQuiescence(CommunityQuiescence newQuiescence, String newDetail) {
        return new CommunitySummary(communityId,
                                    governorNodeId,
                                    communityTerm,
                                    communityEpoch,
                                    memberCount,
                                    healthyMembers,
                                    suspectedMembers,
                                    faultyMembers,
                                    partitions,
                                    assignedSpokesman,
                                    lastAckAtCore,
                                    newQuiescence,
                                    newDetail);
    }

    public CommunitySummary withLastAckAtCore(Epoch newLastAckAtCore) {
        return new CommunitySummary(communityId,
                                    governorNodeId,
                                    communityTerm,
                                    communityEpoch,
                                    memberCount,
                                    healthyMembers,
                                    suspectedMembers,
                                    faultyMembers,
                                    partitions,
                                    assignedSpokesman,
                                    newLastAckAtCore,
                                    quiescence,
                                    quiescenceDetail);
    }
}
