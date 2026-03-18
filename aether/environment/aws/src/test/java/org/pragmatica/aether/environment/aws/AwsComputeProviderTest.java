package org.pragmatica.aether.environment.aws;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.cloud.aws.AwsError;
import org.pragmatica.cloud.aws.api.Instance;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cloud.aws.AwsConfig.awsConfig;

class AwsComputeProviderTest {

    private static final AwsEnvironmentConfig CONFIG = AwsEnvironmentConfig.awsEnvironmentConfig(
        awsConfig("test-key", "test-secret", "us-east-1"),
        "ami-12345", "t3.medium",
        Option.some("my-key"),
        List.of("sg-123"),
        "subnet-abc",
        "#!/bin/bash\necho hello").unwrap();

    private TestAwsClient testClient;
    private AwsComputeProvider provider;

    @BeforeEach
    void setUp() {
        testClient = new TestAwsClient();
        provider = AwsComputeProvider.awsComputeProvider(testClient, CONFIG).unwrap();
    }

    @Nested
    class ProvisionTests {

        @Test
        void provision_success_returnsInstanceInfo() {
            testClient.runInstancesResponse = Promise.success(
                TestAwsClient.runResponseWith(runningInstance("i-abc123")));

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(AwsComputeProviderTest::assertProvisionedInstanceInfo);
        }

        @Test
        void provision_failure_mapsToEnvironmentError() {
            testClient.runInstancesResponse = new AwsError.ApiError(500, "InternalError", "Something broke").promise();

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(AwsComputeProviderTest::assertProvisionFailedError);
        }
    }

    @Nested
    class TerminateTests {

        @Test
        void terminate_success_returnsUnit() {
            provider.terminate(new InstanceId("i-abc123"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastTerminatedIds).containsExactly("i-abc123");
        }

        @Test
        void terminate_failure_mapsToEnvironmentError() {
            testClient.terminateResponse = new AwsError.ApiError(404, "NotFound", "Instance not found").promise();

            provider.terminate(new InstanceId("i-missing"))
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(AwsComputeProviderTest::assertTerminateFailedError);
        }
    }

    @Nested
    class ListInstancesTests {

        @Test
        void listInstances_success_returnsMappedList() {
            testClient.describeResponse = Promise.success(
                TestAwsClient.describeResponseWith(List.of(
                    runningInstance("i-1"),
                    pendingInstance("i-2"))));

            provider.listInstances()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(AwsComputeProviderTest::assertTwoInstanceList);
        }

        @Test
        void listInstances_empty_returnsEmptyList() {
            provider.listInstances()
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(instances -> assertThat(instances).isEmpty());
        }

        @Test
        void listInstances_failure_mapsToEnvironmentError() {
            testClient.describeResponse = new AwsError.ApiError(500, "InternalError", "Fail").promise();

            provider.listInstances()
                    .await()
                    .onSuccess(list -> assertThat(list).isNull())
                    .onFailure(AwsComputeProviderTest::assertListInstancesFailedError);
        }

        @Test
        void listInstances_withTagFilter_usesTagQuery() {
            testClient.describeResponse = Promise.success(
                TestAwsClient.describeResponseWith(List.of(
                    instanceWithTags("i-1", Map.of("env", "prod")))));

            provider.listInstances(Map.of("env", "prod"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(instances -> assertThat(instances).hasSize(1));

            assertThat(testClient.lastDescribeTagKey).isEqualTo("env");
            assertThat(testClient.lastDescribeTagValue).isEqualTo("prod");
        }
    }

    @Nested
    class RestartTests {

        @Test
        void restart_success_callsReboot() {
            provider.restart(new InstanceId("i-abc123"))
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastRebootedIds).containsExactly("i-abc123");
        }
    }

    @Nested
    class ApplyTagsTests {

        @Test
        void applyTags_success_createsTags() {
            var tags = Map.of("env", "prod", "team", "aether");

            provider.applyTags(new InstanceId("i-abc123"), tags)
                    .await()
                    .onFailure(cause -> assertThat(cause).isNull())
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testClient.lastTagResourceIds).containsExactly("i-abc123");
            assertThat(testClient.lastTags).isEqualTo(tags);
        }
    }

    @Nested
    class StatusMappingTests {

        @Test
        void mapStatus_pending_returnsProvisioning() {
            assertThat(AwsComputeProvider.mapStatus("pending")).isEqualTo(InstanceStatus.PROVISIONING);
        }

        @Test
        void mapStatus_running_returnsRunning() {
            assertThat(AwsComputeProvider.mapStatus("running")).isEqualTo(InstanceStatus.RUNNING);
        }

