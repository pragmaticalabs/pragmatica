// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry.RegisteredByKind;
import org.pragmatica.aether.stream.StreamPartitionManager;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1224: `POST /streams/{namespace}/{stream}/{version}` (`StreamApiRoutes#createStream`) must
/// register a [org.pragmatica.aether.slice.stream.StreamRegistryEntry] in the catalog in addition
/// to materializing the rings — before the fix the handler only called
/// [StreamPartitionManager#createStream], so a created stream never appeared in `GET /streams`
/// (`aether streams list`). This is the counterpart of
/// [StreamApiRoutesDeleteStreamTest]: that file pins delete-side release/tolerance behavior; this
/// one pins that create actually writes the entry the delete side later releases.
class StreamApiRoutesCreateStreamTest {
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final String ADDRESS = NAMESPACE + ":" + STREAM + ":" + VERSION;

    private static ManageableNode nodeWith(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager));
    }

    private static Object stubbed(String method, StreamPartitionManager manager) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }

    private static StreamApiRoutes routesFor(StreamPartitionManager manager, StreamNamespacesService namespacesService) {
        return StreamApiRoutes.streamApiRoutes(() -> nodeWith(manager), namespacesService, null, null);
    }

    private static void assertCreatedResponse(StreamApiRoutes.CreateResponse response) {
        assertThat(response.address()).isEqualTo(ADDRESS);
        assertThat(response.status()).isEqualTo("created");
    }

    private static void assertOperatorEntry(StreamRegistryEntry entry) {
        assertThat(entry.address().asString()).isEqualTo(ADDRESS);
        assertThat(entry.registeredBy()).isEqualTo(RegisteredByKind.OPERATOR);
    }

    @Test
    void createStream_newAddress_registersInCatalogAndAppearsInSnapshot() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = StreamNamespacesService.inMemory();
        try {
            routesFor(manager, namespacesService).createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null))
                                                 .onFailure(_ -> fail("stream create must succeed"))
                                                 .onSuccess(StreamApiRoutesCreateStreamTest::assertCreatedResponse);

            assertThat(namespacesService.snapshot()).anySatisfy(StreamApiRoutesCreateStreamTest::assertOperatorEntry);
        } finally {
            manager.close();
        }
    }

    /// Mirrors [StreamApiRoutes#createAtAddress]'s check-exists-first shape: a repeat create for the
    /// same address must not re-register (which would hit `ALREADY_REGISTERED`) or bump `refCount` —
    /// it reports `"exists"` and leaves the single entry untouched.
    @Test
    void createStream_repeatedAddress_isIdempotentAndDoesNotDuplicateRegistration() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = StreamNamespacesService.inMemory();
        try {
            var routes = routesFor(manager, namespacesService);
            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null))
                  .onFailure(_ -> fail("first stream create must succeed"));

            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null))
                  .onFailure(_ -> fail("repeated stream create must succeed"))
                  .onSuccess(response -> assertThat(response.status()).isEqualTo("exists"));

            assertThat(namespacesService.snapshot()).hasSize(1);
            assertThat(namespacesService.snapshot().getFirst().refCount()).isEqualTo(1);
        } finally {
            manager.close();
        }
    }

    /// #1224 proof requirement: an operator-created entry IS removed by the explicit `stream delete`
    /// route (distinct from [StreamApiRoutesDeleteStreamTest], whose fixtures bypass this
    /// catalog-registering handler entirely and so only exercise the `NOT_FOUND`-tolerance path).
    @Test
    void createStream_thenDeleteStream_removesFromCatalogSnapshot() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = StreamNamespacesService.inMemory();
        try {
            var routes = routesFor(manager, namespacesService);
            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null))
                  .onFailure(_ -> fail("stream create must succeed"));

            assertThat(namespacesService.snapshot()).hasSize(1);

            routes.deleteStream(NAMESPACE, STREAM, VERSION)
                  .onFailure(_ -> fail("destroy of an existing stream must succeed"))
                  .onSuccess(response -> assertThat(response.status()).isEqualTo("deleted"));

            assertThat(namespacesService.snapshot()).isEmpty();
        } finally {
            manager.close();
        }
    }
}
