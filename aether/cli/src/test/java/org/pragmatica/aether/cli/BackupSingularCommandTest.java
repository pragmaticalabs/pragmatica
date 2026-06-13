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
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression tests for the singular `aether backup` CLI surface — Phase 2 P-NEW-C
/// (`aether/docs/internal/production-readiness-followup-2026-05-21.md`,
/// test target `TC-NEW-G4-kv-store-backup`).
///
/// Pins the picocli wiring of:
///   `aether backup create [--wait] [--timeout N]`
///   `aether backup restore <commit> [--wait]`
///   `aether backup list`
///
/// Body composition for POST routes is asserted via reflection (no HTTP send).
/// The existing plural form (`aether backups trigger|list|restore`) is verified
/// to coexist — the singular surface is an additional alias, not a replacement.
class BackupSingularCommandTest {

    @Test
    void backupCommand_exposesCreateRestoreListSubcommands() {
        var cmd = new CommandLine(new AetherCli.BackupSingularCommand());
        var subcommands = cmd.getSubcommands();

        assertNotNull(subcommands.get("create"));
        assertNotNull(subcommands.get("restore"));
        assertNotNull(subcommands.get("list"));
    }

    @Test
    void pluralBackupsCommand_stillExposesTriggerListRestore() {
        var cmd = new CommandLine(new AetherCli.BackupCommand());
        var subcommands = cmd.getSubcommands();

        assertNotNull(subcommands.get("trigger"));
        assertNotNull(subcommands.get("list"));
        assertNotNull(subcommands.get("restore"));
    }

    @Test
    void createCommand_waitDefaultsFalse_timeoutDefaultsSixty() throws Exception {
        var cmd = new AetherCli.BackupSingularCommand.CreateCommand();
        new CommandLine(cmd).parseArgs();

        assertFalse((boolean) readField(cmd, "waitForCompletion"));
        assertEquals(60, readField(cmd, "timeoutSeconds"));
    }

    @Test
    void createCommand_waitFlagAndCustomTimeoutBound() throws Exception {
        var cmd = new AetherCli.BackupSingularCommand.CreateCommand();
        new CommandLine(cmd).parseArgs("--wait", "--timeout", "120");

        assertTrue((boolean) readField(cmd, "waitForCompletion"));
        assertEquals(120, readField(cmd, "timeoutSeconds"));
    }

    @Test
    void restoreCommand_commitIdRequired_buildsExpectedBody() throws Exception {
        var cmd = new AetherCli.BackupSingularCommand.RestoreCommand();
        new CommandLine(cmd).parseArgs("abc123");

        assertEquals("abc123", readField(cmd, "commitId"));
        assertEquals("{\"commit\":\"abc123\"}", invokeBuildRestoreBody(cmd));
    }

    @Test
    void restoreCommand_escapesQuotesAndBackslashesInCommitId() throws Exception {
        var cmd = new AetherCli.BackupSingularCommand.RestoreCommand();
        new CommandLine(cmd).parseArgs("a\"b\\c");

        assertEquals("{\"commit\":\"a\\\"b\\\\c\"}", invokeBuildRestoreBody(cmd));
    }

    @Test
    void restoreCommand_waitFlagAcceptedButDocumentedNoop() throws Exception {
        var cmd = new AetherCli.BackupSingularCommand.RestoreCommand();
        new CommandLine(cmd).parseArgs("abc123", "--wait");

        assertTrue((boolean) readField(cmd, "waitForCompletion"));
    }

    @Test
    void countBackupEntries_emptyOrErrorResponse_returnsZero() throws Exception {
        assertEquals(0, invokeCountBackupEntries(""));
        assertEquals(0, invokeCountBackupEntries(null));
        assertEquals(0, invokeCountBackupEntries("{\"error\":\"unauthorized\"}"));
    }

    @Test
    void countBackupEntries_countsCommitIdOccurrences() throws Exception {
        var body = "[{\"commitId\":\"a\"},{\"commitId\":\"b\"},{\"commitId\":\"c\"}]";

        assertEquals(3, invokeCountBackupEntries(body));
    }

    @Test
    void countBackupEntries_singleEntry_returnsOne() throws Exception {
        var body = "[{\"commitId\":\"abc123\",\"timestamp\":1700000000000}]";

        assertEquals(1, invokeCountBackupEntries(body));
    }

    @Test
    void backupCommand_registeredOnAetherCliRoot() {
        var cmd = new CommandLine(new AetherCli());
        var subcommands = cmd.getSubcommands();

        // singular alias for TC-NEW-G4
        assertNotNull(subcommands.get("backup"));
        // plural form retained for compatibility with operators/scripts
        assertNotNull(subcommands.get("backups"));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Object readField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String invokeBuildRestoreBody(Object target) throws Exception {
        var method = target.getClass().getDeclaredMethod("buildRestoreBody");
        method.setAccessible(true);
        return (String) method.invoke(target);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static int invokeCountBackupEntries(String response) throws Exception {
        var method = AetherCli.BackupSingularCommand.CreateCommand.class.getDeclaredMethod("countBackupEntries",
                                                                                           String.class);
        method.setAccessible(true);
        return (int) method.invoke(null, response);
    }
}
