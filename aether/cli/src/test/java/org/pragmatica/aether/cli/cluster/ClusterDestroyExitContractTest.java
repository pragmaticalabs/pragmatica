// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #587 (3) — the exit code is a retry signal, and it must agree with the registry. #521's contract is
/// "non-zero + registry entry KEPT" for a cloud cleanup failure: something is still billing, re-run.
/// A drain or shutdown failure followed by a COMPLETE cleanup used to exit `ExitCode.ERROR` with the
/// entry already removed, so a script keying on the exit code retried a destroy that had nothing left
/// to do (and could not even find the cluster). The honest outcome is exit 0 with a warning that names
/// what was not drained — the VMs are gone, and no exit code undoes that.
class ClusterDestroyExitContractTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("exit-contract").unwrap();

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;
    private BiFunction<ClusterRegistry, ClusterName, Result<ClusterRegistry>> originalRemover;
    private List<String> removals;

    @BeforeEach
    void capture() {
        originalOut = System.out;
        originalErr = System.err;
        originalRemover = ClusterDestroyCommand.registryRemover;
        removals = new ArrayList<>();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        ClusterDestroyCommand.registryRemover = (registry, name) -> {
            removals.add(name.value());

            return registry.remove(name.value());
        };
    }

    @AfterEach
    void restore() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        ClusterDestroyCommand.registryRemover = originalRemover;
    }

    private static ClusterRegistry registry() {
        return ClusterRegistry.clusterRegistry(Path.of("unused-in-test.toml"),
                                               some(CLUSTER.value()),
                                               List.of(new ClusterRegistry.ClusterEntry(CLUSTER.value(),
                                                                                        "https://cluster.example:8080",
                                                                                        none())));
    }

    private static ClusterDestroyCommand.NodeResult result(String nodeId, boolean success) {
        return success
               ? ClusterDestroyCommand.NodeResult.succeeded(nodeId)
               : ClusterDestroyCommand.NodeResult.failed(nodeId, "refused with HTTP 409");
    }

    private static List<ClusterDestroyCommand.NodeResult> results(boolean first, boolean second, boolean third) {
        return List.of(result("core-1", first), result("core-2", second), result("core-3", third));
    }

    @Test
    void finalizeDestruction_drainFailuresWithCompleteCleanup_exitZero_andWarnWhatWasNotDrained() {
        var code = ClusterDestroyCommand.finalizeDestruction(registry(),
                                                             CLUSTER,
                                                             true,
                                                             List.of("core-1", "core-2", "core-3"),
                                                             results(true, false, false),
                                                             results(true, true, true))
                                        .onFailure(cause -> fail(cause.message()))
                                        .or(-1);

        assertThat(removals).as("complete cleanup removes the entry — that is unchanged")
                  .containsExactly(CLUSTER.value());
        assertThat(code).as("with the entry removed there is nothing a re-run can do, so the exit code "
                           + "must not tell a script to re-run")
                  .isEqualTo(ExitCode.SUCCESS);
        assertThat(err.toString(StandardCharsets.UTF_8)).as("the undrained nodes are named WITH their reason, the drained "
                                                             + "one is not, and the contract is stated")
                  .contains("2 of 3 drain operations failed")
                  .contains("core-2: refused with HTTP 409")
                  .contains("core-3: refused with HTTP 409")
                  .doesNotContain("core-1")
                  .contains("nothing is left to retry");
    }

    @Test
    void finalizeDestruction_shutdownFailuresWithCompleteCleanup_exitZero_andWarn() {
        var code = ClusterDestroyCommand.finalizeDestruction(registry(),
                                                             CLUSTER,
                                                             true,
                                                             List.of("core-1", "core-2", "core-3"),
                                                             results(true, true, true),
                                                             results(false, true, true))
                                        .onFailure(cause -> fail(cause.message()))
                                        .or(-1);

        assertThat(code).isEqualTo(ExitCode.SUCCESS);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("1 of 3 shutdown operations failed")
                  .contains("core-1: refused with HTTP 409")
                  .doesNotContain("core-2")
                  .doesNotContain("core-3");
    }

    /// The control: #521's half of the contract is untouched. Cleanup failure still keeps the entry
    /// and still exits `CLEANUP_FAILED`, whatever the drains did.
    @Test
    void finalizeDestruction_cleanupFailure_stillKeepsEntryAndExitsCleanupFailed() {
        var code = ClusterDestroyCommand.finalizeDestruction(registry(),
                                                             CLUSTER,
                                                             false,
                                                             List.of("core-1", "core-2", "core-3"),
                                                             results(true, false, false),
                                                             results(true, true, true))
                                        .onFailure(cause -> fail(cause.message()))
                                        .or(-1);

        assertThat(removals).isEmpty();
        assertThat(code).isEqualTo(ExitCode.CLEANUP_FAILED);
    }

    @Test
    void finalizeDestruction_allDrainedAndShutDown_printsNoDrainWarning() {
        ClusterDestroyCommand.finalizeDestruction(registry(),
                                                  CLUSTER,
                                                  true,
                                                  List.of("core-1", "core-2", "core-3"),
                                                  results(true, true, true),
                                                  results(true, true, true))
                             .onFailure(cause -> fail(cause.message()));
        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("operations failed");
    }
}
