package org.pragmatica.aether.environment.gcp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.cloud.gcp.GcpError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.gcp.GcpConfig.gcpConfig;

class GcpComputeProviderTest {

    private static final GcpEnvironmentConfig CONFIG = GcpEnvironmentConfig.gcpEnvironmentConfig(
        gcpConfig("test-project", "us-central1-a", "test@test.iam.gserviceaccount.com", "fake-key"),
        "e2-medium", "projects/debian-cloud/global/images/debian-12",
        "global/networks/default", "regions/us-central1/subnetworks/default",
        "#!/bin/bash\necho hello").unwrap();

    private TestGcpClient testClient;
    private GcpComputeProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new TestGcpClient();
        provider = GcpComputeProvider.gcpComputeProvider(testClient, CONFIG).unwrap();
    }

    @Nested
    class ProvisionTests {

        @Test
        void provision_success_returnsInstanceInfo() {
            testClient.insertInstanceResponse = Promise.success(runningInstance("aether-test"));

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(GcpComputeProviderTest::assertProvisionedInstanceInfo);
        }

        @Test
        void provision_failure_mapsToEnvironmentError() {
            testClient.insertInstanceResponse = new GcpError.ApiError(500, "INTERNAL", "Internal error").promise();

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(GcpComputeProviderTest::assertProvisionFailedError);
        }
    }

    @Nested
    class TerminateTests {

        @Test
        void terminate_success_returnsUnit() {
            testClient.deleteInstanceResponse = Promise.success(Unit.unit());

            provider.terminate(new InstanceId("aether-test"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastDeletedInstanceName).isEqualTo("aether-test");
        }

        @Test
        void terminate_failure_mapsToEnvironmentError() {
            testClient.deleteInstanceResponse = new GcpError.ApiError(404, "NOT_FOUND", "Not found").promise();

            provider.terminate(new InstanceId("missing"))
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(GcpComputeProviderTest::assertTerminateFailedError);
        }
    }

    @Nested
    class ListInstancesTests {

        @Test
        void listInstances_success_returnsMappedList() {
            testClient.listInstancesResponse = Promise.success(List.of(
                runningInstance("server-1"),
                stagingInstance("server-2")));

            provider.listInstances()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(GcpComputeProviderTest::assertTwoInstanceList);
        }

        @Test
        void listInstances_empty_returnsEmptyList() {
            testClient.listInstancesResponse = Promise.success(List.of());

            provider.listInstances()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(instances -> assertThat(instances).isEmpty());
        }

        @Test
        void listInstances_failure_mapsToEnvironmentError() {
            testClient.listInstancesResponse = new GcpError.ApiError(500, "INTERNAL", "Fail").promise();

            provider.listInstances()
                    .await()
                    .onSuccess(list -> assertThat(list).isNull())
                    .onFailure(GcpComputeProviderTest::assertListInstancesFailedError);
        }

        @Test
        void listInstances_withTagFilter_usesLabelFilter() {
            testClient.listInstancesResponse = Promise.success(List.of(
                instanceWithLabels("server-1", Map.of("env", "prod"))));

            provider.listInstances(Map.of("env", "prod"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(instances -> assertThat(instances).hasSize(1));

            assertThat(testClient.lastLabelFilter).isEqualTo("labels.env=prod");
        }
    }

    @Nested
    class RestartTests {

        @Test
        void restart_success_callsReset() {
            provider.restart(new InstanceId("aether-test"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastResetInstanceName).isEqualTo("aether-test");
        }
    }

    @Nested
    class ApplyTagsTests {

        @Test
        void applyTags_success_setsLabels() {
            var tags = Map.of("env", "prod", "team", "aether");

            provider.applyTags(new InstanceId("aether-test"), tags)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastSetLabelsInstanceName).isEqualTo("aether-test");
            assertThat(testClient.lastSetLabels).isEqualTo(tags);
        }
    }

    @Nested
    class InstanceStatusTests {

        @Test
        void instanceStatus_success_returnsInstanceInfo() {
            testClient.getInstanceResponse = Promise.success(runningInstance("my-server"));

            provider.instanceStatus(new InstanceId("my-server"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(GcpComputeProviderTest::assertRunningInstance);

            assertThat(testClient.lastGetInstanceName).isEqualTo("my-server");
        }

        @Test
        void instanceStatus_failure_mapsToEnvironmentError() {
            testClient.getInstanceResponse = new GcpError.ApiError(404, "NOT_FOUND", "Not found").promise();

            provider.instanceStatus(new InstanceId("missing"))
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(GcpComputeProviderTest::assertProvisionFailedError);
        }
    }

    @Nested
    class StatusMappingTests {

        @Test
        void mapStatus_provisioning_returnsProvisioning() {
            assertThat(GcpComputeProvider.mapStatus("PROVISIONING")).isEqualTo(InstanceStatus.PROVISIONING);
        }

        @Test
        void mapStatus_staging_returnsProvisioning() {
            assertThat(GcpComputeProvider.mapStatus("STAGING")).isEqualTo(InstanceStatus.PROVISIONING);
        }

        @Test
        void mapStatus_running_returnsRunning() {
            assertThat(GcpComputeProvider.mapStatus("RUNNING")).isEqualTo(InstanceStatus.RUNNING);
        }

        @Test
        void mapStatus_stopping_returnsStopping() {
            assertThat(GcpComputeProvider.mapStatus("STOPPING")).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapStatus_terminated_returnsStopping() {
            assertThat(GcpComputeProvider.mapStatus("TERMINATED")).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapStatus_suspended_returnsStopping() {
            assertThat(GcpComputeProvider.mapStatus("SUSPENDED")).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapStatus_unknown_returnsTerminated() {
            assertThat(GcpComputeProvider.mapStatus("UNKNOWN")).isEqualTo(InstanceStatus.TERMINATED);
        }
    }

    @Nested
    class AddressCollectionTests {

        @Test
        void collectAddresses_withNetworkInterfaces_returnsIps() {
            var instance = instanceWithNetworkIps("10.0.0.1", "10.0.0.2");
            var addresses = GcpComputeProvider.collectAddresses(instance);

            assertThat(addresses).containsExactly("10.0.0.1", "10.0.0.2");
        }

        @Test
        void collectAddresses_noInterfaces_returnsEmpty() {
            var instance = instanceWithoutNetwork("test");
            var addresses = GcpComputeProvider.collectAddresses(instance);

            assertThat(addresses).isEmpty();
        }
    }

    @Nested
    class LabelFilterTests {

        @Test
        void toLabelFilter_singleEntry_formatsCorrectly() {
            assertThat(GcpComputeProvider.toLabelFilter(Map.of("key1", "val1")))
                .isEqualTo("labels.key1=val1");
        }

        @Test
        void toLabelFilter_emptyMap_returnsEmptyString() {
            assertThat(GcpComputeProvider.toLabelFilter(Map.of())).isEmpty();
        }
    }

    @Nested
    class EnvironmentIntegrationTests {

        @Test
        void compute_returnsProvider() {
            var integration = GcpEnvironmentIntegration.gcpEnvironmentIntegration(testClient, CONFIG).unwrap();

            assertThat(integration.compute().isPresent()).isTrue();
        }

        @Test
        void secrets_returnsGcpProvider() {
            var integration = GcpEnvironmentIntegration.gcpEnvironmentIntegration(testClient, CONFIG).unwrap();

            assertThat(integration.secrets().isPresent()).isTrue();
        }

        @Test
        void discovery_presentWhenClusterNameSet() {
            var configWithDiscovery = CONFIG.withDiscovery("my-cluster");
            var integration = GcpEnvironmentIntegration.gcpEnvironmentIntegration(testClient, configWithDiscovery).unwrap();

            assertThat(integration.discovery().isPresent()).isTrue();
        }

        @Test
        void discovery_emptyWhenNoClusterName() {
            var integration = GcpEnvironmentIntegration.gcpEnvironmentIntegration(testClient, CONFIG).unwrap();

            assertThat(integration.discovery().isPresent()).isFalse();
        }
    }

    // --- Assertion helpers ---

    private static void assertProvisionedInstanceInfo(InstanceInfo info) {
        assertThat(info.id().value()).isEqualTo("aether-test");
        assertThat(info.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(info.addresses()).contains("10.0.0.1");
        assertThat(info.type()).isEqualTo(InstanceType.ON_DEMAND);
    }

    private static void assertRunningInstance(InstanceInfo info) {
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

    private static void assertTwoInstanceList(java.util.List<InstanceInfo> instances) {
        assertThat(instances).hasSize(2);
        assertThat(instances.get(0).id().value()).isEqualTo("server-1");
        assertThat(instances.get(0).status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instances.get(1).id().value()).isEqualTo("server-2");
        assertThat(instances.get(1).status()).isEqualTo(InstanceStatus.PROVISIONING);
    }

    // --- Instance factory helpers ---

    static org.pragmatica.cloud.gcp.api.Instance runningInstance(String name) {
        return new org.pragmatica.cloud.gcp.api.Instance(name, "RUNNING", "us-central1-a",
            List.of(new org.pragmatica.cloud.gcp.api.Instance.NetworkInterface("10.0.0.1", "default")),
            Map.of());
    }

    private static org.pragmatica.cloud.gcp.api.Instance stagingInstance(String name) {
        return new org.pragmatica.cloud.gcp.api.Instance(name, "STAGING", "us-central1-a",
            List.of(new org.pragmatica.cloud.gcp.api.Instance.NetworkInterface("10.0.0.2", "default")),
            Map.of());
    }

    private static org.pragmatica.cloud.gcp.api.Instance instanceWithLabels(String name, Map<String, String> labels) {
        return new org.pragmatica.cloud.gcp.api.Instance(name, "RUNNING", "us-central1-a",
            List.of(new org.pragmatica.cloud.gcp.api.Instance.NetworkInterface("10.0.0.1", "default")),
            labels);
    }

    private static org.pragmatica.cloud.gcp.api.Instance instanceWithNetworkIps(String... ips) {
        var interfaces = java.util.Arrays.stream(ips)
            .map(ip -> new org.pragmatica.cloud.gcp.api.Instance.NetworkInterface(ip, "default"))
            .toList();
        return new org.pragmatica.cloud.gcp.api.Instance("test", "RUNNING", "us-central1-a", interfaces, Map.of());
    }

    private static org.pragmatica.cloud.gcp.api.Instance instanceWithoutNetwork(String name) {
        return new org.pragmatica.cloud.gcp.api.Instance(name, "RUNNING", "us-central1-a", null, Map.of());
    }
}
