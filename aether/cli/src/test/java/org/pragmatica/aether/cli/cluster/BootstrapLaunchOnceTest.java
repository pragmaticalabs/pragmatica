// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.config.cluster.AutoHealSpec;
import org.pragmatica.aether.config.cluster.CloudProviderName;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.aether.config.cluster.CoreTopology;
import org.pragmatica.aether.config.cluster.InfrastructureConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.OperationsConfig;
import org.pragmatica.aether.config.cluster.PortMapping;
import org.pragmatica.aether.config.cluster.ReplacementNodeConfigComposer;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.RuntimeProfile;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.config.cluster.SshConfig;
import org.pragmatica.aether.config.cluster.TimeoutsConfig;
import org.pragmatica.aether.config.cluster.TlsDeploymentConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.NodeAddress;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.ProvisionedNode;
import org.pragmatica.config.toml.TomlWriter;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Functions.Fn4;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.ClusterName.clusterName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// #1543 part B — a node id is launched ONCE, and the initial cores' ids are the genesis roster.
///
/// Fixture: cores spread over TWO sources (`eu-1` CLOUD x3, `dc-1` SSH x2), which is the shape
/// `discoveryAssembly` refuses and the SSH push serves — the shape that used to start every node in
/// cloud-init and then `docker rm -f` + `docker run` it again with final PEERS.
class BootstrapLaunchOnceTest {
    private static final ClusterName CLUSTER = uniqueClusterName();
    private static final String SECRET = "super-secret-token";
    private static final String VERSION = "1.0.0";
    private static final List<String> EU = List.of("eu-1-core-0", "eu-1-core-1", "eu-1-core-2");
    private static final List<String> DC = List.of("dc-1-core-0", "dc-1-core-1");
    private static final List<String> ALL_CORES = concat(DC, EU);

    private static ClusterName uniqueClusterName() {
        var suffix = new byte[6];

        new SecureRandom().nextBytes(suffix);

        return clusterName("launch-once-1543-" + HexFormat.of().formatHex(suffix)).unwrap();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        var all = new ArrayList<>(first);

        all.addAll(second);

        return List.copyOf(all);
    }

