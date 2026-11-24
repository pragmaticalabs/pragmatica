// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.environment.AutoHealConfig;


/// The remedy sentence of the `genesis-seat-unreachable` operator warning (#1654), true in BOTH auto-heal states. The consensus engine
/// reads it once, when it raises the warning, from the node's live auto-heal state (`RabiaNode#useGenesisSeatRemedy`); it is never
/// raised again when the state changes later.
///
/// - Auto-heal on: a replacement is expected within the bound the config sets, startup cooldown plus provisioning timeout.
/// - Auto-heal off: no replacement can come; the manual remedies are named.
public interface GenesisSeatRemedy {
    static String genesisSeatRemedy(boolean autoHealEnabled, AutoHealConfig config) {
        return autoHealEnabled
               ? autoHealOn(config)
               : autoHealOff();
    }

    private static String autoHealOn(AutoHealConfig config) {
        var boundSeconds = TimeUnit.NANOSECONDS.toSeconds(config.startupCooldown().nanos() + config.provisioningTimeout()
                                                                                                   .nanos());

        return "Auto-heal is on: a replacement core is expected to take the seat within " + boundSeconds
             + " s (startup cooldown plus provisioning timeout). Operator action: if the seat has not been replaced by then, check that node (#1654).";
    }

    private static String autoHealOff() {
        return "Auto-heal is off: no replacement will be provisioned. Operator action: restart that node, or enable auto-heal "
             + "(POST /api/cluster/topology/auto-heal/enable) so a replacement is provisioned (#1654).";
    }
}
