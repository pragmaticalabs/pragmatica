// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

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
///   `aether streams create <name> [--partitions N]`
///   `aether streams delete <name|address> [--force]` (catalog-form `STREAMS_DELETE` —
///   bare name defaults client-side to `system:name:1.0.0`, see
///   `AetherCli.StreamCommand#resolveStreamAddress`)
///   `aether streams consumer-group join <group> <stream> --consumer-id <id> [--partitions N]`
///   `aether streams consumer-group leave <group> <stream> --consumer-id <id>`
///   `aether streams consumer-group status <group> [<stream>]`
///
/// Body composition for POST routes is asserted via reflection (no HTTP send).
class StreamsLifecycleCommandTest {

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

    @Test
    void createCommand_nameBound_partitionsNullByDefault() throws Exception {
        var cmd = new AetherCli.StreamCommand.CreateCommand();
        new CommandLine(cmd).parseArgs("orders");

        assertEquals("orders", readField(cmd, "name"));
        assertNull(readField(cmd, "partitions"));
        assertEquals("{\"name\":\"orders\"}", invokeBuildCreateBody(cmd));
    }

    @Test
    void createCommand_partitionsOption_isBoundAndSerialised() throws Exception {
        var cmd = new AetherCli.StreamCommand.CreateCommand();
        new CommandLine(cmd).parseArgs("orders", "--partitions", "8");

        assertEquals("orders", readField(cmd, "name"));
        assertEquals(8, readField(cmd, "partitions"));
        assertEquals("{\"name\":\"orders\",\"partitions\":8}", invokeBuildCreateBody(cmd));
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

    @SuppressWarnings("JBCT-EX-01")
    private static String invokeBuildCreateBody(Object target) throws Exception {
        var method = target.getClass().getDeclaredMethod("buildCreateBody");
        method.setAccessible(true);
        return (String) method.invoke(target);
    }
}
