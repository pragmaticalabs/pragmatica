// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// The read seams of the cluster FSM (spec CI-15, CI-15c): an immutable snapshot of what membership and the local
/// wiring say, delivered with the input that needs it (R-3.1.1) and never read from inside the transition function.
///
/// - `countedCores`, `ready`, `draining`: the placement audience (B-034, B-035).
/// - `coreView`: membership's current **core** member set (`MembershipFsm.coreMembers()`), `none` while the seam is unwired (R-2.15; C10). It
///   also feeds the core liveness evidence (R-2.11 (b)) and the deferral of C17b for a liveness-pending core (G-4); `none` makes both no-ops.
/// - `localAlive`: the leader's local alive view, `none` while the supplier is unwired (R-2.15, M8-2).
/// - `superseded`: ids whose retirement tombstone carries `supersededBy` (J1).
/// - `memberSource`: the membership `source` label per node, for the worker community choice (B-070).
/// - `communityPolicies`: the configured community placement policies, parsed from the cluster config (B-070).
public record Seams(Set<NodeId> countedCores,
                    Set<NodeId> ready,
                    Set<NodeId> draining,
                    Option<Set<NodeId>> coreView,
                    Option<Set<NodeId>> localAlive,
                    Set<NodeId> superseded,
                    Map<NodeId, String> memberSource,
                    Map<String, CommunityPlacement> communityPolicies) {
    public static final Seams UNWIRED = new Seams(Set.of(),
                                                  Set.of(),
                                                  Set.of(),
                                                  Option.none(),
                                                  Option.none(),
                                                  Set.of(),
                                                  Map.of(),
                                                  Map.of());

    public Seams {
        countedCores = Set.copyOf(countedCores);
        ready = Set.copyOf(ready);
        draining = Set.copyOf(draining);
        coreView = coreView.map(Set::copyOf);
        localAlive = localAlive.map(Set::copyOf);
        superseded = Set.copyOf(superseded);
        memberSource = Map.copyOf(memberSource);
        communityPolicies = Map.copyOf(communityPolicies);
    }

    public static Seams seams(Set<NodeId> countedCores,
                              Set<NodeId> ready,
                              Set<NodeId> draining,
                              Option<Set<NodeId>> coreView,
                              Option<Set<NodeId>> localAlive,
                              Set<NodeId> superseded,
                              Map<NodeId, String> memberSource,
                              Map<String, CommunityPlacement> communityPolicies) {
        return new Seams(countedCores,
                         ready,
                         draining,
                         coreView,
                         localAlive,
                         superseded,
                         memberSource,
                         communityPolicies);
    }

    public Seams withCountedCores(Set<NodeId> value) {
        return new Seams(value, ready, draining, coreView, localAlive, superseded, memberSource, communityPolicies);
    }

    public Seams withReady(Set<NodeId> value) {
        return new Seams(countedCores,
                         value,
                         draining,
                         coreView,
                         localAlive,
                         superseded,
                         memberSource,
                         communityPolicies);
    }

    public Seams withDraining(Set<NodeId> value) {
        return new Seams(countedCores, ready, value, coreView, localAlive, superseded, memberSource, communityPolicies);
    }

    public Seams withCoreView(Option<Set<NodeId>> value) {
        return new Seams(countedCores, ready, draining, value, localAlive, superseded, memberSource, communityPolicies);
    }

    public Seams withLocalAlive(Option<Set<NodeId>> value) {
        return new Seams(countedCores, ready, draining, coreView, value, superseded, memberSource, communityPolicies);
    }

    public Seams withSuperseded(Set<NodeId> value) {
        return new Seams(countedCores, ready, draining, coreView, localAlive, value, memberSource, communityPolicies);
    }

    public Seams withMemberSource(Map<NodeId, String> value) {
        return new Seams(countedCores, ready, draining, coreView, localAlive, superseded, value, communityPolicies);
    }

    public Seams withCommunityPolicies(Map<String, CommunityPlacement> value) {
        return new Seams(countedCores, ready, draining, coreView, localAlive, superseded, memberSource, value);
    }
}
