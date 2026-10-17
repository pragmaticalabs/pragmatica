// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.health;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.lang.Option;


/// Wires the bounded observation protocol independently of consensus commit or metrics tick cadence.
public record CommunityHealthRuntime(NodeId self,
                                     CommunityMemberDirectory directory,
                                     CommunityHealthIndex index,
                                     CommunityHealthReporter reporter,
                                     Function<String, Option<GovernorAnnouncementValue>> authority,
                                     BooleanSupplier core,
                                     Supplier<String> lifecycle,
                                     LongSupplier incarnation,
                                     BiConsumer<NodeId, ProtocolMessage> send,
                                     Consumer<CommunityHealthIndex.GovernorEvidence> positiveEvidence) {
    public org.pragmatica.lang.Unit poll() {
        reporter.recordSelf(lifecycle.get(), incarnation.getAsLong());
        if (!core.getAsBoolean()) {
            return org.pragmatica.lang.Unit.unit();
        }

        var communities = directory.communities();

        index.retainCommunities(communities);
        communities.forEach(community -> index.request(community)
                                              .onPresent(request -> authority.apply(community)
                                                                             .filter(value -> !value.dissolved() && value.communityTerm() == request.governorTerm())
                                                                             .onPresent(value -> send.accept(value.governorId(),
                                                                                                             request))));

        return org.pragmatica.lang.Unit.unit();
    }

    public org.pragmatica.lang.Unit onRequest(CommunityHealthMessage.Request request) {
        reporter.recordSelf(lifecycle.get(), incarnation.getAsLong());
        reporter.respond(request.sender(), request).onPresent(report -> send.accept(request.sender(), report));

        return org.pragmatica.lang.Unit.unit();
    }

    public org.pragmatica.lang.Unit onReport(CommunityHealthMessage.Report report) {
        if (core.getAsBoolean() && index.acceptAuthenticated(report)) {
            index.positiveEvidence(report.communityId()).forEach(positiveEvidence);
        }

        return org.pragmatica.lang.Unit.unit();
    }

    public Set<NodeId> readyNodes(Set<NodeId> directReady) {
        var result = withoutAssignedWorkers(directReady);

        directory.communities()
                 .forEach(community -> directory.members(community)
                                                .stream()
                                                .filter(index::isReady)
                                                .forEach(result::add));

        return Set.copyOf(result);
    }

    public Set<NodeId> aliveNodes(Set<NodeId> directAlive) {
        var result = withoutAssignedWorkers(directAlive);

        directory.communities()
                 .forEach(community -> index.positiveEvidence(community)
                                            .forEach(value -> result.add(value.member().node())));

        return Set.copyOf(result);
    }

    private HashSet<NodeId> withoutAssignedWorkers(Set<NodeId> nodes) {
        var result = new HashSet<NodeId>();

        nodes.stream().filter(node -> directory.assignment(node)
                                               .isEmpty()).forEach(result::add);

        return result;
    }
}