        @Test
        void mapStatus_stopping_returnsStopping() {
            assertThat(AwsComputeProvider.mapStatus("stopping")).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapStatus_stopped_returnsStopping() {
            assertThat(AwsComputeProvider.mapStatus("stopped")).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapStatus_shuttingDown_returnsTerminated() {
            assertThat(AwsComputeProvider.mapStatus("shutting-down")).isEqualTo(InstanceStatus.TERMINATED);
        }

        @Test
        void mapStatus_terminated_returnsTerminated() {
            assertThat(AwsComputeProvider.mapStatus("terminated")).isEqualTo(InstanceStatus.TERMINATED);
        }

        @Test
        void mapStatus_unknown_returnsTerminated() {
            assertThat(AwsComputeProvider.mapStatus("unknown")).isEqualTo(InstanceStatus.TERMINATED);
        }
    }

    @Nested
    class AddressCollectionTests {

        @Test
        void collectAddresses_withPublicAndPrivate_returnsBoth() {
            var instance = instanceWithAddresses("1.2.3.4", "10.0.0.1");
            var addresses = AwsComputeProvider.collectAddresses(instance);

            assertThat(addresses).containsExactly("1.2.3.4", "10.0.0.1");
        }

        @Test
        void collectAddresses_publicOnly_returnsPublic() {
            var instance = instanceWithAddresses("1.2.3.4", null);
            var addresses = AwsComputeProvider.collectAddresses(instance);

            assertThat(addresses).containsExactly("1.2.3.4");
        }

        @Test
        void collectAddresses_noAddresses_returnsEmpty() {
            var instance = instanceWithAddresses(null, null);
            var addresses = AwsComputeProvider.collectAddresses(instance);

            assertThat(addresses).isEmpty();
        }
    }

    @Nested
    class TagExtractionTests {

        @Test
        void extractTags_withTags_returnsMap() {
            var instance = instanceWithTags("i-1", Map.of("env", "prod", "team", "aether"));
            var tags = AwsComputeProvider.extractTags(instance);

            assertThat(tags).containsEntry("env", "prod");
            assertThat(tags).containsEntry("team", "aether");
        }

        @Test
        void extractTags_noTags_returnsEmptyMap() {
            var instance = runningInstance("i-1");
            var tags = AwsComputeProvider.extractTags(instance);

            assertThat(tags).isEmpty();
        }
    }

    @Nested
    class EnvironmentIntegrationTests {

        @Test
        void compute_returnsProvider() {
            var integration = AwsEnvironmentIntegration.awsEnvironmentIntegration(testClient, CONFIG).unwrap();

            assertThat(integration.compute().isPresent()).isTrue();
        }

        @Test
        void secrets_returnsProvider() {
            var integration = AwsEnvironmentIntegration.awsEnvironmentIntegration(testClient, CONFIG).unwrap();

            assertThat(integration.secrets().isPresent()).isTrue();
        }

        @Test
        void discovery_presentWhenClusterNameSet() {
            var configWithDiscovery = CONFIG.withDiscovery("my-cluster");
            var integration = AwsEnvironmentIntegration.awsEnvironmentIntegration(testClient, configWithDiscovery).unwrap();

            assertThat(integration.discovery().isPresent()).isTrue();
        }

        @Test
        void discovery_emptyWhenNoClusterName() {
            var integration = AwsEnvironmentIntegration.awsEnvironmentIntegration(testClient, CONFIG).unwrap();

            assertThat(integration.discovery().isPresent()).isFalse();
        }

        @Test
        void loadBalancer_presentWhenConfigured() {
            var lbConfig = AwsEnvironmentConfig.AwsLbConfig.awsLbConfig("arn:aws:elasticloadbalancing:us-east-1:123:targetgroup/my-tg/abc").unwrap();
            var configWithLb = AwsEnvironmentConfig.awsEnvironmentConfig(
                CONFIG.awsConfig(), CONFIG.amiId(), CONFIG.instanceType(),
                CONFIG.keyName(), CONFIG.securityGroupIds(), CONFIG.subnetId(),
                CONFIG.userData(), lbConfig).unwrap();
            var integration = AwsEnvironmentIntegration.awsEnvironmentIntegration(testClient, configWithLb).unwrap();

            assertThat(integration.loadBalancer().isPresent()).isTrue();
        }

        @Test
        void loadBalancer_emptyWhenNotConfigured() {
            var integration = AwsEnvironmentIntegration.awsEnvironmentIntegration(testClient, CONFIG).unwrap();

            assertThat(integration.loadBalancer().isPresent()).isFalse();
        }
    }

    // --- Assertion helpers ---

    private static void assertProvisionedInstanceInfo(org.pragmatica.aether.environment.InstanceInfo info) {
        assertThat(info.id().value()).isEqualTo("i-abc123");
        assertThat(info.status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(info.type()).isEqualTo(InstanceType.ON_DEMAND);
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
        assertThat(instances.get(0).id().value()).isEqualTo("i-1");
        assertThat(instances.get(0).status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instances.get(1).id().value()).isEqualTo("i-2");
        assertThat(instances.get(1).status()).isEqualTo(InstanceStatus.PROVISIONING);
    }

    // --- Instance factory helpers ---

    static Instance runningInstance(String instanceId) {
        return new Instance(instanceId, "t3.medium", "ami-12345",
                            "10.0.0.1", "1.2.3.4",
                            new Instance.InstanceState("running", 16),
                            null);
    }

    static Instance pendingInstance(String instanceId) {
        return new Instance(instanceId, "t3.medium", "ami-12345",
                            "10.0.0.2", null,
                            new Instance.InstanceState("pending", 0),
                            null);
    }

    static Instance instanceWithAddresses(String publicIp, String privateIp) {
        return new Instance("i-test", "t3.medium", "ami-12345",
                            privateIp, publicIp,
                            new Instance.InstanceState("running", 16),
                            null);
    }

    static Instance instanceWithTags(String instanceId, Map<String, String> tags) {
        var tagItems = tags.entrySet()
                           .stream()
                           .map(e -> new Instance.Tag(e.getKey(), e.getValue()))
                           .toList();
        return new Instance(instanceId, "t3.medium", "ami-12345",
                            "10.0.0.1", "1.2.3.4",
                            new Instance.InstanceState("running", 16),
                            new Instance.TagSet(tagItems));
    }
}
