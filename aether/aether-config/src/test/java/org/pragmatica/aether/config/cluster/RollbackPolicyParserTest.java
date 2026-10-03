// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// #1573: the cluster-wide `[rollback]` policy. Automatic rollback is ON by default (owner ruling), so the
/// defaults and the off switch are pinned here, and every key is typed at apply.
class RollbackPolicyParserTest {
    private static final String CLUSTER = """
                                          config_version = "1.0.0"
                                          [cluster]
                                          name = "policy"
                                          version = "1.0.0"
                                          [source.default]
                                          type = "forge"
                                          [source.default.core]
                                          count = 3
                                          """;

    @Test
    void fromClusterToml_bootstrapSeed_isTheBuiltInDefault_enabled() {
        var policy = RollbackPolicyParser.fromClusterToml(Option.none()).unwrap();

        assertThat(policy).isEqualTo(RollbackConfig.rollbackConfig());
        assertThat(policy.enabled()).isTrue();
        assertThat(policy.triggerOnAllInstancesFailed()).isTrue();
        assertThat(policy.bakeWindow()).isEqualTo(TimeSpan.timeSpan(15).minutes());
    }

    @Test
    void fromClusterToml_noRollbackSection_isTheBuiltInDefault_enabled() {
        assertThat(RollbackPolicyParser.fromClusterToml(Option.some(CLUSTER)).unwrap()).isEqualTo(RollbackConfig.rollbackConfig());
    }

    @Test
    void fromClusterToml_enabledFalse_turnsItOff() {
        assertThat(RollbackPolicyParser.fromClusterToml(Option.some(CLUSTER + "[rollback]\nenabled = false\n")).unwrap().enabled()).isFalse();
    }

    @Test
    void fromClusterToml_everyKey_isRead() {
        var policy = RollbackPolicyParser.fromClusterToml(Option.some(CLUSTER + """
                                                                    [rollback]
                                                                    enabled = true
                                                                    trigger_on_all_instances_failed = false
                                                                    cooldown = "10m"
                                                                    max_rollbacks = 5
                                                                    bake_window = "30m"
                                                                    """)).unwrap();

        assertThat(policy.triggerOnAllInstancesFailed()).isFalse();
        assertThat(policy.cooldown()).isEqualTo(TimeSpan.timeSpan(10).minutes());
        assertThat(policy.maxRollbacks()).isEqualTo(5);
        assertThat(policy.bakeWindow()).isEqualTo(TimeSpan.timeSpan(30).minutes());
    }

    @Test
    void apply_mistypedEnabled_isRefused() {
        assertRefused("[rollback]\nenabled = \"no\"\n", "enabled");
    }

    @Test
    void apply_negativeMaxRollbacks_isRefused() {
        assertRefused("[rollback]\nmax_rollbacks = -1\n", "max_rollbacks");
    }

    @Test
    void apply_zeroBakeWindow_isRefused() {
        assertRefused("[rollback]\nbake_window = \"0s\"\n", "bake_window");
    }

    @Test
    void apply_unparsableCooldown_isRefused() {
        assertRefused("[rollback]\ncooldown = \"soon\"\n", "cooldown");
    }

    @Test
    void apply_unknownKey_isRefused() {
        assertRefused("[rollback]\nenable = false\n", "enable");
    }

    /// The apply path validates through the cluster parser, so a bad `[rollback]` refuses the whole document.
    private static void assertRefused(String section, String key) {
        var result = ClusterBootstrapConfigParser.parse(CLUSTER + section);

        assertThat(result.isFailure()).as("[rollback] %s must refuse the apply", key).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("[rollback]").contains(key));
    }
}
