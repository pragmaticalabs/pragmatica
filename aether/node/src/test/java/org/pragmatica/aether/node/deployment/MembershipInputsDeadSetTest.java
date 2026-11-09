// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #2937 (C17r): the DEAD set a `Rebuild` carries is split by the removal input each id takes, by the same role test the delta
/// projector routes joins with: a core-role member gets `NodeRemoved`, any other (worker or unlabelled) `WorkerLeave`.
class MembershipInputsDeadSetTest {
    private static final NodeId CORE = new NodeId("node-core");
    private static final NodeId WORKER = new NodeId("node-worker");
    private static final NodeId UNLABELLED = new NodeId("node-unlabelled");

    private static MemberDescriptor withRole(String role) {
        return new MemberDescriptor(Option.none(), role, "", "");
    }

    // Red when: a core-role DEAD member is classed as a worker, or a non-core one as a core.
    @Test
    void deadSet_splitsByCoreRole() {
        var dead = MembershipInputs.deadSet(Map.of(CORE,
                                                   withRole("core"),
                                                   WORKER,
                                                   withRole("worker"),
                                                   UNLABELLED,
                                                   MemberDescriptor.UNKNOWN));

        assertThat(dead).isEqualTo(ClusterInput.DeadSet.deadSet(Set.of(CORE), Set.of(WORKER, UNLABELLED)));
    }

    // Red when: an empty DEAD set yields anything to synthesise.
    @Test
    void deadSet_empty_isNone() {
        assertThat(MembershipInputs.deadSet(Map.of())).isEqualTo(ClusterInput.DeadSet.NONE);
    }
}
