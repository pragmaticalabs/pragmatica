// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.environment.azure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.pragmatica.cloud.azure.api.ResourceRow;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.MarketOptions;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.cloud.azure.AzureError;
import org.pragmatica.cloud.azure.api.CreateVmRequest.ImageReference;
import org.pragmatica.cloud.azure.api.VirtualMachine;
import org.pragmatica.cloud.azure.api.VirtualMachine.InstanceViewStatus;
import org.pragmatica.cloud.azure.api.VirtualMachine.Status;
import org.pragmatica.cloud.azure.api.VirtualMachine.VmProperties;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.ClusterName.maybeClusterName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.pragmatica.cloud.azure.AzureConfig.azureConfig;

class AzureComputeProviderTest {

    private static final AzureEnvironmentConfig CONFIG = AzureEnvironmentConfig.azureEnvironmentConfig(
        azureConfig("tenant", "client", "secret", "sub", "rg", "eastus"),
        "Standard_B2s", "Canonical:0001-com-ubuntu-server-jammy:22_04-lts-gen2:latest",
        "azureuser", "ssh-ed25519 AAAA", "/subscriptions/sub/resourceGroups/rg/providers/Microsoft.Network/virtualNetworks/vnet/subnets/default",
        "#!/bin/bash\necho hello").unwrap();

