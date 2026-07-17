// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.cluster.BootstrapState.PhaseStatus;
import org.pragmatica.aether.cli.cluster.CreatedResource.ProvisionedVm;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


class ClusterDestroyCommandTest {

    private static final String CLUSTER_NAME = "test-cluster";

    private Function<String, Option<BootstrapState>> originalLoader;

    private Function<BootstrapState, Result<Unit>> originalCleaner;

    private BiFunction<BootstrapState, String, Result<Unit>> originalSweeper;

    @BeforeEach
    void saveStaticSeams() {
        originalLoader = ClusterDestroyCommand.stateLoader;
        originalCleaner = ClusterDestroyCommand.resourceCleaner;
        originalSweeper = ClusterDestroyCommand.sshKeySweeper;
        ClusterDestroyCommand.sshKeySweeper = (state, name) -> Result.unitResult();
    }

    @AfterEach
    void restoreStaticSeams() {
        ClusterDestroyCommand.stateLoader = originalLoader;
        ClusterDestroyCommand.resourceCleaner = originalCleaner;
        ClusterDestroyCommand.sshKeySweeper = originalSweeper;
    }

    private static BootstrapState stateWithVms(int vmCount) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {
            phases.put(phase, PhaseStatus.COMPLETED);
        }
        var resources = new ArrayList<CreatedResource>();
        for (var i = 0; i <vmCount; i++) {
            resources.add(new ProvisionedVm("hetzner", "vm-" + i, "core-source", "core"));
        }
        return BootstrapState.bootstrapState(CLUSTER_NAME,
                                             "hash-1",
                                             "2026-05-01T00:00:00Z",
                                             phases,
                                             List.copyOf(resources),
                                             List.of(),
                                             List.of());
    }

    private static BootstrapState emptyState() {
        return BootstrapState.initialState(CLUSTER_NAME, "hash-1", "2026-05-01T00:00:00Z");
    }

    @Nested
    class CleanupInvocation {

        @Test
        void destroy_invokesBootstrapCleanup_whenStateFileExists() {
            var captured = new AtomicReference<BootstrapState>();
            ClusterDestroyCommand.stateLoader = name -> some(stateWithVms(2));
            ClusterDestroyCommand.resourceCleaner = state -> {
                captured.set(state);
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(2, captured.get().createdResources().size());
        }

        @Test
        void destroy_skipsCleanup_whenStateFileMissing() {
            var calls = new AtomicInteger(0);
            ClusterDestroyCommand.stateLoader = name -> none();
            ClusterDestroyCommand.resourceCleaner = state -> {
                calls.incrementAndGet();
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(0, calls.get());
        }

        @Test
        void destroy_skipsCleanup_whenBootstrapStateHasNoResources() {
            var calls = new AtomicInteger(0);
            ClusterDestroyCommand.stateLoader = name -> some(emptyState());
            ClusterDestroyCommand.resourceCleaner = state -> {
                calls.incrementAndGet();
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(0, calls.get());
        }

        @Test
        void destroy_keepResourcesFlag_skipsCleanup() {
            var loaderCalls = new AtomicInteger(0);
            var cleanerCalls = new AtomicInteger(0);
            ClusterDestroyCommand.stateLoader = name -> {
                loaderCalls.incrementAndGet();
                return some(stateWithVms(3));
            };
            ClusterDestroyCommand.resourceCleaner = state -> {
                cleanerCalls.incrementAndGet();
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();
            command.setKeepResources(true);

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(0, loaderCalls.get());
            assertEquals(0, cleanerCalls.get());
        }

        @Test
        void destroy_partialCleanupFailure_returnsFalseButDoesNotThrow() {
            ClusterDestroyCommand.stateLoader = name -> some(stateWithVms(1));
            ClusterDestroyCommand.resourceCleaner = state -> new TestCause("api error").result();
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertFalse(ok);
        }

        @Test
        void destroy_passesCorrectClusterNameToLoader() {
            var capturedName = new AtomicReference<String>();
            ClusterDestroyCommand.stateLoader = name -> {
                capturedName.set(name);
                return none();
            };
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(CLUSTER_NAME, capturedName.get());
        }

        @Test
        void destroy_invokesSshKeySweeper_afterStateCleanup() {
            var sweepClusterName = new AtomicReference<String>();
            ClusterDestroyCommand.stateLoader = name -> some(stateWithVms(2));
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();
            ClusterDestroyCommand.sshKeySweeper = (state, clusterName) -> {
                sweepClusterName.set(clusterName);
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(CLUSTER_NAME, sweepClusterName.get(),
                         "sweeper must be invoked with the cluster name from the loaded bootstrap state");
        }

        @Test
        void destroy_sweeperFailure_returnsFalseButDoesNotThrow() {
            ClusterDestroyCommand.stateLoader = name -> some(stateWithVms(1));
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();
            ClusterDestroyCommand.sshKeySweeper = (state, clusterName) -> new TestCause("sweep api error").result();
            var command = new ClusterDestroyCommand();

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertFalse(ok, "a failed ssh-key sweep must surface as cleanup failure");
        }

        @Test
        void destroy_keepResourcesFlag_skipsSweep() {
            var sweepCalls = new AtomicInteger(0);
            ClusterDestroyCommand.stateLoader = name -> some(stateWithVms(3));
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();
            ClusterDestroyCommand.sshKeySweeper = (state, clusterName) -> {
                sweepCalls.incrementAndGet();
                return Result.unitResult();
            };
            var command = new ClusterDestroyCommand();
            command.setKeepResources(true);

            var ok = command.cleanupCloudResources(CLUSTER_NAME);

            assertTrue(ok);
            assertEquals(0, sweepCalls.get(), "--keep-resources must short-circuit before the sweeper runs");
        }
    }

    @Nested
    class ClusterOverrideValidation {

        private PrintStream originalErr;

        private ByteArrayOutputStream errCapture;

        @BeforeEach
        void redirectErr() {
            originalErr = System.err;
            errCapture = new ByteArrayOutputStream();
            System.setErr(new PrintStream(errCapture));
        }

        @AfterEach
        void restoreErr() {
            System.setErr(originalErr);
        }

        @Test
        void destroy_invalidClusterOverrideWithSpecialChars_returnsUsageExitCode() {
            var command = new ClusterDestroyCommand();
            command.setClusterNameOverride("INVALID@NAME");

            var exitCode = command.call();

            assertEquals(ExitCode.USAGE, exitCode);
            assertTrue(errCapture.toString().contains("Invalid --cluster value"),
                       "Expected validation error in stderr, got: " + errCapture);
        }

        @Test
        void destroy_invalidClusterOverrideStartingWithDigit_returnsUsageExitCode() {
            var command = new ClusterDestroyCommand();
            command.setClusterNameOverride("9bad-name");

            var exitCode = command.call();

            assertEquals(ExitCode.USAGE, exitCode);
        }

        @Test
        void destroy_invalidClusterOverrideUppercase_returnsUsageExitCode() {
            var command = new ClusterDestroyCommand();
            command.setClusterNameOverride("UpperCase");

            var exitCode = command.call();

            assertEquals(ExitCode.USAGE, exitCode);
        }

        @Test
        void destroy_clusterOverrideParsedFromCli() {
            var command = new ClusterDestroyCommand();
            new CommandLine(command).parseArgs("--cluster", "my-cluster", "--yes", "--keep-resources");

            assertTrue(command.cleanupCloudResources("my-cluster"),
                       "Override + --keep-resources should compose: cleanup short-circuits to ok");
        }

        @Test
        void destroy_clusterOverrideAndKeepResourcesCompose() {
            var loaderCalls = new AtomicInteger(0);
            ClusterDestroyCommand.stateLoader = name -> {
                loaderCalls.incrementAndGet();
                return some(stateWithVms(2));
            };
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();

            var command = new ClusterDestroyCommand();
            new CommandLine(command).parseArgs("--cluster", "other-cluster", "--keep-resources", "--yes");

            var ok = command.cleanupCloudResources("other-cluster");

            assertTrue(ok);
            assertEquals(0, loaderCalls.get(), "--keep-resources must short-circuit before loader is consulted");
        }

        @Test
        void destroy_clusterOverrideRoutesNameToStateLoader() {
            var capturedName = new AtomicReference<String>();
            ClusterDestroyCommand.stateLoader = name -> {
                capturedName.set(name);
                return none();
            };
            ClusterDestroyCommand.resourceCleaner = state -> Result.unitResult();

            var command = new ClusterDestroyCommand();
            new CommandLine(command).parseArgs("--cluster", "named-cluster", "--yes");

            command.cleanupCloudResources("named-cluster");

            assertEquals("named-cluster", capturedName.get(),
                         "Override name must be routed to BootstrapStatePersistence loader");
        }
    }

    record TestCause(String message) implements Cause {}
}
