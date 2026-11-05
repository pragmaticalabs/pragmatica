// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapContext;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigValidator;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2124 — re-running a partly failed docker bootstrap resumes the nodes of the earlier run; it does not create a second set.
///
/// The provider is a stateful fake of the docker host: it keeps the containers it created, refuses a name already in use (as docker does),
/// and lists them. Every scenario ends on the leak invariant: the containers on the host are exactly the names the ledger FILE holds, and no
/// name was created twice. The ledger is the real persisted one, under a cluster name owned by this test.
class DockerBootstrapResumeTest {
    private static final ClusterName CLUSTER = uniqueClusterName();

    private static final String TOML = toml(3, 2, 0);

    private static String toml(int cores, int workers, int spots) {
        var spot = spots == 0
                   ? ""
                   : """

                     [source.local.spot]
                     count = %d
                     """.formatted(spots);

        return """
               config_version = "1.0.0"

               [cluster]
               name = "%s"
               version = "1.0.0"

               [source.local]
               type = "docker"

               [source.local.core]
               count = %d

               [source.local.worker]
               count = %d
               """.formatted(CLUSTER.value(), cores, workers) + spot;
    }

    private static ClusterName uniqueClusterName() {
        var suffix = new byte[6];

        new SecureRandom().nextBytes(suffix);

        return ClusterName.clusterName("resume-2124-" + HexFormat.of().formatHex(suffix)).unwrap();
    }

    /// The docker host. `created` holds only creates that SUCCEEDED, in order; `attempts` every create call.
    private static final class FakeDocker implements ComputeProvider {
        final Map<String, InstanceStatus> containers = new LinkedHashMap<>();
        final List<String> attempts = new ArrayList<>();
        final List<String> created = new ArrayList<>();
        final List<String> terminated = new ArrayList<>();
        final List<Integer> ledgerRowsAtCreate = new ArrayList<>();
        final List<ProvisionRequest> requests = new ArrayList<>();
        int failOnAttempt = -1;
        boolean listFails;

        @Override
        public ProviderDefaults providerDefaults() {
            return ProviderDefaults.providerDefaults("docker", "", "", "", Option.empty(), false);
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            var name = request.context().nodeId().unwrap();

            requests.add(request);
            attempts.add(name);
            ledgerRowsAtCreate.add(ledgerNames().size());

            if (attempts.size() == failOnAttempt) {
                return Promise.failure(Causes.cause("injected failure"));
            }

            if (containers.containsKey(name)) {
                return Promise.failure(Causes.cause("container name already in use: " + name));
            }

            containers.put(name, InstanceStatus.RUNNING);
            created.add(name);

            return InstanceInfo.instanceInfo(InstanceId.instanceId(idOf(name)).unwrap(), InstanceStatus.RUNNING, List.of(name), InstanceType.ON_DEMAND)
                               .async();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            var name = instanceId.value().substring(ID_PREFIX.length());

            terminated.add(name);
            containers.remove(name);

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            if (listFails) {
                return Promise.failure(Causes.cause("docker daemon unreachable"));
            }

            return Promise.success(containers.entrySet()
                                             .stream()
                                             .map(entry -> listed(entry.getKey(), entry.getValue()))
                                             .toList());
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return Promise.failure(Causes.cause("not used"));
        }

        /// Docker's own id of a container is not its name; the provider reports the id, the ledger and the cluster use the name.
        static final String ID_PREFIX = "cid-";

        static String idOf(String name) {
            return ID_PREFIX + name;
        }

        private static InstanceInfo listed(String name, InstanceStatus status) {
            return new InstanceInfo(new InstanceId(idOf(name)), status, List.of(), InstanceType.ON_DEMAND, Map.of(), Option.some(name), Option.none());
        }
    }

    private FakeDocker docker;

