// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.environment.hetzner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.NodeGroupConfig;
import org.pragmatica.aether.environment.QuotaStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


class HetznerCloudProviderTest {

    private StubComputeProvider stubCompute;
    private HetznerCloudProvider provider;

    @BeforeEach
    void setUp() {
        stubCompute = new StubComputeProvider();
        provider = HetznerCloudProvider.hetznerCloudProvider(stubCompute);
    }

    @Nested
    class SupportsPreemptibleTests {

        @Test
        void supportsPreemptible_returnsFalse() {
            assertThat(provider.supportsPreemptible()).isFalse();
        }
    }

    @Nested
    class CheckQuotaTests {

        @Test
        void checkQuota_returnsUnknown() {
            var group = NodeGroupConfig.nodeGroupConfig("src", "core", 3, "cx22", "fsn1", Map.of());

            provider.checkQuota(group)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(HetznerCloudProviderTest::assertUnknownQuota);
        }
    }

    @Nested
    class ProvisionSpotTests {

        @Test
        void provisionSpot_returnsError() {
            var group = NodeGroupConfig.nodeGroupConfig("src", "core", 1, "cx22", "fsn1", Map.of());

            provider.provisionSpot(group)
                    .await()
                    .onSuccess(nodes -> assertThat(nodes).isNull())
                    .onFailure(HetznerCloudProviderTest::assertOperationNotSupported);
        }
    }

    @Nested
    class ProvisionTests {

        @Test
        void provision_success_returnsProvisionedNodes() {
            var group = NodeGroupConfig.nodeGroupConfig("src", "core", 2, "cx22", "fsn1", Map.of());

            provider.provision(group)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(nodes -> assertThat(nodes).hasSize(2));
        }
    }

    @Nested
    class DestroyTests {

        @Test
        void destroy_success_terminatesAll() {
            provider.destroy(List.of("node-1", "node-2"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(stubCompute.terminateCount).isEqualTo(2);
        }
    }

    @Nested
    class IngressTests {

        @Test
        void openIngress_returnsNotSupported() {
            provider.openIngress("fw-1", 8080, "tcp", "0.0.0.0/0", "test")
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(HetznerCloudProviderTest::assertOperationNotSupported);
        }

        @Test
        void closeIngress_returnsNotSupported() {
            provider.closeIngress("fw-1", 8080, "tcp", "0.0.0.0/0")
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(HetznerCloudProviderTest::assertOperationNotSupported);
        }
    }

    private static void assertUnknownQuota(QuotaStatus status) {
        assertThat(status.sufficient()).isTrue();
        assertThat(status.requested()).isEqualTo(3);
        assertThat(status.availableInRegion()).isEqualTo(-1);
        assertThat(status.limitingResource()).isEqualTo("unknown");
    }

    private static void assertOperationNotSupported(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.OperationNotSupported.class);
    }

    /// Minimal stub for ComputeProvider that returns canned responses.
    static final class StubComputeProvider implements ComputeProvider {
        int terminateCount;

        @Override
        public Promise<InstanceInfo> provision(InstanceType instanceType) {
            return Promise.success(stubInstanceInfo("srv-1"));
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            terminateCount++;
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Promise.success(stubInstanceInfo(instanceId.value()));
        }

        private static InstanceInfo stubInstanceInfo(String id) {
            return new InstanceInfo(new InstanceId(id),
                                    InstanceStatus.RUNNING,
                                    List.of("1.2.3.4", "10.0.0.1"),
                                    InstanceType.ON_DEMAND,
                                    Map.of());
        }
    }
}
