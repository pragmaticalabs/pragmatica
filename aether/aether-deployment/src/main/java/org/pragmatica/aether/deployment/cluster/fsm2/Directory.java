// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Directives, communities and governor announcements (spec C13, C14, J5, B-066, B-069, B-070, R-4.3.3).
///
/// - A **directive** names a node's role; the FSM assigns a CORE directive to a joining non-seed core (J5) and a WORKER
///   directive, minting the community when needed, to a joining worker (B-069, B-070). It **never removes** one (R-4.3.1).
/// - A **community** is chosen by incumbency, then by the lowest fill ratio, counting only live, non-departed assignments
///   (R-3.3.9, N4-4, NT-39).
/// - An **announcement** refreshes the leader-local freshness clock of its community (R-2.14c).
public sealed interface Directory {
    // -- C13 -------------------------------------------------------------------------------------------------------
    static ClusterModel onDirectivePut(ClusterModel m, Ctx ctx, NodeId node, ActivationDirectiveValue value) {
        Indexer.indexDirective(m, node, value);
        Absence.refresh(m, ctx, node);

        return Reconcile.request(m, ctx);
    }

    /// C13 on Remove: a node that is departed gets R-4.3.3: its departure record goes with its rows, in one transaction.
    static ClusterModel onDirectiveRemove(ClusterModel m, Ctx ctx, NodeId node) {
        Indexer.removeDirective(m, node);
        Absence.clear(m, node);

        return m.departed.contains(node)
               ? removeDeparted(m, ctx, node)
               : Reconcile.request(m, ctx);
    }

    /// R-4.3.3: `Remove(NodeDepartureKey)` expecting the DEPARTED value, plus the observed rows at their exact values and any
    /// remaining desired and route keys.
    private static ClusterModel removeDeparted(ClusterModel m, Ctx ctx, NodeId node) {
        var record = m.records.get(node);
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        record.onPresent(value -> mutations.add(new KVCommand.Mutation<>(new NodeDepartureKey(node),
                                                                         Option.some((AetherValue) value),
                                                                         Option.none())));
        Idx.members(m.observedByNode, node)
           .toList()
           .stream()
           .sorted(Comparator.comparing(artifact -> artifact.asString()))
           .forEach(artifact -> m.observed.get(SlicePair.slicePair(node, artifact))
                                          .onPresent(row -> mutations.add(new KVCommand.Mutation<>(SlicePair.slicePair(node,
                                                                                                                       artifact).observedKey(),
                                                                                                   Option.some((AetherValue) row),
                                                                                                   Option.none()))));
        Idx.members(m.desiredByNode, node)
           .toList()
           .stream()
           .sorted(Comparator.comparing(artifact -> artifact.asString()))
           .forEach(artifact -> m.desired.get(SlicePair.slicePair(node, artifact))
                                         .onPresent(value -> mutations.add(new KVCommand.Mutation<>(SlicePair.slicePair(node,
                                                                                                                        artifact).desiredKey(),
                                                                                                    Option.some((AetherValue) value),
                                                                                                    Option.none()))));
        Idx.members(m.routesByNode, node)
           .toList()
           .stream()
           .sorted(Comparator.comparing(artifact -> artifact.asString()))
           .forEach(artifact -> m.routes.get(SlicePair.slicePair(node, artifact))
                                        .onPresent(value -> mutations.add(new KVCommand.Mutation<>(new AetherKey.NodeRoutesKey(node,
                                                                                                                               artifact),
                                                                                                   Option.some((AetherValue) value),
                                                                                                   Option.none()))));
        if (!mutations.isEmpty()) {
            Outbox.direct(m,
                          TxDraft.draft(SubmitKind.DIRECTIVE_REMOVAL,
                                        mutations.getFirst().key(),
                                        mutations)
                                 .withNode(node));
        }

        return Reconcile.request(m, ctx);
    }

