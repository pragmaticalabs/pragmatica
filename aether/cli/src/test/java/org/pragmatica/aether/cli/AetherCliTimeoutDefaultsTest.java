// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Test;

import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Regression tests for `--wait` + `--timeout` interaction across the three CLI sites
/// that previously rejected `--wait` without explicit `--timeout` with `ExitCode.ERROR`.
/// After the fix, `--timeout` defaults to 300 seconds when omitted.
class AetherCliTimeoutDefaultsTest {

    @Test
    void scaleCommand_waitWithoutTimeout_defaultsToThreeHundredSeconds() throws Exception {
        var cmd = new AetherCli.ScaleCommand();
        new CommandLine(cmd).parseArgs("org.example:slice:1.0.0", "-n", "3", "--wait");

        assertEquals(Boolean.TRUE, readField(cmd, "waitForCompletion"));
        assertEquals(300, readField(cmd, "timeoutSeconds"));
    }

    @Test
    void scaleCommand_explicitTimeout_keepsExplicitValue() throws Exception {
        var cmd = new AetherCli.ScaleCommand();
        new CommandLine(cmd).parseArgs("org.example:slice:1.0.0", "-n", "3", "--wait", "--timeout", "120");

        assertEquals(120, readField(cmd, "timeoutSeconds"));
    }

    @Test
    void blueprintDeployCommand_waitWithoutTimeout_defaultsToThreeHundredSeconds() throws Exception {
        var cmd = new AetherCli.BlueprintCommand.DeployArtifactCommand();
        new CommandLine(cmd).parseArgs("org.example:my-blueprint:1.0.0", "--wait");

        assertEquals(Boolean.TRUE, readField(cmd, "waitForCompletion"));
        assertEquals(300, readField(cmd, "timeoutSeconds"));
    }

    @Test
    void blueprintDeployCommand_explicitTimeout_keepsExplicitValue() throws Exception {
        var cmd = new AetherCli.BlueprintCommand.DeployArtifactCommand();
        new CommandLine(cmd).parseArgs("org.example:my-blueprint:1.0.0", "--wait", "--timeout", "60");

        assertEquals(60, readField(cmd, "timeoutSeconds"));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Object readField(Object target, String fieldName) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
