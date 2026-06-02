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
/// and consensus-write delivery for the new node-role promotion endpoint.
class NodeLifecycleRoutesPromoteTest {

    private static final NodeId TARGET = new NodeId("node-2");

    private KVStore<AetherKey, AetherValue> kvStore;
    private List<KVCommand<AetherKey>> capturedCommands;
    private NodeLifecycleRoutes routes;

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
                case "apply" -> captureAndAck((List<KVCommand<AetherKey>>) args[0]);
                case "route" -> null;
                default -> throw new UnsupportedOperationException("Not implemented in test proxy: " + method.getName());
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
    class HappyPath {

        @Test
        void promote_coreToWorker_writesActivationDirective() {
            var response = routes.promoteNode(TARGET.id(), new PromoteNodeRequest("WORKER"))
                                  .onFailure(cause -> fail("Promote must succeed: " + cause.message()))
                                  .await()
                                  .or((PromoteNodeResponse) null);

            assertThat(response).isNotNull();
            assertThat(response.success()).isTrue();
            assertThat(response.nodeId()).isEqualTo(TARGET.id());
            assertThat(response.previousRole()).isEqualTo(ActivationDirectiveValue.CORE);
            assertThat(response.newRole()).isEqualTo(ActivationDirectiveValue.WORKER);
            assertThat(capturedCommands).hasSize(1);

            var put = (KVCommand.Put<?, ?>) capturedCommands.getFirst();

            assertThat(put.key()).isInstanceOf(ActivationDirectiveKey.class);
            assertThat(((ActivationDirectiveKey) put.key()).nodeId()).isEqualTo(TARGET);
            assertThat(put.value()).isEqualTo(new ActivationDirectiveValue(ActivationDirectiveValue.WORKER));
        }

        @Test
        void promote_workerToCore_writesActivationDirective() {
            kvStore.process(kvStore.createBatch(List.of(new KVCommand.Put<>(ActivationDirectiveKey.activationDirectiveKey(TARGET),
                                                 ActivationDirectiveValue.worker()))));

            var response = routes.promoteNode(TARGET.id(), new PromoteNodeRequest("CORE"))
                                  .onFailure(cause -> fail("Promote must succeed: " + cause.message()))
                                  .await()
                                  .or((PromoteNodeResponse) null);

            assertThat(response).isNotNull();
            assertThat(response.previousRole()).isEqualTo(ActivationDirectiveValue.WORKER);
            assertThat(response.newRole()).isEqualTo(ActivationDirectiveValue.CORE);
            assertThat(capturedCommands).hasSize(1);
        }

        @Test
        void promote_caseInsensitiveTargetRole_normalisesToUpper() {
            var response = routes.promoteNode(TARGET.id(), new PromoteNodeRequest("worker"))
                                  .onFailure(cause -> fail("Promote must succeed: " + cause.message()))
                                  .await()
                                  .or((PromoteNodeResponse) null);

            assertThat(response).isNotNull();
            assertThat(response.newRole()).isEqualTo(ActivationDirectiveValue.WORKER);
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