    // -- C14, C11e: announcements and communities ------------------------------------------------------------------
    static ClusterModel onAnnouncementPut(ClusterModel m, Ctx ctx, String community, GovernorAnnouncementValue value) {
        var previous = rosterMembers(m, community);

        Indexer.indexAnnouncement(m, ctx, community, value);
        refreshCommunity(m, ctx, community, previous);

        return Reconcile.request(m, ctx);
    }

    static ClusterModel onAnnouncementRemove(ClusterModel m, Ctx ctx, String community) {
        var previous = rosterMembers(m, community);

        Indexer.removeAnnouncement(m, community);
        refreshCommunity(m, ctx, community, previous);

        return Reconcile.request(m, ctx);
    }

    static ClusterModel onCommunityPut(ClusterModel m, Ctx ctx, String community, CommunityValue value) {
        Indexer.indexCommunity(m, community, value);
        refreshCommunity(m, ctx, community, List.of());

        return Reconcile.request(m, ctx);
    }

    private static List<NodeId> rosterMembers(ClusterModel m, String community) {
        return m.rosters.get(community)
                        .map(GovernorAnnouncementValue::members)
                        .or(List.of());
    }

    /// Re-evaluate the absence of every node that belongs to, or is listed by, `community` (the previous roster included).
    private static ClusterModel refreshCommunity(ClusterModel m, Ctx ctx, String community, List<NodeId> previous) {
        var nodes = new TreeSet<NodeId>(Idx.members(m.assignedByCommunity, community).toList());

        nodes.addAll(previous);
        nodes.addAll(rosterMembers(m, community));
        nodes.forEach(node -> Absence.refresh(m, ctx, node));

        return m;
    }

    // -- J5: a core ------------------------------------------------------------------------------------------------
    /// J5 (core): non-seed only, and not for a superseded id (M-6). The caller (J6) has already established that no directive exists.
    static ClusterModel assignCore(ClusterModel m, Ctx ctx, NodeId node) {
        var assignable = !ctx.settings().seedNodes().contains(node) && !m.seams.superseded().contains(node);

        if (assignable) {
            putDirective(m, node, ActivationDirectiveValue.core(), List.of());
        }

        Reconcile.request(m, ctx);

        return Placement.cores(m).isEmpty()
               ? Joins.retryAllocation(m, ctx)
               : m;
    }

    private static ClusterModel putDirective(ClusterModel m,
                                             NodeId node,
                                             ActivationDirectiveValue directive,
                                             List<KVCommand.Mutation<AetherKey, AetherValue>> before) {
        var key = new ActivationDirectiveKey(node);
        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>(before);

        mutations.add(new KVCommand.Mutation<>(key, Option.none(), Option.some((AetherValue) directive)));
        Outbox.direct(m,
                      TxDraft.draft(SubmitKind.DIRECTIVE, key, mutations).withNode(node));

        return m;
    }

    // -- J5: a worker (B-069, B-070) -------------------------------------------------------------------------------
    /// J5 (worker): resolve the source (default when blank), choose the community, and commit the community (when absent)
    /// and the WORKER directive in one transaction.
    static ClusterModel assignWorker(ClusterModel m, Ctx ctx, NodeId node) {
        if (m.directives.get(node).map(Rows::isCommunityWorker).or(false)) {
            return m;
        }

        var source = Option.option(m.seams.memberSource().get(node))
                           .filter(label -> !label.isBlank())
                           .or(Joins.DEFAULT_SOURCE);
        var policies = m.seams.communityPolicies();

        if (policies.isEmpty()) {
            return commit(m,
                          ctx,
                          node,
                          source + Joins.WORKER_COMMUNITY_SUFFIX,
                          source,
                          ctx.settings().communityTargetSize());
        }

        var zone = m.nodePlacements.get(node)
                                   .filter(placement -> placement.sourceName()
                                                                 .equals(source))
                                   .flatMap(placement -> placement.observedZone());

        return reserved(m, node, source, zone, policies).orElse(() -> configured(m, source, zone, policies))
                       .fold(() -> m,
                             policy -> commit(m,
                                              ctx,
                                              node,
                                              policy.id(),
                                              "",
                                              policy.targetSize()));
    }