    @BeforeEach
    void install() {
        docker = new FakeDocker();
        var _ = BootstrapStatePersistence.delete(CLUSTER);
        var _ = BootstrapStatePersistence.save(BootstrapState.initialState(CLUSTER, "h", "now"));
        DockerHostPorts.override = containerId -> Result.success(40_000 + Math.floorMod(containerId.hashCode(), 20_000));
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                return Result.success(EnvironmentIntegration.withCompute(docker));
            }
        };
    }

    @AfterEach
    void remove() throws Exception {
        DockerHostPorts.override = null;
        ProviderResolver.dockerFactoryOverride = null;
        var _ = BootstrapStatePersistence.delete(CLUSTER);
        Files.deleteIfExists(BootstrapStatePersistence.statePath(CLUSTER).getParent());
    }

    private static ClusterBootstrapConfig config() {
        return config(TOML);
    }

    private static ClusterBootstrapConfig config(String toml) {
        return ClusterBootstrapConfigParser.parse(toml).flatMap(ClusterBootstrapConfigValidator::validate).unwrap();
    }

    /// The validator refuses a docker spot (PF-15), so the spot scenario skips it: what it pins is that the provisioning code treats every
    /// role of [DockerCores#ROLE_ORDER] alike, not that the config schema admits the role on docker.
    private static ClusterBootstrapConfig configUnvalidated(String toml) {
        return ClusterBootstrapConfigParser.parse(toml).unwrap();
    }

    /// One run of the PROVISION phase over the ledger FILE as it stands, which is what a re-run starts from.
    private static Result<BootstrapContext> runPhase() {
        return runPhase(TOML);
    }

    private static Result<BootstrapContext> runPhase(String toml) {
        return runPhase(config(toml));
    }

    private static Result<BootstrapContext> runPhase(ClusterBootstrapConfig config) {
        var state = BootstrapStatePersistence.read(CLUSTER).unwrap().unwrap();

        return BootstrapPhaseProvision.execute(BootstrapContext.bootstrapContext(config, state, List.of(), List.of()));
    }

    private static List<String> rolesInLedger() {
        return BootstrapStatePersistence.read(CLUSTER)
                                        .unwrap()
                                        .unwrap()
                                        .createdResources()
                                        .stream()
                                        .filter(CreatedResource.ProvisionedVm.class::isInstance)
                                        .map(CreatedResource.ProvisionedVm.class::cast)
                                        .map(CreatedResource.ProvisionedVm::role)
                                        .toList();
    }

    /// A view of the fake host as the two real providers differ: the creating one starts containers but is not cluster-scoped (it refuses to
    /// list or terminate), the cluster-scoped one lists and removes but starts nothing.
    private static final class ScopedView implements ComputeProvider {
        private final FakeDocker host;
        private final boolean scoped;

        ScopedView(FakeDocker host, boolean scoped) {
            this.host = host;
            this.scoped = scoped;
        }

        @Override
        public ProviderDefaults providerDefaults() {
            return host.providerDefaults();
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return scoped
                   ? Promise.failure(Causes.cause("the cluster-scoped provider creates nothing"))
                   : host.createFrom(request);
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return scoped
                   ? host.terminate(instanceId)
                   : Promise.failure(Causes.cause("no cluster scope known"));
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return scoped
                   ? host.listInstances()
                   : Promise.failure(Causes.cause("no cluster scope known"));
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return host.instanceStatus(instanceId);
        }
    }

    private static List<String> ledgerNames() {
        return BootstrapStatePersistence.read(CLUSTER)
                                        .map(state -> state.map(DockerBootstrapResumeTest::vmNames).or(List.of()))
                                        .or(List.of());
    }

    private static List<String> vmNames(BootstrapState state) {
        return state.createdResources()
                    .stream()
                    .filter(CreatedResource.ProvisionedVm.class::isInstance)
                    .map(CreatedResource::resourceId)
                    .toList();
    }

    /// The leak invariant: what runs on the host is what the ledger names, and no name was created twice.
    private void assertNoLeakNoDuplicate() {
        assertNoLeak();
        assertThat(docker.created).as("no name created twice").doesNotHaveDuplicates();
    }

    private void assertNoLeak() {
        assertThat(docker.containers.keySet()).as("containers on the host == names in the ledger file").containsExactlyInAnyOrderElementsOf(ledgerNames());
        assertThat(ledgerNames()).as("no name recorded twice").doesNotHaveDuplicates();
    }

    private static List<String> peersOf(ProvisionRequest request) {
        return Arrays.asList(request.context().peers().or("").split(","));
    }

    @Test
    void firstRun_recordsEveryNodeBeforeTheFirstCreate() {
        var first = runPhase();

        assertThat(first.isSuccess()).isTrue();
        assertThat(first.map(ctx -> ctx.nodes().stream().map(node -> node.serverId()).toList()).or(List.of()))
            .as("a created node is addressed by its container name, the name its ledger row holds").containsExactlyInAnyOrderElementsOf(docker.containers.keySet());
        assertThat(first.map(ctx -> vmNames(ctx.state())).or(List.of())).as("one row per node in the returned state").containsExactlyInAnyOrderElementsOf(ledgerNames());
        assertThat(docker.ledgerRowsAtCreate).as("all 5 names were in the ledger file at every create call, the first included").containsOnly(5);
        assertNoLeakNoDuplicate();
        assertThat(docker.containers).hasSize(5);
    }

    @Test
    void rerun_afterFailureAtTheThirdCreate_createsEachNodeExactlyOnce_underTheSameIds() {
        docker.failOnAttempt = 3;
        assertThat(runPhase().isFailure()).isTrue();
        var firstRunNames = List.copyOf(ledgerNames());
        var firstRunCorePeers = peersOf(docker.requests.getFirst());
        var firstRunAttempts = docker.attempts.size();

        docker.failOnAttempt = -1;
        var second = runPhase();

        assertThat(second.isSuccess()).isTrue();
        assertThat(docker.containers).as("exactly M containers").hasSize(5);
        assertThat(ledgerNames()).as("the re-run recorded no new id").containsExactlyElementsOf(firstRunNames);
        assertThat(docker.attempts.size() - firstRunAttempts).as("only the 3 nodes not yet created were created again").isEqualTo(3);
        assertThat(docker.requests.stream().skip(firstRunAttempts).map(request -> peersOf(request)).collect(Collectors.toSet()))
            .as("the re-run's cores carry the very peers list of the first run").containsExactly(firstRunCorePeers);
        assertThat(second.map(ctx -> ctx.nodes().size()).or(0)).as("the phase returns all 5 nodes, adopted ones included").isEqualTo(5);
        assertNoLeakNoDuplicate();
    }

    @Test
    void rerun_ofACompleteRun_adoptsEveryNode_createsAndRemovesNothing() {
        assertThat(runPhase().isSuccess()).isTrue();
        var attemptsBefore = docker.attempts.size();

        var second = runPhase();

        assertThat(second.isSuccess()).isTrue();
        assertThat(docker.attempts.size()).as("healthy containers are not created again").isEqualTo(attemptsBefore);
        assertThat(docker.terminated).as("healthy containers are not replaced").isEmpty();
        assertThat(second.map(ctx -> ctx.nodes().stream().map(node -> node.serverId()).toList()).or(List.of()))
            .as("the adopted nodes are the recorded containers, by name").containsExactlyInAnyOrderElementsOf(docker.containers.keySet());
        assertThat(second.map(ctx -> vmNames(ctx.state())).or(List.of()))
            .as("the state the phase returns holds each node once, the row written before the create included").containsExactlyInAnyOrderElementsOf(ledgerNames());
        assertNoLeakNoDuplicate();
    }

    @Test
    void rerun_replacesOnlyTheFailedContainer_underItsOwnId() {
        assertThat(runPhase().isSuccess()).isTrue();
        var failed = docker.containers.keySet().stream().skip(3).findFirst().orElseThrow();
        docker.containers.put(failed, InstanceStatus.STOPPING);
        var attemptsBefore = docker.attempts.size();

        assertThat(runPhase().isSuccess()).isTrue();

        assertThat(docker.terminated).as("only the failed container is removed").containsExactly(failed);
        assertThat(docker.attempts.subList(attemptsBefore, docker.attempts.size())).as("and created again under the same name").containsExactly(failed);
        assertThat(docker.containers.get(failed)).isEqualTo(InstanceStatus.RUNNING);
        assertNoLeak();
    }

    @Test
    void rerun_createsTheContainerThatWentMissing_underItsOwnId() {
        assertThat(runPhase().isSuccess()).isTrue();
        var missing = docker.containers.keySet().iterator().next();
        docker.containers.remove(missing);
        docker.created.remove(missing);
        var attemptsBefore = docker.attempts.size();

        assertThat(runPhase().isSuccess()).isTrue();

        assertThat(docker.attempts.subList(attemptsBefore, docker.attempts.size())).containsExactly(missing);
        assertThat(docker.terminated).isEmpty();
        assertNoLeakNoDuplicate();
    }

    @Test
    void rerun_whenTheHostCannotBeListed_createsNothing() {
        docker.failOnAttempt = 3;
        assertThat(runPhase().isFailure()).isTrue();
        var attemptsBefore = docker.attempts.size();

        docker.failOnAttempt = -1;
        docker.listFails = true;

        assertThat(runPhase().isFailure()).as("could not look is not nothing there").isTrue();
        assertThat(docker.attempts.size()).as("no create beside containers that could not be seen").isEqualTo(attemptsBefore);
    }

    @Test
    void firstRun_whenTheLedgerCannotHoldTheNames_createsNothing() {
        var _ = BootstrapStatePersistence.delete(CLUSTER);
        var ctx = BootstrapContext.bootstrapContext(config(), BootstrapState.initialState(CLUSTER, "h", "now"), List.of(), List.of());

        var result = BootstrapPhaseProvision.execute(ctx);

        assertThat(result.isFailure()).isTrue();
        assertThat(docker.attempts).as("a name the ledger cannot hold is not created").isEmpty();
    }

    @Test
    void rerun_keepsEveryRecordedNameDistinct_andInTheTwoRoles() {
        docker.failOnAttempt = 2;
        var _ = runPhase();
        docker.failOnAttempt = -1;
        var _ = runPhase();

        var names = Set.copyOf(ledgerNames());

        assertThat(names).hasSize(5);
        assertThat(BootstrapStatePersistence.read(CLUSTER).unwrap().unwrap().createdResources().stream()
                                            .filter(CreatedResource.ProvisionedVm.class::isInstance)
                                            .map(CreatedResource.ProvisionedVm.class::cast)
                                            .map(CreatedResource.ProvisionedVm::role)
                                            .collect(Collectors.groupingBy(role -> role, Collectors.counting())))
            .containsEntry("core", 3L).containsEntry("worker", 2L);
    }

    @Test
    void rerun_listsAndRemovesThroughTheClusterScopedProvider_createsThroughTheCreatingOne() {
        ProviderResolver.dockerFactoryOverride = new EnvironmentIntegrationFactory() {
            @Override
            public String providerName() {
                return "docker";
            }

            @Override
            public Result<EnvironmentIntegration> create(CloudConfig config) {
                var scoped = config.compute().containsKey("cluster_name");

                return Result.success(EnvironmentIntegration.withCompute(new ScopedView(docker, scoped)));
            }
        };
        assertThat(runPhase().isSuccess()).isTrue();
        var failed = docker.containers.keySet().stream().skip(3).findFirst().orElseThrow();
        docker.containers.put(failed, InstanceStatus.STOPPING);
        var attemptsBefore = docker.attempts.size();

        var second = runPhase();

        assertThat(second.isSuccess()).as("the lookups went to the provider that can answer them").isTrue();
        assertThat(docker.terminated).containsExactly(failed);
        assertThat(docker.attempts.subList(attemptsBefore, docker.attempts.size())).containsExactly(failed);
        assertNoLeak();
    }

    @Test
    void spotRole_isRecordedBeforeTheFirstCreate_andSurvivesAReRun() {
        var withSpot = configUnvalidated(toml(3, 2, 1));
        docker.failOnAttempt = 6;
        assertThat(runPhase(withSpot).isFailure()).isTrue();
        var recorded = List.copyOf(ledgerNames());
        var failedBefore = docker.attempts.size();

        assertThat(recorded).as("the spot's name was recorded before any create, with the cores' and workers'").hasSize(6);
        assertThat(rolesInLedger()).containsOnlyOnce("spot");
        assertThat(docker.ledgerRowsAtCreate).containsOnly(6);

        docker.failOnAttempt = -1;
        var second = runPhase(withSpot);

        assertThat(second.isSuccess()).isTrue();
        assertThat(ledgerNames()).as("the re-run recorded no new id").containsExactlyElementsOf(recorded);
        assertThat(docker.attempts.size() - failedBefore).as("only the spot, which did not come up, was created again").isEqualTo(1);
        assertThat(second.map(ctx -> ctx.nodes().size()).or(0)).isEqualTo(6);
        assertNoLeakNoDuplicate();
        assertThat(docker.containers).hasSize(6);
    }

    @Test
    void rerun_withASmallerCoreCount_resumesOnlyTheCountedNodes_andKeepsTheDroppedRowsInTheLedger() {
        assertThat(runPhase(toml(5, 2, 0)).isSuccess()).isTrue();
        var recorded = List.copyOf(ledgerNames());
        var firstCore = ledgerNames().getFirst();
        docker.containers.remove(firstCore);
        docker.created.remove(firstCore);
        var attemptsBefore = docker.attempts.size();

        var second = runPhase(toml(3, 2, 0));

        assertThat(second.isSuccess()).isTrue();
        assertThat(second.map(ctx -> ctx.nodes().size()).or(0)).as("3 cores + 2 workers: the fourth and fifth core are no longer part of the plan").isEqualTo(5);
        assertThat(docker.attempts.subList(attemptsBefore, docker.attempts.size())).as("only the core that went missing was created").containsExactly(firstCore);
        assertThat(peersOf(docker.requests.getLast())).as("its peers are the 3 counted cores, not the 5 recorded").hasSize(3).anyMatch(peer -> peer.startsWith(firstCore + ":"));
        assertThat(ledgerNames()).as("the dropped cores' rows stay, so cleanup still names their containers").containsExactlyInAnyOrderElementsOf(recorded);
        assertNoLeak();
    }
}
