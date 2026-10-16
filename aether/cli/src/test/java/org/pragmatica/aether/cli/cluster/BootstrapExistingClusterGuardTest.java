// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.cli.cluster.BootstrapExistingClusterGuard.Probes;
import org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.ClusterIdentity;
import org.pragmatica.aether.config.cluster.CoreTopology;
import org.pragmatica.aether.config.cluster.InfrastructureConfig;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NetworkingType;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.OperationsConfig;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

/// Owner Q5 — a fresh bootstrap refuses an existing cluster name before minting a secret or writing state.
///
/// The guard tests inject all three observations ([Probes]) and a temp directory stands in for the operator's
/// home: the registry file and the state directory live there, and every refusal asserts the directory is
/// byte-for-byte what it was. The orchestrator test at the bottom runs the REAL entry point against the real
/// `~/.aether/clusters/<name>` (as `BootstrapStatePersistencePermissionsTest` does: `AETHER_DIR` is fixed at class
/// load) under a per-run random name, and pins the one property the unit tests cannot: a refused run must not
/// reach the failure-path cleanup that reaps the ledger recorded under that name.
class BootstrapExistingClusterGuardTest {
    @TempDir
    Path home;

    private ClusterName name;

    private List<String> probed;

    @BeforeEach
    void setUp() {
        name = randomName();
        probed = new ArrayList<>();
    }

    @Nested
    class RegistryCheck {
        @Test
        void refuseExisting_refuses_whenRegistryHoldsTheName() throws IOException {
            writeRegistry(name);
            var before = snapshot(home);

            var cause = refusal(cloudConfig(name), probes(List.of()));

            assertThat(cause).isInstanceOf(ClusterConfigError.ClusterAlreadyExists.class);
            assertThat(cause.message()).contains("registry").contains("--resume").contains("destroy").contains(name.value());
            assertThat(snapshot(home)).as("no secret, state or registry write on refusal").isEqualTo(before);
        }

        @Test
        void refuseExisting_refuses_whenRegistryCannotBeRead() {
            var probes = new Probes(() -> Causes.cause("registry broken").<ClusterRegistry>result(),
                                    stateIn(home),
                                    (source, cluster) -> Result.success(List.of()));

            var cause = refusal(cloudConfig(name), probes);

            assertThat(cause).isInstanceOf(BootstrapError.ExistingClusterCheckFailed.class);
            assertThat(cause.message()).contains("could not be made").contains("registry");
        }
    }

    @Nested
    class LocalStateCheck {
        @Test
        void refuseExisting_refuses_whenLocalBootstrapStateExists() throws IOException {
            writeState(name);
            var before = snapshot(home);

            var cause = refusal(cloudConfig(name), probes(List.of()));

            assertThat(cause).isInstanceOf(ClusterConfigError.ClusterAlreadyExists.class);
            assertThat(cause.message()).contains("local bootstrap state").contains("--resume").contains("destroy");
            assertThat(snapshot(home)).as("the existing state file is untouched and nothing is added").isEqualTo(before);
        }

        @Test
        void refuseExisting_refuses_whenLocalBootstrapStateIsUnreadable() throws IOException {
            var before = snapshot(home);
            var probes = new Probes(registryIn(home),
                                    cluster -> Causes.cause("torn json").<Option<BootstrapState>>result(),
                                    (source, cluster) -> Result.success(List.of()));

            var cause = refusal(cloudConfig(name), probes);

            assertThat(cause).as("an unreadable file EXISTS: that is a match, not a failed check")
                      .isInstanceOf(ClusterConfigError.ClusterAlreadyExists.class);
            assertThat(cause.message()).contains("unreadable");
            assertThat(snapshot(home)).isEqualTo(before);
        }
    }

    @Nested
    class CloudInstanceCheck {
        @Test
        void refuseExisting_refuses_whenProviderListsALabelledInstance() throws IOException {
            var before = snapshot(home);

            var cause = refusal(cloudConfig(name), probes(List.of(instance())));

            assertThat(cause).isInstanceOf(ClusterConfigError.ClusterAlreadyExists.class);
            assertThat(cause.message()).contains("cloud instance").contains("aether-cluster=" + name.value())
                      .contains("--resume").contains("destroy");
            assertThat(probed).as("the provider was asked about exactly this cluster").containsExactly(name.value());
            assertThat(snapshot(home)).isEqualTo(before);
        }

