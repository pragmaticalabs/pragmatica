// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.cluster.NodeLifecycleManager;
import org.pragmatica.aether.environment.AutoHealConfig;


/// The remedy sentence of the `genesis-seat-unreachable` operator warning (#1654), true in EVERY auto-heal and provider state. The consensus
/// engine reads it once, when it raises the warning, from the node's live state (`RabiaNode#useGenesisSeatRemedy`); it is never raised
/// again when the state changes later.
///
/// - No compute provider (the node can provision nothing, whatever the auto-heal state): no replacement can come; the remedies are named.
/// - Provider present, auto-heal on: a replacement is expected, within an estimate the config sets (startup cooldown plus provisioning
///   timeout; the reconcile tick, join and voter swap come on top).
/// - Provider present, auto-heal off: no replacement can come; the manual remedies are named.
public interface GenesisSeatRemedy {
    /// The binding the node hands the consensus engine: each read takes the live auto-heal state and the lifecycle manager's own provider gate
    /// ([NodeLifecycleManager#isCloudManaged]: a local compute integration or a committed cluster source), then picks the arm.
    static Supplier<String> remedyBinding(BooleanSupplier autoHealEnabled,
                                          NodeLifecycleManager lifecycleManager,
                                          AutoHealConfig config) {
        return () -> genesisSeatRemedy(autoHealEnabled.getAsBoolean(), lifecycleManager.isCloudManaged(), config);
    }

    static String genesisSeatRemedy(boolean autoHealEnabled, boolean providerPresent, AutoHealConfig config) {
        return providerPresent
               ? autoHealArm(autoHealEnabled, config)
               : noProvider();
    }

    private static String autoHealArm(boolean autoHealEnabled, AutoHealConfig config) {
        return autoHealEnabled
               ? autoHealOn(config)
               : autoHealOff();
    }

    private static String autoHealOn(AutoHealConfig config) {
        var boundSeconds = TimeUnit.NANOSECONDS.toSeconds(config.startupCooldown().nanos() + config.provisioningTimeout()
                                                                                                   .nanos());

        return "Auto-heal is on: a replacement core is typically expected to take the seat within ~" + boundSeconds
             + " s (startup cooldown plus provisioning timeout; an estimate, not a deadline: the reconcile tick, join and voter swap come on top). "
             + "Operator action: if the seat has not been replaced well after that, check that node (#1654).";
    }

    private static String autoHealOff() {
        return "Auto-heal is off: no replacement will be provisioned. Operator action: restart that node, or enable auto-heal "
             + "(POST /api/cluster/topology/auto-heal/enable) so a replacement is provisioned (#1654).";
    }

    private static String noProvider() {
        return "No compute provider is available (no local compute integration and no committed cluster source), so no replacement can be "
             + "provisioned whatever the auto-heal state. Operator action: restart that node, or configure a compute provider (a cluster source) "
             + "and keep auto-heal enabled so a replacement is provisioned (#1654).";
    }
}
