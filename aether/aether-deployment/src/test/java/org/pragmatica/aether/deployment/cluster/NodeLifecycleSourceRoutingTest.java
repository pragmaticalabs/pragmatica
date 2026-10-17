// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.environment.*;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class NodeLifecycleSourceRoutingTest {
    private static final SourceName EAST = SourceName.sourceName("east").unwrap();
    private static final SourceName WEST = SourceName.sourceName("west").unwrap();
    private static final NodeId NODE = new NodeId("worker-1");

    @Test
    void provisionUsesExactSourceAndPreservesOperationContextWithoutExtraQueries() {
        var east = new RecordingProvider();
        var west = new RecordingProvider();
        var manager = manager(east, west);
        var context = ProvisionContext.forBootstrap(ClusterName.clusterName("test").unwrap(),
                                                    "worker",
                                                    WEST,
                                                    NODE.id());
        var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "small", "workers", context)
                                .unwrap()
                                .withImage("image");

        assertThat(manager.provisionNode(spec).await().isSuccess()).isTrue();
        assertThat(east.requests).isEmpty();
        assertThat(west.requests).hasSize(1);
        assertThat(west.requests.getFirst().context()).isEqualTo(context);
        assertThat(east.lists + west.lists).isZero();
    }

    @Test
    void inventoryAndTerminationUseNamedSourceOnly() {
        var east = new RecordingProvider();
        var west = new RecordingProvider();
        var manager = manager(east, west);

        assertThat(manager.listInstances(Map.of("aether.source", "west")).await().isSuccess()).isTrue();
        assertThat(manager.terminateNode(NODE).await().isSuccess()).isTrue();
        assertThat(east.lists).isZero();
        assertThat(west.lists).isEqualTo(2);
        assertThat(west.terminated).hasSize(1);
    }

    @Test
    void unknownSourceOrNodeCannotFallbackToAnotherAccount() {
        var east = new RecordingProvider();
        var west = new RecordingProvider();
        var manager = manager(east, west);

        assertThat(manager.listInstances(Map.of()).await().isFailure()).isTrue();
        assertThat(manager.listInstances(Map.of("aether.source", "unknown")).await().isFailure()).isTrue();
        assertThat(manager.terminateNode(new NodeId("unknown")).await().isFailure()).isTrue();
        assertThat(east.lists + west.lists).isZero();
    }

    @Test
    void clusterScopedFleetInventoryCallsEachSelectedSourceOnce() {
        var east = new RecordingProvider("east");
        var west = new RecordingProvider("west");
        var providers = Map.<SourceName, ComputeProvider> of(EAST, east, WEST, west);
        SourceComputeRegistry registry = new SourceComputeRegistry() {
            @Override
            public Result<ComputeProvider> resolve(SourceName source) {
                return Option.option(providers.get(source)).toResult(EnvironmentError.operationNotSupported("Unknown source"));
            }

            @Override
            public Result<List<SourceName>> sources(Map<String, String> filter) {
                return Result.success(List.of(EAST, WEST));
            }
        };
        var manager = NodeLifecycleManager.nodeLifecycleManager(registry,
                                                                _ -> Result.success(WEST),
                                                                Option.none(),
                                                                Option.none());
        var inventory = manager.listInstances(Map.of("aether.cluster", "test")).await().unwrap();

        assertThat(inventory).hasSize(2);
        assertThat(east.lists).isEqualTo(1);
        assertThat(west.lists).isEqualTo(1);
    }

    private static NodeLifecycleManager manager(ComputeProvider east, ComputeProvider west) {
        var providers = Map.of(EAST, east, WEST, west);
        SourceComputeRegistry registry = source -> Option.option(providers.get(source)).toResult(EnvironmentError.operationNotSupported("Unknown source"));

        return NodeLifecycleManager.nodeLifecycleManager(registry,
                                                         node -> node.equals(NODE)
                                                                 ? Result.success(WEST)
                                                                 : EnvironmentError.operationNotSupported("Unknown node placement").result(),
                                                         Option.none(),
                                                         Option.none());
    }

    private static final class RecordingProvider implements ComputeProvider {
        final List<ProvisionRequest> requests = new ArrayList<>();
        final List<InstanceId> terminated = new ArrayList<>();
        int lists;
        final String source;

        RecordingProvider() {
            this("west");
        }

        RecordingProvider(String source) {
            this.source = source;
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            requests.add(request);

            return Promise.success(instance());
        }

        @Override
        public Promise<Unit> terminate(InstanceId id) {
            terminated.add(id);

            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            lists++;

            return Promise.success(List.of(instance()));
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId id) {
            return Promise.success(instance());
        }

        private InstanceInfo instance() {
            return new InstanceInfo(InstanceId.instanceId("i-1").unwrap(),
                                    InstanceStatus.RUNNING,
                                    List.of(),
                                    InstanceType.ON_DEMAND,
                                    Map.of("aether.node-id",
                                           NODE.id(),
                                           "aether.source",
                                           source,
                                           "aether.cluster",
                                           "test"),
                                    Option.none(),
                                    Option.none());
        }
    }
}
