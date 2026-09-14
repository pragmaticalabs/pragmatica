// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #1050 R4 / S1 — terminate is idempotent at the lifecycle layer. The departure reap, the drain-grace
/// backstop and the activation replay can each reach a node whose instance is already gone. That is the
/// requested end state, so it must complete quietly: no provider call, and no WARN or failure that the
/// callers would re-log. An AMBIGUOUS match (more than one instance for one node id) is still refused,
/// because terminating the wrong server is not idempotent.
class NodeLifecycleManagerTerminateTest {
    private static final String NODE_ID_TAG = "aether.node-id";
    private static final NodeId NODE = nodeId("node-d").unwrap();

    @Test
    void terminateNode_noMatchingInstance_completesAsDone_withoutCallingProvider() {
        var provider = new RecordingProvider(List.of());
        var manager = NodeLifecycleManager.nodeLifecycleManager(Option.some(provider));

        var result = manager.terminateNode(NODE)
                            .await();

        assertThat(result.isSuccess()).as("an already-gone instance is the requested end state: %s", result)
                                      .isTrue();
        assertThat(provider.terminated).as("nothing to terminate, so the provider is never asked")
                                       .isEmpty();
    }

    @Test
    void terminateNode_oneMatchingInstance_terminatesIt() {
        var provider = new RecordingProvider(List.of(instanceFor("i-1", NODE)));
        var manager = NodeLifecycleManager.nodeLifecycleManager(Option.some(provider));

        var result = manager.terminateNode(NODE)
                            .await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(provider.terminated).containsExactly("i-1");
    }

    @Test
    void terminateNode_ambiguousMatch_isStillRefused() {
        var provider = new RecordingProvider(List.of(instanceFor("i-1", NODE), instanceFor("i-2", NODE)));
        var manager = NodeLifecycleManager.nodeLifecycleManager(Option.some(provider));

        var result = manager.terminateNode(NODE)
                            .await();

        assertThat(result.isFailure()).as("two instances for one node id must not be guessed between")
                                      .isTrue();
        assertThat(provider.terminated).isEmpty();
    }

    private static InstanceInfo instanceFor(String instanceId, NodeId nodeId) {
        return InstanceInfo.instanceInfo(InstanceId.instanceId(instanceId)
                                                   .unwrap(),
                                         InstanceStatus.RUNNING,
                                         List.of("127.0.0.1"),
                                         InstanceType.ON_DEMAND,
                                         Map.of(NODE_ID_TAG, nodeId.id()),
                                         Option.some(nodeId.id()))
                           .unwrap();
    }

    private static final class RecordingProvider implements ComputeProvider {
        private final List<InstanceInfo> instances;
        private final List<String> terminated = new CopyOnWriteArrayList<>();

        private RecordingProvider(List<InstanceInfo> instances) {
            this.instances = instances;
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return Causes.cause("not used").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            terminated.add(instanceId.value());

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(instances);
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Causes.cause("not used").promise();
        }
    }
}
