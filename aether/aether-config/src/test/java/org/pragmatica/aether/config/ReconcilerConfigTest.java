// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Phase 5 PR-E + grace-period fix — verifies `ReconcilerConfig.defaults()` returns the
/// enforcing rule set (five rules enforce, two audit-only forever per spec §7.1) AND
/// surfaces the `normalPhaseWarmup` grace period (default 60s) that gates rule
/// evaluation after every NORMAL-phase entry. The warmup absorbs SWIM gossip flap
/// during cluster formation / RECOVERING re-entry so the enforcing rules don't
/// force-decommission peers that are still on the recovery path.
class ReconcilerConfigTest {

    @Nested
    class Defaults {
        @Test
        void defaults_returnsRulesWhereJoiningTimeoutIsEnforcing() {
            var config = ReconcilerConfig.defaults();

            assertThat(config.rules().joiningTimeout().enforce()).isTrue();
            assertThat(config.rules().joiningTimeout().enabled()).isTrue();
        }

        @Test
        void defaults_flipsFiveEnforcingRules() {
            var rules = ReconcilerConfig.defaults().rules();

            assertThat(rules.joiningTimeout().enforce()).isTrue();
            assertThat(rules.onDutyFaulty().enforce()).isTrue();
            assertThat(rules.drainTimeout().enforce()).isTrue();
            assertThat(rules.generationLifecycleGap().enforce()).isTrue();
            assertThat(rules.swimLifecycleGap().enforce()).isTrue();
        }

        @Test
        void defaults_keepsAlertOnlyRulesAuditOnly() {
            var rules = ReconcilerConfig.defaults().rules();

            assertThat(rules.joiningStuckAlert().enforce()).isFalse();
            assertThat(rules.stoppedZombie().enforce()).isFalse();
        }

        @Test
        void defaults_preservesTickIntervalAndCapacity() {
            var config = ReconcilerConfig.defaults();

            assertThat(config.enabled()).isTrue();
            assertThat(config.tickInterval()).isEqualTo(ReconcilerConfig.DEFAULT_TICK_INTERVAL);
            assertThat(config.recentDecisionsCapacity()).isEqualTo(ReconcilerConfig.DEFAULT_RECENT_DECISIONS_CAPACITY);
        }

        @Test
        void defaults_surfacesNormalPhaseWarmup() {
            var config = ReconcilerConfig.defaults();

            assertThat(config.normalPhaseWarmup()).isEqualTo(ReconcilerConfig.DEFAULT_NORMAL_PHASE_WARMUP);
            // The default must be longer than `SWIM_FAULTY_DECLARATION × 3` (=30s) to
            // guarantee OnDutyFaulty doesn't fire on transient flap during cluster
            // formation — otherwise the warmup gate adds nothing over the existing rule
            // budget. 60s gives comfortable headroom without being operator-perceived
            // sluggish.
            assertThat(config.normalPhaseWarmup().millis()).isGreaterThanOrEqualTo(60_000L);
        }
    }

    @Nested
    class BackwardCompatConstructor {
        @Test
        void fourArg_constructor_defaultsWarmupTo60s() {
            var config = new ReconcilerConfig(true,
                                              ReconcilerConfig.DEFAULT_TICK_INTERVAL,
                                              ReconcilerRulesConfig.enforcingDefaults(),
                                              ReconcilerConfig.DEFAULT_RECENT_DECISIONS_CAPACITY);

            assertThat(config.normalPhaseWarmup()).isEqualTo(ReconcilerConfig.DEFAULT_NORMAL_PHASE_WARMUP);
        }
    }

    @Nested
    class DryRunDefaults {
        @Test
        void dryRunDefaults_returnsRulesWhereAllEnforceFalse() {
            var rules = ReconcilerRulesConfig.dryRunDefaults();

            assertThat(rules.joiningTimeout().enforce()).isFalse();
            assertThat(rules.joiningStuckAlert().enforce()).isFalse();
            assertThat(rules.onDutyFaulty().enforce()).isFalse();
            assertThat(rules.drainTimeout().enforce()).isFalse();
            assertThat(rules.generationLifecycleGap().enforce()).isFalse();
            assertThat(rules.swimLifecycleGap().enforce()).isFalse();
            assertThat(rules.stoppedZombie().enforce()).isFalse();
        }

        @Test
        void dryRunDefaults_keepsAllRulesEnabled() {
            var rules = ReconcilerRulesConfig.dryRunDefaults();

            assertThat(rules.joiningTimeout().enabled()).isTrue();
            assertThat(rules.joiningStuckAlert().enabled()).isTrue();
            assertThat(rules.onDutyFaulty().enabled()).isTrue();
            assertThat(rules.drainTimeout().enabled()).isTrue();
            assertThat(rules.generationLifecycleGap().enabled()).isTrue();
            assertThat(rules.swimLifecycleGap().enabled()).isTrue();
            assertThat(rules.stoppedZombie().enabled()).isTrue();
        }
    }

    @Nested
    class EnforcingDefaults {
        @Test
        void enforcingDefaults_flipsFiveRulesToEnforcing() {
            var rules = ReconcilerRulesConfig.enforcingDefaults();

            assertThat(rules.joiningTimeout().enforce()).isTrue();
            assertThat(rules.onDutyFaulty().enforce()).isTrue();
            assertThat(rules.drainTimeout().enforce()).isTrue();
            assertThat(rules.generationLifecycleGap().enforce()).isTrue();
            assertThat(rules.swimLifecycleGap().enforce()).isTrue();
        }

        @Test
        void enforcingDefaults_keepsJoiningStuckAlertAndStoppedZombieAuditOnly() {
            var rules = ReconcilerRulesConfig.enforcingDefaults();

            assertThat(rules.joiningStuckAlert().enforce()).isFalse();
            assertThat(rules.stoppedZombie().enforce()).isFalse();
        }

        @Test
        void enforcingDefaults_keepsAllRulesEnabled() {
            var rules = ReconcilerRulesConfig.enforcingDefaults();

            assertThat(rules.joiningTimeout().enabled()).isTrue();
            assertThat(rules.joiningStuckAlert().enabled()).isTrue();
            assertThat(rules.onDutyFaulty().enabled()).isTrue();
            assertThat(rules.drainTimeout().enabled()).isTrue();
            assertThat(rules.generationLifecycleGap().enabled()).isTrue();
            assertThat(rules.swimLifecycleGap().enabled()).isTrue();
            assertThat(rules.stoppedZombie().enabled()).isTrue();
        }

        @Test
        void enforcingDefaults_matchesDefaultsRulesShape() {
            assertThat(ReconcilerConfig.defaults().rules()).isEqualTo(ReconcilerRulesConfig.enforcingDefaults());
        }
    }
}
