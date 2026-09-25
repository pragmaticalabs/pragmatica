// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.PromoteNodeRequest;
import org.pragmatica.aether.api.ManagementApiResponses.PromoteNodeResponse;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Covers `POST /api/nodes/promote/{id}` (P-NEW-E, 2026-05-21).
/// Validates request-body parsing, target-role normalisation, no-op detection,
/// and immutable-role refusal without consensus writes.
class NodeLifecycleRoutesPromoteTest {

    private static final NodeId TARGET = new NodeId("node-2");

    private KVStore<AetherKey, AetherValue> kvStore;
    private List<KVCommand<AetherKey>> capturedCommands;
    private NodeLifecycleRoutes routes;
    private final org.pragmatica.aether.deployment.membership.fsm.MembershipFsm fsm = org.pragmatica.aether.deployment.membership.fsm.MembershipFsm.membershipFsm();

    @BeforeEach
    void setUp() {
        var router = MessageRouter.DelegateRouter.delegate();
        router.quiesce();
        kvStore = new KVStore<>(router, noopSerializer(), null);
        capturedCommands = new ArrayList<>();
        routes = NodeLifecycleRoutes.nodeLifecycleRoutes(this::nodeProxy);
    }

    /// No-op serializer: this test seeds the KV store directly (not via consensus dedup), so
    /// the content-based batch id is irrelevant — an empty encoding satisfies `createBatch`.
    private static org.pragmatica.serialization.Serializer noopSerializer() {
        return new org.pragmatica.serialization.Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        };
    }

    private ManageableNode nodeProxy() {
        return (ManageableNode) Proxy.newProxyInstance(
            ManageableNode.class.getClassLoader(),
            new Class[]{ManageableNode.class},
            (_, method, args) -> switch (method.getName()) {
                case "kvStore" -> kvStore;
                case "membershipFsm" -> fsm;
                case "apply" -> captureAndAck((List<KVCommand<AetherKey>>) args[0]);
                case "route" -> null;
                default -> fail("Not implemented in test proxy: " + method.getName());
            }
        );
    }

    private Promise<List<Object>> captureAndAck(List<KVCommand<AetherKey>> commands) {
        capturedCommands.addAll(commands);
        return Promise.success(List.of());
    }

    @Nested
    class Validation {

        @Test
        void promote_nullBody_returnsFailure() {
            var result = routes.promoteNode("node-2", null)
                                .onSuccess(_ -> fail("Null body must be rejected"))
                                .await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message().toLowerCase()).contains("targetrole"));
        }

        @Test
        void promote_blankTargetRole_returnsFailure() {
            var result = routes.promoteNode("node-2", new PromoteNodeRequest(""))
                                .onSuccess(_ -> fail("Blank targetRole must be rejected"))
                                .await();

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void promote_unknownTargetRole_returnsFailure() {
            var result = routes.promoteNode("node-2", new PromoteNodeRequest("SPECTATOR"))
                                .onSuccess(_ -> fail("Unknown targetRole must be rejected"))
                                .await();

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message().toUpperCase()).contains("CORE"));
        }

        @Test
        void promote_invalidNodeId_returnsFailure() {
            var result = routes.promoteNode("", new PromoteNodeRequest("WORKER"))
                                .onSuccess(_ -> fail("Empty node id must be rejected"))
                                .await();

            assertThat(result.isFailure()).isTrue();
        }
    }

    @Nested
    class ImmutableRoles {
        @Test
        void workerToCoreAndCoreToWorkerAreRefusedWithoutWrites() {
            for (var role : List.of("CORE", "WORKER", "SPOT")) {
                kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(ActivationDirectiveKey.activationDirectiveKey(TARGET), new ActivationDirectiveValue(role)))));
                var target = role.equals("CORE") ? "WORKER" : "CORE";
                var result = routes.promoteNode(TARGET.id(), new PromoteNodeRequest(target)).await();
                assertThat(result.isFailure()).isTrue();
                result.onFailure(cause -> assertThat(cause.message()).contains("immutable"));
                assertThat(capturedCommands).isEmpty();
                assertThat(kvStore.get(ActivationDirectiveKey.activationDirectiveKey(TARGET)).unwrap()).isEqualTo(new ActivationDirectiveValue(role));
            }
        }

        @Test
        void unknownNodeDoesNotDefaultToCore() {
            var result = routes.promoteNode("unknown-node", new PromoteNodeRequest("CORE")).await();
            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains("no known"));
            assertThat(capturedCommands).isEmpty();
        }

        @Test
        void immutableDescriptorOverridesConflictingDirective() {
            fsm.onMemberDescriptor(org.pragmatica.consensus.net.NodeInfo.nodeInfo(TARGET,
                org.pragmatica.net.tcp.NodeAddress.nodeAddress("host", 6000).unwrap(),
                java.util.Map.of(org.pragmatica.consensus.net.NodeInfo.LABEL_ROLE, "worker")));
            kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(ActivationDirectiveKey.activationDirectiveKey(TARGET), new ActivationDirectiveValue("CORE")))));
            assertThat(routes.promoteNode(TARGET.id(), new PromoteNodeRequest("CORE")).await().isFailure()).isTrue();
            assertThat(capturedCommands).isEmpty();
        }

        @Test
        void knownSpotRoleAllowsOnlySameRoleAcknowledgement() {
            kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(ActivationDirectiveKey.activationDirectiveKey(TARGET), new ActivationDirectiveValue("SPOT")))));
            assertThat(routes.promoteNode(TARGET.id(), new PromoteNodeRequest("spot")).await().isSuccess()).isTrue();
            assertThat(capturedCommands).isEmpty();
        }
    }

    @Nested
    class Idempotency {

        @Test
        void promote_alreadyAtTargetRole_skipsConsensusWrite() {
            kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(ActivationDirectiveKey.activationDirectiveKey(TARGET),
                                                 ActivationDirectiveValue.worker()))));

            var response = routes.promoteNode(TARGET.id(), new PromoteNodeRequest("WORKER"))
                                  .onFailure(cause -> fail("Idempotent promote must succeed: " + cause.message()))
                                  .await()
                                  .or((PromoteNodeResponse) null);

            assertThat(response).isNotNull();
            assertThat(response.success()).isTrue();
            assertThat(response.previousRole()).isEqualTo(ActivationDirectiveValue.WORKER);
            assertThat(response.newRole()).isEqualTo(ActivationDirectiveValue.WORKER);
            assertThat(capturedCommands)
                .as("Idempotent promote must not emit consensus writes")
                .isEmpty();
        }
    }

    @Nested
    class RouteRegistration {

        @Test
        void routes_includesPromoteRoute() {
            var names = routes.routes().map(r -> r.name()).toList();

            assertThat(names).contains("NODE_PROMOTE");
        }
    }
}
