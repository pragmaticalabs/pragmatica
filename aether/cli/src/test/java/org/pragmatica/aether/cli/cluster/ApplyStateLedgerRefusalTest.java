// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.FirewallId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.config.cluster.ClusterBootstrapConfig.clusterBootstrapConfig;
import static org.pragmatica.aether.config.cluster.ClusterIdentity.clusterIdentity;
import static org.pragmatica.aether.config.cluster.CoreTopology.defaultCoreTopology;
import static org.pragmatica.aether.config.cluster.InfrastructureConfig.infrastructureConfig;
import static org.pragmatica.aether.config.cluster.OperationsConfig.defaultOperationsConfig;
import static org.pragmatica.aether.config.cluster.RoleSubTable.roleSubTable;
import static org.pragmatica.aether.config.cluster.RuntimeProfile.runtimeProfile;
import static org.pragmatica.aether.config.cluster.SourceProfile.sourceProfile;
import static org.pragmatica.aether.environment.FirewallName.firewallName;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The apply-state ledger gets the same refusal discipline as the bootstrap ledger: a file that is
/// present but refused (torn, unknown resource type, blank id) is never read as "no state" and never
/// written over. `apply-state.json` can hold the only handles on resources a rollout created.
///
/// Runs against the REAL `~/.aether/clusters/<name>` path, for the reason
/// `BootstrapStatePersistenceAppendResourceTest` gives; a per-run random cluster name keeps concurrent
/// reactors from racing each other's files.
class ApplyStateLedgerRefusalTest {
    private static final String RUNTIME_REF = "default";
    private final ClusterName clusterName = uniqueClusterName();

    private static ClusterName uniqueClusterName() {
        var suffix = new byte[6];

        new SecureRandom().nextBytes(suffix);

        return ClusterName.clusterName("ledger-r2-" + HexFormat.of().formatHex(suffix)).unwrap();
    }

    @AfterEach
    @SuppressWarnings("JBCT-EX-01")
    void cleanUp() throws Exception {
        var dir = BootstrapStatePersistence.AETHER_DIR.resolve(clusterName.value());

        Files.deleteIfExists(dir.resolve(BootstrapStatePersistence.STATE_FILE_NAME));
        Files.deleteIfExists(dir.resolve("apply-state.json"));
        Files.deleteIfExists(dir);
    }

    private Path applyStatePath() {
        return BootstrapStatePersistence.AETHER_DIR.resolve(clusterName.value()).resolve("apply-state.json");
    }

    @SuppressWarnings("JBCT-EX-01")
    private void writeApplyState(String content) throws Exception {
        Files.createDirectories(applyStatePath().getParent());
        Files.writeString(applyStatePath(), content);
    }

    private static String applyStateJson(String clusterName, String resourceEntry) {
        return ApplyStateFixtures.json(clusterName, resourceEntry);
    }

    private ClusterBootstrapConfig config(int coreCount) {
        return clusterBootstrapConfig(clusterIdentity(clusterName.value(), "1.0.0").unwrap(),
                                      defaultCoreTopology(),
                                      Map.of("forge", forgeSource(coreCount)),
                                      Map.of(RUNTIME_REF, runtimeProfile(RuntimeType.JVM, none(), none())),
                                      infrastructureConfig(),
                                      defaultOperationsConfig(),
                                      Map.of());
    }

    private static SourceProfile forgeSource(int coreCount) {
        return sourceProfile(sourceNameOrDefault("forge"),
                             SourceType.FORGE,
                             none(), none(), none(), none(), none(), none(), none(),
                             LoadBalancerMode.NONE,
                             List.of(),
                             none(),
                             Map.of(),
                             Map.of(NodeRole.CORE, roleSubTable(some(coreCount), none(), none(), RUNTIME_REF)),
                             List.of());
    }

    @Nested
    class Reading {
        @Test
        void read_absentFile_isNone() {
            ApplyState.read(clusterName)
                      .onFailure(cause -> fail("an absent file is not a failure: " + cause.message()))
                      .onSuccess(state -> assertThat(state.isPresent()).isFalse());
        }

