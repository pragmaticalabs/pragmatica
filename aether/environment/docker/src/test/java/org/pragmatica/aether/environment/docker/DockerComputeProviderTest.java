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
import org.pragmatica.aether.environment.MarketOptions;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.ClusterName.maybeClusterName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

class DockerComputeProviderTest {

    private static final DockerConfig CONFIG = DockerConfig.dockerConfig().unwrap();

    /// `docker inspect` output reporting a RUNNING container, as parsed by
    /// `parseInspectOutput`: `{{.State.Status}}\t{{.Name}}\t{{.Config.Hostname}}\t{{.Id}}`.
    /// confirmRunning() issues this read right after `docker run`, so provisioning tests
    /// must queue it to let infra-readiness confirmation pass.
    private static final String RUNNING_INSPECT = "running\t/aether-node\taether-node\tabc";

    private TestDockerCommandRunner testRunner;
    private DockerComputeProvider provider;

    @BeforeEach
    void setUp() {
        testRunner = new TestDockerCommandRunner();
        provider = DockerComputeProvider.dockerComputeProvider(testRunner, CONFIG).unwrap();
    }

    @Nested
    class CreateFromTests {

        @Test
        void createFrom_spotRequest_rejectedLoudBeforeDockerRun() {
            // Docker has no spot concept; a SPOT request must fail loud, never issue a docker run.
            var context = ProvisionContext.forBootstrap(clusterName("c").unwrap(), "spot", sourceNameOrDefault("s"), "n0");
            var request = new ProvisionRequest(InstanceType.SPOT, "docker", "", "",
                                               Option.empty(), MarketOptions.spot(), context);

            provider.createFrom(request)
                    .await()
                    .onSuccess(info -> assertThat(info).isNull())
                    .onFailure(DockerComputeProviderTest::assertProvisionFailedError);

            assertThat(testRunner.allCommands).as("spot request must not issue any docker command").isEmpty();
        }
    }

    @Nested
    class BuildRunCommandTests {

        @Test
        void buildRunCommand_exposeHostPortsFalse_doesNotAddPortMapping() {
            testRunner.queuedResponses.add(Promise.success("container-id-0"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            assertThat(testRunner.allCommands.getFirst()).doesNotContain("-p");
        }

        @Test
        void buildRunCommand_exposeHostPortsTrue_publishesEphemeralManagementAndAppPorts() {
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
            exposingRunner.queuedResponses.add(Promise.success("container-id-0"));
            exposingRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));
            var exposingProvider = DockerComputeProvider.dockerComputeProvider(exposingRunner, exposingConfig).unwrap();

            exposingProvider.provision(InstanceType.ON_DEMAND)
                            .await()
                            .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // KSUID-minted replacements have no numeric slot, so BOTH in-container ports —
            // management (8080) and app plane (8070) — are published to ephemeral host ports
            // (`-p 8080`, `-p 8070`) rather than `base + slot`. The app port MUST be present:
            // once every compose seed is replaced by a minted node, the app plane is only
            // host-reachable if these replacements map it.
            var command = exposingRunner.allCommands.getFirst();
            assertThat(command).containsSequence("-p", "8080");
            assertThat(command).containsSequence("-p", "8070");
            assertThat(command.stream().filter("-p"::equals).count()).isEqualTo(2);
        }

