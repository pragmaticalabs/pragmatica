package org.pragmatica.aether.environment.docker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class DockerComputeProviderTest {

    private static final DockerConfig CONFIG = DockerConfig.dockerConfig().unwrap();

    private TestDockerCommandRunner testRunner;
    private DockerComputeProvider provider;

    @BeforeEach
    void setUp() {
        testRunner = new TestDockerCommandRunner();
        provider = DockerComputeProvider.dockerComputeProvider(testRunner, CONFIG).unwrap();
    }

    @Nested
    class ProvisionTests {

        @Test
        void provision_success_returnsInstanceInfo() {
            testRunner.nextResponse = Promise.success("abc123def456");

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                    .onSuccess(DockerComputeProviderTest::assertProvisionedInstance);
        }

        @Test
        void provision_withSpec_passesTagsToCommand() {
            testRunner.nextResponse = Promise.success("container-id-1");
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "docker", "staging",
                                                    Map.of("aether.cluster", "test-cluster",
                                                           "aether.role", "worker")).unwrap();

            provider.provision(spec)
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                    .onSuccess(info -> assertThat(info.tags().get("aether.cluster")).isEqualTo("test-cluster"));

            assertThat(testRunner.lastCommand).isNotEmpty();
            assertThat(testRunner.lastCommand).contains("--network", "aether-network");
        }

        @Test
        void provision_failure_mapsToEnvironmentError() {
            testRunner.nextResponse = new DockerError.CommandExecutionFailed(new RuntimeException("connection refused")).promise();

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(DockerComputeProviderTest::assertProvisionFailedError);
        }
    }

    @Nested
    class TerminateTests {

        @Test
        void terminate_success_returnsUnit() {
            testRunner.nextResponse = Promise.success("container-id");

            provider.terminate(new InstanceId("container-id"))
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                    .onSuccess(unit -> assertThat(unit).isNotNull());
        }

        @Test
        void terminate_failure_mapsToEnvironmentError() {
            testRunner.nextResponse = new DockerError.CommandExecutionFailed(new RuntimeException("no such container")).promise();

            provider.terminate(new InstanceId("nonexistent"))
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(DockerComputeProviderTest::assertTerminateFailedError);
        }
    }

    @Nested
    class ListInstancesTests {

        @Test
        void listInstances_success_returnsMappedList() {
            testRunner.nextResponse = Promise.success(
                "abc123\taether-default-node-0\trunning\tdefault\tcore\tnode-0\n" +
                "def456\taether-default-node-1\texited\tdefault\tworker\tnode-1");

            provider.listInstances()
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                    .onSuccess(DockerComputeProviderTest::assertTwoInstanceList);
        }

        @Test
        void listInstances_empty_returnsEmptyList() {
            testRunner.nextResponse = Promise.success("");

            provider.listInstances()
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                    .onSuccess(instances -> assertThat(instances).isEmpty());
        }

        @Test
        void listInstances_failure_mapsToEnvironmentError() {
            testRunner.nextResponse = new DockerError.CommandExecutionFailed(new RuntimeException("daemon not running")).promise();

            provider.listInstances()
                    .await()
                    .onSuccess(list -> assertThat(list).isNull())
                    .onFailure(DockerComputeProviderTest::assertListInstancesFailedError);
        }

        @Test
        void listInstances_withTagFilter_usesFilterArgs() {
            testRunner.nextResponse = Promise.success("abc123\taether-node-0\trunning\tprod\tcore\tnode-0");

            provider.listInstances(Map.of("aether.cluster", "prod"))
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                    .onSuccess(instances -> assertThat(instances).hasSize(1));

            assertThat(testRunner.lastCommand).contains("--filter", "label=aether.cluster=prod");
        }
    }

    @Nested
    class InstanceStatusTests {

        @Test
        void instanceStatus_success_returnsInstanceInfo() {
            testRunner.nextResponse = Promise.success("running\t/aether-node-0\taether-node-0\tabc123");

            provider.instanceStatus(new InstanceId("abc123"))
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                    .onSuccess(info -> assertThat(info.status()).isEqualTo(InstanceStatus.RUNNING));
        }

        @Test
        void instanceStatus_failure_mapsToEnvironmentError() {
            testRunner.nextResponse = new DockerError.CommandExecutionFailed(new RuntimeException("no such container")).promise();

            provider.instanceStatus(new InstanceId("missing"))
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(DockerComputeProviderTest::assertProvisionFailedError);
        }
    }

    @Nested
    class RestartTests {

        @Test
        void restart_success_callsDockerRestart() {
            testRunner.nextResponse = Promise.success("container-id");

            provider.restart(new InstanceId("container-id"))
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                    .onSuccess(unit -> assertThat(unit).isNotNull());

            assertThat(testRunner.lastCommand).contains("docker", "restart", "container-id");
        }
    }

    @Nested
    class ApplyTagsTests {

        @Test
        void applyTags_returnsNotSupported() {
            provider.applyTags(new InstanceId("any"), Map.of("key", "value"))
                    .await()
                    .onSuccess(unit -> assertThat(unit).isNull())
                    .onFailure(cause -> assertThat(cause).isInstanceOf(EnvironmentError.OperationNotSupported.class));
        }
    }

    @Nested
    class StatusMappingTests {

        @Test
        void mapDockerState_created_returnsProvisioning() {
            assertThat(DockerComputeProvider.mapDockerState("created")).isEqualTo(InstanceStatus.PROVISIONING);
        }

        @Test
        void mapDockerState_restarting_returnsProvisioning() {
            assertThat(DockerComputeProvider.mapDockerState("restarting")).isEqualTo(InstanceStatus.PROVISIONING);
        }

        @Test
        void mapDockerState_running_returnsRunning() {
            assertThat(DockerComputeProvider.mapDockerState("running")).isEqualTo(InstanceStatus.RUNNING);
        }

        @Test
        void mapDockerState_exited_returnsStopping() {
            assertThat(DockerComputeProvider.mapDockerState("exited")).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapDockerState_paused_returnsStopping() {
            assertThat(DockerComputeProvider.mapDockerState("paused")).isEqualTo(InstanceStatus.STOPPING);
        }

        @Test
        void mapDockerState_dead_returnsTerminated() {
            assertThat(DockerComputeProvider.mapDockerState("dead")).isEqualTo(InstanceStatus.TERMINATED);
        }

        @Test
        void mapDockerState_unknown_returnsTerminated() {
            assertThat(DockerComputeProvider.mapDockerState("garbage")).isEqualTo(InstanceStatus.TERMINATED);
        }
    }

    @Nested
    class ParseTests {

        @Test
        void parseContainerList_emptyString_returnsEmptyList() {
            assertThat(DockerComputeProvider.parseContainerList("")).isEmpty();
        }

        @Test
        void parseContainerList_singleLine_returnsSingleInstance() {
            var result = DockerComputeProvider.parseContainerList("abc\tnode-0\trunning\tcluster\tcore\tnode-id");

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().id().value()).isEqualTo("abc");
            assertThat(result.getFirst().status()).isEqualTo(InstanceStatus.RUNNING);
        }

        @Test
        void parseInspectOutput_validOutput_returnsInstanceInfo() {
            var result = DockerComputeProvider.parseInspectOutput("running\t/aether-node-0\taether-node-0\tabc123",
                                                                  new InstanceId("abc123"));

            assertThat(result.status()).isEqualTo(InstanceStatus.RUNNING);
            assertThat(result.addresses()).contains("aether-node-0");
        }
    }

    @Nested
    class EnvironmentIntegrationTests {

        @Test
        void compute_returnsProvider() {
            var integration = DockerEnvironmentIntegration.dockerEnvironmentIntegration(testRunner, CONFIG).unwrap();
            assertThat(integration.compute().isPresent()).isTrue();
        }

        @Test
        void secrets_returnsEmpty() {
            var integration = DockerEnvironmentIntegration.dockerEnvironmentIntegration(testRunner, CONFIG).unwrap();
            assertThat(integration.secrets().isPresent()).isFalse();
        }

        @Test
        void loadBalancer_returnsEmpty() {
            var integration = DockerEnvironmentIntegration.dockerEnvironmentIntegration(testRunner, CONFIG).unwrap();
            assertThat(integration.loadBalancer().isPresent()).isFalse();
        }

        @Test
        void discovery_returnsEmpty() {
            var integration = DockerEnvironmentIntegration.dockerEnvironmentIntegration(testRunner, CONFIG).unwrap();
            assertThat(integration.discovery().isPresent()).isFalse();
        }
    }

    @Nested
    class FactoryTests {

        @Test
        void providerName_returnsDocker() {
            var factory = new DockerEnvironmentIntegrationFactory();
            assertThat(factory.providerName()).isEqualTo("docker");
        }

        @Test
        void create_withDefaults_succeeds() {
            var config = new org.pragmatica.aether.environment.CloudConfig("docker", Map.of(), Map.of(),
                                                                            Map.of(), Map.of(), Map.of(), Map.of());
            var factory = new DockerEnvironmentIntegrationFactory();
            var result = factory.create(config);

            result.onFailure(cause -> fail("Expected success but got: " + cause.message()))
                  .onSuccess(env -> assertThat(env.compute().isPresent()).isTrue());
        }

        @Test
        void create_withCustomConfig_appliesValues() {
            var compute = Map.of("image_name", "my-image:latest",
                                 "network_name", "custom-net",
                                 "management_port_base", "9000",
                                 "app_port_base", "9100");
            var config = new org.pragmatica.aether.environment.CloudConfig("docker", Map.of(), compute,
                                                                            Map.of(), Map.of(), Map.of(), Map.of());
            var factory = new DockerEnvironmentIntegrationFactory();
            var result = factory.create(config);

            result.onFailure(cause -> fail("Expected success but got: " + cause.message()))
                  .onSuccess(env -> assertThat(env.compute().isPresent()).isTrue());
        }
    }

    // --- Assertion helpers ---

    private static void assertProvisionedInstance(org.pragmatica.aether.environment.InstanceInfo info) {
        assertThat(info.id().value()).isEqualTo("abc123def456");
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
        assertThat(instances.get(0).id().value()).isEqualTo("abc123");
        assertThat(instances.get(0).status()).isEqualTo(InstanceStatus.RUNNING);
        assertThat(instances.get(1).id().value()).isEqualTo("def456");
        assertThat(instances.get(1).status()).isEqualTo(InstanceStatus.STOPPING);
    }

    /// Test stub for DockerCommandRunner that returns canned responses and captures arguments.
    static final class TestDockerCommandRunner implements DockerCommandRunner {
        Promise<String> nextResponse = Promise.success("");
        List<String> lastCommand = List.of();

        @Override
        public Promise<String> execute(List<String> command) {
            lastCommand = command;
            return nextResponse;
        }
    }
}
