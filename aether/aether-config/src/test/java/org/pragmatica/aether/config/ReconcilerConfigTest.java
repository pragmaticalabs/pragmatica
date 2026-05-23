// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Phase 5 PR-E — verifies the enforcing flip of `ReconcilerConfig.defaults()` and the
/// dual factory split in `ReconcilerRulesConfig`. The flip switches five of the seven
/// reconciliation rules to enforcing while keeping `joiningStuckAlert` and `stoppedZombie`
/// audit-only forever (spec §7.1). All rules remain `enabled=true` in both factories —
/// operators flip back to dry-run by per-rule TOML override, never by auto-disable.
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
