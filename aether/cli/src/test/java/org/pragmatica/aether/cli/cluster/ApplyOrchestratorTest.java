// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigDiff;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Result;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.LoadBalancerMode;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RuntimeType;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.config.cluster.ClusterBootstrapConfig.clusterBootstrapConfig;
import static org.pragmatica.aether.config.cluster.ClusterIdentity.clusterIdentity;
import static org.pragmatica.aether.config.cluster.CoreTopology.defaultCoreTopology;
import static org.pragmatica.aether.config.cluster.InfrastructureConfig.infrastructureConfig;
import static org.pragmatica.aether.config.cluster.OperationsConfig.defaultOperationsConfig;
import static org.pragmatica.aether.config.cluster.RoleSubTable.roleSubTable;
import static org.pragmatica.aether.config.cluster.RuntimeProfile.runtimeProfile;
import static org.pragmatica.aether.config.cluster.SourceProfile.sourceProfile;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class ApplyOrchestratorTest {

    private static final String RUNTIME_REF = "default";

    private static SourceProfile forgeSource(String name, int coreCount) {
        return sourceProfile(
            sourceNameOrDefault(name), SourceType.FORGE, none(), none(), none(), none(),
            none(), none(), none(), LoadBalancerMode.NONE, List.of(), none(),
            Map.of(),
            Map.of(NodeRole.CORE, roleSubTable(some(coreCount), none(), none(), RUNTIME_REF)),
            List.of()
        );
    }

    private static SourceProfile sourceWithRole(String name, NodeRole role, int count) {
        return sourceProfile(
            sourceNameOrDefault(name), SourceType.FORGE, none(), none(), none(), none(),
            none(), none(), none(), LoadBalancerMode.NONE, List.of(), none(),
            Map.of(),
            Map.of(role, roleSubTable(some(count), none(), none(), RUNTIME_REF)),
            List.of()
        );
    }

    private static ClusterBootstrapConfig configWithSources(Map<String, SourceProfile> sources) {
        return clusterBootstrapConfig(clusterIdentity("test-cluster", "1.0.0").unwrap(),
            defaultCoreTopology(), sources,
            Map.of(RUNTIME_REF, runtimeProfile(RuntimeType.JVM, none(), none())),
            infrastructureConfig(),
            defaultOperationsConfig()
        , java.util.Map.of());
    }

    private static ClusterBootstrapConfig forgeConfig(int coreCount) {
        return configWithSources(Map.of("forge", forgeSource("forge", coreCount)));
    }

    private static ClusterBootstrapConfig configWithName(String name, int coreCount) {
        return clusterBootstrapConfig(clusterIdentity(name, "1.0.0").unwrap(),
            defaultCoreTopology(),
            Map.of("forge", forgeSource("forge", coreCount)),
            Map.of(RUNTIME_REF, runtimeProfile(RuntimeType.JVM, none(), none())),
            infrastructureConfig(),
            defaultOperationsConfig()
        , java.util.Map.of());
    }

    @Nested
    class DryRunTests {
        @Test
        void dryRun_identicalConfigs_returnsFormattedOutput() {
            var config = forgeConfig(3);

            ApplyOrchestrator.dryRun(config, config)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(output -> assertTrue(output.contains("Cluster: test-cluster")));
        }

        @Test
        void dryRun_scaleUp_returnsFormattedPlan() {
            var stored = forgeConfig(3);
            var desired = forgeConfig(5);

            ApplyOrchestrator.dryRun(desired, stored)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(ApplyOrchestratorTest::assertScaleUpPlanFormatted);
        }

        @Test
        void dryRun_immutableChange_returnsError() {
            var stored = configWithName("cluster-a", 3);
            var desired = configWithName("cluster-b", 3);

            ApplyOrchestrator.dryRun(desired, stored)
                .onSuccess(_ -> fail("Expected failure"))
                .onFailure(cause -> assertInstanceOf(ClusterConfigError.ImmutableFieldChange.class, cause));
        }
    }

    @Nested
    class ApplyTests {
        @Test
        void apply_identicalConfigs_returnsZeroCounts() {
            var config = forgeConfig(3);

            ApplyOrchestrator.apply(config, config)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(ApplyOrchestratorTest::assertZeroCounts);
        }

        @Test
        void apply_scaleUp_returnsCorrectNodeCounts() {
            var stored = forgeConfig(3);
            var desired = forgeConfig(5);

            ApplyOrchestrator.apply(desired, stored)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(ApplyOrchestratorTest::assertScaleUpCounts);
        }

        @Test
        void apply_immutableChange_returnsError() {
            var stored = configWithName("cluster-a", 3);
            var desired = configWithName("cluster-b", 3);

            ApplyOrchestrator.apply(desired, stored)
                .onSuccess(_ -> fail("Expected failure"))
                .onFailure(cause -> assertInstanceOf(ClusterConfigError.ImmutableFieldChange.class, cause));
        }

        @Test
        void apply_addSource_countsNodesAdded() {
            var stored = forgeConfig(3);
            var desired = configWithSources(Map.of(
                "forge", forgeSource("forge", 3),
                "cloud-a", sourceWithRole("cloud-a", NodeRole.WORKER, 5)
            ));

            ApplyOrchestrator.apply(desired, stored)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(result -> assertEquals(5, result.nodesAdded()));
        }

        @Test
        void apply_removeSource_removalsTrackedAsActions() {
            var stored = configWithSources(Map.of(
                "forge", forgeSource("forge", 3),
                "cloud-a", sourceWithRole("cloud-a", NodeRole.WORKER, 5)
            ));
            var desired = forgeConfig(3);

            ApplyOrchestrator.apply(desired, stored)
                .onFailure(_ -> fail("Expected success"))
                .onSuccess(result -> assertTrue(result.executedPlan().removals().size() > 0));
        }
    }

    /// F3: the ledger is written BEFORE the executor runs, and a ledger that was not written stops the apply.
    @Nested
    class LedgerTests {
        private static final ClusterName CLUSTER = ClusterName.clusterName("test-cluster").unwrap();

        @TempDir
        Path stateRoot;

        private Function<ClusterName, Path> originalResolver;
        private final AtomicInteger executed = new AtomicInteger();
        private final ApplyChaosHarness harness = new ApplyChaosHarness();

        @BeforeEach
        void redirect() {
            originalResolver = ApplyState.stateDirResolver;
            ApplyState.stateDirResolver = name -> stateRoot.resolve(name.value());
        }

        @AfterEach
        void restore() {
            ApplyState.stateDirResolver = originalResolver;
        }

        private Path ledgerFile() {
            return stateRoot.resolve(CLUSTER.value()).resolve("apply-state.json");
        }

        private Result<ApplyResult> run(org.pragmatica.lang.Functions.Fn3<Result<ApplyResult>, DiffPlan, ClusterBootstrapConfig, ClusterBootstrapConfig> executor) {
            var stored = forgeConfig(3);
            var desired = forgeConfig(5);

            return ApplyOrchestrator.executeAndPersist(ClusterBootstrapConfigDiff.diff(stored, desired), stored, desired, executor);
        }

        private Result<ApplyResult> countingSuccess(DiffPlan plan, ClusterBootstrapConfig stored, ClusterBootstrapConfig desired) {
            executed.incrementAndGet();

            return Result.success(ApplyResult.applyResult(plan, 2, 0, 0));
        }

        /// Creates a VM labelled with the running ledger's apply id and records its row, as P2 will.
        private ApplyState createAndRecord(String nodeId) {
            var ledger = ApplyState.read(CLUSTER).unwrap().unwrap();
            var info = harness.provider.create(nodeId, Map.of(ApplyChaosHarness.APPLY_LABEL, ledger.applyId()));
            var recorded = ledger.withNode(ApplyResource.applyResource("k",
                                                                       nodeId,
                                                                       "fake",
                                                                       org.pragmatica.lang.Option.some(info.id().value()),
                                                                       ApplyResource.State.CREATED));

            ApplyState.save(recorded).onFailure(cause -> fail(cause.message()));

            return recorded;
        }

        private Result<ApplyResult> createsOneThenFails(DiffPlan plan, ClusterBootstrapConfig stored, ClusterBootstrapConfig desired) {
            executed.incrementAndGet();
            createAndRecord("n-1");

            return org.pragmatica.lang.utils.Causes.cause("provider refused the second create (test cause)").result();
        }

        private Result<ApplyResult> failingExecutor(DiffPlan plan, ClusterBootstrapConfig stored, ClusterBootstrapConfig desired) {
            executed.incrementAndGet();

            return org.pragmatica.lang.utils.Causes.cause("provider refused (test cause)").result();
        }

        @Test
        void executeAndPersist_initialSaveFails_refusesBeforeExecuting() throws IOException {
            var blocker = Files.writeString(stateRoot.resolve("blocker"), "a file where a directory is needed");

            ApplyState.stateDirResolver = name -> blocker.resolve(name.value());

            run(this::countingSuccess).onSuccess(_ -> fail("an apply whose ledger cannot be written must not run"))
                                      .onFailure(cause -> assertTrue(cause.message().contains("persistence"),
                                                                     () -> "the refusal must name the ledger write, was: " + cause.message()));

            assertEquals(0, executed.get(), "no create may be issued without a ledger behind it");
            assertEquals(0, harness.provider.totalCreates());
            harness.assertNoApplyLabelledVm();
        }

        @Test
        void executeAndPersist_ledgerIsDurableAndRunning_whenTheExecutorStarts() {
            var seen = new java.util.concurrent.atomic.AtomicReference<ApplyState>();

            run((plan, stored, desired) -> {
                seen.set(ApplyState.read(CLUSTER).unwrap().unwrap());
                var recorded = createAndRecord("n-1");
                harness.assertNoLeak(recorded.applyId(), ApplyState.read(CLUSTER).unwrap().unwrap());
                return countingSuccess(plan, stored, desired);
            }).onFailure(cause -> fail(cause.message()));

            harness.assertAtMostOneCreatePerNodeId();
            assertEquals(ApplyPhase.RUNNING, seen.get().phase());
            assertEquals(ClusterBootstrapOrchestrator.computeConfigHash(forgeConfig(5)), seen.get().configHash());
            assertEquals(List.of("ScaleUp|forge|core|3->5"), seen.get().actions().stream().map(ApplyAction::key).toList());
            assertTrue(ApplyState.isApplyId(seen.get().applyId()));
            assertEquals(0L, seen.get().storedVersion());
        }

        @Test
        void executeAndPersist_executorFails_ledgerIsKeptForResumeAndRollback() {
            run(this::createsOneThenFails).onSuccess(_ -> fail("the executor failed"));

            assertTrue(Files.exists(ledgerFile()), "a failed apply keeps its ledger");

            var kept = ApplyState.read(CLUSTER).unwrap().unwrap();

            assertEquals(ApplyPhase.RUNNING, kept.phase());
            harness.assertNoLeak(kept.applyId(), kept);
            harness.assertAtMostOneCreatePerNodeId();
        }

        @Test
        void executeAndPersist_success_removesTheLedger() {
            run((plan, stored, desired) -> {
                createAndRecord("n-1");
                createAndRecord("n-2");
                return countingSuccess(plan, stored, desired);
            }).onFailure(cause -> fail(cause.message()))
              .onSuccess(result -> assertEquals(harness.provider.totalCreates(), result.nodesAdded(), "created == planned"));

            // On success the ledger is removed on purpose (the VMs now belong to the desired configuration), so the
            // leak invariant has no ledger to compare with here; the money check is created == planned, above.
            harness.assertAtMostOneCreatePerNodeId();
            assertTrue(Files.notExists(ledgerFile()));
        }

        @Test
        void executeAndPersist_successButTheLedgerCannotBeRemoved_stillSucceeds() {
            var originalErr = System.err;
            var captured = new java.io.ByteArrayOutputStream();

            System.setErr(new java.io.PrintStream(captured, true));
            try {
                run((plan, stored, desired) -> {
                    replaceLedgerWithNonEmptyDirectory();
                    return countingSuccess(plan, stored, desired);
                }).onFailure(cause -> fail("the VMs exist; the exit status must not claim otherwise: " + cause.message()))
                  .onSuccess(result -> assertEquals(2, result.nodesAdded()));
            } finally {
                System.setErr(originalErr);
            }

            assertTrue(captured.toString().contains("could not be removed"), () -> "the leftover must be reported, stderr was: " + captured);
        }

        private void replaceLedgerWithNonEmptyDirectory() {
            try {
                Files.delete(ledgerFile());
                Files.createDirectories(ledgerFile().resolve("pinned"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static void assertScaleUpPlanFormatted(String output) {
        assertTrue(output.contains("Current: 3 cores"));
        assertTrue(output.contains("Desired: 5 cores"));
        assertTrue(output.contains("~ forge.core  count: 3 -> 5"));
    }

    private static void assertZeroCounts(ApplyResult result) {
        assertEquals(0, result.nodesAdded());
        assertEquals(0, result.nodesRemoved());
        assertEquals(0, result.nodesModified());
        assertTrue(result.executedPlan().isEmpty());
    }

    private static void assertScaleUpCounts(ApplyResult result) {
        assertEquals(2, result.nodesAdded());
        assertEquals(0, result.nodesRemoved());
        assertEquals(0, result.nodesModified());
    }
}
