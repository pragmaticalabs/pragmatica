// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.connectionRefused;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.drainAccepted;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.lifecycle;
import static org.pragmatica.aether.cli.cluster.ScriptedDrainHttp.notFound;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #2132 — replace-before-retire (a source field changed) used to drain the literal `<source>-<role>-old-N`, at a "host" that was that same
/// string, so the drain never reached a real node. The old node is now discovered from the provider by its labels, and drained by the id it
/// registered under, at the address the provider reports for it. The replacements this run just created carry the same labels and are never
/// drained.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WaveExecutorReplaceBeforeRetireTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("prod-cluster").unwrap();
    /// Sorts AFTER any node id minted now, so a run that did not exclude its own replacements would pick the replacement as the oldest.
    private static final String OLD_NODE = "aether-prod-cluster-node-zz0old0000000000000000000";
    private static final String DOCKER = """
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.local]
            type = "docker"

            [source.local.core]
            count = 1
            """;
    private static final Map<String, String> DOCKER_LABELS = Map.of("aether.cluster", "prod-cluster", "aether.source", "local", "aether.role", "core");

    @RegisterExtension
    final LedgerDirExtension ledger = new LedgerDirExtension();

    private final ApplyChaosHarness harness = new ApplyChaosHarness();
    private HttpOperations originalHttp;
    private PrintStream originalOut;

    @BeforeEach
    void stubStreamsAndHttp() {
        originalHttp = ClusterHttpClient.HTTP_OPS_REF.get();
        originalOut = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        ClusterHttpClient.HTTP_OPS_REF.set(originalHttp);
        ProviderResolver.dockerFactoryOverride = null;
    }

    /// The docker provider as the apply sees it: a create stamps the `aether.*` labels a daemon listing filters by.
    private final class DockerLike implements ComputeProvider {
        @Override
        public Promise<InstanceInfo> provision(ProvisionSpec spec) {
            var context = spec.context();
            var tags = new HashMap<>(context.extraTags());

            tags.putAll(Map.of("aether.cluster", context.clusterName().map(ClusterName::value).or(""), "aether.source", context.sourceName().value(), "aether.role", context.role()));

            return Promise.success(harness.provider.create(context.nodeId().or(""), tags));
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return Causes.cause("not used").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return harness.provider.terminate(instanceId);
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return harness.provider.listInstances();
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return harness.provider.instanceStatus(instanceId);
        }
    }

    private ClusterBootstrapConfig installDocker() {
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                return Result.success(EnvironmentIntegration.withLocalCompute(new DockerLike()));
            }
        };

        return ClusterBootstrapConfigParser.parse(DOCKER).unwrap();
    }

    private Result<?> replace(ClusterBootstrapConfig config) {
        var action = new DiffAction.SourceFieldChange(sourceNameOrDefault("local"), "zone");
        var plan = DiffPlan.diffPlan(List.of(), List.of(action), List.of(), List.of());

        return WaveExecutor.execute(plan, config, config, ledger.recorder(CLUSTER, List.of(ApplyAction.actionKey(action))));
    }

    /// The old node's drain script: the readiness probe of the replacement answers first, then the old node's own drain wait (draining, a 404
    /// still relayed by the live node, then its port refusing because it halted).
    private static ScriptedDrainHttp scriptedFor(String oldNode) {
        return new ScriptedDrainHttp(drainAccepted(oldNode),
                                     new ScriptedDrainHttp.Step.Reply(200, "{}"),
                                     lifecycle(oldNode, "DRAINING"),
                                     notFound(oldNode),
                                     connectionRefused());
    }

    @Test
    void theOldNodeIsDrainedByItsRealId_atItsRealAddress_andTheReplacementIsNot() {
        var config = installDocker();
        var old = harness.provider.create(OLD_NODE, DOCKER_LABELS);
        var http = scriptedFor(OLD_NODE);

        ClusterHttpClient.HTTP_OPS_REF.set(http);

        var result = replace(config);

        assertThat(result.isSuccess()).as("replace-before-retire outcome: %s", result).isTrue();
        assertThat(http.drainOrder()).as("exactly the old node was drained, by the id it registered under").containsExactly(OLD_NODE);
        assertThat(http.requestUris()).as("the drain is sent to the address the provider reports for the old node")
                                      .anyMatch(uri -> uri.startsWith("POST http://" + old.addresses().getFirst() + ":8080/api/v1/nodes/drain/" + OLD_NODE));
        assertThat(harness.provider.totalCreates()).as("one replacement was created before the drain").isEqualTo(2);
    }

    /// The listing finds the nodes; fewer than the plan names is refused before a single drain, because draining whatever matches could drain
    /// a node the plan did not name.
    @Test
    void noOldNodeExists_isRefusedNamingIt_andNothingIsDrained() {
        var config = installDocker();
        var http = scriptedFor(OLD_NODE);

        ClusterHttpClient.HTTP_OPS_REF.set(http);

        var result = replace(config);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("only 0 existing node(s)").contains("nothing was drained"));
        assertThat(http.drainPosts()).isZero();
    }

    private static InstanceInfo cloudNode(String nodeId, String cluster, String source, String role, String address) {
        return new InstanceInfo(InstanceId.instanceId("srv-" + nodeId).unwrap(),
                                InstanceStatus.RUNNING,
                                address.isEmpty() ? List.of() : List.of(address),
                                InstanceType.ON_DEMAND,
                                Map.of("aether-cluster", cluster, "aether-source", source, "aether-role", role),
                                Option.some(nodeId),
                                Option.none());
    }

    private static final class Listing implements ComputeProvider {
        final List<InstanceInfo> instances;

        Listing(List<InstanceInfo> instances) {
            this.instances = instances;
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return Causes.cause("unused").promise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(instances);
        }

        /// A selector that is ignored: whatever the filter, every instance comes back, so the labels each instance carries are the only proof.
        @Override
        public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
            return Promise.success(instances);
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Causes.cause("unused").promise();
        }
    }

    private static Result<List<WaveExecutor.OldNode>> cloudOldNodes(List<InstanceInfo> instances, int count, ProvisionedNode... replacements) {
        return WaveExecutor.listedOldNodes(new Listing(instances),
                                           WaveExecutor.LabelKeys.CLOUD,
                                           sourceNameOrDefault("eu-1"),
                                           NodeRole.CORE,
                                           count,
                                           CLUSTER,
                                           List.of(replacements));
    }

    @Test
    void cloudListing_picksTheOldestNodesOfThatSourceRoleAndCluster_withTheirAddresses_neverTheReplacements() {
        var instances = List.of(cloudNode("aether-node-04", "prod-cluster", "eu-1", "core", "10.0.0.4"),
                                cloudNode("aether-node-02", "prod-cluster", "eu-1", "core", "10.0.0.2"),
                                cloudNode("aether-node-03", "prod-cluster", "eu-1", "core", "10.0.0.3"),
                                cloudNode("aether-node-01", "prod-cluster", "eu-1", "core", "10.0.0.1"),
                                cloudNode("aether-node-000", "prod-cluster", "eu-2", "core", "10.0.9.0"),
                                cloudNode("aether-node-001", "prod-cluster", "eu-1", "worker", "10.0.9.1"),
                                cloudNode("aether-node-002", "other", "eu-1", "core", "10.0.9.2"));

        var result = cloudOldNodes(instances, 2, ProvisionedNode.provisionedNode("aether-node-01", "unmatched-server", "10.0.0.1"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.unwrap()).containsExactly(WaveExecutor.OldNode.oldNode("aether-node-02", "10.0.0.2"),
                                                    WaveExecutor.OldNode.oldNode("aether-node-03", "10.0.0.3"));
    }

    /// Exactly as many old nodes as the plan names is enough; the replacement is matched by its server id as well as by its node id (the first cloud test matches by node id alone).
    @Test
    void cloudListing_exactlyTheNamedNumber_isEnough_andAReplacementMatchedByServerIdIsExcluded() {
        var instances = List.of(cloudNode("aether-node-01", "prod-cluster", "eu-1", "core", "10.0.0.1"),
                                cloudNode("aether-node-02", "prod-cluster", "eu-1", "core", "10.0.0.2"));

        var result = cloudOldNodes(instances, 1, ProvisionedNode.provisionedNode("unrelated-id", "srv-aether-node-01", "10.0.0.1"));

        assertThat(result.unwrap()).containsExactly(WaveExecutor.OldNode.oldNode("aether-node-02", "10.0.0.2"));
    }

    @Test
    void cloudListing_aNodeWithNoAddressCannotBeDrained_isRefusedNamingIt() {
        var result = cloudOldNodes(List.of(cloudNode("aether-node-01", "prod-cluster", "eu-1", "core", "")), 1);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("aether-node-01").contains("no address").contains("nothing was drained"));
    }

    @Test
    void cloudListing_aSelectorThatIgnoredTheLabels_neverMakesANodeOurs() {
        var result = cloudOldNodes(List.of(cloudNode("aether-node-09", "other", "eu-1", "core", "10.0.0.9")), 1);

        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void labelKeys_followTheProviderFamily_dockerDotted_cloudHyphenated() {
        assertThat(WaveExecutor.labelKeys(SourceType.DOCKER)).isEqualTo(WaveExecutor.LabelKeys.DOCKER);
        assertThat(WaveExecutor.labelKeys(SourceType.CLOUD)).isEqualTo(WaveExecutor.LabelKeys.CLOUD);
        assertThat(WaveExecutor.LabelKeys.DOCKER.cluster()).isEqualTo("aether.cluster");
        assertThat(WaveExecutor.LabelKeys.CLOUD.cluster()).isEqualTo("aether-cluster");
    }

    private static ClusterBootstrapConfig sshTwoHosts() {
        return ClusterBootstrapConfigParser.parse("""
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.dc-1]
            type = "ssh"

            [source.dc-1.core]
            hosts = ["10.1.0.1", "10.1.0.2"]
            """).unwrap();
    }

    @Test
    void sshSource_oldNodesAreTheConfiguredHosts_atTheHostAddress() {
        var source = sshTwoHosts().sources().get("dc-1");

        var result = WaveExecutor.findOldNodes(sourceNameOrDefault("dc-1"), source, NodeRole.CORE, 2, CLUSTER, List.of());

        assertThat(source.type()).isEqualTo(SourceType.SSH);
        assertThat(result.unwrap()).containsExactly(WaveExecutor.OldNode.oldNode("dc-1-core-0", "10.1.0.1"),
                                                    WaveExecutor.OldNode.oldNode("dc-1-core-1", "10.1.0.2"));
    }

    /// More hosts than the plan replaces: only the first `count` are retired, the rest keep serving.
    @Test
    void sshSource_moreHostsThanTheCount_onlyTheFirstCountAreNamed() {
        var result = WaveExecutor.findOldNodes(sourceNameOrDefault("dc-1"), sshTwoHosts().sources().get("dc-1"), NodeRole.CORE, 1, CLUSTER, List.of());

        assertThat(result.unwrap()).containsExactly(WaveExecutor.OldNode.oldNode("dc-1-core-0", "10.1.0.1"));
    }

    @Test
    void sshSource_fewerHostsThanTheCount_isRefused() {
        var result = WaveExecutor.findOldNodes(sourceNameOrDefault("dc-1"), sshTwoHosts().sources().get("dc-1"), NodeRole.CORE, 3, CLUSTER, List.of());

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("only 2 existing node(s)"));
    }

    @Test
    void forgeSource_hasNoManagementEndpointToDrain_soNoOldNodeIsNamed() {
        var forge = ClusterBootstrapConfigParser.parse("""
            config_version = "1.0.0"

            [cluster]
            name = "prod-cluster"
            version = "1.0.0"

            [source.sim]
            type = "forge"

            [source.sim.core]
            count = 1
            """).unwrap().sources().get("sim");

        var result = WaveExecutor.findOldNodes(sourceNameOrDefault("sim"), forge, NodeRole.CORE, 1, CLUSTER, List.of());

        assertThat(result.unwrap()).isEmpty();
    }

    /// The first refused drain ends the run: a node after it is not drained, because the refusal may be the slice floor the next drain would
    /// breach too.
    @Test
    void aRefusedDrain_endsTheRun_andLaterNodesAreNotDrained() {
        var http = new ScriptedDrainHttp(ScriptedDrainHttp.otherConflict("first"));

        ClusterHttpClient.HTTP_OPS_REF.set(http);

        var result = WaveExecutor.drainOldNodes(List.of(WaveExecutor.OldNode.oldNode("first", "10.0.0.1"), WaveExecutor.OldNode.oldNode("second", "10.0.0.2")), 8080);

        assertThat(result.isFailure()).isTrue();
        assertThat(http.drainOrder()).containsExactly("first");
    }
}
