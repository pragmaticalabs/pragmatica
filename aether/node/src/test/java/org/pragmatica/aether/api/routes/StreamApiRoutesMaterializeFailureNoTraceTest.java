// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #3794 (b8): the management publish auto-create guard (`ensureStreamExists`) turns a failed materialization into a 409
/// `StreamUnavailable` that names the stream and the failure. When the failure is a caught throwable, the body carries the
/// fixed client text and nothing of the throwable: the cause stays a cause until the renderer, it is never copied out as text.
class StreamApiRoutesMaterializeFailureNoTraceTest {
    private static final String SENTINEL = "SENTINEL-3794-materialize";
    private static final String STREAM_ADDRESS = "com.example.app:orders:1.0.0";

    @Test
    void materializationFailingWithAThrowable_answers409_withNoTraceInTheBody() {
        var manager = mock(StreamPartitionManager.class);

        when(manager.ensureStreamMaterialized(any())).thenReturn(Causes.fromThrowable(new IllegalStateException(SENTINEL)).<Unit> result());

        var failure = new AtomicReference<Cause>();

        routesFor(manager).ensureStreamExists(STREAM_ADDRESS)
                          .onSuccess(_ -> fail("a failed materialization must fail the guard"))
                          .onFailure(failure::set);

        assertThat(failure.get()).isInstanceOf(ManagementServerError.StreamUnavailable.class);

        var status = new AtomicReference<HttpStatus>();
        var body = new AtomicReference<String>();

        ProblemResponses.writeProblem(new ResponseWriter() {
            @Override
            public void write(HttpStatus responseStatus, byte[] bytes, ContentType contentType) {
                status.set(responseStatus);
                body.set(new String(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public ResponseWriter header(String name, String value) {
                return this;
            }
        }, failure.get(), "/x", "req_1");

        assertThat(status.get()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body.get()).contains(STREAM_ADDRESS)
                              .doesNotContain(SENTINEL)
                              .doesNotContain("IllegalStateException")
                              .doesNotContain("\\tat ")
                              .doesNotContain("\tat ")
                              .doesNotContain("at java");
    }

    private static StreamApiRoutes routesFor(StreamPartitionManager manager) {
        return StreamApiRoutes.streamApiRoutes(() -> nodeWith(manager),
                                               StreamNamespacesService.inMemory(),
                                               ConsumerGroupCoordinator.noOp(),
                                               ConsumerGroupRegistry.consumerGroupRegistry());
    }

    private static ManageableNode nodeWith(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
                                                           case "streamPartitionManager" -> manager;
                                                           case "kvStore" -> new KVStore<AetherKey, AetherValue>(null, null, null);
                                                           default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method.getName());
                                                       });
    }
}
