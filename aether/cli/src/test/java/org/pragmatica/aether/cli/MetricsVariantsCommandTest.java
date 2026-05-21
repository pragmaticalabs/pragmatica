// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/// Regression tests for `aether metrics <variant>` — Phase 3 item C1
/// (unblocks integration-test-audit-2026-05-21 §3.3 B).
///
/// Verifies that the picocli wiring registers all 5 metrics variant subcommands
/// (`prometheus`, `transport`, `comprehensive`, `derived`, `history`) under the
/// `metrics` parent command, and that `history` correctly binds + composes its
/// optional `--range` / `--since` query parameters.
class MetricsVariantsCommandTest {

    @Test
    void metricsCommand_hasAllFiveVariantSubcommands() {
        var cmd = new CommandLine(new AetherCli.MetricsCommand());
        var subcommands = cmd.getSubcommands();

        assertNotNull(subcommands.get("prometheus"));
        assertNotNull(subcommands.get("transport"));
        assertNotNull(subcommands.get("comprehensive"));
        assertNotNull(subcommands.get("derived"));
        assertNotNull(subcommands.get("history"));
    }

    @Test
    void prometheusCommand_isInstantiable() {
        var cmd = new AetherCli.MetricsCommand.PrometheusCommand();
        new CommandLine(cmd).parseArgs();
        assertNotNull(cmd);
    }

    @Test
    void transportCommand_isInstantiable() {
        var cmd = new AetherCli.MetricsCommand.TransportCommand();
        new CommandLine(cmd).parseArgs();
        assertNotNull(cmd);
    }

    @Test
    void comprehensiveCommand_isInstantiable() {
        var cmd = new AetherCli.MetricsCommand.ComprehensiveCommand();
        new CommandLine(cmd).parseArgs();
        assertNotNull(cmd);
    }

    @Test
    void derivedCommand_isInstantiable() {
        var cmd = new AetherCli.MetricsCommand.DerivedCommand();
        new CommandLine(cmd).parseArgs();
        assertNotNull(cmd);
    }

    @Test
    void historyCommand_noOptions_buildEmptyQuery() throws Exception {
        var cmd = new AetherCli.MetricsCommand.HistoryCommand();
        new CommandLine(cmd).parseArgs();

        assertNull(readField(cmd, "range"));
        assertNull(readField(cmd, "since"));
        assertEquals("", invokeBuildHistoryQuery(cmd));
    }

    @Test
    void historyCommand_rangeOption_isBound() throws Exception {
        var cmd = new AetherCli.MetricsCommand.HistoryCommand();
        new CommandLine(cmd).parseArgs("--range", "15m");

        assertEquals("15m", readField(cmd, "range"));
        assertEquals("range=15m", invokeBuildHistoryQuery(cmd));
    }

    @Test
    void historyCommand_sinceOption_isBound() throws Exception {
        var cmd = new AetherCli.MetricsCommand.HistoryCommand();
        new CommandLine(cmd).parseArgs("--since", "1h");

        assertEquals("1h", readField(cmd, "since"));
        assertEquals("range=1h", invokeBuildHistoryQuery(cmd));
    }

    @Test
    void historyCommand_rangePreferredOverSince() throws Exception {
        var cmd = new AetherCli.MetricsCommand.HistoryCommand();
        new CommandLine(cmd).parseArgs("--range", "5m", "--since", "2h");

        assertEquals("range=5m", invokeBuildHistoryQuery(cmd));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Object readField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String invokeBuildHistoryQuery(Object target) throws Exception {
        var method = target.getClass().getDeclaredMethod("buildHistoryQuery");
        method.setAccessible(true);
        return (String) method.invoke(target);
    }
}
