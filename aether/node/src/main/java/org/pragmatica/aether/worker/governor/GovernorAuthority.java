// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;


/// The core leader allocates authority; local worker election only nominates a candidate.
public interface GovernorAuthority {
    Promise<GovernorAuthorityMessage.Response> handle(GovernorAuthorityMessage.Request request);

    /// Core-initiated recovery after the reporting contract expires; no worker request is forged.
    Promise<Option<GovernorAnnouncementValue>> reconcile(String community,
                                                         NodeId candidate,
                                                         long expectedTerm,
                                                         String address);

    static GovernorAuthority governorAuthority(NodeId self,
                                               ClusterNode<KVCommand<AetherKey>> cluster,
                                               KVStore<AetherKey, AetherValue> store,
                                               BooleanSupplier isActiveLeader,
                                               Function<String, List<NodeId>> eligibleMembers,
                                               Predicate<NodeId> candidateEligible,
                                               Supplier<Epoch> coreEpoch,
                                               HlcClock clock) {
        record authority(NodeId self,
                         ClusterNode<KVCommand<AetherKey>> cluster,
                         KVStore<AetherKey, AetherValue> store,
                         BooleanSupplier isActiveLeader,
                         Function<String, List<NodeId>> eligibleMembers,
                         Predicate<NodeId> candidateEligible,
                         Supplier<Epoch> coreEpoch,
                         HlcClock clock) implements GovernorAuthority {
            @Override
            public Promise<GovernorAuthorityMessage.Response> handle(GovernorAuthorityMessage.Request request) {
                return grant(request.communityId(),
                             request.sender(),
                             request.expectedTerm(),
                             request.tcpAddress(),
                             false).map(value -> new GovernorAuthorityMessage.Response(self,
                                                                                       request.communityId(),
                                                                                       request.requestId(),
                                                                                       value));
            }

            @Override
            public Promise<Option<GovernorAnnouncementValue>> reconcile(String community,
                                                                        NodeId candidate,
                                                                        long expectedTerm,
                                                                        String address) {
                return grant(community, candidate, expectedTerm, address, true);
            }

            private Promise<Option<GovernorAnnouncementValue>> grant(String communityId,
                                                                     NodeId nominee,
                                                                     long expectedTerm,
                                                                     String address,
                                                                     boolean recovery) {
                return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                            .filter(leader -> leader.leader()
                                                    .equals(self) && isActiveLeader.getAsBoolean())
                            .map(leader -> authorize(communityId, nominee, expectedTerm, address, recovery, leader))
                            .or(() -> Causes.cause("Governor authority requires the active committed core leader").promise());
            }

            private Promise<Option<GovernorAnnouncementValue>> authorize(String communityId,
                                                                         NodeId nominee,
                                                                         long expectedTerm,
                                                                         String address,
                                                                         boolean recovery,
                                                                         LeaderValue leader) {
                var key = AetherKey.GovernorAnnouncementKey.forCommunity(communityId);
                var previous = store.getTyped(key, GovernorAnnouncementValue.class);
                var communityKey = AetherKey.CommunityKey.communityKey(communityId);
                var directiveKey = AetherKey.ActivationDirectiveKey.activationDirectiveKey(nominee);
                var community = store.getTyped(communityKey, AetherValue.CommunityValue.class)
                                     .filter(value -> value.state() != org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVING && value.state() != org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVED);
                var directive = store.getTyped(directiveKey, AetherValue.ActivationDirectiveValue.class)
                                     .filter(value -> (value.role()
                                                            .equals(AetherValue.ActivationDirectiveValue.WORKER) || "SPOT".equalsIgnoreCase(value.role())) && value.communityId()
                                                                                                                                                                   .equals(communityId));

                if (community.isEmpty() || directive.isEmpty()) {
                    return Promise.success(previous);
                }

                var members = eligibleMembers.apply(communityId).stream().distinct().sorted().toList();
                var candidate = previous.filter(value -> !value.dissolved()
                                                         && members.contains(value.governorId())
                                                         && candidateEligible.test(value.governorId()))
                                        .map(GovernorAnnouncementValue::governorId)
                                        .orElse(() -> Option.from(members.stream().filter(candidateEligible).findFirst()));

                if ((!recovery && !candidate.filter(nominee::equals).isPresent()) || !members.contains(nominee) || !candidateEligible.test(nominee) || expectedTerm != previous.map(GovernorAnnouncementValue::communityTerm)
                                                                                                                                                                               .or(0L)) {
                    return Promise.success(previous);
                }

                var updated = update(previous, nominee, address, members);
                var command = new KVCommand.LeaderPut<AetherKey, AetherValue>(key,
                                                                              previous.map(value -> (AetherValue) value),
                                                                              updated,
                                                                              leader,
                                                                              List.of(new KVCommand.ReadWitness<>(communityKey,
                                                                                                                  community.map(value -> (Object) value)),
                                                                                      new KVCommand.ReadWitness<>(directiveKey,
                                                                                                                  directive.map(value -> (Object) value))));

                return cluster.apply(List.<KVCommand<AetherKey>> of(command))
                              .map(_ -> store.getTyped(key, GovernorAnnouncementValue.class));
            }

            private GovernorAnnouncementValue update(Option<GovernorAnnouncementValue> previous,
                                                     NodeId nominee,
                                                     String address,
                                                     List<NodeId> members) {
                return previous.filter(value -> !value.dissolved() && value.governorId()
                                                                           .equals(nominee))
                               .map(value -> value.withMembers(members,
                                                               address,
                                                               coreEpoch.get()))
                               .or(() -> previous.or(() -> GovernorAnnouncementValue.governorAnnouncementValue(nominee,
                                                                                                               0))
                                                 .withGovernorChange(nominee,
                                                                     members,
                                                                     address,
                                                                     coreEpoch.get(),
                                                                     clock.now()));
            }
        }

        return new authority(self, cluster, store, isActiveLeader, eligibleMembers, candidateEligible, coreEpoch, clock);
    }
}