        @Test
        void refuseExisting_refuses_whenProviderCallFails() throws IOException {
            var before = snapshot(home);
            var probes = new Probes(registryIn(home),
                                    stateIn(home),
                                    (source, cluster) -> Causes.cause("HTTP 401 from provider").<List<InstanceInfo>>result());

            var cause = refusal(cloudConfig(name), probes);

            assertThat(cause).isInstanceOf(BootstrapError.ExistingClusterCheckFailed.class);
            assertThat(cause.message()).contains("could not be made").contains("HTTP 401").contains("nothing was written");
            assertThat(snapshot(home)).isEqualTo(before);
        }
    }

    @Nested
    class NewName {
        @Test
        void refuseExisting_proceeds_forANameNothingHolds() {
            var config = cloudConfig(name);

            Result<ClusterBootstrapConfig> result = BootstrapExistingClusterGuard.refuseExisting(config, probes(List.of()));

            result.onFailure(cause -> Assertions.fail(cause.message()))
                  .onSuccess(passed -> assertThat(passed).isSameAs(config));
            assertThat(probed).as("the cloud check really ran for a cloud source").containsExactly(name.value());
        }

        @Test
        void refuseExisting_proceeds_forANameNothingHoldsWithoutACloudSource() {
            var result = BootstrapExistingClusterGuard.refuseExisting(forgeConfig(name), probes(List.of()));

            assertThat(result.isSuccess()).isTrue();
            assertThat(probed).as("a forge source has no provider to ask").isEmpty();
        }
    }

    /// The property the unit tests cannot reach: `freshBootstrap` folds its failures through
    /// `decorateAfterCleanup`, which reaps the ledger recorded under the cluster name. A refused run must never
    /// get there, because the ledger is the live cluster's.
    @Nested
    class OrchestratorEntryPoint {
        private Function<BootstrapState, Result<Unit>> originalHook;

        private final List<String> reaped = new ArrayList<>();

        @BeforeEach
        void captureCleanup() {
            originalHook = ClusterBootstrapOrchestrator.cleanupHook();
            ClusterBootstrapOrchestrator.setCleanupHook(state -> {
                reaped.add(state.clusterName().value());
                return Result.unitResult();
            });
        }

        @AfterEach
        @SuppressWarnings("JBCT-EX-01")
        void restoreAndRemove() throws IOException {
            ClusterBootstrapOrchestrator.setCleanupHook(originalHook);
            var dir = BootstrapStatePersistence.AETHER_DIR.resolve(name.value());

            Files.deleteIfExists(BootstrapStatePersistence.statePath(name));
            Files.deleteIfExists(dir);
        }

        @Test
        @SuppressWarnings("JBCT-EX-01")
        void bootstrap_refusesAFreshRunOverExistingState_withoutReapingItsLedger() throws IOException {
            var live = BootstrapState.initialState(name, "live-config-hash", "2026-10-16T00:00:00Z")
                                     .withClusterSecret("live-cluster-secret")
                                     .withResource(CreatedResource.ProvisionedVm.provisionedVm("hetzner", "4242", "src", "core"));

            assertThat(BootstrapStatePersistence.save(live).isSuccess()).isTrue();
            var stateFile = BootstrapStatePersistence.statePath(name);
            var bytesBefore = Files.readAllBytes(stateFile);
            var dirBefore = snapshot(stateFile.getParent());

            var cause = ClusterBootstrapOrchestrator.bootstrap(forgeConfig(name), false, false)
                                                    .fold(c -> c, _ -> Assertions.<Cause>fail("expected a refusal"));

            assertThat(cause).isInstanceOf(ClusterConfigError.ClusterAlreadyExists.class);
            assertThat(reaped).as("the refused run must not reach the cleanup that reaps this ledger").isEmpty();
            assertThat(Files.readAllBytes(stateFile)).as("the live ledger and its secret are not overwritten").isEqualTo(bytesBefore);
            assertThat(snapshot(stateFile.getParent())).isEqualTo(dirBefore);
        }
    }

