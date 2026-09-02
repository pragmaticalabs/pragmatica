// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// `DELETE /api/streams/{namespace}/{stream}/{version}` (`StreamApiRoutes#deleteStream`) must
/// surface a failed destroy as an error rather than swallowing it and always answering
/// `"deleted"`. Before the fix a `.recover(_ -> Unit.unit())` on the destroy result masked
/// every failure (e.g. destroying a stream that does not exist), so callers got a 2xx
/// `"deleted"` for a stream that was never removed — a silent-wrong-state response.
class StreamApiRoutesDeleteStreamTest {
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final String STREAM_ADDRESS = NAMESPACE + ":" + STREAM + ":" + VERSION;

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

    private static StreamApiRoutes routesFor(StreamPartitionManager manager) {
        return StreamApiRoutes.streamApiRoutes(() -> nodeWith(manager), null, null, null);
    }

    @Test
    void deleteStream_destroyFails_surfacesErrorNotDeleted() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            routesFor(manager).deleteStream(NAMESPACE, STREAM, VERSION)
                              .onSuccess(_ -> fail("a failed destroy must surface as an error, not \"deleted\""));
        } finally {
            manager.close();
        }
    }

    @Test
    void deleteStream_destroySucceeds_returnsDeleted() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            manager.createStream(StreamConfig.streamConfig(STREAM_ADDRESS))
                   .onFailure(_ -> fail("stream create must succeed"));

            routesFor(manager).deleteStream(NAMESPACE, STREAM, VERSION)
                              .onFailure(_ -> fail("destroy of an existing stream must succeed"))
                              .onSuccess(response -> assertThat(response.status()).isEqualTo("deleted"));
        } finally {
            manager.close();
        }
    }

    /// `system`-namespace regression: the engine keys an operator-created flat stream by its bare
    /// name (the shape `STREAM_CREATE` materializes it under), not the full catalog address. Before
    /// the fix `destroyAtAddress` used `addr.asString()` ("system:diagnostics:1.0.0") as the engine
    /// key, so this deleted a stream that was never created and surfaced as a failure instead of
    /// "deleted" — the same silent-wrong-state failure mode this file already guards, one level up.
    @Test
    void deleteStream_systemNamespace_resolvesEngineKeyToBareName() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            manager.createStream(StreamConfig.streamConfig("diagnostics"))
                   .onFailure(_ -> fail("stream create must succeed"));

            routesFor(manager).deleteStream("system", "diagnostics", "1.0.0")
                              .onFailure(_ -> fail("destroy of a system-namespace stream must resolve to its bare engine key"))
                              .onSuccess(response -> assertThat(response.status()).isEqualTo("deleted"));
        } finally {
            manager.close();
        }
    }
}