        @Test
        void buildRunCommand_exposeHostPortsFalse_publishesNeitherManagementNorAppPort() {
            testRunner.queuedResponses.add(Promise.success("container-id-0"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // exposeHostPorts=false → no `-p` at all, so neither the management (8080) nor
            // the app (8070) in-container port is mapped to a host port.
            var command = testRunner.allCommands.getFirst();
            assertThat(command).doesNotContain("-p");
            assertThat(command).doesNotContain("8080");
            assertThat(command).doesNotContain("8070");
        }

        @Test
        void buildRunCommand_twoProvisions_mintDistinctKsuidNames() {
            var exposingRunner = new TestDockerCommandRunner();
            var exposingProvider = DockerComputeProvider.dockerComputeProvider(exposingRunner, CONFIG).unwrap();

            exposingRunner.queuedResponses.add(Promise.success("container-id-0"));
            exposingRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));
            exposingProvider.provision(InstanceType.ON_DEMAND).await();
            var firstName = nameArg(exposingRunner.allCommands.getFirst());

            exposingRunner.queuedResponses.add(Promise.success("container-id-1"));
            exposingRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));
            exposingProvider.provision(InstanceType.ON_DEMAND).await();
            var secondName = nameArg(exposingRunner.allCommands.get(2));

            // Each provision mints a fresh KSUID identity — no slot reuse, always distinct.
            assertThat(firstName).startsWith("aether-default-node-");
            assertThat(secondName).startsWith("aether-default-node-");
            assertThat(firstName).isNotEqualTo(secondName);
        }

        @Test
        void buildRunCommand_always_setsExplicitRestartNo() {
            testRunner.queuedResponses.add(Promise.success("container-id-0"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // Explicit `--restart no` guards against a daemon-level default-restart-policy
            // resurrecting a terminally-removed NodeId.
            assertThat(testRunner.allCommands.getFirst()).containsSequence("--restart", "no");
        }
    }

    @Nested
    class ProvisionTests {

        @Test
        void provision_success_returnsInstanceInfo() {
            testRunner.queuedResponses.add(Promise.success("abc123def456"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                    .onSuccess(DockerComputeProviderTest::assertProvisionedInstance);
        }

        @Test
        void provision_withSpec_passesTagsToCommand() {
            testRunner.queuedResponses.add(Promise.success("container-id-1"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));
            var ctx = ProvisionContext.provisionContext(maybeClusterName("test-cluster"), "worker", sourceNameOrDefault("default"),
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "docker", "staging", ctx).unwrap();

            provider.provision(spec)
                    .await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                    .onSuccess(info -> assertThat(info.tags().get("aether.cluster")).isEqualTo("test-cluster"));

            assertThat(testRunner.allCommands.getFirst()).isNotEmpty();
            assertThat(testRunner.allCommands.getFirst()).contains("--network", "aether-network");
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
        void provision_containerNeverReachesRunning_failsWithReadinessTimeout() {
            // docker run succeeds, but inspect reports `exited` (boot crash). confirmRunning
            // must FAIL the provision (not phantom-succeed) and trigger a rollback rm -f.
            testRunner.queuedResponses.add(Promise.success("crashed-container-id"));
            testRunner.queuedResponses.add(Promise.success("exited\t/aether-node\taether-node\tcrashed-container-id"));
            testRunner.queuedResponses.add(Promise.success("removed"));

            provider.provision(InstanceType.ON_DEMAND)
                    .await()
                    .onSuccess(info -> fail("Expected readiness failure but got phantom success: " + info))
                    .onFailure(DockerComputeProviderTest::assertProvisionFailedError);

            // run, inspect (exited), rollback rm -f.
            assertThat(testRunner.allCommands).hasSize(3);
            assertThat(testRunner.allCommands.get(1).getFirst()).isEqualTo("docker");
            assertThat(testRunner.allCommands.get(1)).contains("inspect");
            assertThat(testRunner.allCommands.get(2).subList(0, 3)).containsExactly("docker", "rm", "-f");
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
            // uses ProvisionContext.provisionContext(maybeClusterName("default"), "core", sourceNameOrDefault("default"), ...),
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
            testRunner.queuedResponses.add(Promise.success("container-id-success"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));

            provider.provision(InstanceType.ON_DEMAND).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // `docker run` then readiness `docker inspect` (running) — no rollback. Two commands.
            assertThat(testRunner.allCommands).hasSize(2);
            assertThat(testRunner.allCommands.getFirst()).contains("run");
            assertThat(testRunner.allCommands.get(1)).contains("inspect");
        }
    }

    @Nested
    class ContainerNameTests {

        @Test
        void buildContainerName_mintsKsuidNameForCluster() {
            testRunner.queuedResponses.add(Promise.success("id-0"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));
            var ctx = ProvisionContext.provisionContext(maybeClusterName("test-cluster"), "core", sourceNameOrDefault("default"),
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "docker", "worker-pool", ctx).unwrap();

            provider.provision(spec).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // KSUID-minted identity: `aether-<cluster>-node-<ksuid>`. Cluster segment from
            // ProvisionContext.clusterName; the KSUID suffix is unique and k-sortable.
            var name = nameArg(testRunner.allCommands.getFirst());
            assertThat(name).startsWith("aether-test-cluster-node-");
            assertThat(name).isNotEqualTo("aether-test-cluster-node-");
        }

        @Test
        void buildContainerName_honorsCallerSuppliedNodeId() {
            testRunner.queuedResponses.add(Promise.success("id-0"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));
            var ctx = ProvisionContext.provisionContext(maybeClusterName("test-cluster"), "core", sourceNameOrDefault("default"),
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP)
                                      .withNodeId("aether-test-cluster-node-1");
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "docker", "worker-pool", ctx).unwrap();

            provider.provision(spec).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // Caller-supplied nodeId (bootstrap path) is used verbatim as the container name.
            assertThat(nameArg(testRunner.allCommands.getFirst())).isEqualTo("aether-test-cluster-node-1");
        }

        @Test
        void buildContainerName_defaultsClusterWhenAbsent() {
            testRunner.queuedResponses.add(Promise.success("id-0"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));

            provider.provision(InstanceType.ON_DEMAND).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            // provision(InstanceType) hardcodes cluster="default"; KSUID-minted name.
            assertThat(nameArg(testRunner.allCommands.getFirst())).startsWith("aether-default-node-");
        }
    }

    @Nested
    class IdentityEnvPropagationTests {

        @Test
        void buildRunCommand_emitsApiKeyExactlyOnce_noDoubleEmissionFromAllowList() {
            // AETHER_API_KEY is emitted from config.apiKey() AND is a member of the
            // IDENTITY_VARS allow-list. The dedupe guard must keep it to a single emission.
            testRunner.queuedResponses.add(Promise.success("id-0"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));

            provider.provision(InstanceType.ON_DEMAND).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            var command = testRunner.allCommands.getFirst();
            var apiKeyCount = command.stream().filter(arg -> arg.startsWith("AETHER_API_KEY=")).count();
            assertThat(apiKeyCount).isEqualTo(1);
        }

        @Test
        void buildRunCommand_emitsClusterNameFromContext_equalsLabel_notHostEnv() {
            // Regression: the aether.cluster LABEL derived from ProvisionContext.clusterName
            // while AETHER_CLUSTER_NAME rode the verbatim IDENTITY_VARS allow-list (the leader's
            // host env). When the two disagreed (compose label `b` vs bootstrap `integration-test`)
            // the node's boot guard (Main.verifyClusterLabelConsistency) exited(1) and CTM retried
            // forever → auto-heal storm. The env must come from the SAME ctx source as the label,
            // so it equals the label regardless of host env, and is emitted exactly once (dedupe).
            testRunner.queuedResponses.add(Promise.success("id-0"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));
            var ctx = ProvisionContext.provisionContext(maybeClusterName("integration-test"), "core", sourceNameOrDefault("default"),
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "docker", "worker-pool", ctx).unwrap();

            provider.provision(spec).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            var command = testRunner.allCommands.getFirst();
            assertThat(command).contains("AETHER_CLUSTER_NAME=integration-test");
            assertThat(command).contains("aether.cluster=integration-test");
            var clusterNameCount = command.stream().filter(arg -> arg.startsWith("AETHER_CLUSTER_NAME=")).count();
            assertThat(clusterNameCount).isEqualTo(1);
        }

        @Test
        void buildRunCommand_emitsRoleFromContext_equalsLabel_notHostEnv() {
            // Wave 2 / W4 (cluster-topology-overhaul spec): AETHER_ROLE is AUTHORITATIVE from
            // ProvisionContext.role() — the SAME source as the aether.role label — and emitted
            // exactly once (the dedupe guard drops the IDENTITY_VARS host-env inherit). A
            // worker-intent provision yields role=worker end-to-end; the provisioning host's
            // own AETHER_ROLE can never leak onto a node it mints.
            testRunner.queuedResponses.add(Promise.success("id-0"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));
            var ctx = ProvisionContext.provisionContext(maybeClusterName("test-cluster"), "worker", sourceNameOrDefault("default"),
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "docker", "worker-pool", ctx).unwrap();

            provider.provision(spec).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            var command = testRunner.allCommands.getFirst();
            assertThat(command).contains("AETHER_ROLE=worker");
            assertThat(command).contains("aether.role=worker");
            var roleCount = command.stream().filter(arg -> arg.startsWith("AETHER_ROLE=")).count();
            assertThat(roleCount).isEqualTo(1);
        }

        @Test
        void buildRunCommand_emptyCluster_noLongerYieldsDefault() {
            // ctx with an empty cluster name + bootstrap origin (passes preflight). The old
            // clusterOrDefault returned literal "default"; now it returns "" so the minted name
            // carries no "default" segment and the aether.cluster label is empty. coreNodeNamePrefix
            // is blank-defensive: an empty cluster collapses to the canonical "aether-node-" (single
            // dash) rather than the malformed "aether--node-" (empty segment between dashes).
            testRunner.queuedResponses.add(Promise.success("id-0"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));
            var ctx = ProvisionContext.provisionContext(maybeClusterName(""), "core", sourceNameOrDefault("default"),
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var spec = ProvisionSpec.provisionSpec(InstanceType.ON_DEMAND, "docker", "default", ctx).unwrap();

            provider.provision(spec).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()))
                    .onSuccess(info -> assertThat(info.tags().get("aether.cluster")).isEqualTo(""));

            var command = testRunner.allCommands.getFirst();
            assertThat(command).contains("aether.cluster=");
            assertThat(command).doesNotContain("aether.cluster=default");
            var nameIdx = command.indexOf("--name");
            assertThat(command.get(nameIdx + 1)).startsWith("aether-node-");
            assertThat(command.get(nameIdx + 1)).doesNotStartWith("aether--node-");
        }

        @Test
        void buildRunCommand_allowListVarsPresent_whenSetInEnv() {
            // Real assertion when the integration harness exports these; a no-op skip
            // otherwise (System.getenv cannot be set in-process). Covers IDENTITY_VARS,
            // DOCKER_INFRA_VARS, and the isolated dev-mode flag.
            testRunner.queuedResponses.add(Promise.success("id-0"));
            testRunner.queuedResponses.add(Promise.success(RUNNING_INSPECT));

            provider.provision(InstanceType.ON_DEMAND).await()
                    .onFailure(cause -> fail("Expected success but got: " + cause.message()));

            var command = testRunner.allCommands.getFirst();
            assertEnvPropagatedIfSet(command, "AETHER_CLUSTER_SECRET");
            assertEnvPropagatedIfSet(command, "AETHER_DOCKER_NETWORK");
            assertEnvPropagatedIfSet(command, "DOCKER_GID");
            assertEnvPropagatedIfSet(command, "AETHER_INSECURE_DEV_MODE");
        }

        private void assertEnvPropagatedIfSet(List<String> command, String name) {
            var value = System.getenv(name);
            if (value != null && !value.isEmpty()) {
                assertThat(command).contains(name + "=" + value);
            }
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
