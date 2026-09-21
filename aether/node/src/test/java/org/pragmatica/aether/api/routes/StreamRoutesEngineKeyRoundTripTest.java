// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.cluster.state.kvstore.KVStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// The engine keys a `system`-namespace stream by its BARE NAME ([StreamManager#systemAddress]).
/// `StreamApiRoutes`'s catalog-form create, publish and delete all resolve their engine key via
/// [StreamManager#engineKey], which reduces a `system`-namespace address to that bare name. This
/// pins the property those resolutions depend on: CREATE -> PUBLISH -> DELETE (all catalog-form,
/// engineKey-resolved) must agree on ONE engine key, so a rename or addressing change on any side
/// cannot silently split the cycle across two different keys — the defect
/// `StreamApiRoutesDeleteStreamTest#deleteStream_systemNamespace_resolvesEngineKeyToBareName`
/// already guards for DELETE alone, extended here across the full CREATE-PUBLISH-DELETE cycle.
/// (#968: the CREATE leg used the legacy body-carried route with the bare name; that route now
/// refuses bare names — and, by the #1282 order pinned in `StreamRoutesReservedPrefixTest`, the
/// `system:` spelling — so the cycle is driven through the catalog-form create.)
class StreamRoutesEngineKeyRoundTripTest {
    private static final String STREAM_NAME = "diagnostics";
    private static final String VERSION = "1.0.0";

    private static ManageableNode nodeWith(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                        new Class[]{ManageableNode.class},
                                                        (_, method, _) -> stubbed(method.getName(), manager));
    }

    /// `publishOne`'s auto-create guard reads `kvStore()` before falling back to the management
    /// default (recovered on any failure), and `streamWriteRouter()` needs a real router over the
    /// SAME manager to append locally — both additional to the single `streamPartitionManager()`
    /// stub `StreamApiRoutesDeleteStreamTest`'s delete-only proxy required.
    private static Object stubbed(String method, StreamPartitionManager manager) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            case "kvStore" -> new KVStore<AetherKey, AetherValue>(null, null, null);
            case "streamWriteRouter" -> StreamWriteRouter.localOnly(manager);
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }

    @Test
    void engineKey_roundTripsAcrossCreatePublishDelete_asOneIdentity() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            Supplier<ManageableNode> nodeSupplier = () -> nodeWith(manager);
            var apiRoutes = StreamApiRoutes.streamApiRoutes(nodeSupplier,
                                                             StreamNamespacesService.inMemory(),
                                                             ConsumerGroupCoordinator.noOp(),
                                                             ConsumerGroupRegistry.consumerGroupRegistry());

            // CREATE: catalog-form `system`-namespace address, minted under the bare-name engine key.
            apiRoutes.createStream(StreamManager.SYSTEM_NAMESPACE,
                                   STREAM_NAME,
                                   VERSION,
                                   new StreamApiRoutes.CreateRequest(1))
                     .onFailure(cause -> fail("stream create must succeed: " + cause))
                     .onSuccess(response -> assertThat(response.status()).isEqualTo("created"));
            assertThat(manager.streamInfo(STREAM_NAME).isPresent()).as("minted under the bare-name engine key").isTrue();

            // PUBLISH: catalog-form `system`-namespace address must resolve to the SAME bare-name
            // engine key CREATE minted, not a shadow stream materialized under a different key.
            apiRoutes.publishEvent(StreamManager.SYSTEM_NAMESPACE,
                                   STREAM_NAME,
                                   VERSION,
                                   new StreamApiRoutes.PublishRequest("payload", null))
                     .await()
                     .onFailure(cause -> fail("publish against the system-namespace address must resolve to the "
                                              + "stream CREATE minted: " + cause))
                     .onSuccess(response -> assertThat(response.offset()).isEqualTo(0L));

            // DELETE: same catalog-form address, same engine-key resolution — must tear down the
            // SAME stream PUBLISH just wrote to, not fail "not found" against a different key.
            apiRoutes.deleteStream(StreamManager.SYSTEM_NAMESPACE, STREAM_NAME, VERSION)
                     .onFailure(cause -> fail("delete against the system-namespace address must resolve to the "
                                              + "SAME engine key publish used: " + cause))
                     .onSuccess(response -> assertThat(response.status()).isEqualTo("deleted"));
        } finally {
            manager.close();
        }
    }
}
