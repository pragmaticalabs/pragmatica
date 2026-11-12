// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.config.cluster.CommunityPolicy;
import org.pragmatica.aether.deployment.cluster.CommunityGrowthPolicy;
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
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Verify;


/// Directives, communities and governor announcements (spec C13, C14, J5, B-066, B-069, B-070, R-4.3.3).
///
/// - A **directive** names a node's role; the FSM assigns a CORE directive to a joining non-seed core (J5) and a WORKER
///   directive, minting the community when needed, to a joining worker (B-069, B-070). It **never removes** one (R-4.3.1).
/// - A **community** is chosen by incumbency, then by the lowest fill ratio, counting only live, non-departed assignments
///   (R-3.3.9, N4-4, NT-39).
/// - An **announcement** refreshes the leader-local freshness clock of its community (R-2.14c).
public sealed interface Directory {
    /// R-CF.17: the legacy growth warnings, same codes and subject (the source), so an operator's alert reads both engines alike.
    String GROWTH_BLOCKED = "community-growth-blocked";
    String GROWTH_RESUMED = "community-growth-resumed";
    /// R-CF.17 parity (LEGACY `ClusterDeploymentState.retryOrReport`): the same codes and subject (the worker).
    String ASSIGNMENT_UNPLACED = "community-assignment-unplaced";
    String ASSIGNMENT_PLACED = "community-assignment-placed";
    /// R-CF.17 parity: the refused WORKER assignment commits in a row after which the leader gives up on the worker, as LEGACY's
    /// `CommunityAdmissionLedger.MAX_ATTEMPTS` (a parity test pins the two equal).
    int MAX_ASSIGNMENT_ATTEMPTS = 3;

    // -- C13 -------------------------------------------------------------------------------------------------------
    static ClusterModel onDirectivePut(ClusterModel m, Ctx ctx, NodeId node, ActivationDirectiveValue value) {
        Indexer.indexDirective(m, node, value);
        Absence.refresh(m, ctx, node);

        return Reconcile.request(placed(m, ctx, node), ctx);
    }

    /// R-CF.17 parity (LEGACY `confirmPlaced`): the worker's directive committed. Its run of refused assignments ends, and a worker
    /// reported `community-assignment-unplaced` is reported `community-assignment-placed`, same subject.
    private static ClusterModel placed(ClusterModel m, Ctx ctx, NodeId node) {
        m.assignmentRefusals = m.assignmentRefusals.remove(node);
        if (m.unplacedWarned.contains(node)) {
            m.unplacedWarned = m.unplacedWarned.remove(node);
            ctx.warn(ASSIGNMENT_PLACED, node.id(), "Worker " + node.id() + " was assigned a community");
        }

        return m;
    }

    /// R-CF.17 parity (LEGACY `settleAssignment` / `retryOrReport`): a WORKER assignment commit was refused (the community moved between
    /// the decision and the commit). Each worker it carries counts one more refusal in a row. Below [#MAX_ASSIGNMENT_ATTEMPTS] its
    /// community is decided afresh, as before; at the bound the leader gives up on it: `community-assignment-unplaced`, subject the
    /// worker, and its pending join is dropped, so it takes a community when it is admitted again. Answers the workers given up on.
    static List<NodeId> assignmentRefused(ClusterModel m, Ctx ctx, TxDraft draft) {
        var workers = assignedWorkers(draft).toList();

        workers.forEach(node -> countRefusal(m, node));
        var gaveUp = workers.stream()
                            .filter(node -> m.assignmentRefusals.get(node)
                                                                .or(0) >= MAX_ASSIGNMENT_ATTEMPTS)
                            .toList();

        gaveUp.forEach(node -> giveUp(m, ctx, node));

        return gaveUp;
    }

    private static Stream<NodeId> assignedWorkers(TxDraft draft) {
        return draft.mutations()
                    .stream()
                    .filter(mutation -> mutation.replacement()
                                                .filter(ActivationDirectiveValue.class::isInstance)
                                                .map(ActivationDirectiveValue.class::cast)
                                                .filter(Rows::isCommunityWorker)
                                                .isPresent())
                    .map(mutation -> mutation.key())
                    .filter(ActivationDirectiveKey.class::isInstance)
                    .map(key -> ((ActivationDirectiveKey) key).nodeId())
                    .distinct();
    }

