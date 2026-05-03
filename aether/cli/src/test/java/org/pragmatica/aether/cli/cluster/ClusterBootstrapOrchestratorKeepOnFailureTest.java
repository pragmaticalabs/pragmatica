// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.BootstrapState.PhaseStatus;
import org.pragmatica.aether.cli.cluster.CreatedResource.ProvisionedVm;
import org.pragmatica.aether.cli.cluster.CreatedResource.SshKeyResource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterBootstrapOrchestratorKeepOnFailureTest {

    private static final String CLUSTER_NAME = "kof-test-cluster";

    private Function<BootstrapState, Result<Unit>> originalHook;

    @BeforeEach
    void setUp() {
        originalHook = ClusterBootstrapOrchestrator.cleanupHook();
        // Ensure a clean slate even if a previous test (or external state) left a file behind:
        // BootstrapStatePersistence.AETHER_DIR is captured at class-load time, so we must clean
        // the real persistence path rather than rely on user.home overrides.
        var _ = BootstrapStatePersistence.delete(CLUSTER_NAME);
    }

    @AfterEach
    void tearDown() {
        ClusterBootstrapOrchestrator.setCleanupHook(originalHook);
        var _ = BootstrapStatePersistence.delete(CLUSTER_NAME);
    }

    private static BootstrapState stateWithVms(int vmCount, int sshKeyCount) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {phases.put(phase, PhaseStatus.PENDING);}
        phases.put(BootstrapPhase.PROVISION, PhaseStatus.FAILED);
        var resources = new ArrayList<CreatedResource>();
        for (var i = 0; i < vmCount; i++) {
            resources.add(new ProvisionedVm("hetzner", "vm-" + i, "core-source", "core"));
        }
        for (var i = 0; i < sshKeyCount; i++) {
            resources.add(new SshKeyResource("hetzner", 1000L + i, "key-" + i));
        }
        return BootstrapState.bootstrapState(CLUSTER_NAME,
                                             "hash-1",
                                             "2026-05-03T00:00:00Z",
                                             phases,
                                             List.copyOf(resources),
                                             List.of(),
                                             List.of());
    }

    @Nested
    class CleanupGating {

        @Test
        void handleFailure_keepOnFailureFalse_invokesCleanupHook() {
            var hookCalls = new AtomicInteger(0);
            var captured = new AtomicReference<BootstrapState>();
            ClusterBootstrapOrchestrator.setCleanupHook(state -> {
                hookCalls.incrementAndGet();
                captured.set(state);
                return Result.unitResult();
            });
            BootstrapStatePersistence.save(stateWithVms(2, 1));

            ClusterBootstrapOrchestrator.handleFailure(CLUSTER_NAME, new TestCause("boom"), false);

            assertEquals(1, hookCalls.get());
            assertEquals(2, captured.get().createdResources().stream()
                                          .filter(r -> r instanceof ProvisionedVm).count());
        }

        @Test
        void handleFailure_keepOnFailureTrue_skipsCleanupHook() {
            var hookCalls = new AtomicInteger(0);
            ClusterBootstrapOrchestrator.setCleanupHook(state -> {
                hookCalls.incrementAndGet();
                return Result.unitResult();
            });
            BootstrapStatePersistence.save(stateWithVms(3, 2));

            ClusterBootstrapOrchestrator.handleFailure(CLUSTER_NAME, new TestCause("boom"), true);

            assertEquals(0, hookCalls.get());
        }

        @Test
        void handleFailure_keepOnFailureFalse_noStateFile_skipsHook() {
            var hookCalls = new AtomicInteger(0);
            ClusterBootstrapOrchestrator.setCleanupHook(state -> {
                hookCalls.incrementAndGet();
                return Result.unitResult();
            });

            ClusterBootstrapOrchestrator.handleFailure(CLUSTER_NAME, new TestCause("no state"), false);

            assertEquals(0, hookCalls.get());
        }

        @Test
        void handleFailure_keepOnFailureFalse_emptyResources_skipsHook() {
            var hookCalls = new AtomicInteger(0);
            ClusterBootstrapOrchestrator.setCleanupHook(state -> {
                hookCalls.incrementAndGet();
                return Result.unitResult();
            });
            BootstrapStatePersistence.save(BootstrapState.initialState(CLUSTER_NAME, "hash-1", "2026-05-03T00:00:00Z"));

            ClusterBootstrapOrchestrator.handleFailure(CLUSTER_NAME, new TestCause("empty"), false);

            assertEquals(0, hookCalls.get());
        }
    }

    @Nested
    class WarningOutput {

        private PrintStream originalErr;

        private ByteArrayOutputStream captured;

        @BeforeEach
        void captureStderr() {
            originalErr = System.err;
            captured = new ByteArrayOutputStream();
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        }

        @AfterEach
        void restoreStderr() {
            System.setErr(originalErr);
        }

        @Test
        void handleFailure_keepOnFailureTrue_logsClusterNamePhaseAndCounts() {
            ClusterBootstrapOrchestrator.setCleanupHook(state -> Result.unitResult());
            BootstrapStatePersistence.save(stateWithVms(3, 2));

            ClusterBootstrapOrchestrator.handleFailure(CLUSTER_NAME, new TestCause("quorum-fail"), true);

            var output = captured.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains(CLUSTER_NAME), "warning should name cluster: " + output);
            assertTrue(output.contains("PROVISION"), "warning should name failed phase: " + output);
            assertTrue(output.contains("3 provisioned VM"), "warning should count VMs: " + output);
            assertTrue(output.contains("2 SSH key"), "warning should count SSH keys: " + output);
            assertTrue(output.contains("aether cluster destroy --cluster " + CLUSTER_NAME),
                       "warning should print destroy hint: " + output);
            assertTrue(output.contains("ssh aether@"), "warning should print SSH inspect hint: " + output);
        }
    }

    record TestCause(String message) implements Cause {}
}
