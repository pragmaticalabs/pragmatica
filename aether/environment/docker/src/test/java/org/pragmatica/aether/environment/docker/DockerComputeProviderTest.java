// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.environment.docker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

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
    class BuildRunCommandTests {

        @Test
        void buildRunCommand_exposeHostPortsFalse_doesNotAddPortMapping() {
            testRunner.nextResponse = Promise.success("container-id-0");

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            assertThat(testRunner.lastCommand).doesNotContain("-p");
        }

        @Test
        void buildRunCommand_exposeHostPortsTrue_publishesEphemeralManagementPort() {
            var exposingConfig = DockerConfig.dockerConfig("aether-node:local",
                                                            "aether-network",
                                                            5160,
                                                            8080,
                                                            6000,
                                                            "/var/run/docker.sock",
                                                            "",
                                                            "",
                                                            true)
                                              .unwrap();
            var exposingRunner = new TestDockerCommandRunner();
            exposingRunner.nextResponse = Promise.success("container-id-0");
            var exposingProvider = DockerComputeProvider.dockerComputeProvider(exposingRunner, exposingConfig).unwrap();

            exposingProvider.provision(InstanceType.ON_DEMAND)
                            .await()
                            .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // KSUID-minted replacements have no numeric slot, so the management port is
            // published to an ephemeral host port (`-p 8080`) rather than `base + slot`.
            assertThat(exposingRunner.lastCommand).containsSequence("-p", "8080");
        }

        @Test
        void buildRunCommand_twoProvisions_mintDistinctKsuidNames() {
            var exposingRunner = new TestDockerCommandRunner();
            var exposingProvider = DockerComputeProvider.dockerComputeProvider(exposingRunner, CONFIG).unwrap();

            exposingRunner.nextResponse = Promise.success("container-id-0");
            exposingProvider.provision(InstanceType.ON_DEMAND).await();
            var firstName = nameArg(exposingRunner.lastCommand);

            exposingRunner.nextResponse = Promise.success("container-id-1");
            exposingProvider.provision(InstanceType.ON_DEMAND).await();
            var secondName = nameArg(exposingRunner.lastCommand);

            // Each provision mints a fresh KSUID identity — no slot reuse, always distinct.
            assertThat(firstName).startsWith("aether-default-node-");
            assertThat(secondName).startsWith("aether-default-node-");
            assertThat(firstName).isNotEqualTo(secondName);
        }
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
            var ctx = ProvisionContext.provisionContext("test-cluster", "worker", "default",
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "docker", "staging", ctx).unwrap();

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

        @Test
        void provisionFailure_dockerRunFails_rollbackInvokedByContainerName() {
            // Queue: 1) docker run fails (port bind), 2) docker rm -f succeeds (rollback).
            // No slot-probe is issued any more — the identity is minted in-process via KSUID.
            testRunner.queuedResponses.add(new DockerError.CommandExecutionFailed(
                new RuntimeException("driver failed programming external connectivity")).promise());
            testRunner.queuedResponses.add(Promise.success("removed-container-name"));

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onSuccess(info -> fail("Expected provision failure but got: " + info));

            assertThat(testRunner.allCommands).hasSize(2);
            var rollbackCmd = testRunner.allCommands.get(1);
            assertThat(rollbackCmd.subList(0, 3)).containsExactly("docker", "rm", "-f");
            // The 4th arg is the KSUID-minted container name. provision(InstanceType)
            // uses ProvisionContext.provisionContext("default", "core", "default", ...),
            // so the name is `aether-default-node-<ksuid>`.
            assertThat(rollbackCmd.get(3)).startsWith("aether-default-node-");
        }

        @Test
        void provisionFailure_dockerRunAndRollbackBothFail_rollbackErrorLoggedNotPropagated() {
            // Queue: 1) run fails, 2) rollback fails. No slot-probe precedes these.
            testRunner.queuedResponses.add(new DockerError.CommandExecutionFailed(
                new RuntimeException("port bind failed")).promise());
            testRunner.queuedResponses.add(new DockerError.CommandExecutionFailed(
                new RuntimeException("no such container")).promise());

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onSuccess(info -> fail("Expected provision failure"))
                    .onFailure(DockerComputeProviderTest::assertProvisionFailedError);

            assertThat(testRunner.allCommands).hasSize(2);
        }

        @Test
        void provisionSuccess_noRollbackInvoked() {
            testRunner.nextResponse = Promise.success("container-id-success");

            provider.provision(InstanceType.ON_DEMAND).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // Only `docker run` (no slot-probe, no rollback). One command total.
            assertThat(testRunner.allCommands).hasSize(1);
            assertThat(testRunner.allCommands.get(0)).contains("run");
        }
    }

    @Nested
    class ContainerNameTests {

        @Test
        void buildContainerName_mintsKsuidNameForCluster() {
            testRunner.nextResponse = Promise.success("id-0");
            var ctx = ProvisionContext.provisionContext("test-cluster", "core", "default",
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "docker", "worker-pool", ctx).unwrap();

            provider.provision(spec).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // KSUID-minted identity: `aether-<cluster>-node-<ksuid>`. Cluster segment from
            // ProvisionContext.clusterName; the KSUID suffix is unique and k-sortable.
            var name = nameArg(testRunner.lastCommand);
            assertThat(name).startsWith("aether-test-cluster-node-");
            assertThat(name).isNotEqualTo("aether-test-cluster-node-");
        }

        @Test
        void buildContainerName_honorsCallerSuppliedNodeId() {
            testRunner.nextResponse = Promise.success("id-0");
            var ctx = ProvisionContext.provisionContext("test-cluster", "core", "default",
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP)
                                      .withNodeId("aether-test-cluster-node-1");
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "docker", "worker-pool", ctx).unwrap();

            provider.provision(spec).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // Caller-supplied nodeId (bootstrap path) is used verbatim as the container name.
            assertThat(nameArg(testRunner.lastCommand)).isEqualTo("aether-test-cluster-node-1");
        }

        @Test
        void buildContainerName_defaultsClusterWhenAbsent() {
            testRunner.nextResponse = Promise.success("id-0");

            provider.provision(InstanceType.ON_DEMAND).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // provision(InstanceType) hardcodes cluster="default"; KSUID-minted name.
            assertThat(nameArg(testRunner.lastCommand)).startsWith("aether-default-node-");
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

        @Test
        void create_withUnresolvedEnvPlaceholder_fallsBackToDefaults() {
            // Simulates the case where ConfigLoader didn't substitute ${env:AETHER_MGMT_PORT_BASE}
            // (env var unset). Integer.parseInt on the literal placeholder fails, factory
            // must fall back to baked-in numeric defaults (5150 / 8070).
            var compute = Map.of("management_port_base", "${env:AETHER_MGMT_PORT_BASE}",
                                 "app_port_base", "${env:AETHER_APP_PORT_BASE}");
            var config = new org.pragmatica.aether.environment.CloudConfig("docker", Map.of(), compute,
                                                                            Map.of(), Map.of(), Map.of(), Map.of());
            var factory = new DockerEnvironmentIntegrationFactory();
            var result = factory.create(config);

            // Factory accepts the input (no failure), produces an integration with defaults.
            result.onFailure(cause -> fail("Expected success but got: " + cause.message()))
                  .onSuccess(env -> assertThat(env.compute().isPresent()).isTrue());
        }
    }

    // --- Assertion helpers ---

    /// Extract the value following `--name` in a captured `docker run` command.
    private static String nameArg(List<String> command) {
        var nameIdx = command.indexOf("--name");
        assertThat(nameIdx).isPositive();
        return command.get(nameIdx + 1);
    }

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
        List<List<String>> allCommands = new ArrayList<>();
        Queue<Promise<String>> queuedResponses = new LinkedList<>();

        @Override
        public Promise<String> execute(List<String> command) {
            lastCommand = command;
            allCommands.add(List.copyOf(command));
            if (!queuedResponses.isEmpty()) {
                return queuedResponses.poll();
            }
            return nextResponse;
        }
    }
}
