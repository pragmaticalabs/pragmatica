// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.cli.cluster.ClusterCommand;
import org.pragmatica.aether.cli.ttm.TtmCommand;

import java.util.Arrays;
import java.util.stream.Collectors;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression tests for the Phase 3 CLI wrappers around existing REST routes:
/// - C2: `aether slices status` (→ `GET /api/slices/status`)
/// - C2: `aether slices topology` (→ `GET /api/slices/topology`)
/// - C3: `aether cluster governors` (→ `GET /api/cluster/governors`)
/// - C4: `aether ttm status` (→ `GET /api/ttm/status`)
/// - C4: `aether ttm training-data` (→ `GET /api/ttm/training-data`)
///
/// Each test asserts that picocli resolves the subcommand path and binds it
/// to the expected Callable/Runnable class — closing the §3.3 B, C, D, E gaps
/// in `aether/docs/internal/audits/integration-test-audit-2026-05-21.md`.
class CliRouteWrapperTest {

    @Test
    void slicesStatus_subcommand_resolvesToStatusCommand() {
        var sub = subcommandClass(new CommandLine(new AetherCli()), "slices", "status");

        assertTrue(AetherCli.SlicesCommand.StatusCommand.class.isAssignableFrom(sub),
                   "Expected slices status to resolve to SlicesCommand.StatusCommand, got " + sub);
    }

    @Test
    void slicesTopology_subcommand_resolvesToTopologyCommand() {
        var sub = subcommandClass(new CommandLine(new AetherCli()), "slices", "topology");

        assertTrue(AetherCli.SlicesCommand.TopologyCommand.class.isAssignableFrom(sub),
                   "Expected slices topology to resolve to SlicesCommand.TopologyCommand, got " + sub);
    }

    @Test
    void slicesListing_withoutSubcommand_stillBindsTopLevel() {
        var cli = new AetherCli();
        var cmd = new CommandLine(cli);
        var spec = cmd.getSubcommands().get("slices");

        assertNotNull(spec, "`slices` parent command must remain registered");
        assertTrue(AetherCli.SlicesCommand.class.isAssignableFrom(spec.getCommand().getClass()),
                   "Expected `slices` parent to remain a SlicesCommand (backward compat for `aether slices [--state]`)");
    }

    @Test
    void clusterGovernors_subcommand_resolvesToGovernorsCommand() {
        var cli = new AetherCli();
        var cmd = new CommandLine(cli);
        var clusterSpec = cmd.getSubcommands().get("cluster");

        assertNotNull(clusterSpec, "`cluster` parent command must be registered");
        assertTrue(ClusterCommand.class.isAssignableFrom(clusterSpec.getCommand().getClass()));

        var governorsSpec = clusterSpec.getSubcommands().get("governors");

        assertNotNull(governorsSpec,
                      "`cluster governors` subcommand must be registered. Known subcommands: "
                      + clusterSpec.getSubcommands().keySet());
    }

    @Test
    void ttm_parentCommand_isRegistered() {
        var cmd = new CommandLine(new AetherCli());
        var ttmSpec = cmd.getSubcommands().get("ttm");

        assertNotNull(ttmSpec, "`ttm` parent command must be registered on AetherCli");
        assertTrue(TtmCommand.class.isAssignableFrom(ttmSpec.getCommand().getClass()),
                   "Expected `ttm` to resolve to TtmCommand, got " + ttmSpec.getCommand().getClass());
    }

    @Test
    void ttmStatus_subcommand_isRegistered() {
        var sub = subcommandClass(new CommandLine(new AetherCli()), "ttm", "status");

        assertTrue(sub.getSimpleName().equals("TtmStatusCommand"),
                   "Expected ttm status to resolve to TtmStatusCommand, got " + sub.getSimpleName());
    }

    @Test
    void ttmTrainingData_subcommand_isRegistered() {
        var sub = subcommandClass(new CommandLine(new AetherCli()), "ttm", "training-data");

        assertTrue(sub.getSimpleName().equals("TtmTrainingDataCommand"),
                   "Expected ttm training-data to resolve to TtmTrainingDataCommand, got " + sub.getSimpleName());
    }

    private static Class<?> subcommandClass(CommandLine root, String... path) {
        var current = root;

        for (var name : path) {
            var next = current.getSubcommands().get(name);

            assertNotNull(next, "Subcommand path " + joinPath(path) + " broke at '" + name
                                + "'. Available: " + current.getSubcommands().keySet());
            current = next;
        }

        return current.getCommand().getClass();
    }

    private static String joinPath(String... path) {
        return Arrays.stream(path).collect(Collectors.joining(" "));
    }
}
