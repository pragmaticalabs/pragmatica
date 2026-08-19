// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #298 — the fleet cap at the single provisioning chokepoint.
///
/// The fake below implements only the no-arg `listInstances()` and stamps a REAL `aether-cluster`
/// tag on each instance, so the production default `listInstances(Map)` tag-filter actually runs
/// against it. A fake that ignored the filter and returned everything would pass these tests while
/// hiding a tag-mismatch — the cap would then count the wrong cluster's nodes in production.
class NodeLifecycleManagerCapTest {
    private static final String CLUSTER = "prod-eu";
    private static final String CLUSTER_TAG = "aether.cluster";

    @Test
    void provisionNode_refuses_whenObservedCountReachedCap() {
        var provider = fakeProvider(existing(3, CLUSTER));
        var manager = NodeLifecycleManager.nodeLifecycleManager(Option.some(provider),
                                                                Option.some(CLUSTER),
                                                                Option.some(3));

        manager.provisionNode(spec())
               .await()
               .onSuccess(_ -> fail("Expected refusal at the cap"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(EnvironmentError.NodeCapExceeded.class));

        assertThat(provider.provisionCalls()).as("provider must not be called once the cap is reached")
                                             .isZero();
    }

    @Test
    void provisionNode_proceeds_whenBelowCap() {
        var provider = fakeProvider(existing(2, CLUSTER));
        var manager = NodeLifecycleManager.nodeLifecycleManager(Option.some(provider),
                                                                Option.some(CLUSTER),
                                                                Option.some(3));

        manager.provisionNode(spec())
               .await()
               .onFailure(cause -> fail("Expected provisioning below the cap, got: " + cause.message()));

        assertThat(provider.provisionCalls()).isEqualTo(1);
    }

    /// The cap counts THIS cluster only. Instances belonging to another cluster in the same account
    /// must not consume the budget — this is what the tag scoping buys, and it fails if the filter
    /// is dropped.
    @Test
    void provisionNode_ignoresOtherClustersInstances_whenCounting() {
        var instances = new ArrayList<InstanceInfo>(existing(1, CLUSTER));

        instances.addAll(existing(5, "other-cluster"));

        var provider = fakeProvider(instances);
        var manager = NodeLifecycleManager.nodeLifecycleManager(Option.some(provider),
                                                                Option.some(CLUSTER),
                                                                Option.some(3));

        manager.provisionNode(spec())
               .await()
               .onFailure(cause -> fail("Other clusters must not consume this cluster's cap, got: " + cause.message()));

        assertThat(provider.provisionCalls()).isEqualTo(1);
    }

    @Test
    void provisionNode_proceedsUnbounded_whenNoCapConfigured() {
        var provider = fakeProvider(existing(99, CLUSTER));
        var manager = NodeLifecycleManager.nodeLifecycleManager(Option.some(provider),
                                                                Option.some(CLUSTER),
                                                                Option.empty());

        manager.provisionNode(spec())
               .await()
               .onFailure(cause -> fail("No cap configured must remain unbounded, got: " + cause.message()));

        assertThat(provider.provisionCalls()).isEqualTo(1);
    }

    /// The anti-dead-surface case. If a failed count were treated as "no reason to refuse", an
    /// unreachable provider API would silently disable the guard while it still looked wired.
    @Test
    void provisionNode_refuses_whenInstanceCountCannotBeRead() {
        var provider = failingListProvider();
        var manager = NodeLifecycleManager.nodeLifecycleManager(Option.some(provider),
                                                                Option.some(CLUSTER),
                                                                Option.some(3));

        manager.provisionNode(spec())
               .await()
               .onSuccess(_ -> fail("A failed cap read must not authorize provisioning"));

        assertThat(provider.provisionCalls()).as("provider must not be called when the cap cannot be evaluated")
                                             .isZero();
    }

    private static List<InstanceInfo> existing(int count, String cluster) {
        var instances = new ArrayList<InstanceInfo>();

        for (var i = 0; i < count; i++) {
            instances.add(new InstanceInfo(InstanceId.instanceId(cluster + "-" + i).unwrap(),
                                           InstanceStatus.RUNNING,
                                           List.of(),
                                           InstanceType.ON_DEMAND,
                                           Map.of(CLUSTER_TAG, cluster),
                                           Option.empty()));
        }

        return instances;
    }

    private static ProvisionSpec spec() {
        return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                           "cx23",
                                           "core",
                                           ProvisionContext.forBootstrap(CLUSTER, "core", sourceNameOrDefault("default"), "node-1"))
                            .unwrap();
    }

    private static FakeProvider fakeProvider(List<InstanceInfo> instances) {
        return new FakeProvider(instances, false);
    }

    private static FakeProvider failingListProvider() {
        return new FakeProvider(List.of(), true);
    }

    private static final class FakeProvider implements ComputeProvider {
        private final List<InstanceInfo> instances;
        private final boolean listFails;
        private final AtomicInteger provisionCalls = new AtomicInteger();

        private FakeProvider(List<InstanceInfo> instances, boolean listFails) {
            this.instances = List.copyOf(instances);
            this.listFails = listFails;
        }

        int provisionCalls() {
            return provisionCalls.get();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return listFails
                   ? EnvironmentError.listInstancesFailed(new IllegalStateException("provider API unreachable")).promise()
                   : Promise.success(instances);
        }

        @Override
        public Promise<InstanceInfo> createFrom(org.pragmatica.aether.environment.ProvisionRequest request) {
            provisionCalls.incrementAndGet();

            return Promise.success(new InstanceInfo(InstanceId.instanceId("new-node").unwrap(),
                                                    InstanceStatus.RUNNING,
                                                    List.of(),
                                                    InstanceType.ON_DEMAND,
                                                    Map.of(CLUSTER_TAG, CLUSTER),
                                                    Option.empty()));
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return EnvironmentError.instanceNotFound(instanceId).promise();
        }
    }
}
