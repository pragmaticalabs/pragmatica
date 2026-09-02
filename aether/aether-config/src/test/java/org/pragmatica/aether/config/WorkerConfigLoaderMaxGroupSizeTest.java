// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// The #673 config-trap pins, executed under the #366 re-scope ruling (2026-08-29). The trap: an
/// explicit `max_group_size = 1` (or 0) was silently reset to the default of 100, so a typo
/// produced a plausible-looking green run instead of a config error — unobservable precisely
/// because the knob gates the unbuilt group-splitting mechanism, so no behavior ever contradicted
/// the wrong value. Absent stays defaulted; explicit-invalid now refuses at parse.
class WorkerConfigLoaderMaxGroupSizeTest {
    /// THE trap pin: the explicitly-set invalid value refuses instead of silently becoming 100.
    @Test
    void loadFromString_explicitMaxGroupSizeBelowTwo_isRejectedAtParse() {
        var result = WorkerConfigLoader.loadFromString("""
            [worker]
            max_group_size = 1
            """);

        result.onSuccess(config -> fail("max_group_size = 1 must refuse at parse, not become "
                                        + config.maxGroupSize()));
        result.onFailure(cause -> assertThat(cause.message()).contains("max_group_size")
                                                             .contains("got 1"));
    }

    /// The default path is untouched: no key, default value.
    @Test
    void loadFromString_absentMaxGroupSize_defaults() {
        var config = WorkerConfigLoader.loadFromString("""
            [worker]
            core_nodes = ["core-1:localhost:6000"]
            zone = "z1"
            """)
                                       .fold(cause -> fail("must parse: " + cause.message()),
                                             parsed -> parsed);

        assertThat(config.maxGroupSize()).isEqualTo(WorkerConfig.DEFAULT_MAX_GROUP_SIZE);
    }

    /// A low-but-valid explicit value SURVIVES — the arming counterpart proving the rejection is a
    /// boundary at 2, not a blanket reset of small values (the pre-fix behavior kept 3 but turned
    /// 1 into 100, which is what made the trap so hard to see).
    @Test
    void loadFromString_explicitMaxGroupSizeOfThree_isKept() {
        var config = WorkerConfigLoader.loadFromString("""
            [worker]
            core_nodes = ["core-1:localhost:6000"]
            max_group_size = 3
            """)
                                       .fold(cause -> fail("must parse: " + cause.message()),
                                             parsed -> parsed);

        assertThat(config.maxGroupSize()).isEqualTo(3);
    }
}
