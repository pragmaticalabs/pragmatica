// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// The auto-heal settings the runtime actually reads — and only those (#675). `startupCooldown`
/// delays `ClusterTopologyManagerRecord.activateWithFormation`'s formation check,
/// `provisioningTimeout` bounds a replacement's provisioning and the reap backstop, `swimHintsTtl`
/// ages the SWIM hint registry (`MembershipFsm`), `maxNodes` caps provisioning (#298). The five
/// fields this record used to carry alongside them (retry interval, stale-observation TTL, QUIC
/// miss threshold, provision stability window, decommissioned retention) had no reader anywhere;
/// they were removed rather than left as knobs that changed nothing.
public record AutoHealConfig(TimeSpan startupCooldown,
                             TimeSpan provisioningTimeout,
                             TimeSpan swimHintsTtl,
                             Option<Integer> maxNodes) {
    public static final TimeSpan DEFAULT_STARTUP_COOLDOWN = timeSpan(15).seconds();
    public static final TimeSpan DEFAULT_PROVISIONING_TIMEOUT = timeSpan(60).seconds();
    // Aligned with the SWIM/NTT detection window: SwimConfig.suspectTimeout (10s) and
    // MembershipConfig.nttDepartureTimeout (15s). This is the value that actually reaches the
    // running SwimHintsRegistry (AetherNode wires config.autoHeal().swimHintsTtl()); lowered
    // from 60s so the hint backstop is short-lived.
    public static final TimeSpan DEFAULT_SWIM_HINTS_TTL = timeSpan(15).seconds();
    /// #298 — no fleet cap by default. A default numeric cap would silently refuse provisioning on
    /// any existing cluster larger than the number we picked, so absence means "unbounded" and the
    /// cap is opt-in. Set it via [#withMaxNodes].
    ///
    /// MUST be declared BEFORE [#DEFAULT]. Static initialisers run in textual order, and `DEFAULT`'s
    /// initialiser reaches this constant through `autoHealConfig(...)`. While it sat below `DEFAULT`
    /// it was still `null` at that moment, so `DEFAULT.maxNodes()` was a NULL `Option` — and every
    /// auto-heal replacement then died on `maxNodes.fold(...)` in
    /// `NodeLifecycleManagerRecord.capGuardedProvision`. The NPE was swallowed by the scheduler's
    /// `runGuarded` ("task recurrence preserved"), so the circuit breaker never saw a failure, the
    /// provisioning API kept reporting a permitted provision, and a killed node was simply never
    /// replaced. Nothing about that was visible at the call site. Do not move this back down.
    public static final Option<Integer> NO_CAP = Option.empty();

    public static final AutoHealConfig DEFAULT = autoHealConfig(DEFAULT_STARTUP_COOLDOWN,
                                                                DEFAULT_PROVISIONING_TIMEOUT,
                                                                DEFAULT_SWIM_HINTS_TTL).unwrap();

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan startupCooldown, TimeSpan provisioningTimeout) {
        return autoHealConfig(startupCooldown, provisioningTimeout, DEFAULT_SWIM_HINTS_TTL);
    }

    public static Result<AutoHealConfig> autoHealConfig(TimeSpan startupCooldown,
                                                        TimeSpan provisioningTimeout,
                                                        TimeSpan swimHintsTtl) {
        return success(new AutoHealConfig(startupCooldown, provisioningTimeout, swimHintsTtl, NO_CAP));
    }

    /// #675 — `[timeouts.scaling] auto_heal_startup_cooldown` reaches the runtime through here.
    public AutoHealConfig withStartupCooldown(TimeSpan startupCooldown) {
        return new AutoHealConfig(startupCooldown, provisioningTimeout, swimHintsTtl, maxNodes);
    }

    /// #298 — operator-set ceiling on the number of nodes this cluster may have provisioned.
    /// Enforced at the single provisioning chokepoint (`NodeLifecycleManager.provisionNode`), which
    /// every path funnels through: the auto-heal reconciler, bootstrap, and CLI wave reprovision.
    ///
    /// This is a cost/blast-radius guardrail, not a scheduler input — nothing consults it when
    /// deciding a target size, so a cap below the cluster's desired size shows up as refused
    /// provisions rather than a resized cluster.
    public AutoHealConfig withMaxNodes(int maxNodes) {
        return new AutoHealConfig(startupCooldown, provisioningTimeout, swimHintsTtl, Option.some(maxNodes));
    }
}
