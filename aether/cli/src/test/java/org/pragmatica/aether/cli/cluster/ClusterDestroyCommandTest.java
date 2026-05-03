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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


class ClusterDestroyCommandTest {

    private static final String CLUSTER_NAME = "test-cluster";

    private Function<String, Option<BootstrapState>> originalLoader;

    private Function<BootstrapState, Result<Unit>> originalCleaner;

    @BeforeEach
    void saveStaticSeams() {
        originalLoader = ClusterDestroyCommand.stateLoader;
        originalCleaner = ClusterDestroyCommand.resourceCleaner;
    }

    @AfterEach
    void restoreStaticSeams() {
        ClusterDestroyCommand.stateLoader = originalLoader;
        ClusterDestroyCommand.resourceCleaner = originalCleaner;
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
    }

    record TestCause(String message) implements Cause {}
}