        @Test
        void read_unknownResourceType_failsNamingTheType() throws Exception {
            writeApplyState(applyStateJson(clusterName.value(), "{\"type\": \"FutureThing\", \"resourceId\": \"x-9\"}"));

            ApplyState.read(clusterName)
                      .onSuccess(state -> fail("an unknown entry must not load: " + state))
                      .onFailure(cause -> assertThat(cause.message()).contains("FutureThing"));
        }

        @Test
        void read_resourceWithoutType_fails() throws Exception {
            writeApplyState(applyStateJson(clusterName.value(), "{\"resourceId\": \"x-9\"}"));

            ApplyState.read(clusterName)
                      .onSuccess(state -> fail("a typeless entry must not load: " + state))
                      .onFailure(cause -> assertThat(cause.message()).contains("missing"));
        }

        @Test
        void read_vmWithoutOrBlankResourceId_fails() throws Exception {
            for (var entry : List.of("{\"type\": \"ProvisionedVm\", \"provider\": \"hetzner\", \"sourceName\": \"eu-1\", \"role\": \"core\"}",
                                     "{\"type\": \"ProvisionedVm\", \"provider\": \"hetzner\", \"resourceId\": \"  \", \"sourceName\": \"eu-1\", \"role\": \"core\"}")) {
                writeApplyState(applyStateJson(clusterName.value(), entry));

                ApplyState.read(clusterName)
                          .onSuccess(state -> fail("a VM entry without a usable id must not load: " + state))
                          .onFailure(cause -> assertThat(cause.message()).contains("ProvisionedVm").contains("resourceId"));
            }
        }

        @Test
        void read_containerWithoutOrBlankContainerId_fails() throws Exception {
            for (var entry : List.of("{\"type\": \"DockerContainer\", \"sourceName\": \"eu-1\"}",
                                     "{\"type\": \"DockerContainer\", \"containerId\": \"   \", \"sourceName\": \"eu-1\"}")) {
                writeApplyState(applyStateJson(clusterName.value(), entry));

                ApplyState.read(clusterName)
                          .onSuccess(state -> fail("a container entry without a usable id must not load: " + state))
                          .onFailure(cause -> assertThat(cause.message()).contains("DockerContainer").contains("containerId"));
            }
        }
    }

    @Nested
    class ResumeAndRollback {
        private static final String FUTURE_ENTRY = "{\"type\": \"FutureThing\", \"resourceId\": \"x-9\"}";

        @Test
        void resume_unreadableApplyState_failsNamingTheRefusal_notNothingToResume() throws Exception {
            writeApplyState(applyStateJson(clusterName.value(), FUTURE_ENTRY));

            ApplyOrchestrator.resume(config(3), config(3))
                             .onSuccess(result -> fail("resume must not proceed past a refused state"))
                             .onFailure(cause -> assertThat(cause.message()).contains("unreadable")
                                                                             .contains("FutureThing")
                                                                             .doesNotContain("Nothing to resume"));
        }

        @Test
        void resume_absentApplyState_stillReportsNothingToResume() {
            ApplyOrchestrator.resume(config(3), config(3))
                             .onSuccess(result -> fail("nothing to resume"))
                             .onFailure(cause -> assertThat(cause.message()).contains("Nothing to resume"));
        }

    }

    @Nested
    class FreshApply {
        private final AtomicInteger executed = new AtomicInteger();

        private org.pragmatica.lang.Result<ApplyResult> run() {
            var stored = config(3);
            var desired = config(5);
            var plan = ClusterBootstrapConfigDiff.diff(stored, desired);

            return ApplyOrchestrator.executeAndPersist(plan, stored, desired, this::stubExecutor);
        }

        private org.pragmatica.lang.Result<ApplyResult> stubExecutor(org.pragmatica.aether.config.cluster.DiffPlan plan,
                                                                     ClusterBootstrapConfig stored,
                                                                     ClusterBootstrapConfig desired) {
            executed.incrementAndGet();

            return org.pragmatica.lang.Result.success(ApplyResult.applyResult(plan, 0, 0, 0));
        }

