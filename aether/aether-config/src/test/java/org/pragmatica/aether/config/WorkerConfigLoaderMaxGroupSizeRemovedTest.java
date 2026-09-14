// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #673 (CTO ruling: DELETE). `[worker] max_group_size` gated the worker group-splitting chain
/// (`GroupMembershipTracker` → `GroupAssignment.computeGroups`), which was never wired: communities
/// are minted one per source. The chain is removed, and a key that was accepted while changing
/// nothing is REFUSED at parse (PF-style, like #675's PF-26): an inert key that stays accepted is
/// the very defect class this ticket names. Replaces `WorkerConfigLoaderMaxGroupSizeTest`, whose
/// "a valid value is kept" pin described a knob with no effect.
class WorkerConfigLoaderMaxGroupSizeRemovedTest {
    @Test
    void loadFromString_presentMaxGroupSize_isRefused_namingTheRemoval() {
        for (var value : new int[]{1, 3, 100}) {
            var result = WorkerConfigLoader.loadFromString("""
                [worker]
                core_nodes = ["core-1:localhost:6000"]
                max_group_size = %d
                """.formatted(value));

            result.onSuccess(_ -> fail("max_group_size = " + value + " must refuse at parse: the key was removed in #673"));
            result.onFailure(cause -> assertThat(cause.message()).contains("max_group_size")
                                                                 .contains("#673")
                                                                 .contains("one per source"));
        }
    }

    /// Control: a worker config without the key parses exactly as before.
    @Test
    void loadFromString_absentMaxGroupSize_parses() {
        var config = WorkerConfigLoader.loadFromString("""
            [worker]
            core_nodes = ["core-1:localhost:6000"]
            zone = "z1"
            """)
                                       .fold(cause -> fail("must parse: " + cause.message()),
                                             parsed -> parsed);

        assertThat(config.zone()).isEqualTo("z1");
    }
}
