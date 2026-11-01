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
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;


/// The core leader allocates authority; local worker election only nominates a candidate.
public interface GovernorAuthority {
    /// A guess, not a measurement: long enough to gather the announcements one membership event provokes (the SWIM
    /// edges of one failure reach the governors within a few milliseconds of each other), far below the 30 s
    /// re-announce period and the 60 s freshness window the announcement serves, so the delay costs nothing observable.
    TimeSpan DEFAULT_COMMIT_WINDOW = TimeSpan.timeSpan(50).millis();

    /// A guess: one command list stays small enough to apply in one step; a window that fills is submitted at once.
    int DEFAULT_COMMIT_CAP = 16;

    Promise<GovernorAuthorityMessage.Response> handle(GovernorAuthorityMessage.Request request);

    /// Core-initiated recovery after the reporting contract expires; no worker request is forged.
    Promise<Option<GovernorAnnouncementValue>> reconcile(String community,
                                                         NodeId candidate,
                                                         long expectedTerm,
                                                         String address);

    /// Unbatched: each announcement transaction is its own submission. The core's wiring passes the batching window.
    static GovernorAuthority governorAuthority(NodeId self,
                                               ClusterNode<KVCommand<AetherKey>> cluster,
                                               KVStore<AetherKey, AetherValue> store,
                                               BooleanSupplier isActiveLeader,
                                               Function<String, List<NodeId>> eligibleMembers,
                                               Predicate<NodeId> candidateEligible,
                                               Supplier<Epoch> coreEpoch,
                                               HlcClock clock) {
        return governorAuthority(self,
                                 cluster,
                                 store,
                                 isActiveLeader,
                                 eligibleMembers,
                                 candidateEligible,
                                 coreEpoch,
                                 clock,
                                 DEFAULT_COMMIT_WINDOW,
                                 1);
    }

    /// `window` and `cap` bound how long and how many announcement transactions are gathered into one submission to
    /// the core's log; a `cap` of 1 submits each one on its own (see [GovernorCommitWindow]).
    static GovernorAuthority governorAuthority(NodeId self,
                                               ClusterNode<KVCommand<AetherKey>> cluster,
                                               KVStore<AetherKey, AetherValue> store,
                                               BooleanSupplier isActiveLeader,
                                               Function<String, List<NodeId>> eligibleMembers,
                                               Predicate<NodeId> candidateEligible,
                                               Supplier<Epoch> coreEpoch,
                                               HlcClock clock,
                                               TimeSpan window,
                                               int cap) {
        record authority(NodeId self,
                         ClusterNode<KVCommand<AetherKey>> cluster,
                         KVStore<AetherKey, AetherValue> store,
                         BooleanSupplier isActiveLeader,
                         Function<String, List<NodeId>> eligibleMembers,
                         Predicate<NodeId> candidateEligible,
                         Supplier<Epoch> coreEpoch,
                         HlcClock clock,
                         GovernorCommitWindow commits) implements GovernorAuthority {
            @Override
            public Promise<GovernorAuthorityMessage.Response> handle(GovernorAuthorityMessage.Request request) {
                return grant(request.communityId(),
                             request.sender(),
                             request.expectedTerm(),
                             request.tcpAddress(),
                             false,
                             request.refresh()).map(value -> new GovernorAuthorityMessage.Response(self,
                                                                                                   request.communityId(),
                                                                                                   request.requestId(),
                                                                                                   value));
            }

            @Override
            public Promise<Option<GovernorAnnouncementValue>> reconcile(String community,
                                                                        NodeId candidate,
                                                                        long expectedTerm,
                                                                        String address) {
                return grant(community, candidate, expectedTerm, address, true, true);
            }

            private Promise<Option<GovernorAnnouncementValue>> grant(String communityId,
                                                                     NodeId nominee,
                                                                     long expectedTerm,
                                                                     String address,
                                                                     boolean recovery,
                                                                     boolean refresh) {
                return store.getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                            .filter(leader -> leader.leader()
                                                    .equals(self) && isActiveLeader.getAsBoolean())
                            .map(leader -> authorize(communityId,
                                                     nominee,
                                                     expectedTerm,
                                                     address,
                                                     recovery,
                                                     refresh,
                                                     leader))
                            .or(() -> Causes.cause("Governor authority requires the active committed core leader").promise());
            }

            private Promise<Option<GovernorAnnouncementValue>> authorize(String communityId,
                                                                         NodeId nominee,
                                                                         long expectedTerm,
                                                                         String address,
                                                                         boolean recovery,
                                                                         boolean refresh,
                                                                         LeaderValue leader) {
                var key = AetherKey.GovernorAnnouncementKey.forCommunity(communityId);
                var previous = store.getTyped(key, GovernorAnnouncementValue.class);
                var communityKey = AetherKey.CommunityKey.communityKey(communityId);
                var directiveKey = AetherKey.ActivationDirectiveKey.activationDirectiveKey(nominee);
                var community = store.getTyped(communityKey, AetherValue.CommunityValue.class)
                                     .filter(value -> value.state() != org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVING && value.state() != org.pragmatica.aether.slice.kvstore.CommunityState.DISSOLVED);
                var directive = store.getTyped(directiveKey, AetherValue.ActivationDirectiveValue.class)
                                     .filter(value -> value.role()
                                                           .equals(AetherValue.ActivationDirectiveValue.WORKER) && value.communityId()
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
                // #2542: an announcement equal to the committed one (all but its two time stamps) is no mutation unless
                // the governor's timer asks for the liveness refresh. This is the one commit path, so no caller bypasses it.
                if (!refresh && previous.filter(updated::unchangedFrom).isPresent()) {
                    return Promise.success(previous);
                }

                var ticket = commits.enqueue(key,
                                             nominee,
                                             id -> transaction(id, key, leader, communityKey, community, directiveKey, directive, previous, updated));

                return ticket.outcomes()
                             .map(outcomes -> accepted(outcomes, ticket.transactionId())
                                              ? store.getTyped(key, GovernorAnnouncementValue.class)
                                              : Option.none());
            }

            private KVCommand<AetherKey> transaction(String transactionId,
                                                     AetherKey key,
                                                     LeaderValue leader,
                                                     AetherKey communityKey,
                                                     Option<AetherValue.CommunityValue> community,
                                                     AetherKey directiveKey,
                                                     Option<AetherValue.ActivationDirectiveValue> directive,
                                                     Option<GovernorAnnouncementValue> previous,
                                                     GovernorAnnouncementValue updated) {
                return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                               transactionId,
                                                                               leader,
                                                                               List.of(new KVCommand.ReadWitness<>(communityKey,
                                                                                                                   community.map(value -> (Object) value)),
                                                                                       new KVCommand.ReadWitness<>(directiveKey,
                                                                                                                   directive.map(value -> (Object) value))),
                                                                               List.of(new KVCommand.Mutation<>(key,
                                                                                                                previous.map(value -> (AetherValue) value),
                                                                                                                Option.some(updated))));
            }

            private boolean accepted(List<Object> outcomes, String transactionId) {
                return outcomes.stream()
                               .anyMatch(outcome -> outcome instanceof KVCommand.TransactionResult result
                                                    && result.transactionId()
                                                             .equals(transactionId)
                                                    && result.accepted());
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

        return new authority(self,
                             cluster,
                             store,
                             isActiveLeader,
                             eligibleMembers,
                             candidateEligible,
                             coreEpoch,
                             clock,
                             new GovernorCommitWindow(commands -> cluster.<Object> apply(commands), window, cap));
    }
}
