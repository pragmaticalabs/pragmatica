// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;


/// Static-initialisation-order guard for [AutoHealConfig].
///
/// `DEFAULT` is a constant whose initialiser reaches `NO_CAP` through `autoHealConfig(...)`. Static
/// initialisers run in TEXTUAL order, so while `NO_CAP` was declared BELOW `DEFAULT` it was still
/// `null` when `DEFAULT` was built — making `DEFAULT.maxNodes()` a null `Option` rather than
/// `Option.empty()`.
///
/// Nothing about that was visible at the call site, and it broke auto-heal outright: every
/// replacement provision died on `maxNodes.fold(...)` inside
/// `NodeLifecycleManagerRecord.capGuardedProvision`, the NPE was swallowed by the scheduler's
/// `runGuarded` ("task recurrence preserved"), the circuit breaker therefore recorded
/// `consecutiveFailures=0`, and `/api/cluster/provisioning` kept reporting a PERMITTED provision
/// while a killed node was never replaced. Observed live: a 5-node cluster held `deficit=1` for
/// over an hour with every gate reporting healthy.
///
/// A plain reorder fixes it but nothing stops it being reordered back, which is what this test is
/// for. It asserts the OBSERVABLE value rather than the declaration order, so it stays valid however
/// the constants are later arranged.
class AutoHealConfigStaticInitTest {

    @Test
    void defaultConfig_hasNonNullMaxNodes_notMerelyEmpty() {
        assertThat(AutoHealConfig.DEFAULT.maxNodes())
                .withFailMessage("AutoHealConfig.DEFAULT.maxNodes() is null — NO_CAP was read before its "
                                + "own initialiser ran (static init order). This silently disables auto-heal "
                                + "provisioning: capGuardedProvision NPEs and the failure is swallowed.")
                .isNotNull();
    }

    @Test
    void defaultConfig_maxNodesIsEmpty_soProvisioningIsUnbounded() {
        assertThat(AutoHealConfig.DEFAULT.maxNodes()).isEqualTo(Option.empty());
    }

    @Test
    void noCapConstant_isEmptyOption() {
        assertThat(AutoHealConfig.NO_CAP).isNotNull()
                                         .isEqualTo(Option.empty());
    }

    /// Every factory overload must yield a usable `maxNodes`, not only the one `DEFAULT` happens to
    /// call — a caller reaching a different arity would hit the same NPE.
    @Test
    void everyFactoryOverload_yieldsNonNullMaxNodes() {
        var twoArg = AutoHealConfig.autoHealConfig(AutoHealConfig.DEFAULT.retryInterval(),
                                                   AutoHealConfig.DEFAULT.startupCooldown());

        assertThat(twoArg.isSuccess()).isTrue();
        twoArg.onSuccess(config -> assertThat(config.maxNodes()).isNotNull());
    }

    @Test
    void withMaxNodes_setsTheCap() {
        assertThat(AutoHealConfig.DEFAULT.withMaxNodes(7)
                                         .maxNodes()).isEqualTo(Option.some(7));
    }
}
