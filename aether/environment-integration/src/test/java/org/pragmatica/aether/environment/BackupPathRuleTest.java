// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1968: the one backup path rule. Every surface (bootstrap parser, node load, Docker provider factory) calls it, so it must answer every
/// input with a refusal or none, never throw.
class BackupPathRuleTest {
    @Test
    void aNulInTheValue_isRefusedAsInvalid_notThrown_forBothMountKinds() {
        for (var namedVolume : new boolean[] {true, false}) {
            for (var bad : new String[] {"/data/back\0ups", "/var/aether/back\0ups", "/\0"}) {
                org.pragmatica.lang.Option<String> refusal;

                try {
                    refusal = BackupPathRule.refusal(bad, namedVolume);
                } catch (RuntimeException e) {
                    throw new AssertionError("the rule THREW " + e.getClass().getSimpleName() + " instead of refusing (namedVolume=" + namedVolume + ")");
                }

                assertThat(refusal.isPresent()).as("path with NUL, namedVolume=" + namedVolume).isTrue();
                assertThat(refusal.unwrap()).contains("not a valid path").doesNotContain("\0");
            }
        }
    }

    @Test
    void relativeAndEscapingPaths_areRefused_withTheRule() {
        assertThat(BackupPathRule.refusal("rel/backups", false).unwrap()).contains("must be an absolute path");
        assertThat(BackupPathRule.refusal("/data/../etc", true).unwrap()).contains("must be under /data");
        assertThat(BackupPathRule.refusal("/database/x", true).unwrap()).contains("must be under /data");
    }

    @Test
    void acceptedPaths_giveNoRefusal() {
        assertThat(BackupPathRule.refusal("/data", true).isEmpty()).isTrue();
        assertThat(BackupPathRule.refusal("/data/a/../b", true).isEmpty()).isTrue();
        assertThat(BackupPathRule.refusal("/var/aether/backups", false).isEmpty()).as("a bind mount accepts any absolute path").isTrue();
    }
}
