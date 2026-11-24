// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.AutoHealConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.GenesisSeatRemedy.genesisSeatRemedy;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1654: the remedy sentence of the dead-seat warning is true in both auto-heal states. Each arm pins its own text; the two must differ,
/// so a binding that swaps the arms or ignores the state fails here.
class GenesisSeatRemedyTest {
    @Test
    void autoHealOn_namesTheReplacementBound_andNoManualToggle() {
        var text = genesisSeatRemedy(true, AutoHealConfig.DEFAULT);

        assertThat(text).contains("Auto-heal is on", "replacement core is expected", "within 75 s")
                        .doesNotContain("no replacement will be provisioned", "auto-heal/enable");
    }

    @Test
    void autoHealOn_boundFollowsTheConfig() {
        var config = AutoHealConfig.DEFAULT.withStartupCooldown(timeSpan(5).seconds())
                                           .withProvisioningTimeout(timeSpan(20).seconds());

        assertThat(genesisSeatRemedy(true, config)).contains("within 25 s");
    }

    @Test
    void autoHealOff_saysNoReplacementComes_andNamesTheManualRemedy() {
        var text = genesisSeatRemedy(false, AutoHealConfig.DEFAULT);

        assertThat(text).contains("Auto-heal is off", "no replacement will be provisioned", "restart that node", "POST /api/cluster/topology/auto-heal/enable")
                        .doesNotContain("replacement core is expected");
    }
}
