// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression tests for the streams-lifecycle CLI surface — Phase 2 P7
/// (audit `aether/docs/internal/audits/integration-test-audit-2026-05-21.md` §3.3 A).
///
/// Pins the picocli wiring of:
///   `aether streams create <name>` — #1224: REFUSED unconditionally; replaced by
///   `aether stream create <namespace:stream:version> [--partitions N]` (singular, catalog-addressed)
///   `aether streams delete <name|address> [--force]` (catalog-form `STREAMS_DELETE` —
///   bare name defaults client-side to `system:name:1.0.0`, see
///   `AetherCli.StreamCommand#resolveStreamAddress`)
///   `aether streams consumer-group join <group> <stream> --consumer-id <id> [--partitions N]`
///   `aether streams consumer-group leave <group> <stream> --consumer-id <id>`
///   `aether streams consumer-group status <group> [<stream>]`
///
/// Body composition for POST routes is asserted via reflection (no HTTP send).
class StreamsLifecycleCommandTest {
    private PrintStream originalErr;
    private ByteArrayOutputStream errCapture;

    @BeforeEach
    void redirectErr() {
        originalErr = System.err;
        errCapture = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errCapture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreErr() {
        System.setErr(originalErr);
    }

    @Test
    void streamCommand_exposesLifecycleSubcommands() {
        var cmd = new CommandLine(new AetherCli.StreamCommand());
        var subcommands = cmd.getSubcommands();

        assertNotNull(subcommands.get("list"));
        assertNotNull(subcommands.get("status"));
        assertNotNull(subcommands.get("publish"));
        assertNotNull(subcommands.get("read"));
        assertNotNull(subcommands.get("create"));
        assertNotNull(subcommands.get("delete"));
        assertNotNull(subcommands.get("consumer-group"));
    }

    @Test
    void consumerGroupCommand_hasJoinLeaveStatusSubcommands() {
        var cmd = new CommandLine(new AetherCli.StreamCommand.ConsumerGroupCommand());
        var subcommands = cmd.getSubcommands();

        assertNotNull(subcommands.get("join"));
        assertNotNull(subcommands.get("leave"));
        assertNotNull(subcommands.get("status"));
    }

    /// #1224: the legacy body-carried create is refused unconditionally — no successful path
    /// remains. The refusal names both remedies (#1044's message-shape standard): the exact
    /// retype form the operator should use, and the command that lists catalog addresses.
    @Test
    void createCommand_call_refusesAndNamesRetypeForm() throws Exception {
        var cmd = new AetherCli.StreamCommand.CreateCommand();
        new CommandLine(cmd).parseArgs("orders");

        var exit = cmd.call();

        assertEquals(ExitCode.ERROR, exit);
        var message = errCapture.toString(StandardCharsets.UTF_8);
        assertThat(message).contains("aether stream create")
                           .contains("orders")
                           .contains("aether streams list");
    }

    @Test
    void createCommand_partitionsOption_stillParses_soScriptsGetTheRefusalNotAParseError() throws Exception {
        var cmd = new AetherCli.StreamCommand.CreateCommand();
        new CommandLine(cmd).parseArgs("orders", "--partitions", "8");

        var exit = cmd.call();

        assertEquals(ExitCode.ERROR, exit);
    }

    @Test
    void deleteCommand_addressBound_forceDefaultsFalse() throws Exception {
        var cmd = new AetherCli.StreamCommand.DeleteCommand();
        new CommandLine(cmd).parseArgs("orders");

        assertEquals("orders", readField(cmd, "address"));
        assertFalse((boolean) readField(cmd, "force"));
    }

    @Test
    void deleteCommand_forceFlag_isBound() throws Exception {
        var cmd = new AetherCli.StreamCommand.DeleteCommand();
        new CommandLine(cmd).parseArgs("orders", "--force");

        assertTrue((boolean) readField(cmd, "force"));
    }

    @Test
    void joinCommand_requiresConsumerId() {
        var cmd = new AetherCli.StreamCommand.ConsumerGroupCommand.JoinCommand();
        var parser = new CommandLine(cmd);

        var ex = assertThrows(CommandLine.MissingParameterException.class,
                              () -> parser.parseArgs("g1", "orders"));
        assertTrue(ex.getMessage().contains("--consumer-id"));
    }

    @Test
    void joinCommand_allArgsBound() throws Exception {
        var cmd = new AetherCli.StreamCommand.ConsumerGroupCommand.JoinCommand();
        new CommandLine(cmd).parseArgs("g1", "orders", "--consumer-id", "c-1", "--partitions", "4");

        assertEquals("g1", readField(cmd, "groupId"));
        assertEquals("orders", readField(cmd, "streamName"));
        assertEquals("c-1", readField(cmd, "consumerId"));
        assertEquals(4, readField(cmd, "partitionCount"));
    }

    @Test
    void leaveCommand_argsBound() throws Exception {
        var cmd = new AetherCli.StreamCommand.ConsumerGroupCommand.LeaveCommand();
        new CommandLine(cmd).parseArgs("g1", "orders", "--consumer-id", "c-1");

        assertEquals("g1", readField(cmd, "groupId"));
        assertEquals("orders", readField(cmd, "streamName"));
        assertEquals("c-1", readField(cmd, "consumerId"));
    }

    @Test
    void statusCommand_groupIdRequired_streamNameOptional() throws Exception {
        var cmd = new AetherCli.StreamCommand.ConsumerGroupCommand.StatusCommand();
        new CommandLine(cmd).parseArgs("g1");

        assertEquals("g1", readField(cmd, "groupId"));
        assertNull(readField(cmd, "streamName"));
    }

    @Test
    void statusCommand_streamNamePositional_isBoundWhenSupplied() throws Exception {
        var cmd = new AetherCli.StreamCommand.ConsumerGroupCommand.StatusCommand();
        new CommandLine(cmd).parseArgs("g1", "orders");

        assertEquals("g1", readField(cmd, "groupId"));
        assertEquals("orders", readField(cmd, "streamName"));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Object readField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
