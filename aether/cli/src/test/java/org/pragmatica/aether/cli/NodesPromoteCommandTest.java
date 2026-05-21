// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/// Regression tests for `aether nodes promote <id> --role <CORE|WORKER>` —
/// Phase 2 P-NEW-E (`aether/docs/internal/production-readiness-followup-2026-05-21.md`,
/// test target TC-NEW-M11).
///
/// Pins the picocli wiring for the promote subcommand and asserts the parser
/// captures the `nodeId` argument and `--role` option correctly. The actual
/// REST send is exercised by the server-side `NodeLifecycleRoutesPromoteTest`.
class NodesPromoteCommandTest {

    @Test
    void nodesCommand_exposesPromoteSubcommand() {
        var cmd = new CommandLine(new AetherCli.NodesCommand());
        var subcommands = cmd.getSubcommands();

        assertNotNull(subcommands.get("promote"));
    }

    @Test
    void promoteCommand_requiresRoleOption() {
        var cmd = new AetherCli.NodesCommand.PromoteCommand();

        try {
            new CommandLine(cmd).parseArgs("node-2");
            org.junit.jupiter.api.Assertions.fail("Missing --role must be rejected by picocli");
        } catch (CommandLine.MissingParameterException expected) {
            assertNotEquals("", expected.getMessage());
        }
    }

    @Test
    void promoteCommand_capturesNodeIdAndRole() throws Exception {
        var cmd = new AetherCli.NodesCommand.PromoteCommand();
        new CommandLine(cmd).parseArgs("node-2", "--role", "WORKER");

        assertEquals("node-2", readField(cmd, "nodeId"));
        assertEquals("WORKER", readField(cmd, "role"));
    }

    @Test
    void promoteCommand_acceptsLowercaseRole() throws Exception {
        var cmd = new AetherCli.NodesCommand.PromoteCommand();
        new CommandLine(cmd).parseArgs("node-3", "--role", "worker");

        assertEquals("worker", readField(cmd, "role"));
    }

    @Test
    void promoteCommand_registeredOnAetherCliRoot() {
        var cmd = new CommandLine(new AetherCli());
        var nodes = cmd.getSubcommands().get("nodes");

        assertNotNull(nodes);
        assertNotNull(nodes.getSubcommands().get("promote"));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Object readField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
