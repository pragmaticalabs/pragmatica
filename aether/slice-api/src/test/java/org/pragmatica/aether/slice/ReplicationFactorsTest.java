// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

class ReplicationFactorsTest {
    private static final ReplicationFactors CLUSTER_DEFAULTS = ReplicationFactors.BUILT_IN;

    @Test
    void replicationFactors_confirmationAboveFactor_isRefused() {
        assertThat(cause(ReplicationFactors.replicationFactors(3, 4))).isInstanceOf(ReplicationFactorsError.ConfirmationOutOfRange.class);
    }

    @Test
    void replicationFactors_confirmationZero_isRefused() {
        assertThat(cause(ReplicationFactors.replicationFactors(3, 0))).isInstanceOf(ReplicationFactorsError.ConfirmationOutOfRange.class);
    }

    @Test
    void replicationFactors_negativeConfirmation_isRefused() {
        assertThat(cause(ReplicationFactors.replicationFactors(3, -1))).isInstanceOf(ReplicationFactorsError.ConfirmationOutOfRange.class);
    }

    @Test
    void replicationFactors_factorZero_isRefused() {
        assertThat(cause(ReplicationFactors.replicationFactors(0, 0))).isInstanceOf(ReplicationFactorsError.FactorBelowOne.class);
    }

    @Test
    void replicationFactors_confirmationEqualToFactor_isValid() {
        assertThat(ReplicationFactors.replicationFactors(3, 3).isSuccess()).isTrue();
    }

    @Test
    void withinCoreCount_factorAboveDesiredCores_isRefused() {
        assertThat(cause(ReplicationFactors.BUILT_IN.withinCoreCount(2))).isInstanceOf(ReplicationFactorsError.ExceedsCoreCount.class);
    }

    @Test
    void withinCoreCount_unknownCoreCount_isNotChecked() {
        assertThat(ReplicationFactors.BUILT_IN.withinCoreCount(0).isSuccess()).isTrue();
    }

    @Test
    void sameAsCommitted_differentFactors_isRefused() {
        var declared = new ReplicationFactors(3, 3);

        assertThat(cause(declared.sameAsCommitted("stream orders", ReplicationFactors.BUILT_IN)))
            .isInstanceOf(ReplicationFactorsError.ChangedOnLiveResource.class);
    }

    @Test
    void resolve_nothingDeclared_takesTheDefaults() {
        assertThat(resolved(ReplicationDeclaration.NONE).factors()).isEqualTo(CLUSTER_DEFAULTS);
    }

    @Test
    void resolve_declaredValues_overrideTheDefaults() {
        var declaration = ReplicationDeclaration.replicationDeclaration(some(5), some(4));

        assertThat(resolved(declaration).factors()).isEqualTo(new ReplicationFactors(5, 4));
    }

    @Test
    void resolve_declaredFactorBelowDefaultConfirmation_takesConfirmationDownToTheFactor() {
        var declaration = ReplicationDeclaration.replicationDeclaration(some(1), none());

        assertThat(resolved(declaration).factors()).isEqualTo(new ReplicationFactors(1, 1));
    }

    @Test
    void resolve_explicitConfirmationAboveFactor_isRefused() {
        var declaration = ReplicationDeclaration.replicationDeclaration(some(2), some(3));

        assertThat(cause(declaration.resolve(CLUSTER_DEFAULTS))).isInstanceOf(ReplicationFactorsError.ConfirmationOutOfRange.class);
    }

    @Test
    void resolve_defaultedFactorBelowThree_isRefused() {
        var lowDefaults = new ReplicationFactors(2, 1);

        assertThat(cause(ReplicationDeclaration.NONE.resolve(lowDefaults))).isInstanceOf(ReplicationFactorsError.ImplicitFactorBelowThree.class);
    }

    @Test
    void resolve_declaredFactorBelowThree_isAcceptedWithTheLoudWarning() {
        var declaration = ReplicationDeclaration.replicationDeclaration(some(2), none());

        assertThat(resolved(declaration).warnings()).contains(ReplicationWarning.FACTOR_BELOW_THREE);
        assertThat(ReplicationWarning.FACTOR_BELOW_THREE.loud()).isTrue();
    }

    @Test
    void resolve_confirmationEqualToFactor_warns() {
        var declaration = ReplicationDeclaration.replicationDeclaration(some(3), some(3));

        assertThat(resolved(declaration).warnings()).containsExactly(ReplicationWarning.CONFIRMATION_EQUALS_FACTOR);
    }

    @Test
    void resolve_confirmationOne_warns() {
        var declaration = ReplicationDeclaration.replicationDeclaration(some(3), some(1));

        assertThat(resolved(declaration).warnings()).containsExactly(ReplicationWarning.CONFIRMATION_OWNER_ONLY);
    }

    @Test
    void resolve_factorOne_raisesAllThreeWarnings() {
        var declaration = ReplicationDeclaration.replicationDeclaration(some(1), none());

        assertThat(resolved(declaration).warnings()).containsExactly(ReplicationWarning.FACTOR_BELOW_THREE,
                                                                    ReplicationWarning.CONFIRMATION_EQUALS_FACTOR,
                                                                    ReplicationWarning.CONFIRMATION_OWNER_ONLY);
    }

    @Test
    void resolve_builtInDefaults_raiseNoWarning() {
        assertThat(resolved(ReplicationDeclaration.NONE).warnings()).isEmpty();
    }

    private static ReplicationDeclaration.Resolved resolved(ReplicationDeclaration declaration) {
        return declaration.resolve(CLUSTER_DEFAULTS).unwrap();
    }

    private static Object cause(Result<?> result) {
        return result.fold(cause -> cause, value -> value);
    }
}
