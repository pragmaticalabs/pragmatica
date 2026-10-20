// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// The leader's local memory of the assignments it has made, for the derived-community growth policy (#241).
///
/// - **Pending assignments.** A joiner's directive commits through consensus, so the next join can be decided before the
///   previous one is visible in the KV. Counting the in-flight assignments is what keeps two joiners from both taking the last
///   free slot of a community inside one leader. An assignment stops being pending when its directive is visible, or after
///   [#PENDING_TTL_MS]: a commit that was lost then costs a slot for that long, never for good.
/// - **Recent assignments.** A worker the leader has just placed has not answered the leader's liveness probe yet, so the
///   liveness view cannot tell it from a dead one. For [#JOIN_GRACE_MS] after the assignment it counts as live anyway; afterwards
///   the liveness view decides.
/// - **Blocked sources.** The sources a refused join has been reported for, so the recovery event is raised only for a source
///   that was reported.
///
/// Kept on the deployment context, not on the `Active` state, so it survives a leader FSM rebuild on this node. Nothing here is
/// replicated: a new leader starts empty, which can only let one extra joiner into a full community, inside the soft cap.
public record CommunityAdmissionLedger(ConcurrentMap<NodeId, Pending> pending,
                                       Set<String> blockedSources,
                                       ConcurrentMap<NodeId, Integer> refusals,
                                       Set<NodeId> unplaced) {
    /// How many times in a row the leader decides a worker's community afresh after the commit was refused, before it reports the worker unplaced.
    public static final int MAX_ATTEMPTS = 3;
    public static final long PENDING_TTL_MS = 30_000L;
    public static final long JOIN_GRACE_MS = 60_000L;

    public record Pending(String communityId, long atMs) {
        public static Pending pending(String communityId, long atMs) {
            return new Pending(communityId, atMs);
        }
    }

    public static CommunityAdmissionLedger communityAdmissionLedger() {
        return new CommunityAdmissionLedger(new ConcurrentHashMap<>(),
                                            ConcurrentHashMap.newKeySet(),
                                            new ConcurrentHashMap<>(),
                                            ConcurrentHashMap.newKeySet());
    }

    @Contract
    public void register(NodeId node, String communityId, long nowMs) {
        pending.put(node, Pending.pending(communityId, nowMs));
    }

    /// Ends the in-flight assignment of `node`: its commit was refused or lost, so it holds no slot, and a retry of the same node must not
    /// count its own earlier attempt against the community it is retrying into.
    @Contract
    public void forget(NodeId node) {
        pending.remove(node);
    }

    /// The in-flight assignments per community: directive not yet visible (`committed` answers for a node) and younger than the TTL.
    /// Assignments older than the join grace are dropped on the way.
    public Map<String, Integer> pendingByCommunity(long nowMs, Predicate<NodeId> committed) {
        pending.values().removeIf(entry -> nowMs - entry.atMs() > JOIN_GRACE_MS);

        return pending.entrySet()
                      .stream()
                      .filter(entry -> isInFlight(entry, nowMs, committed))
                      .collect(Collectors.toMap(entry -> entry.getValue()
                                                              .communityId(),
                                                _ -> 1,
                                                Integer::sum));
    }

    private static boolean isInFlight(Map.Entry<NodeId, Pending> entry, long nowMs, Predicate<NodeId> committed) {
        return nowMs - entry.getValue()
                            .atMs() <= PENDING_TTL_MS && !committed.test(entry.getKey());
    }

    /// Whether the leader placed `node` within the join grace, so that a silent liveness probe does not yet mean it is gone.
    public boolean isRecent(NodeId node, long nowMs) {
        return Option.option(pending.get(node))
                     .filter(entry -> nowMs - entry.atMs() <= JOIN_GRACE_MS)
                     .isPresent();
    }

    /// Records that a join for `source` was refused; true on the first refusal since the last admission.
    public boolean markBlocked(String source) {
        return blockedSources.add(source);
    }

    /// Records that a join for `source` was admitted; true when the source had been reported blocked.
    public boolean clearBlocked(String source) {
        return blockedSources.remove(source);
    }

    /// Counts one more refused commit for `node` and answers how many in a row there have been.
    public int refused(NodeId node) {
        return refusals.merge(node, 1, Integer::sum);
    }

    /// The leader gave up on `node` for now: its count restarts, and it is remembered as unplaced so that its placement can be reported.
    @Contract
    public void giveUp(NodeId node) {
        refusals.remove(node);
        unplaced.add(node);
    }

    /// `node` was placed: its count restarts. True when it had been reported unplaced.
    public boolean placed(NodeId node) {
        refusals.remove(node);

        return unplaced.remove(node);
    }
}