    private Cause refusal(ClusterBootstrapConfig config, Probes probes) {
        return BootstrapExistingClusterGuard.refuseExisting(config, probes)
                                            .fold(cause -> cause, _ -> Assertions.<Cause>fail("expected a refusal"));
    }

    private Probes probes(List<InstanceInfo> labelled) {
        return new Probes(registryIn(home),
                          stateIn(home),
                          (source, cluster) -> {
                              probed.add(cluster.value());
                              return Result.success(labelled);
                          });
    }

    private static org.pragmatica.lang.Functions.Fn0<Result<ClusterRegistry>> registryIn(Path dir) {
        return () -> ClusterRegistry.load(dir.resolve("clusters.toml"));
    }

    private static org.pragmatica.lang.Functions.Fn1<Result<Option<BootstrapState>>, ClusterName> stateIn(Path dir) {
        return cluster -> readState(dir.resolve("clusters").resolve(cluster.value()).resolve("bootstrap-state.json"));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Option<BootstrapState>> readState(Path file) {
        if (!Files.exists(file)) {
            return Result.success(Option.none());
        }

        return Result.lift(Causes::fromThrowable, () -> Files.readString(file))
                     .flatMap(BootstrapState::fromJson)
                     .map(Option::some);
    }

    @SuppressWarnings("JBCT-EX-01")
    private void writeRegistry(ClusterName cluster) throws IOException {
        Files.writeString(home.resolve("clusters.toml"),
                          "[clusters." + cluster.value() + "]\nendpoint = \"https://10.0.0.1:8080\"\n");
    }

    @SuppressWarnings("JBCT-EX-01")
    private void writeState(ClusterName cluster) throws IOException {
        var dir = Files.createDirectories(home.resolve("clusters").resolve(cluster.value()));
        var state = BootstrapState.initialState(cluster, "hash", "2026-10-16T00:00:00Z").withClusterSecret("s3cret");

        Files.writeString(dir.resolve("bootstrap-state.json"), state.toJson());
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Map<String, String> snapshot(Path dir) throws IOException {
        var result = new TreeMap<String, String>();

        try (var paths = Files.walk(dir)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                result.put(dir.relativize(path).toString(), Files.readString(path));
            }
        }

        return result;
    }

    private static InstanceInfo instance() {
        return new InstanceInfo(new InstanceId("1234"),
                                InstanceStatus.RUNNING,
                                List.of("10.0.0.1"),
                                InstanceType.ON_DEMAND,
                                Map.of("aether-cluster", "x"),
                                Option.none(),
                                Option.none());
    }

    private static ClusterName randomName() {
        var suffix = new byte[6];

        new SecureRandom().nextBytes(suffix);

        return ClusterName.clusterName("q5-guard-" + HexFormat.of().formatHex(suffix)).unwrap();
    }

    private static ClusterBootstrapConfig cloudConfig(ClusterName cluster) {
        return config(cluster, "cloud-1", SourceType.CLOUD);
    }

    private static ClusterBootstrapConfig forgeConfig(ClusterName cluster) {
        return config(cluster, "forge-1", SourceType.FORGE);
    }

    private static ClusterBootstrapConfig config(ClusterName cluster, String sourceName, SourceType type) {
        var source = SourceProfile.sourceProfile(sourceNameOrDefault(sourceName),
                                                 type,
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

        return ClusterBootstrapConfig.clusterBootstrapConfig("1.0.0",
                                                             ClusterIdentity.clusterIdentity(cluster.value(), "1.0.0").unwrap(),
                                                             CoreTopology.defaultCoreTopology(),
                                                             Map.of(sourceName, source),
                                                             Map.of(),
                                                             InfrastructureConfig.infrastructureConfig(NetworkingType.MANUAL),
                                                             OperationsConfig.defaultOperationsConfig(),
                                                             Map.of());
    }
}
