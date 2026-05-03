// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;


class CloudProviderSupportTest {

    @Nested
    class BuildProvisionSpecTests {

        @Test
        void buildProvisionSpec_passesClusterLabelsToProvisionSpec() {
            var labels = Map.of("aether-cluster", "test-a", "aether-source", "hetzner-eu", "aether-role", "core");
            var group = NodeGroupConfig.nodeGroupConfig("hetzner-eu", "core", 3, "cx22", "fsn1", labels);

            CloudProviderSupport.buildProvisionSpec(group)
                                .onFailure(cause -> assertThat(cause).isNull())
                                .onSuccess(CloudProviderSupportTest::assertHasClusterLabels);
        }

        @Test
        void buildProvisionSpec_passesInstanceSizeAndPool() {
            var labels = Map.<String, String>of();
            var group = NodeGroupConfig.nodeGroupConfig("hetzner-eu", "worker", 2, "cx32", "fsn1", labels);

            CloudProviderSupport.buildProvisionSpec(group)
                                .onFailure(cause -> assertThat(cause).isNull())
                                .onSuccess(CloudProviderSupportTest::assertSizeAndPool);
        }

        @Test
        void buildProvisionSpec_zoneDefault_omitsPlacement() {
            var group = NodeGroupConfig.nodeGroupConfig("src", "core", 1, "default", "default", Map.of());

            CloudProviderSupport.buildProvisionSpec(group)
                                .onFailure(cause -> assertThat(cause).isNull())
                                .onSuccess(spec -> assertThat(spec.placement().isEmpty()).isTrue());
        }

        @Test
        void buildProvisionSpec_zoneSpecified_addsZonePlacement() {
            var group = NodeGroupConfig.nodeGroupConfig("src", "core", 1, "default", "fsn1", Map.of());

            CloudProviderSupport.buildProvisionSpec(group)
                                .onFailure(cause -> assertThat(cause).isNull())
                                .onSuccess(CloudProviderSupportTest::assertZoneFsn1);
        }
    }

    @Nested
    class ProvisionViaTests {

        @Test
        void provisionVia_passesTagsToComputeProvider() {
            var captured = new AtomicReference<ProvisionSpec>();
            var stub = new RecordingComputeProvider(captured);
            var labels = Map.of("aether-cluster", "test-a", "aether-source", "hetzner-eu", "aether-role", "core");
            var group = NodeGroupConfig.nodeGroupConfig("hetzner-eu", "core", 1, "cx22", "fsn1", labels);

            CloudProviderSupport.provisionVia(stub, group)
                                .await()
                                .onFailure(cause -> assertThat(cause).isNull())
                                .onSuccess(nodes -> assertThat(nodes).hasSize(1));

            assertThat(captured.get()).isNotNull();
            assertHasClusterLabels(captured.get());
        }
    }

    private static void assertHasClusterLabels(ProvisionSpec spec) {
        assertThat(spec.tags()).containsEntry("aether-cluster", "test-a")
                               .containsEntry("aether-source", "hetzner-eu")
                               .containsEntry("aether-role", "core");
    }

    private static void assertZoneFsn1(ProvisionSpec spec) {
        assertThat(spec.placement().isEmpty()).isFalse();
        spec.placement().onPresent(CloudProviderSupportTest::assertHintIsZoneFsn1);
    }

    private static void assertHintIsZoneFsn1(PlacementHint hint) {
        assertThat(hint).isInstanceOf(PlacementHint.ZoneHint.class);
        assertThat(((PlacementHint.ZoneHint) hint).zoneName()).isEqualTo("fsn1");
    }

    private static void assertSizeAndPool(ProvisionSpec spec) {
        assertThat(spec.instanceSize()).isEqualTo("cx32");
        assertThat(spec.pool()).isEqualTo("worker");
    }

    /// Stub provider that captures the ProvisionSpec it receives so the test can
    /// assert what CloudProviderSupport propagates from NodeGroupConfig.
    static final class RecordingComputeProvider implements ComputeProvider {
        private final AtomicReference<ProvisionSpec> captured;

        RecordingComputeProvider(AtomicReference<ProvisionSpec> captured) {
            this.captured = captured;
        }

        @Override
        public Promise<InstanceInfo> provision(InstanceType instanceType) {
            return Promise.success(stubInstance("srv-default"));
        }

        @Override
        public Promise<InstanceInfo> provision(ProvisionSpec spec) {
            captured.set(spec);
            return Promise.success(stubInstance("srv-1"));
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Promise.success(stubInstance(instanceId.value()));
        }

        private static InstanceInfo stubInstance(String id) {
            return new InstanceInfo(new InstanceId(id),
                                    InstanceStatus.RUNNING,
                                    List.of("1.2.3.4"),
                                    InstanceType.ON_DEMAND,
                                    Map.of());
        }
    }
}
