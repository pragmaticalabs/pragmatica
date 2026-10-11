// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.backup.BackupPreflight.GitProbeTimedOut;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// #2007: a node with `[backup]` enabled that cannot run git refuses to boot, naming `[backup]` and git; one that can, boots.
/// The probe runs a real process, so "git missing" is a command that does not exist and "git failing" is one that exits non-zero.
class BackupPreflightTest {
    @Test
    void gitPresent_isAccepted() {
        assertThat(BackupPreflight.requireGit().isSuccess()).as("git --version runs on the machine that runs this test").isTrue();
    }

    @Test
    void gitMissing_refusesTheBoot_namingBackupAndGit() {
        var result = BackupPreflight.requireGit(List.of("definitely-not-a-git-binary-2007", "--version"));

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("[backup]").contains("git").contains("install git"));
    }

    @Test
    void gitThatExitsNonZero_refusesTheBoot() {
        var result = BackupPreflight.requireGit(List.of("sh", "-c", "echo broken >&2; exit 3"));

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("[backup]").contains("exit 3").contains("broken"));
    }

    /// A git that hangs is refused with a typed cause naming the timeout, never accepted (v-2001 mutation X4 was 0 red).
    @Test
    void gitThatTimesOut_refusesTheBoot_withATypedTimeoutCause() {
        var result = BackupPreflight.requireGit(List.of("sleep", "30"), TimeSpan.timeSpan(300).millis());

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(GitProbeTimedOut.class);
            assertThat(cause.message()).contains("[backup]").contains("timed out");
        });
    }
}