    private static ClusterModel countRefusal(ClusterModel m, NodeId node) {
        m.assignmentRefusals = m.assignmentRefusals.put(node,
                                                        m.assignmentRefusals.get(node).or(0) + 1);

        return m;
    }

    private static ClusterModel giveUp(ClusterModel m, Ctx ctx, NodeId node) {
        m.assignmentRefusals = m.assignmentRefusals.remove(node);
        m.pendingJoins = m.pendingJoins.remove(node);
        // #2834: once per episode: an open warning, this leader's or inherited from the committed open set (`Warnings.inherit`), is not raised again.
        if (!m.unplacedWarned.contains(node)) {
            m.unplacedWarned = m.unplacedWarned.add(node);
            ctx.warn(ASSIGNMENT_UNPLACED,
                     node.id(),
                     "Worker " + node.id()
                    + " has no community: its assignment changed before commit " + MAX_ASSIGNMENT_ATTEMPTS
                    + " times in a row. It takes one when it is admitted again");
        }

        return m;
    }

    /// R-CF.17 parity: a departing worker's run of refusals is dropped; one reported unplaced is closed with the reason subject-gone (the
    /// D.6 convention of FSM2's recoveries; LEGACY leaves it open).
    static ClusterModel assignmentGone(ClusterModel m, Ctx ctx, NodeId node) {
        m.assignmentRefusals = m.assignmentRefusals.remove(node);
        if (m.unplacedWarned.contains(node)) {
            m.unplacedWarned = m.unplacedWarned.remove(node);
            ctx.warn(ASSIGNMENT_PLACED,
                     node.id(),
                     Recoveries.gone(node.id(), "the worker departed before it was assigned a community"));
        }

        return m;
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
    /// remaining desired, route and topic-row (#1337) keys. The topic rows ride here because the node leaves `departed` with its record, so
    /// the C17b tick sweep never reaches a row this transaction leaves.
    private static ClusterModel removeDeparted(ClusterModel m, Ctx ctx, NodeId node) {
        return Reconcile.request(submitRemovals(m, node), ctx);
    }

    private static List<KVCommand.Mutation<AetherKey, AetherValue>> removalMutations(ClusterModel m,
                                                                                     NodeId node,
                                                                                     NodeDepartureValue record) {
        return Stream.of(List.of(DepartureFlow.removal(new NodeDepartureKey(node),
                                                       record)),
                         DepartureFlow.observedRemovals(m, node),
                         DepartureFlow.desiredRemovals(m, node),
                         DepartureFlow.routeRemovals(m, node),
                         DepartureFlow.topicRowRemovals(m, node))
                     .flatMap(List::stream)
                     .toList();
    }

    /// R-4.3.3 derived again from the mirror, after a refusal (R-5.7.2) or a lost or failed submission (#2143). R-4.3.3 is the only remover
    /// of a departure record (R-4.3.5, MQ-4) and the directive it answers is already gone, so no reconcile step derives it: dropping it
    /// would leave the record of a terminated id for good (NT-106). It stops when the node is no longer departed (its record moved or went)
    /// or has a directive again.
    static Option<TxDraft> refreshedRemoval(ClusterModel m, TxDraft draft) {
        return draft.node()
                    .filter(node -> m.departed.contains(node) && !m.directives.containsKey(node))
                    .flatMap(node -> removalDraft(m,
                                                  node,
                                                  draft.attempt() + 1));
    }

    private static ClusterModel submitRemovals(ClusterModel m, NodeId node) {
        return removalDraft(m, node, 0).fold(() -> m, draft -> Outbox.direct(m, draft));
    }

    /// The record's removal first (it expects the DEPARTED value), then the rows, desired keys and route keys at their current values.
    private static Option<TxDraft> removalDraft(ClusterModel m, NodeId node, int attempt) {
        return m.records.get(node)
                        .map(record -> removalDraft(m, node, record, attempt));
    }

    private static TxDraft removalDraft(ClusterModel m, NodeId node, NodeDepartureValue record, int attempt) {
        var mutations = removalMutations(m, node, record);

        return new TxDraft(SubmitKind.DIRECTIVE_REMOVAL,
                           mutations.getFirst().key(),
                           mutations,
                           List.of(),
                           Set.of(),
                           Option.some(node),
                           attempt,
                           List.of());
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

    /// A DIRECTIVE transaction that writes `node`'s directive is waiting to be sent or to be settled: a second one would expect the
    /// same absent key. A draft is matched by the directive key it mutates, not by its `node`: a joint mint (`joinMint`) carries a
    /// second worker's directive under the first worker's `node`. Complete because every DIRECTIVE draft is built by `putDirective`
    /// or `commit`, and both put the target's `ActivationDirectiveKey` among its mutations.
    static boolean directiveInFlight(ClusterModel m, NodeId node) {
        var key = new ActivationDirectiveKey(node);

        return drafts(m).anyMatch(draft -> draft.kind() == SubmitKind.DIRECTIVE && writes(draft, key));
    }

    private static boolean writes(TxDraft draft, AetherKey key) {
        return draft.mutations()
                    .stream()
                    .anyMatch(mutation -> mutation.key()
                                                  .equals(key));
    }

    // -- J5: a worker (B-069, B-070) -------------------------------------------------------------------------------
    /// J5 (worker): resolve the source (default when blank), choose the community, and commit the community (when absent)
    /// and the WORKER directive in one transaction.
    static ClusterModel assignWorker(ClusterModel m, Ctx ctx, NodeId node) {
        return policyMatched(place(m, ctx, node), ctx, node);
    }

    /// D.6 row 22: a worker reported `worker-no-community-policy` that J5 has now placed (its directive is in flight) ends the episode:
    /// `worker-community-policy-matched`, same subject.
    private static ClusterModel policyMatched(ClusterModel m, Ctx ctx, NodeId node) {
        if (m.noPolicyWarned.contains(node) && directiveInFlight(m, node)) {
            m.noPolicyWarned = m.noPolicyWarned.remove(node);
            ctx.warn(Recoveries.WORKER_COMMUNITY_POLICY_MATCHED,
                     node.id(),
                     "A community policy now accepts worker " + node.id());
        }

        return m;
    }

    /// D.6 row 22: a reported worker departed before any policy matched it: closed with the reason subject-gone.
    static ClusterModel policyGone(ClusterModel m, Ctx ctx, NodeId node) {
        if (m.noPolicyWarned.contains(node)) {
            m.noPolicyWarned = m.noPolicyWarned.remove(node);
            ctx.warn(Recoveries.WORKER_COMMUNITY_POLICY_MATCHED,
                     node.id(),
                     Recoveries.gone(node.id(), "the worker departed before a community policy accepted it"));
        }

        return m;
    }

    private static ClusterModel place(ClusterModel m, Ctx ctx, NodeId node) {
        if (m.directives.get(node).map(Rows::isCommunityWorker).or(false)) {
            return m;
        }

        var source = Option.option(m.seams.memberSource().get(node))
                           .filter(Verify.Is::notBlank)
                           .or(Joins.DEFAULT_SOURCE);
        var policies = m.seams.communityPolicies();

        if (policies.isEmpty()) {
            return derived(m, ctx, node, source);
        }

        var zone = m.nodePlacements.get(node)
                                   .filter(placement -> placement.sourceName()
                                                                 .equals(source))
                                   .flatMap(placement -> placement.observedZone());

        return reserved(m, node, source, zone, policies).orElse(() -> configured(m, source, zone, policies))
                       .fold(() -> unplaceable(m, ctx, node, source),
                             policy -> commit(m,
                                              ctx,
                                              node,
                                              policy.id(),
                                              "",
                                              policy.targetSize()));
    }

    // FER (degrade forward): a worker whose source matches no reserved and no configured community policy gets no directive now and is
    // reported. Guarantee: the failure is visible, never silent. Mechanism: the warning below, closed by worker-community-policy-matched
    // (D.6 row 22). No join stays pending for the worker (no directive is queued), so only its next join runs J5 again: a seams refresh
    // that adds a policy places it at that rejoin, not by itself.
    private static ClusterModel unplaceable(ClusterModel m, Ctx ctx, NodeId node, String source) {
        // #2834: once per episode: an open warning, this leader's or inherited from the committed open set (`Warnings.inherit`), is not raised again.
        if (!m.noPolicyWarned.contains(node)) {
            m.noPolicyWarned = m.noPolicyWarned.add(node);
            ctx.warn("worker-no-community-policy",
                     node.id(),
                     "No community policy accepts worker " + node.id()
                    + " of source " + source
                    + "; it gets no directive");
        }

        return m;
    }

    // -- J5: a derived community (R-CF.16, R-CF.17, #241) ------------------------------------------------------------
    /// A worker of a source with no declared community takes a DERIVED community, minted at the COMMITTED `max_size` (R-CF.16). With
    /// growth off (the default) it is the source's single `<source>-w-0`; with growth on it is what `CommunityGrowthPolicy` picks over the
    /// source's derived communities (R-CF.17). A worker whose directive is already in flight never reaches here (`Joins.directive`): that is
    /// the legacy ledger's duplicate-join dedup.
    private static ClusterModel derived(ClusterModel m, Ctx ctx, NodeId node, String source) {
        var policy = CommunityPolicies.current(m, ctx);

        return policy.growth()
               ? grow(m, ctx, node, source, policy)
               : commit(m, ctx, node, source + Joins.WORKER_COMMUNITY_SUFFIX, source, policy.maxSize());
    }

    /// The growth decision (R-CF.17), the legacy `decideAndAdmit`. The legacy admission ledger serialized read, decide and register under a
    /// lock; here the FSM step is the serialization (one input at a time over one model), and an in-flight assignment is a DIRECTIVE draft
    /// whose directive is not visible yet (`inFlightByCommunity`), so two joins in one drain never both take the last free slot.
    private static ClusterModel grow(ClusterModel m, Ctx ctx, NodeId node, String source, CommunityPolicy policy) {
        return switch (CommunityGrowthPolicy.pick(source, candidates(m, source), policy)) {
            case CommunityGrowthPolicy.Decision.Join join -> admit(m, ctx, node, source, join.communityId(), policy);
            case CommunityGrowthPolicy.Decision.Mint mint -> admit(m, ctx, node, source, mint.communityId(), policy);
            case CommunityGrowthPolicy.Decision.Refusal _ -> refuseGrowth(m, ctx, node, source, policy);
        };
    }

    private static ClusterModel admit(ClusterModel m,
                                      Ctx ctx,
                                      NodeId node,
                                      String source,
                                      String communityId,
                                      CommunityPolicy policy) {
        commit(m, ctx, node, communityId, source, policy.maxSize());

        return m.growthBlocked.contains(source)
               ? growthResumed(m, ctx, node, source, communityId)
               : m;
    }

    private static ClusterModel growthResumed(ClusterModel m, Ctx ctx, NodeId node, String source, String communityId) {
        m.growthBlocked = m.growthBlocked.remove(source);
        ctx.warn(GROWTH_RESUMED,
                 source,
                 "Worker " + node.id() + " of source " + source + " was admitted to community " + communityId + " again");

        return m;
    }

    // FER (degrade forward), as the legacy refusal: the refused worker gets no community now and the refusal is reported. Guarantee: the
    // failure is visible, never silent, and the worker is not placed work as a community member. Mechanism: the warning below, closed by
    // community-growth-resumed on the next admission for the source; a refusal queues no directive, so the worker takes a community when it
    // joins again (the legacy behaviour), not by a leader retry.
    private static ClusterModel refuseGrowth(ClusterModel m,
                                             Ctx ctx,
                                             NodeId node,
                                             String source,
                                             CommunityPolicy policy) {
        // #2834: once per episode: an open warning, this leader's or inherited from the committed open set (`Warnings.inherit`), is not raised again.
        if (!m.growthBlocked.contains(source)) {
            m.growthBlocked = m.growthBlocked.add(source);
            ctx.warn(GROWTH_BLOCKED,
                     source,
                     "No community can take worker " + node.id()
                    + " of source " + source
                    + ": its communities are at the " + CommunityPolicy.CEILING
                    + " ceiling and the source is at the bound of " + policy.maxCommunities()
                    + " (max_size " + policy.maxSize()
                    + "). Raise community_policy.max_communities or max_size, or add worker capacity under another source; "
                    + "the worker takes a community when it rejoins");
        }

        return m;
    }

    /// The source's derived communities, committed and being minted, with the live-aware load the growth policy chooses by (legacy
    /// `DerivedCommunities`): a member holds a slot while it is not departed, departing or judged absent, and an in-flight assignment
    /// holds one until its directive is visible. A community minted by an in-flight draft is a FORMING candidate, so the next joiner
    /// joins it instead of minting the same id again.
    static List<CommunityGrowthPolicy.Candidate> candidates(ClusterModel m, String source) {
        var inFlight = inFlightByCommunity(m);
        var committed = m.communities.entries()
                                     .stream()
                                     .filter(entry -> isDerivedOf(source,
                                                                  entry.getKey()))
                                     .map(entry -> committedCandidate(m,
                                                                      inFlight,
                                                                      entry.getKey(),
                                                                      entry.getValue()));
        var minting = inFlight.entrySet()
                              .stream()
                              .filter(entry -> isMinting(m,
                                                         source,
                                                         entry.getKey()))
                              .map(entry -> mintingCandidate(entry.getKey(),
                                                             entry.getValue()));

        return Stream.concat(committed, minting)
                     .sorted(Comparator.comparing(CommunityGrowthPolicy.Candidate::id))
                     .toList();
    }

    private static CommunityGrowthPolicy.Candidate committedCandidate(ClusterModel m,
                                                                      Map<String, Integer> inFlight,
                                                                      String communityId,
                                                                      CommunityValue value) {
        return CommunityGrowthPolicy.Candidate.candidate(communityId,
                                                         value.state(),
                                                         liveLoad(m, communityId) + inFlight.getOrDefault(communityId, 0));
    }

    private static boolean isMinting(ClusterModel m, String source, String communityId) {
        return isDerivedOf(source, communityId) && !m.communities.containsKey(communityId);
    }

    private static CommunityGrowthPolicy.Candidate mintingCandidate(String communityId, int load) {
        return CommunityGrowthPolicy.Candidate.candidate(communityId, CommunityState.FORMING, load);
    }

    private static boolean isDerivedOf(String source, String communityId) {
        return CommunityGrowthPolicy.derivedIndex(source, communityId).isPresent();
    }

    private static int liveLoad(ClusterModel m, String communityId) {
        return (int) Idx.members(m.assignedByCommunity, communityId)
                        .toList()
                        .stream()
                        .filter(node -> holdsSlot(m, node))
                        .count();
    }

    private static boolean holdsSlot(ClusterModel m, NodeId node) {
        return ! m.departed.contains(node)
               && !m.departing.containsKey(node)
               && !m.absentSince.containsKey(node);
    }

    /// One in-flight assignment: a WORKER directive in a DIRECTIVE draft (queued or submitted) whose directive is not visible yet.
    record Assignment(NodeId node, String communityId) {
        static Assignment assignment(NodeId node, String communityId) {
            return new Assignment(node, communityId);
        }
    }

    /// The in-flight assignments per community, each node counted once however many drafts carry it.
    static Map<String, Integer> inFlightByCommunity(ClusterModel m) {
        return directiveMutations(m).flatMap(mutation -> pendingAssignment(m, mutation).stream())
                                 .distinct()
                                 .collect(Collectors.toMap(Assignment::communityId, _ -> 1, Integer::sum));
    }

    private static Stream<KVCommand.Mutation<AetherKey, AetherValue>> directiveMutations(ClusterModel m) {
        return drafts(m).filter(draft -> draft.kind() == SubmitKind.DIRECTIVE)
                     .flatMap(draft -> draft.mutations()
                                            .stream());
    }

    private static Option<Assignment> pendingAssignment(ClusterModel m,
                                                        KVCommand.Mutation<AetherKey, AetherValue> mutation) {
        return mutation.key() instanceof ActivationDirectiveKey(var node) && !m.directives.containsKey(node)
               ? mutation.replacement()
                         .filter(ActivationDirectiveValue.class::isInstance)
                         .map(ActivationDirectiveValue.class::cast)
                         .filter(Rows::isCommunityWorker)
                         .map(directive -> Assignment.assignment(node,
                                                                 directive.communityId()))
               : Option.none();
    }

    private static Stream<TxDraft> drafts(ClusterModel m) {
        return Stream.concat(m.submitted.values().stream().flatMap(submitted -> submitted.drafts()
                                                                                         .stream()),
                             m.outbox.stream());
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
        var community = m.communities.get(communityId);

        if (m.directives.containsKey(node) || dissolving(community)) {
            return m;
        }

        return community.isEmpty()
               ? mintOrJoin(m, ctx, node, communityId, source, targetSize)
               : writeDirective(m, node, communityId, community, List.of());
    }

    private static boolean dissolving(Option<CommunityValue> community) {
        return community.map(value -> value.state() == CommunityState.DISSOLVING || value.state() == CommunityState.DISSOLVED)
                        .or(false);
    }

    /// The community is absent: a worker of the same drain joins the draft that mints it (one write, no refusal round), else this
    /// worker's draft mints it.
    private static ClusterModel mintOrJoin(ClusterModel m,
                                           Ctx ctx,
                                           NodeId node,
                                           String communityId,
                                           String source,
                                           int targetSize) {
        var communityKey = new CommunityKey(communityId);
        var minted = new CommunityValue(source,
                                        ActivationDirectiveValue.WORKER,
                                        targetSize,
                                        CommunityState.FORMING,
                                        ctx.nowMs(),
                                        Option.none());
        var mint = new KVCommand.Mutation<AetherKey, AetherValue>(communityKey,
                                                                  Option.none(),
                                                                  Option.some((AetherValue) minted));

        return minting(m, communityKey).fold(() -> writeDirective(m, node, communityId, Option.none(), List.of(mint)),
                                             draft -> joinMint(m, draft, node, communityId));
    }

    private static Option<TxDraft> minting(ClusterModel m, CommunityKey communityKey) {
        return Option.from(m.outbox.stream()
                                   .filter(draft -> draft.kind() == SubmitKind.DIRECTIVE && writes(draft, communityKey))
                                   .findFirst());
    }

    /// Appends `node`'s directive to the minting draft; the draft keeps its first worker as `node` (see `directiveInFlight`).
    private static ClusterModel joinMint(ClusterModel m, TxDraft draft, NodeId node, String communityId) {
        var joined = new ArrayList<>(draft.mutations());
        var next = new ArrayList<>(m.outbox);

        joined.add(directiveMutation(node, communityId));
        next.set(next.indexOf(draft),
                 new TxDraft(draft.kind(),
                             draft.key(),
                             joined,
                             draft.guards(),
                             draft.pairs(),
                             draft.node(),
                             draft.attempt(),
                             draft.onAccepted()));
        m.outbox = next;

        return m;
    }

    private static KVCommand.Mutation<AetherKey, AetherValue> directiveMutation(NodeId node, String communityId) {
        return new KVCommand.Mutation<>(new ActivationDirectiveKey(node),
                                        Option.none(),
                                        Option.some((AetherValue) ActivationDirectiveValue.worker(communityId, "")));
    }

    /// The WORKER directive, after `before` (the community mint, when this write mints it), guarded by the cluster config and by the
    /// community value this decision read.
    private static ClusterModel writeDirective(ClusterModel m,
                                               NodeId node,
                                               String communityId,
                                               Option<CommunityValue> community,
                                               List<KVCommand.Mutation<AetherKey, AetherValue>> before) {
        var mutations = new ArrayList<>(before);
        var directiveKey = new ActivationDirectiveKey(node);
        var guards = List.<KVCommand.ReadWitness<AetherKey>> of(new KVCommand.ReadWitness<>(ClusterConfigKey.CURRENT,
                                                                                            m.clusterConfig.map(v -> (Object) v)),
                                                                new KVCommand.ReadWitness<>(new CommunityKey(communityId),
                                                                                            community.map(v -> (Object) v)));

        mutations.add(directiveMutation(node, communityId));

        return Outbox.direct(m,
                             TxDraft.draft(SubmitKind.DIRECTIVE, directiveKey, mutations)
                                    .withGuards(guards)
                                    .withNode(node));
    }

    record unused() implements Directory {}
}
