// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.http.HttpStatus;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1921 (d): `STREAM_CONSUMERS` is `/streams/{namespace}/{stream}/{version}/consumers`, but it was registered with ONE path
/// parameter, so the handler read the NAMESPACE segment and looked it up as a flat engine stream name: a stream that exists was
/// unreachable through its own address, and the namespace was reported as an unknown stream. The stream here is created under
/// the engine key the catalog routes use for its address; the request names it by that address.
class StreamConsumersByAddressTest {
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final List<String> ADDRESS = List.of(NAMESPACE, STREAM, VERSION, "consumers");

    @Test
    void streamConsumers_listsThePartitions_whenTheStreamIsNamedByItsFullAddress() {
        var manager = StreamPartitionManager.streamPartitionManager();

        try {
            var engineKey = StreamManager.engineKey(ResourceAddress.resourceAddress(NAMESPACE, STREAM, VERSION).unwrap());

            manager.createStream(StreamConfig.streamConfig(engineKey,
                                                           2,
                                                           RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 600_000),
                                                           "earliest"))
                   .onFailure(cause -> fail(cause.message()));

            RouteProbe.run(routes(manager), ManagementRoute.STREAM_CONSUMERS, ADDRESS, Map.of())
                      .onFailure(cause -> fail("a known stream must be reachable by its address, got: " + cause.message()))
                      .onSuccess(value -> assertThat(value).isInstanceOfSatisfying(StreamRoutes.StreamConsumersResponse.class,
                                                                                   response -> {
                                                                                       assertThat(response.name()).isEqualTo(NAMESPACE + ":" + STREAM + ":" + VERSION);
                                                                                       assertThat(response.partitions()).hasSize(2);
                                                                                   }));
        } finally {
            manager.close();
        }
    }

    @Test
    void streamConsumers_answers400_whenTheVersionIsMalformed() {
        var manager = StreamPartitionManager.streamPartitionManager();

        try {
            var failure = RouteProbe.failureOf(routes(manager),
                                               ManagementRoute.STREAM_CONSUMERS,
                                               List.of(NAMESPACE, STREAM, "not-a-version", "consumers"),
                                               Map.of());

            assertThat(RouteProbe.problemStatus(failure)).isEqualTo(HttpStatus.BAD_REQUEST);
        } finally {
            manager.close();
        }
    }

    private static java.util.stream.Stream<org.pragmatica.http.routing.Route<?>> routes(StreamPartitionManager manager) {
        return StreamRoutes.streamRoutes(() -> (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                                                       new Class[]{ManageableNode.class},
                                                                                       (_, method, _) -> {
                                                                                           if (method.getName().equals("streamPartitionManager")) {
                                                                                               return manager;
                                                                                           }
                                                                                           throw new UnsupportedOperationException(method.getName());
                                                                                       }),
                                         ConsumerGroupCoordinator.noOp(),
                                         null).routes();
    }
}