        @Test
        @SuppressWarnings("JBCT-EX-01")
        void executeAndPersist_unreadableApplyState_refusesBeforeExecuting_andLeavesTheFileByteIdentical() throws Exception {
            var ledger = applyStateJson(clusterName.value(), "{\"type\": \"FutureThing\", \"resourceId\": \"x-9\"}");

            writeApplyState(ledger);

            run().onSuccess(result -> fail("a fresh apply must not overwrite a ledger it cannot read"))
                 .onFailure(cause -> assertThat(cause.message()).contains("unreadable").contains("FutureThing"));

            assertThat(executed.get()).as("nothing may be executed past the refusal").isZero();
            assertThat(Files.readString(applyStatePath())).isEqualTo(ledger);
        }

        @Test
        @SuppressWarnings("JBCT-EX-01")
        void executeAndPersist_tornApplyState_refusesAndLeavesTheBytesIntact() throws Exception {
            writeApplyState("{ not valid apply state");

            run().onSuccess(result -> fail("a torn ledger must not be overwritten"));

            assertThat(executed.get()).isZero();
            assertThat(Files.readString(applyStatePath())).isEqualTo("{ not valid apply state");
        }

        @Test
        void executeAndPersist_absentApplyState_executes() {
            run().onFailure(cause -> fail("no prior state is the normal case: " + cause.message()));

            assertThat(executed.get()).isEqualTo(1);
        }

        @Test
        void executeAndPersist_readableHaltedState_isReplacedAndExecutes() throws Exception {
            writeApplyState(applyStateJson(clusterName.value(),
                                           "{\"type\": \"ProvisionedVm\", \"provider\": \"hetzner\", \"resourceId\": \"vm-1\", \"sourceName\": \"eu-1\", \"role\": \"core\"}"));

            run().onFailure(cause -> fail("a readable halted state does not block a fresh apply: " + cause.message()));

            assertThat(executed.get()).isEqualTo(1);
        }
    }

    @Nested
    class RoundTrips {
        private List<CreatedResource> allSevenTypes() {
            return List.of(CreatedResource.ProvisionedVm.provisionedVm("hetzner", "vm-1", "eu-1", "core"),
                           CreatedResource.CloudFirewall.cloudFirewall("hetzner",
                                                                       FirewallId.firewallId("77").unwrap(),
                                                                       sourceNameOrDefault("eu-1"),
                                                                       firewallName("aether-eu-1").unwrap()),
                           // "cloud": FloatingIpAssignment#provider() is a constant override, so the provider component a
                           // caller passes is not what the ledger writes (a pre-existing asymmetry, not pinned here).
                           CreatedResource.FloatingIpAssignment.floatingIpAssignment("cloud", "203.0.113.9", "eu-1-core-0"),
                           CreatedResource.DockerNetwork.dockerNetwork("aether-net"),
                           CreatedResource.DockerContainer.dockerContainer("c-1", "eu-1"),
                           CreatedResource.SshDeployedConfig.sshDeployedConfig("10.0.0.1", "/etc/aether/aether.toml"),
                           CreatedResource.SshKeyResource.sshKeyResource("hetzner", 4242L, "aether-key"));
        }

        @Test
        void bootstrapState_roundTrip_preservesAllSevenResourceTypes() {
            var state = allSevenTypes().stream()
                                       .reduce(BootstrapState.initialState(clusterName, "h", "2026-10-18T00:00:00Z"),
                                               BootstrapState::withResource,
                                               (a, b) -> b);

            BootstrapState.fromJson(state.toJson())
                          .onFailure(cause -> fail(cause.message()))
                          .onSuccess(restored -> assertThat(restored.createdResources()).isEqualTo(allSevenTypes()));
        }

        @Test
        void applyState_roundTrip_preservesAllSevenResourceTypes() {
            var state = allSevenTypes().stream()
                                       .reduce(ApplyStateFixtures.state(clusterName),
                                               ApplyState::withResource,
                                               (a, b) -> b);

            ApplyState.fromJson(ApplyState.toJson(state))
                      .onFailure(cause -> fail(cause.message()))
                      .onSuccess(restored -> assertThat(restored.createdResources()).isEqualTo(allSevenTypes()));
        }
    }
}