    @AfterEach
    void removePersistedState() throws IOException {
        var dir = BootstrapStatePersistence.statePath(CLUSTER).getParent();

        if (Files.exists(dir)) {
            try (var files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    private static SourceProfile cloudSource() {
        return SourceProfile.sourceProfile(sourceNameOrDefault("eu-1"),
                                           SourceType.CLOUD,
                                           Option.some(CloudProviderName.HETZNER),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.some("aether"),
                                           Option.empty(),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(3),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of());
    }

    private static SourceProfile sshSource() {
        return SourceProfile.sourceProfile(sourceNameOrDefault("dc-1"),
                                           SourceType.SSH,
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.empty(),
                                           Option.some("aether"),
                                           Option.some("/home/op/.ssh/id"),
                                           Option.empty(),
                                           LoadBalancerMode.NONE,
                                           List.of(),
                                           Option.empty(),
                                           Map.of(),
                                           Map.of(NodeRole.CORE,
                                                  RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                            Option.some(2),
                                                                            Option.empty(),
                                                                            Option.empty(),
                                                                            "default")),
                                           List.of());
    }

    private static ClusterBootstrapConfig config(Map<String, SourceProfile> sources, RuntimeType runtime) {
        var ops = OperationsConfig.operationsConfig(AutoHealSpec.defaultAutoHealSpec(),
                                                    TlsDeploymentConfig.defaultTlsConfig(),
                                                    TimeoutsConfig.timeoutsConfig("3s", "10s", "10s"),
                                                    PortMapping.defaultPortMapping());

        return ClusterBootstrapConfig.clusterBootstrapConfig(VERSION,
                                                             ClusterIdentity.clusterIdentity(CLUSTER.value(), VERSION)
                                                                            .unwrap(),
                                                             CoreTopology.defaultCoreTopology(),
                                                             sources,
                                                             Map.of("default",
                                                                    RuntimeProfile.runtimeProfile("default",
                                                                                                  runtime,
                                                                                                  Option.empty(),
                                                                                                  Option.empty())),
                                                             InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                             ops,
                                                             Map.of());
    }

    private static BootstrapContext multiSourceContext(RuntimeType runtime) {
        var config = config(Map.of("eu-1", cloudSource(), "dc-1", sshSource()), runtime);
        var nodes = List.of(ProvisionedNode.provisionedNode("eu-1-core-0", "100", "203.0.113.10"),
                            ProvisionedNode.provisionedNode("eu-1-core-1", "101", "203.0.113.11"),
                            ProvisionedNode.provisionedNode("eu-1-core-2", "102", "203.0.113.12"),
                            ProvisionedNode.provisionedNode("dc-1-core-0", "ssh", "10.0.0.1"),
                            ProvisionedNode.provisionedNode("dc-1-core-1", "ssh", "10.0.0.2"));

        return contextOf(config, nodes, BootstrapState.initialState(CLUSTER, "h", "now").withClusterSecret(SECRET));
    }

    private static BootstrapContext contextOf(ClusterBootstrapConfig config,
                                              List<ProvisionedNode> nodes,
                                              BootstrapState state) {
        var addresses = nodes.stream()
                             .map(n -> NodeAddress.nodeAddress(n.nodeId(), n.publicIp(), Option.empty()))
                             .toList();

        return BootstrapContext.bootstrapContext(config, state, nodes, addresses).withClusterSecret(SECRET);
    }

    private static Fn1<String, String> envWithKey() {
        return name -> SshKeyResolver.AETHER_SSH_KEY_ENV.equals(name)
                       ? "/home/op/.ssh/aether_id_ed25519"
                       : null;
    }

    private static final Pattern GENESIS = Pattern.compile("genesis_voters\\s*=\\s*\"([^\"]*)\"");

    private static List<String> genesisOf(String text) {
        var matcher = GENESIS.matcher(text);

        assertThat(matcher.find()).as("genesis_voters must be rendered in: " + text).isTrue();

        return List.of(matcher.group(1).split(","));
    }

    /// Captures every spec a node is created from, so the user data production RENDERS is what is asserted.
    private static final class CapturingCompute implements ComputeProvider {
        final List<ProvisionSpec> specs = new ArrayList<>();

        @Override
        public Promise<InstanceInfo> provision(ProvisionSpec spec) {
            specs.add(spec);

            return Promise.success(info(specs.size() - 1));
        }

        private static InstanceInfo info(int index) {
            return new InstanceInfo(new InstanceId("server-" + index),
                                    InstanceStatus.RUNNING,
                                    List.of("203.0.113." + index),
                                    InstanceType.ON_DEMAND,
                                    Map.of());
        }

        @Override public Promise<InstanceInfo> createFrom(ProvisionRequest request) {return Promise.success(info(0));}

        @Override public Promise<Unit> terminate(InstanceId instanceId) {return Promise.success(Unit.unit());}

        @Override public Promise<List<InstanceInfo>> listInstances() {return Promise.success(List.of());}

        @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {return Promise.success(info(0));}

        List<String> userData() {
            return specs.stream().map(spec -> spec.userData().or("")).toList();
        }
    }

    private static CapturingCompute provisionEuCores(BootstrapContext ctx) {
        var compute = new CapturingCompute();
        var result = BootstrapPhaseProvision.provisionCloudRoleGroup(compute,
                                                                     ctx,
                                                                     sourceNameOrDefault("eu-1"),
                                                                     NodeRole.CORE,
                                                                     3,
                                                                     ctx.config().sources().get("eu-1"),
                                                                     CLUSTER,
                                                                     0);

        assertThat(result.isSuccess()).as(() -> "precondition: every core is created: " + result).isTrue();
        assertThat(compute.specs).as("precondition: three user-data scripts were rendered").hasSize(3);

        return compute;
    }

    // ---------------------------------------------------------------------------------------------
    // genesis_voters rendering (owner's acceptance, #1543 comment)
    // ---------------------------------------------------------------------------------------------

    /// The owner's rendering test: through the production provisioning call, every initial core's user
    /// data carries `cluster.genesis_voters` equal to EXACTLY the provisioned initial core ids — both
    /// sources' cores, nothing else.
    @Test
    void initialCores_renderGenesisVoters_equalToExactlyTheProvisionedIds() {
        var ctx = multiSourceContext(RuntimeType.CONTAINER);
        var userData = provisionEuCores(ctx).userData();

        for (var script : userData) {
            assertThat(genesisOf(script)).as("one roster, naming every initial core of every source")
                                         .containsExactlyInAnyOrderElementsOf(ALL_CORES);
        }
        assertThat(ALL_CORES).as("the roster is the provisioned ids, not a parallel list")
                             .containsExactlyInAnyOrderElementsOf(ctx.nodes().stream().map(ProvisionedNode::nodeId).toList());
    }

    /// The SSH source's pushed config carries the same roster, and a WORKER carries none (it does not vote).
    @Test
    void sshCoreConfig_rendersTheSameRoster_andAWorkerRendersNone() {
        var ctx = multiSourceContext(RuntimeType.CONTAINER);
        var core = NodeConfigBuilder.compose(ctx, ctx.config().sources().get("dc-1"), 0, NodeRole.CORE, Option.empty(), Option.some(SECRET));
        var worker = NodeConfigBuilder.compose(ctx, ctx.config().sources().get("dc-1"), 0, NodeRole.WORKER, Option.empty(), Option.some(SECRET));

        assertThat(genesisOf(TomlWriter.toToml(core.unwrap()))).containsExactlyInAnyOrderElementsOf(ALL_CORES);
        assertThat(TomlWriter.toToml(worker.unwrap())).doesNotContain("genesis_voters");
    }

    /// The paired half: the SAME config rendered for a CTM replacement carries NO roster — it joins the
    /// formed electorate through the Rabia section-4 add command (#1526), and a roster would make it wait
    /// for a genesis that already happened.
    @Test
    void replacementRender_carriesNoGenesisVoters_evenWhenTheClusterHasInitialCores() {
        var config = config(Map.of("eu-1", cloudSource(), "dc-1", sshSource()), RuntimeType.CONTAINER);

        assertThat(config.initialCoreIds()).as("control: the same config does name initial cores")
                                           .containsExactlyInAnyOrderElementsOf(ALL_CORES);

        var replacement = ReplacementNodeConfigComposer.compose(config,
                                                                config.sources().get("eu-1"),
                                                                NodeRole.CORE,
                                                                Option.some(SECRET),
                                                                List.of());

        assertThat(TomlWriter.toToml(replacement.unwrap())).doesNotContain("genesis_voters");
    }

    /// A DOCKER or FORGE core source cannot be named by this minting scheme, so no roster is rendered
    /// rather than one that waits for ids which never announce.
    @Test
    void initialCoreIds_areEmpty_whenACoreSourceIsDocker() {
        var docker = SourceProfile.sourceProfile(sourceNameOrDefault("local"),
                                                 SourceType.DOCKER,
                                                 Option.empty(),
                                                 Option.empty(),
                                                 Option.empty(),
                                                 Option.empty(),
                                                 Option.empty(),
                                                 Option.empty(),
                                                 Option.empty(),
                                                 LoadBalancerMode.NONE,
                                                 List.of(),
                                                 Option.empty(),
                                                 Map.of(),
                                                 Map.of(NodeRole.CORE,
                                                        RoleSubTable.roleSubTable(NodeRole.CORE,
                                                                                  Option.some(3),
                                                                                  Option.empty(),
                                                                                  Option.empty(),
                                                                                  "default")),
                                                 List.of());

        assertThat(config(Map.of("eu-1", cloudSource(), "local", docker), RuntimeType.CONTAINER).initialCoreIds()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // Cloud-init installs, the push starts
    // ---------------------------------------------------------------------------------------------

    @Test
    void multiCoreSource_containerUserData_pullsButDoesNotStart() {
        var userData = provisionEuCores(multiSourceContext(RuntimeType.CONTAINER)).userData();

        for (var script : userData) {
            assertThat(script).as("install only: the CLI push performs the first start")
                              .contains("docker pull")
                              .doesNotContain("docker run");
        }
    }

    @Test
    void multiCoreSource_jvmUserData_installsTheUnitButDoesNotStartIt() {
        var userData = provisionEuCores(multiSourceContext(RuntimeType.JVM)).userData();

        for (var script : userData) {
            assertThat(script).contains("/etc/systemd/system/aether-node.service")
                              .doesNotContain("systemctl start");
        }
    }

    /// Control for the two tests above: a single cloud core source engages discovery assembly — nothing is
    /// pushed — so its user data MUST still start the node. Without this the install-only assertions could
    /// pass because nothing ever rendered a start.
    @Test
    void singleCloudCoreSource_userData_stillStartsTheNode() {
        var config = config(Map.of("eu-1", cloudSource()), RuntimeType.CONTAINER);
        var ctx = contextOf(config, List.of(), BootstrapState.initialState(CLUSTER, "h", "now").withClusterSecret(SECRET));

        for (var script : provisionEuCores(ctx).userData()) {
            assertThat(script).contains("docker run");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Every node gets exactly one start, and none of them can be a restart
    // ---------------------------------------------------------------------------------------------

    private record Call(String host, String command) {}

    private static boolean isStart(String command) {
        return command.contains("docker run") || command.contains("systemctl start aether-node");
    }

    private static void assertNoRestartShape(List<Call> calls) {
        for (var call : calls) {
            assertThat(call.command()).doesNotContain("rm -f aether-node")
                                      .doesNotContain("systemctl restart")
                                      .doesNotContain("docker restart")
                                      .doesNotContain("docker stop");
        }
    }

    @Test
    void cloudPush_container_startsEachNodeExactlyOnce_andNeverRestarts() {
        var ctx = multiSourceContext(RuntimeType.CONTAINER);
        var calls = new ConcurrentLinkedQueue<Call>();
        Fn3<Result<String>, String, String, SshConfig> ssh = (host, command, config) -> {
            calls.add(new Call(host, command));
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            url -> Result.success("OK"),
                                                            ssh,
                                                            envWithKey());

        assertThat(result.isSuccess()).as(() -> "deploy: " + result).isTrue();
        for (var host : List.of("203.0.113.10", "203.0.113.11", "203.0.113.12")) {
            assertThat(calls.stream().filter(c -> c.host().equals(host) && isStart(c.command())).count())
                .as("exactly one start for " + host)
                .isEqualTo(1);
        }
        assertNoRestartShape(List.copyOf(calls));
    }

    @Test
    void cloudPush_jvm_startsEachUnitExactlyOnce_andNeverRestarts() {
        var ctx = multiSourceContext(RuntimeType.JVM);
        var calls = new ConcurrentLinkedQueue<Call>();
        Fn3<Result<String>, String, String, SshConfig> ssh = (host, command, config) -> {
            calls.add(new Call(host, command));
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            url -> Result.success("OK"),
                                                            ssh,
                                                            envWithKey());

        assertThat(result.isSuccess()).as(() -> "deploy: " + result).isTrue();
        for (var host : List.of("203.0.113.10", "203.0.113.11", "203.0.113.12")) {
            assertThat(calls.stream().filter(c -> c.host().equals(host) && isStart(c.command())).count())
                .as("exactly one start for " + host)
                .isEqualTo(1);
        }
        assertNoRestartShape(List.copyOf(calls));
    }

    @Test
    void sshPush_startsEachNodeExactlyOnce_andNeverRestarts() {
        var ctx = multiSourceContext(RuntimeType.CONTAINER);
        var calls = new ConcurrentLinkedQueue<Call>();
        Fn3<Result<String>, String, String, SshConfig> ssh = (host, command, config) -> {
            calls.add(new Call(host, command));
            return Result.success("");
        };
        Fn4<Result<Unit>, String, String, String, SshConfig> scp = (local, host, remote, config) -> Result.unitResult();

        var result = BootstrapPhaseDeploy.deploySshSource(ctx,
                                                          ctx.config().sources().get("dc-1"),
                                                          sourceNameOrDefault("dc-1"),
                                                          ssh,
                                                          scp,
                                                          name -> null);

        assertThat(result.isSuccess()).as(() -> "deploy: " + result).isTrue();
        for (var host : List.of("10.0.0.1", "10.0.0.2")) {
            assertThat(calls.stream().filter(c -> c.host().equals(host) && isStart(c.command())).count())
                .as("exactly one start for " + host)
                .isEqualTo(1);
        }
        assertNoRestartShape(List.copyOf(calls));
    }

    // ---------------------------------------------------------------------------------------------
    // The refusal: a host that already holds an aether-node is never relaunched
    // ---------------------------------------------------------------------------------------------

    /// The guard is shell, so a string assertion cannot show it works. This RUNS the generated command with
    /// a stub `docker` that reports an existing `aether-node`: it must exit 17 with the marker and must never
    /// reach `docker run`. The control run (nothing present) reaches `docker run`, so the refusal is not an
    /// artifact of a command that cannot run at all.
    @Test
    void containerStartGuard_refusesAnExistingContainer_andStartsOtherwise() throws Exception {
        var command = BootstrapPhaseDeploy.buildStartCommand("img:1",
                                                             CLUSTER,
                                                             "eu-1-core-0",
                                                             NodeRole.CORE,
                                                             sourceNameOrDefault("eu-1"),
                                                             Option.empty(),
                                                             7000,
                                                             8080,
                                                             "eu-1-core-0:203.0.113.10:7000",
                                                             SECRET,
                                                             name -> null);

        var present = runWithStub("docker", "[ \"$1\" = ps ] && echo aether-node; [ \"$1\" = run ] && echo RAN-DOCKER-RUN; exit 0", command);
        var absent = runWithStub("docker", "[ \"$1\" = ps ] && echo other-container; [ \"$1\" = run ] && echo RAN-DOCKER-RUN; exit 0", command);

        assertThat(present.exit()).isEqualTo(BootstrapPhaseDeploy.ALREADY_PRESENT_EXIT);
        assertThat(present.output()).contains(BootstrapPhaseDeploy.ALREADY_PRESENT_MARKER).doesNotContain("RAN-DOCKER-RUN");
        assertThat(absent.exit()).as("control: %s", absent.output()).isZero();
        assertThat(absent.output()).contains("RAN-DOCKER-RUN").doesNotContain(BootstrapPhaseDeploy.ALREADY_PRESENT_MARKER);
    }

    @Test
    void jvmStartGuard_refusesAnActiveUnit_beforeTouchingTheEnvFile() throws Exception {
        var command = BootstrapPhaseDeploy.buildJvmStartCommand("eu-1-core-0",
                                                                NodeRole.CORE,
                                                                sourceNameOrDefault("eu-1"),
                                                                Option.empty(),
                                                                7000,
                                                                8080,
                                                                "eu-1-core-0:203.0.113.10:7000",
                                                                SECRET,
                                                                CLUSTER,
                                                                name -> null);
        var active = runWithStub("systemctl",
                                 "[ \"$1\" = is-active ] && [ \"$3\" = aether-node.service ] && exit 0; exit 1",
                                 command);

        assertThat(active.exit()).isEqualTo(BootstrapPhaseDeploy.ALREADY_PRESENT_EXIT);
        assertThat(active.output()).contains(BootstrapPhaseDeploy.ALREADY_PRESENT_MARKER);
        assertThat(command.indexOf("exit 17")).as("the guard precedes every write").isLessThan(command.indexOf("install -d"));
    }

    private record Ran(int exit, String output) {}

    private static Ran runWithStub(String stubName, String stubBody, String command) throws Exception {
        var dir = Files.createTempDirectory("launch-once-stub-");

        try {
            var stub = dir.resolve(stubName);

            Files.writeString(stub, "#!/bin/sh\n" + stubBody + "\n");
            stub.toFile().setExecutable(true);
            var builder = new ProcessBuilder("/bin/sh", "-c", command).redirectErrorStream(true);

            builder.environment().put("PATH", dir + ":" + System.getenv("PATH"));
            var process = builder.start();
            var output = new String(process.getInputStream().readAllBytes());

            return new Ran(process.waitFor(), output);
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void cloudPush_hostAlreadyRunningANode_failsWithTheTypedError_notAGenericOne() {
        var ctx = multiSourceContext(RuntimeType.CONTAINER);
        Fn3<Result<String>, String, String, SshConfig> ssh = (host, command, config) -> isStart(command)
                                                                                         ? BootstrapError.DeploymentFailed.class.cast(
                                                                                             new BootstrapError.DeploymentFailed(host,
                                                                                                                                 "exit 17: " + BootstrapPhaseDeploy.ALREADY_PRESENT_MARKER))
                                                                                                         .<String>result()
                                                                                         : Result.success("");

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            url -> Result.success("OK"),
                                                            ssh,
                                                            envWithKey());

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(BootstrapError.NodeAlreadyStarted.class));
        result.onFailure(cause -> assertThat(cause.message()).contains("destroy").contains("replace"));
    }

    @Test
    void sshPush_hostAlreadyRunningANode_failsWithTheTypedError() {
        var ctx = multiSourceContext(RuntimeType.CONTAINER);
        Fn3<Result<String>, String, String, SshConfig> ssh = (host, command, config) -> isStart(command)
                                                                                         ? new BootstrapError.DeploymentFailed(host,
                                                                                                                              "exit 17: " + BootstrapPhaseDeploy.ALREADY_PRESENT_MARKER).<String>result()
                                                                                         : Result.success("");
        Fn4<Result<Unit>, String, String, String, SshConfig> scp = (local, host, remote, config) -> Result.unitResult();

        var result = BootstrapPhaseDeploy.deploySshSource(ctx,
                                                          ctx.config().sources().get("dc-1"),
                                                          sourceNameOrDefault("dc-1"),
                                                          ssh,
                                                          scp,
                                                          name -> null);

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(BootstrapError.NodeAlreadyStarted.class));
    }

    // ---------------------------------------------------------------------------------------------
    // --resume: the per-node started ledger
    // ---------------------------------------------------------------------------------------------

    private static BootstrapContext contextWithStarted(String... startedIds) {
        var base = multiSourceContext(RuntimeType.CONTAINER);
        var state = base.state();

        for (var id : startedIds) {
            state = state.withStartedNodeId(id);
        }

        return contextOf(base.config(), base.nodes(), state);
    }

    @Test
    void resume_startedAndHealthyNode_isSkipped_theOthersAreStarted() {
        var ctx = contextWithStarted("eu-1-core-0");
        var calls = new ConcurrentLinkedQueue<Call>();
        Fn3<Result<String>, String, String, SshConfig> ssh = (host, command, config) -> {
            calls.add(new Call(host, command));
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            url -> Result.success("OK"),
                                                            ssh,
                                                            envWithKey());

        assertThat(result.isSuccess()).as(() -> "resume: " + result).isTrue();
        assertThat(calls.stream().filter(c -> c.host().equals("203.0.113.10") && isStart(c.command())).count())
            .as("a started, healthy node is never launched again").isZero();
        assertThat(calls.stream().filter(c -> c.host().equals("203.0.113.11") && isStart(c.command())).count())
            .as("control: an unstarted node is still started").isEqualTo(1);
        assertThat(calls.stream().filter(c -> c.host().equals("203.0.113.12") && isStart(c.command())).count()).isEqualTo(1);
    }

    @Test
    void resume_startedButUnhealthyNode_isRefusedByName_andNothingIsLaunched() {
        var ctx = contextWithStarted("eu-1-core-0");
        var calls = new ConcurrentLinkedQueue<Call>();
        Fn3<Result<String>, String, String, SshConfig> ssh = (host, command, config) -> {
            calls.add(new Call(host, command));
            return Result.success("");
        };

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            url -> BootstrapPhaseDeploy.NO_HEALTH_CHECK.result(),
                                                            ssh,
                                                            envWithKey());

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(BootstrapError.NodeAlreadyStarted.class));
        result.onFailure(cause -> assertThat(cause.message()).contains("eu-1-core-0").contains("destroy").contains("replace"));
        assertThat(calls.stream().filter(c -> isStart(c.command())).count())
            .as("an unhealthy started node is never relaunched, and the run stops before any other start").isZero();
    }

    @Test
    void resume_sshSource_skipsAStartedHealthyHost() {
        var ctx = contextWithStarted("dc-1-core-0");
        var calls = new ConcurrentLinkedQueue<Call>();
        Fn3<Result<String>, String, String, SshConfig> ssh = (host, command, config) -> {
            calls.add(new Call(host, command));
            return Result.success("");
        };
        Fn4<Result<Unit>, String, String, String, SshConfig> scp = (local, host, remote, config) -> Result.unitResult();

        var result = BootstrapPhaseDeploy.deploySshSource(ctx,
                                                          ctx.config().sources().get("dc-1"),
                                                          sourceNameOrDefault("dc-1"),
                                                          url -> Result.success("OK"),
                                                          ssh,
                                                          scp,
                                                          name -> null);

        assertThat(result.isSuccess()).as(() -> "resume: " + result).isTrue();
        assertThat(calls.stream().filter(c -> c.host().equals("10.0.0.1") && isStart(c.command())).count()).isZero();
        assertThat(calls.stream().filter(c -> c.host().equals("10.0.0.2") && isStart(c.command())).count()).isEqualTo(1);
    }

    /// The ledger the resume path reads is written by the deploy itself, so a failure on a LATER node leaves
    /// the earlier ones recorded. Drives the real persistence (a cluster name owned by this test).
    @Test
    void deploy_recordsEachStartedNode_inThePersistedLedger() {
        var ctx = multiSourceContext(RuntimeType.CONTAINER);

        assertThat(BootstrapStatePersistence.save(ctx.state()).isSuccess()).as("precondition: a state file exists").isTrue();
        Fn3<Result<String>, String, String, SshConfig> ssh = (host, command, config) -> Result.success("");

        var result = BootstrapPhaseDeploy.deployCloudSource(ctx,
                                                            ctx.config().sources().get("eu-1"),
                                                            sourceNameOrDefault("eu-1"),
                                                            url -> Result.success("OK"),
                                                            ssh,
                                                            envWithKey());

        assertThat(result.isSuccess()).isTrue();
        assertThat(BootstrapStatePersistence.load(CLUSTER).map(BootstrapState::startedNodeIds).or(List.of()))
            .containsExactlyInAnyOrderElementsOf(EU);
    }

    @Test
    void startedLedger_survivesTheJsonRoundTrip() {
        var state = BootstrapState.initialState(CLUSTER, "h", "now").withStartedNodeId("a-core-0").withStartedNodeId("a-core-1");
        var parsed = BootstrapState.fromJson(state.toJson());

        assertThat(parsed.isSuccess()).isTrue();
        assertThat(parsed.map(BootstrapState::startedNodeIds).or(List.of())).containsExactly("a-core-0", "a-core-1");
    }

    // ---------------------------------------------------------------------------------------------
    // --resume rebuilds the node list the ledger is consulted against
    // ---------------------------------------------------------------------------------------------

    @Test
    void resume_rebuildsNodesAndAddressesFromTheState_whenProvisioningCompleted() {
        var state = BootstrapState.initialState(CLUSTER, "h", "now")
                                  .withPhaseStatus(BootstrapPhase.PROVISION, BootstrapState.PhaseStatus.COMPLETED)
                                  .withPhaseStatus(BootstrapPhase.COLLECT_ADDRESSES, BootstrapState.PhaseStatus.COMPLETED)
                                  .withPhaseStatus(BootstrapPhase.DEPLOY_RUNTIME, BootstrapState.PhaseStatus.FAILED)
                                  .withProvisionedNodeIds(List.of("eu-1-core-0", "dc-1-core-0"))
                                  .withCollectedAddresses(List.of("203.0.113.10", "10.0.0.1"))
                                  .withClusterSecret(SECRET);
        var ctx = ClusterBootstrapOrchestrator.resumeContext(config(Map.of("eu-1", cloudSource(), "dc-1", sshSource()), RuntimeType.CONTAINER),
                                                             state,
                                                             List.of(),
                                                             "");

        assertThat(ctx.nodes().stream().map(ProvisionedNode::nodeId).toList()).containsExactly("eu-1-core-0", "dc-1-core-0");
        assertThat(ctx.nodes().stream().map(ProvisionedNode::publicIp).toList()).containsExactly("203.0.113.10", "10.0.0.1");
        assertThat(ctx.nodes().get(1).serverId()).as("the SSH launch path keys on the 'ssh' tag").isEqualTo("ssh");
        assertThat(ctx.addresses().stream().map(NodeAddress::publicIp).toList()).containsExactly("203.0.113.10", "10.0.0.1");
    }

    @Test
    void resume_leavesTheNodeListEmpty_whenProvisioningNeverCompleted() {
        var state = BootstrapState.initialState(CLUSTER, "h", "now")
                                  .withProvisionedNodeIds(List.of("eu-1-core-0"))
                                  .withCollectedAddresses(List.of("203.0.113.10"))
                                  .withClusterSecret(SECRET);
        var ctx = ClusterBootstrapOrchestrator.resumeContext(config(Map.of("eu-1", cloudSource()), RuntimeType.CONTAINER),
                                                             state,
                                                             List.of(),
                                                             "");

        assertThat(ctx.nodes()).as("PROVISION will run again and mint its own list").isEmpty();
    }

    @SuppressWarnings("unused")
    private static Path unusedPathImport() {
        return Path.of(".");
    }
}