    private TestAzureClient testClient;
    private AzureComputeProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new TestAzureClient();
        provider = AzureComputeProvider.azureComputeProvider(testClient, CONFIG).unwrap();
    }

    @Nested
    class ProvisionTests {

        @Test
        void provision_success_returnsInstanceInfo() {
            testClient.createVmResponse = Promise.success(runningVm("aether-test"));

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(AzureComputeProviderTest::assertProvisionedInstanceInfo);
        }

        /// SHOULD-FIX from adversarial review: the requested-spec threading added by this change was
        /// pinned for Hetzner and for NOBODY ELSE — reverting it here left 253 tests green. A
        /// mutation that leaves everything green means unpinned, not independent.
        ///
        /// Sentinel values no catalogue contains, so this can only pass if the request's own fields
        /// are threaded onto the failure rather than matched incidentally.
        @Test
        void createFrom_failure_messageNamesRequestedInstanceTypeAndZone() {
            testClient.createVmResponse =
                new AzureError.ApiError(400, "SkuNotAvailable", "Sku not available").promise();
            var request = new ProvisionRequest(InstanceType.ON_DEMAND,
                                               "zz-sentinel-size-99",
                                               "Canonical:0001-com-ubuntu-server-jammy:22_04-lts:latest",
                                               "zz-sentinel-zone-99",
                                               Option.empty(),
                                               MarketOptions.ON_DEMAND,
                                               ProvisionContext.forBootstrap(clusterName("c").unwrap(), "core", sourceNameOrDefault("s"), "n0"));

            provider.createFrom(request)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(cause -> assertThat(cause.message()).contains("zz-sentinel-size-99")
                                                                   .contains("zz-sentinel-zone-99"));
        }

        @Test
        void provision_failure_mapsToEnvironmentError() {
            testClient.createVmResponse = new AzureError.ApiError(500, "InternalError", "Internal error").promise();

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(AzureComputeProviderTest::assertProvisionFailedError);
        }

        @Test
        void provision_setsCustomDataToBase64OfConfigUserData() {
            testClient.createVmResponse = Promise.success(runningVm("aether-test"));

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull());

            var expected = Base64.getEncoder()
                                 .encodeToString("#!/bin/bash\necho hello".getBytes(StandardCharsets.UTF_8));
            var customData = testClient.lastCreateRequest.properties().osProfile().customData();
            assertThat(customData).isEqualTo(expected);
        }

        @Test
        void provision_specUserDataOverridesConfig_customDataIsBase64OfSpec() {
            testClient.createVmResponse = Promise.success(runningVm("aether-test"));
            var ctx = ProvisionContext.provisionContext(maybeClusterName("c1"), "core", sourceNameOrDefault("default"),
                                                         ProvisionContext.PROVISIONED_BY_CTM);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "azure", "default", ctx)
                                    .unwrap()
                                    .withUserData("#cloud-config\nruncmd: [echo, hi]");

            provider.provision(spec)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull());

            var expected = Base64.getEncoder()
                                 .encodeToString("#cloud-config\nruncmd: [echo, hi]".getBytes(StandardCharsets.UTF_8));
            assertThat(testClient.lastCreateRequest.properties().osProfile().customData()).isEqualTo(expected);
        }
    }

    @Nested
    class CreateFromTests {

        @Test
        void createFrom_carriesResolvedVmSizeAndImage_toCreateRequest() {
            testClient.createVmResponse = Promise.success(runningVm("aether-test"));
            var request = new ProvisionRequest(InstanceType.ON_DEMAND,
                                               "Standard_D8s_v5",
                                               "Canonical:0001-com-ubuntu-server-jammy:22_04-lts:latest",
                                               "",
                                               Option.empty(),
                                               MarketOptions.ON_DEMAND,
                                               ProvisionContext.forBootstrap(clusterName("c").unwrap(), "core", sourceNameOrDefault("s"), "n0"));

            provider.createFrom(request)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull());

            var properties = testClient.lastCreateRequest.properties();
            assertThat(properties.hardwareProfile().vmSize()).isEqualTo("Standard_D8s_v5");
            assertThat(properties.storageProfile().imageReference())
                .isEqualTo(new ImageReference("Canonical", "0001-com-ubuntu-server-jammy", "22_04-lts", "latest"));
        }

        @Test
        void createFrom_spotRequest_rejectedLoud() {
            var request = new ProvisionRequest(InstanceType.SPOT,
                                               "Standard_D8s_v5",
                                               "Canonical:0001-com-ubuntu-server-jammy:22_04-lts:latest",
                                               "",
                                               Option.empty(),
                                               MarketOptions.spot(),
                                               ProvisionContext.forBootstrap(clusterName("c").unwrap(), "core", sourceNameOrDefault("s"), "n0"));

            provider.createFrom(request)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(AzureComputeProviderTest::assertProvisionFailedError);

            assertThat(testClient.lastCreateRequest).isNull();
        }
    }

    @Nested
    class TerminateTests {

        @Test
        void terminate_success_returnsUnit() {
            testClient.deleteVmResponse = Promise.success(Unit.unit());

            provider.terminate(new InstanceId("my-vm"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastDeletedVmName).isEqualTo("my-vm");
        }

        @Test
        void terminate_failure_mapsToEnvironmentError() {
            testClient.deleteVmResponse = new AzureError.ApiError(404, "NotFound", "Not found").promise();

            provider.terminate(new InstanceId("missing-vm"))
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(AzureComputeProviderTest::assertTerminateFailedError);
        }
    }

    @Nested
    class ListInstancesTests {

        @Test
        void listInstances_success_returnsMappedList() {
            testClient.listVmsResponse = Promise.success(List.of(
                runningVm("server-1"),
                provisioningVm("server-2")));

            provider.listInstances()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(AzureComputeProviderTest::assertTwoInstanceList);
        }

        @Test
        void listInstances_empty_returnsEmptyList() {
            testClient.listVmsResponse = Promise.success(List.of());

            provider.listInstances()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(instances -> assertThat(instances).isEmpty());
        }

        @Test
        void listInstances_failure_mapsToEnvironmentError() {
            testClient.listVmsResponse = new AzureError.ApiError(500, "InternalError", "Fail").promise();

            provider.listInstances()
                    .await()
                    .onSuccess(list -> assertThat(list).isNull())
                    .onFailure(AzureComputeProviderTest::assertListInstancesFailedError);
        }
    }

    @Nested
    class RestartTests {

        @Test
        void restart_success_callsRestartVm() {
            testClient.restartVmResponse = Promise.success(Unit.unit());

            provider.restart(new InstanceId("my-vm"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastRestartedVmName).isEqualTo("my-vm");
        }
    }

    @Nested
    class ApplyTagsTests {

        @Test
        void applyTags_success_updatesTags() {
            testClient.updateTagsResponse = Promise.success(runningVm("my-vm"));
            var tags = Map.of("env", "prod", "team", "aether");

            provider.applyTags(new InstanceId("my-vm"), tags)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastUpdateTagsVmName).isEqualTo("my-vm");
            assertThat(testClient.lastUpdateTags).isEqualTo(tags);
        }
    }

    @Nested
    class InstanceStatusTests {

        @Test
        void instanceStatus_success_returnsInstanceInfo() {
            testClient.getVmResponse = Promise.success(runningVm("my-vm"));

            provider.instanceStatus(new InstanceId("my-vm"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(AzureComputeProviderTest::assertRunningInstanceMyVm);

            assertThat(testClient.lastGetVmName).isEqualTo("my-vm");
        }

        @Test
        void instanceStatus_failure_mapsToEnvironmentError() {
            testClient.getVmResponse = new AzureError.ApiError(404, "NotFound", "Not found").promise();

            provider.instanceStatus(new InstanceId("missing"))
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(AzureComputeProviderTest::assertProvisionFailedError);
        }
    }

    @Nested
    class StatusMappingTests {

        @Test
        void mapStatus_powerStateRunning_returnsRunning() {
            var vm = vmWithPowerState("PowerState/running");
            assertThat(AzureComputeProvider.mapStatus(vm)).isEqualTo(InstanceStatus.RUNNING);
        }

        @Test
        void mapStatus_powerStateDeallocated_returnsStopping() {
            var vm = vmWithPowerState("PowerState/deallocated");
            assertThat(AzureComputeProvider.mapStatus(vm)).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapStatus_powerStateStopped_returnsStopping() {
            var vm = vmWithPowerState("PowerState/stopped");
            assertThat(AzureComputeProvider.mapStatus(vm)).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapStatus_powerStateStarting_returnsProvisioning() {
            var vm = vmWithPowerState("PowerState/starting");
            assertThat(AzureComputeProvider.mapStatus(vm)).isEqualTo(InstanceStatus.PROVISIONING);
        }

        @Test
        void mapStatus_provisioningStateSucceeded_returnsRunning() {
            var vm = vmWithProvisioningState("Succeeded");
            assertThat(AzureComputeProvider.mapStatus(vm)).isEqualTo(InstanceStatus.RUNNING);
        }

        @Test
        void mapStatus_provisioningStateCreating_returnsProvisioning() {
            var vm = vmWithProvisioningState("Creating");
            assertThat(AzureComputeProvider.mapStatus(vm)).isEqualTo(InstanceStatus.PROVISIONING);
        }

        @Test
        void mapStatus_provisioningStateDeleting_returnsStopping() {
            var vm = vmWithProvisioningState("Deleting");
            assertThat(AzureComputeProvider.mapStatus(vm)).isEqualTo(InstanceStatus.STOPPING);
        }

        /// #1049 — exhaustive over Azure's documented VM power states (States and billing status: starting,
        /// running, stopping, stopped, deallocating, deallocated) and provisioning states (Creating, Updating,
        /// Deleting, Succeeded, Failed), plus `PowerState/unknown` and `Canceled`, which must never read as
        /// terminated.
        @Test
        void mapStatus_everyDocumentedPowerAndProvisioningState_andUnrecognisedOnes_mapExhaustively() {
            var powerStates = Map.ofEntries(Map.entry("PowerState/starting", InstanceStatus.PROVISIONING),
                                            Map.entry("PowerState/running", InstanceStatus.RUNNING),
                                            Map.entry("PowerState/stopping", InstanceStatus.STOPPING),
                                            Map.entry("PowerState/stopped", InstanceStatus.STOPPING),
                                            Map.entry("PowerState/deallocating", InstanceStatus.STOPPING),
                                            Map.entry("PowerState/deallocated", InstanceStatus.STOPPING),
                                            Map.entry("PowerState/unknown", InstanceStatus.UNKNOWN));
            var provisioningStates = Map.ofEntries(Map.entry("Creating", InstanceStatus.PROVISIONING),
                                                   Map.entry("Updating", InstanceStatus.PROVISIONING),
                                                   Map.entry("Deleting", InstanceStatus.STOPPING),
                                                   Map.entry("Succeeded", InstanceStatus.RUNNING),
                                                   Map.entry("Failed", InstanceStatus.STOPPING),
                                                   Map.entry("Canceled", InstanceStatus.UNKNOWN));

            assertThat(powerStates).hasSize(7);
            assertThat(provisioningStates).hasSize(6);
            powerStates.forEach((code, mapped) -> assertThat(AzureComputeProvider.mapStatus(vmWithPowerState(code))).as(code).isEqualTo(mapped));
            provisioningStates.forEach((state, mapped) -> assertThat(AzureComputeProvider.mapStatus(vmWithProvisioningState(state))).as(state).isEqualTo(mapped));
        }
    }

    /// #1049 round 3 (S3) — the auto-heal tracker lists VMs through Resource Graph, and the row's status used
    /// to be hard-coded RUNNING, so a failed or stopped VM read as present until the ten-minute ceiling.
    @Nested
    class ResourceGraphRowStatusTests {

        @Test
        void listInstances_rowWithFailedProvisioningState_readsAsStopping_whichTheTrackerClassifiesFailed() {
            testClient.queryResourcesResponse = Promise.success(List.of(vmRow("vm-1", Map.of("provisioningState", "Failed"))));

            provider.listInstances(Map.of("aether.node-id", "node-7"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(instances -> assertThat(instances.getFirst().status()).isEqualTo(InstanceStatus.STOPPING));
        }

        @Test
        void mapRowStatus_deallocatedPowerState_readsAsStopping() {
            assertThat(AzureComputeProvider.mapRowStatus(vmRow("vm-1", powerStateProperties("PowerState/deallocated", "Succeeded"))))
                .isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapRowStatus_runningPowerStateAfterAFailedUpdate_readsAsRunning() {
            assertThat(AzureComputeProvider.mapRowStatus(vmRow("vm-1", powerStateProperties("PowerState/running", "Failed"))))
                .isEqualTo(InstanceStatus.RUNNING);
        }

        @Test
        void mapRowStatus_noPowerOrProvisioningState_readsAsUnknown_neverRunning() {
            assertThat(AzureComputeProvider.mapRowStatus(vmRow("vm-1", Map.of()))).isEqualTo(InstanceStatus.UNKNOWN);
        }

        @Test
        void mapStatus_vmWithoutProperties_readsAsUnknown_neverTerminated() {
            var vm = new VirtualMachine("/subscriptions/sub/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/vm-1",
                                        "vm-1", "eastus", Map.of(), null);

            assertThat(AzureComputeProvider.mapStatus(vm)).isEqualTo(InstanceStatus.UNKNOWN);
        }

        private static ResourceRow vmRow(String name, Map<String, Object> properties) {
            return new ResourceRow("/subscriptions/sub/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/" + name,
                                   name,
                                   "microsoft.compute/virtualmachines",
                                   "eastus",
                                   Map.of("aether-node-id", "node-7"),
                                   properties);
        }

        private static Map<String, Object> powerStateProperties(String powerStateCode, String provisioningState) {
            return Map.of("provisioningState", provisioningState,
                          "extended", Map.of("instanceView", Map.of("powerState", Map.of("code", powerStateCode))));
        }
    }

    @Nested
    class ImageParsingTests {

        @Test
        void parseImageUrn_fullUrn_parsesAllParts() {
            var ref = AzureComputeProvider.parseImageUrn("Canonical:0001-com-ubuntu-server-jammy:22_04-lts-gen2:latest");
            assertThat(ref).isEqualTo(new ImageReference("Canonical", "0001-com-ubuntu-server-jammy", "22_04-lts-gen2", "latest"));
        }

        @Test
        void parseImageUrn_partialUrn_usesDefaults() {
            var ref = AzureComputeProvider.parseImageUrn("Canonical");
            assertThat(ref.publisher()).isEqualTo("Canonical");
        }
    }

    @Nested
    class TagFilterQueryTests {

        @Test
        void buildTagFilterQuery_singleTag_generatesKql() {
            var query = AzureComputeProvider.buildTagFilterQuery(Map.of("env", "prod"));
            assertThat(query).contains("microsoft.compute/virtualmachines");
            assertThat(query).contains("tags[\"env\"] == \"prod\"");
        }

        /// #1049 — upper layers select a node's VM by the dotted `aether.node-id`, but this provider
        /// stamps the `aether-node-id` tag. Untranslated, the query matches nothing and an existing
        /// replacement reads as deleted to the auto-heal in-flight tracker.
        @Test
        void buildTagFilterQuery_nodeIdTag_translatesToStampedTagKey() {
            var query = AzureComputeProvider.buildTagFilterQuery(Map.of("aether.node-id", "node-7"));
            assertThat(query).contains("tags[\"aether-node-id\"] == \"node-7\"");
            assertThat(query).doesNotContain("aether.node-id");
        }

        @Test
        void buildTagFilterQuery_emptyTags_returnsBaseQuery() {
            var query = AzureComputeProvider.buildTagFilterQuery(Map.of());
            assertThat(query).isEqualTo("Resources | where type == \"microsoft.compute/virtualmachines\"");
        }
    }

    @Nested
    class EnvironmentIntegrationTests {

        @Test
        void compute_returnsProvider() {
            var integration = AzureEnvironmentIntegration.azureEnvironmentIntegration(testClient, CONFIG).unwrap();
            assertThat(integration.compute().isPresent()).isTrue();
        }

        @Test
        void secrets_returnsKeyVaultProvider() {
            var integration = AzureEnvironmentIntegration.azureEnvironmentIntegration(testClient, CONFIG).unwrap();
            assertThat(integration.secrets().isPresent()).isTrue();
        }

        @Test
        void discovery_presentWhenClusterNameSet() {
            var configWithDiscovery = CONFIG.withDiscovery(clusterName("my-cluster").unwrap());
            var integration = AzureEnvironmentIntegration.azureEnvironmentIntegration(testClient, configWithDiscovery).unwrap();
            assertThat(integration.discovery().isPresent()).isTrue();
        }

        @Test
        void discovery_emptyWhenNoClusterName() {
            var integration = AzureEnvironmentIntegration.azureEnvironmentIntegration(testClient, CONFIG).unwrap();
            assertThat(integration.discovery().isPresent()).isFalse();
        }
    }

    // --- Assertion helpers ---

    private static void assertProvisionedInstanceInfo(org.pragmatica.aether.environment.InstanceInfo info) {
        assertThat(info.id().value()).isEqualTo("aether-test");
        assertThat(info.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(info.type()).isEqualTo(InstanceType.ON_DEMAND);
    }

    private static void assertRunningInstanceMyVm(org.pragmatica.aether.environment.InstanceInfo info) {
        assertThat(info.id().value()).isEqualTo("my-vm");
        assertThat(info.status()).isEqualTo(InstanceStatus.RUNNING);
    }

    private static void assertProvisionFailedError(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.ProvisionFailed.class);
    }

    private static void assertTerminateFailedError(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.TerminateFailed.class);
    }

    private static void assertListInstancesFailedError(Cause cause) {
        assertThat(cause).isInstanceOf(EnvironmentError.ListInstancesFailed.class);
    }

    private static void assertTwoInstanceList(java.util.List<org.pragmatica.aether.environment.InstanceInfo> instances) {
        assertThat(instances).hasSize(2);
        assertThat(instances.get(0).id().value()).isEqualTo("server-1");
        assertThat(instances.get(0).status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instances.get(1).id().value()).isEqualTo("server-2");
        assertThat(instances.get(1).status()).isEqualTo(InstanceStatus.PROVISIONING);
    }

    // --- VM factory helpers ---

    static VirtualMachine runningVm(String name) {
        return vmWithPowerState(name, "PowerState/running", "Succeeded");
    }

    private static VirtualMachine provisioningVm(String name) {
        return new VirtualMachine("/subscriptions/sub/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/" + name,
                                   name, "eastus", Map.of(),
                                   new VmProperties("vmid-" + name, "Creating", null));
    }

    private static VirtualMachine vmWithPowerState(String powerState) {
        return vmWithPowerState("test-vm", powerState, "Succeeded");
    }

    private static VirtualMachine vmWithProvisioningState(String provisioningState) {
        return new VirtualMachine("/subscriptions/sub/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/test-vm",
                                   "test-vm", "eastus", Map.of(),
                                   new VmProperties("vmid-test", provisioningState, null));
    }

    private static VirtualMachine vmWithPowerState(String name, String powerState, String provisioningState) {
        var statuses = List.of(new Status("ProvisioningState/succeeded", "VM provisioned"),
                                new Status(powerState, "VM " + powerState));
        return new VirtualMachine("/subscriptions/sub/resourceGroups/rg/providers/Microsoft.Compute/virtualMachines/" + name,
                                   name, "eastus", Map.of(),
                                   new VmProperties("vmid-" + name, provisioningState,
                                                     new InstanceViewStatus(statuses)));
    }
}
