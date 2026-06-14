// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


/// #259: the CDM-assigned role (KV-Store `ActivationDirective`) must be surfaced in cluster
/// topology output, distinct from the self-asserted descriptor role. Without this, a node the
/// CDM demoted to WORKER kept reading as `core` from its `AETHER_ROLE` descriptor — hiding the
/// demotion during triage.
class ClusterTopologyRoutesAssignedRoleTest {
    private static final NodeId DEMOTED = new NodeId("demoted-node");
    private static final NodeId CORE = new NodeId("core-node");
    private static final NodeId UNASSIGNED = new NodeId("unassigned-node");

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        var router = org.pragmatica.messaging.MessageRouter.DelegateRouter.delegate();
        router.quiesce();
        kvStore = new KVStore<>(router, noopSerializer(), null);
    }

    private static org.pragmatica.serialization.Serializer noopSerializer() {
        return new org.pragmatica.serialization.Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        };
    }

    private ManageableNode nodeProxy() {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "kvStore" -> kvStore;
                                                           default -> throw new UnsupportedOperationException("Not in proxy: "
                                                                                                              + method.getName());
                                                       });
    }

    private void seedActivation(NodeId nodeId, ActivationDirectiveValue value) {
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(ActivationDirectiveKey.activationDirectiveKey(nodeId),
                                                                        value))));
    }

    @Test
    void assignedRoles_returnsWorker_forCdmDemotedNode() {
        seedActivation(DEMOTED, ActivationDirectiveValue.worker());

        var roles = ClusterTopologyRoutes.assignedRoles(nodeProxy());

        assertThat(roles).containsEntry(DEMOTED, ActivationDirectiveValue.WORKER);
    }

    @Test
    void assignedRoles_returnsCore_forCdmCoreNode() {
        seedActivation(CORE, ActivationDirectiveValue.core());

        var roles = ClusterTopologyRoutes.assignedRoles(nodeProxy());

        assertThat(roles).containsEntry(CORE, ActivationDirectiveValue.CORE);
    }

    @Test
    void assignedRoles_omitsNode_withoutActivationDirective() {
        seedActivation(CORE, ActivationDirectiveValue.core());

        var roles = ClusterTopologyRoutes.assignedRoles(nodeProxy());

        assertThat(roles).doesNotContainKey(UNASSIGNED);
    }
}
