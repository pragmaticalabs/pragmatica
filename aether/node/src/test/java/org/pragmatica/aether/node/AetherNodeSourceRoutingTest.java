// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.pragmatica.aether.deployment.membership.fsm.MemberDescriptor;
import org.pragmatica.aether.environment.*;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodePlacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class AetherNodeSourceRoutingTest {
    private static final NodeId NODE = new NodeId("worker");

    @Test
    void committedPlacementTakesPrecedenceOverMemberLabelsAndUnknownIsRefused() {
        assertThat(AetherNode.computeSource(NODE,
                                            _ -> Option.some(new NodePlacementValue("west",
                                                                                    Option.none(),
                                                                                    "instance")),
                                            _ -> Option.some(new MemberDescriptor(Option.none(),
                                                                                  "worker",
                                                                                  "east")))
                             .unwrap()
                             .value()).isEqualTo("west");
        assertThat(AetherNode.computeSource(NODE,
                                            _ -> Option.none(),
                                            _ -> Option.some(new MemberDescriptor(Option.none(),
                                                                                  "core",
                                                                                  "east")))
                             .unwrap()
                             .value()).isEqualTo("east");
        assertThat(AetherNode.computeSource(NODE, _ -> Option.none(), _ -> Option.none()).isFailure()).isTrue();
        assertThat(AetherNode.computeSource(NODE,
                                            _ -> Option.none(),
                                            _ -> Option.some(new MemberDescriptor(Option.none(),
                                                                                  "",
                                                                                  "east")))
                             .isFailure()).isTrue();
    }

    @Test
    void explicitLocalEnvironmentCanProvisionBeforeConfigButGenericComputeCannot() {
        var local = new RecordingCompute();
        var localManager = AetherNode.sourceLifecycleManager(Option.some(EnvironmentIntegration.withLocalCompute(local)), Option.none(),
                                                             Option.none(),
                                                             Option.none(),
                                                             Option::none,
                                                             _ -> Result.success(SourceName.DEFAULT));

        assertThat(localManager.isCloudManaged()).isTrue();
        assertThat(localManager.provisionNode(spec()).await().isSuccess()).isTrue();
        assertThat(local.creates).isEqualTo(1);
        var unbound = new RecordingCompute();
        var cloudManager = AetherNode.sourceLifecycleManager(Option.some(EnvironmentIntegration.withCompute(unbound)), Option.none(),
                                                             Option.none(),
                                                             Option.none(),
                                                             Option::none,
                                                             _ -> Result.success(SourceName.DEFAULT));

        assertThat(cloudManager.isCloudManaged()).isFalse();
        assertThat(cloudManager.provisionNode(spec()).await().isFailure()).isTrue();
        assertThat(unbound.creates).isZero();
    }

    private static ProvisionSpec spec() {
        return ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND,
                                           "small",
                                           "workers",
                                           ProvisionContext.forBootstrap(ClusterName.clusterName("test").unwrap(),
                                                                         "worker",
                                                                         SourceName.DEFAULT,
                                                                         NODE.id()))
                            .unwrap()
                            .withImage("image");
    }

    private static final class RecordingCompute implements ComputeProvider {
        int creates;

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            creates++;

            return Promise.success(new InstanceInfo(new InstanceId("instance"),
                                                    InstanceStatus.RUNNING,
                                                    List.of(),
                                                    InstanceType.ON_DEMAND,
                                                    java.util.Map.of(),
                                                    Option.some(NODE.id()),
                                                    Option.none()));
        }

        @Override
        public Promise<Unit> terminate(InstanceId id) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId id) {
            return EnvironmentError.operationNotSupported("status").promise();
        }
    }
}
