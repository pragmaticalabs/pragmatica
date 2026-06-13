// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Regression tests for `aether streams read <name> <partition>` — Phase 2 item P6
/// (unblocks RC1-blocker #1 in `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §2.2).
/// Verifies that the picocli wiring binds positional + optional fields correctly so the
/// command can fetch `STREAM_READ` with the expected path + query-string shape.
class StreamsReadCommandTest {

    @Test
    void readCommand_requiredPositionalArgs_areBound() throws Exception {
        var cmd = new AetherCli.StreamCommand.ReadCommand();
        new CommandLine(cmd).parseArgs("orders", "3");

        assertEquals("orders", readField(cmd, "name"));
        assertEquals("3", readField(cmd, "partition"));
        assertNull(readField(cmd, "since"));
        assertNull(readField(cmd, "limit"));
    }

    @Test
    void readCommand_sinceOption_isBound() throws Exception {
        var cmd = new AetherCli.StreamCommand.ReadCommand();
        new CommandLine(cmd).parseArgs("orders", "3", "--since", "42");

        assertEquals(42L, readField(cmd, "since"));
    }

    @Test
    void readCommand_limitOption_isBound() throws Exception {
        var cmd = new AetherCli.StreamCommand.ReadCommand();
        new CommandLine(cmd).parseArgs("orders", "3", "--limit", "25");

        assertEquals(25, readField(cmd, "limit"));
    }

    @Test
    void readCommand_bothOptions_buildCombinedQuery() throws Exception {
        var cmd = new AetherCli.StreamCommand.ReadCommand();
        new CommandLine(cmd).parseArgs("orders", "3", "--since", "100", "--limit", "50");

        assertEquals("from=100&max=50", invokeBuildReadQuery(cmd));
    }

    @Test
    void readCommand_noOptions_buildEmptyQuery() throws Exception {
        var cmd = new AetherCli.StreamCommand.ReadCommand();
        new CommandLine(cmd).parseArgs("orders", "3");

        assertEquals("", invokeBuildReadQuery(cmd));
    }

    @Test
    void readCommand_onlySince_buildQueryWithoutMax() throws Exception {
        var cmd = new AetherCli.StreamCommand.ReadCommand();
        new CommandLine(cmd).parseArgs("orders", "3", "--since", "7");

        assertEquals("from=7", invokeBuildReadQuery(cmd));
    }

    @Test
    void readCommand_onlyLimit_buildQueryWithoutFrom() throws Exception {
        var cmd = new AetherCli.StreamCommand.ReadCommand();
        new CommandLine(cmd).parseArgs("orders", "3", "--limit", "10");

        assertEquals("max=10", invokeBuildReadQuery(cmd));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Object readField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String invokeBuildReadQuery(Object target) throws Exception {
        var method = target.getClass().getDeclaredMethod("buildReadQuery");
        method.setAccessible(true);
        return (String) method.invoke(target);
    }
}
