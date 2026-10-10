// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #909: the CLI is a client and keeps going on its default address when its config file does not load, but the warning must carry the CAUSE
/// (here: the jwt-without-jwks_url refusal), not a bare "failed to load".
class AetherCliConfigWarningTest {
    @Test
    void aConfigThatFailsValidation_warnsWithTheCauseText(@TempDir Path dir) throws Exception {
        var file = Files.writeString(dir.resolve("aether.toml"),
                                     "[cluster]\nenvironment = \"docker\"\nnodes = 3\n\n[app-http]\nenabled = true\nsecurity_mode = \"jwt\"\n");
        var err = new ByteArrayOutputStream();
        var original = System.err;

        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            new AetherCli().readConfigFromPath(file);
        } finally {
            System.setErr(original);
        }

        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Warning: Failed to load config:").contains("jwks_url");
    }
}