    private static Option<CommunityPlacement> reserved(ClusterModel m,
                                                       NodeId node,
                                                       String source,
                                                       Option<String> zone,
                                                       Map<String, CommunityPlacement> policies) {
        return Option.from(policies.values()
                                   .stream()
                                   .filter(policy -> policy.targetSize() > 0)
                                   .filter(policy -> m.placementOps.get(policy.id())
                                                                   .filter(operation -> operation.active()
                                                                                        && operation.targetNode()
                                                                                                    .equals(node)
                                                                                        && operation.targetSource()
                                                                                                    .equals(source)
                                                                                        && operation.targetZone()
                                                                                                    .fold(() -> true,
                                                                                                          expected -> zone.filter(expected::equals)
                                                                                                                          .isPresent()))
                                                                   .isPresent())
                                   .findFirst());
    }

    /// The eligible policy with the lowest fill ratio, ties by id. Fill counts the community's assigned workers, excluding
    /// departed ids (R-3.3.9, N4-4, NT-39). A worker that already has a community directive never reaches here.
    private static Option<CommunityPlacement> configured(ClusterModel m,
                                                         String source,
                                                         Option<String> zone,
                                                         Map<String, CommunityPlacement> policies) {
        return Option.from(policies.values()
                                   .stream()
                                   .filter(policy -> policy.targetSize() > 0)
                                   .filter(policy -> policy.locations()
                                                           .stream()
                                                           .anyMatch(location -> location.source()
                                                                                         .equals(source) && location.zone()
                                                                                                                    .fold(() -> true,
                                                                                                                          expected -> zone.filter(expected::equals)
                                                                                                                                          .isPresent())))
                                   .min(Comparator.<CommunityPlacement> comparingDouble(policy -> (double) Idx.members(m.assignedByCommunity,
                                                                                                                       policy.id())
                                                                                                              .size() / policy.targetSize()).thenComparing(CommunityPlacement::id)));
    }

    private static ClusterModel commit(ClusterModel m,
                                       Ctx ctx,
                                       NodeId node,
                                       String communityId,
                                       String source,
                                       int targetSize) {
        if (m.directives.containsKey(node)) {
            return m;
        }

        var communityKey = new CommunityKey(communityId);
        var community = m.communities.get(communityId);

        if (community.map(value -> value.state() == CommunityState.DISSOLVING || value.state() == CommunityState.DISSOLVED)
                     .or(false)) {
            return m;
        }

        var mutations = new ArrayList<KVCommand.Mutation<AetherKey, AetherValue>>();

        if (community.isEmpty()) {
            var minted = new CommunityValue(source,
                                            ActivationDirectiveValue.WORKER,
                                            targetSize,
                                            CommunityState.FORMING,
                                            ctx.nowMs(),
                                            Option.none());

            mutations.add(new KVCommand.Mutation<>(communityKey, Option.none(), Option.some((AetherValue) minted)));
        }

        var directiveKey = new ActivationDirectiveKey(node);

        mutations.add(new KVCommand.Mutation<>(directiveKey,
                                               Option.none(),
                                               Option.some((AetherValue) ActivationDirectiveValue.worker(communityId, ""))));
        var guards = List.<KVCommand.ReadWitness<AetherKey>> of(new KVCommand.ReadWitness<>(ClusterConfigKey.CURRENT,
                                                                                            m.clusterConfig.map(v -> (Object) v)),
                                                                new KVCommand.ReadWitness<>(communityKey,
                                                                                            community.map(v -> (Object) v)));

        return Outbox.direct(m,
                             TxDraft.draft(SubmitKind.DIRECTIVE, directiveKey, mutations)
                                    .withGuards(guards)
                                    .withNode(node));
    }

    record unused() implements Directory {}
}
