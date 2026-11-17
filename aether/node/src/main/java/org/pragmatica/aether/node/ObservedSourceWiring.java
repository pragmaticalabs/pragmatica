// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.AllInstancesFailedDetector;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.node.deployment.AdmissionSeam;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// F5b (phase-3 design 8.2 D-10): the node-side wiring of the FSM2 observed-row sources, extracted from [AetherNode] so a unit test can reach
/// the real production lambdas (which counted-member predicate, which admitted-token reader) instead of a
/// rebuilt fixture.
final class ObservedSourceWiring {
    private ObservedSourceWiring() {}

    /// #1573: the ACTIVE instances of every deployed artifact version on nodes the membership FSM still counts, as the all-instances-failed
    /// detector needs them. An instance left in KV on a node membership has declared DEAD serves nothing and is not a host; a counted node that
    /// stopped reporting still is, and its stale metrics make the version undecidable. Rows are judged against the node's admitted boot token
    /// (R-1.6.2). No membership FSM yet: no instances.
    static Map<Artifact, Set<NodeId>> observedActiveInstances(KVStore<AetherKey, AetherValue> kvStore,
                                                              Option<MembershipFsm> membershipFsm,
                                                              AdmissionSeam admissionSeam) {
        return membershipFsm.map(fsm -> AllInstancesFailedDetector.observedActiveInstances(kvStore,
                                                                                           fsm::isCountedMember,
                                                                                           admissionSeam::tokenOf))
                            .or(Map.of());
    }
}
