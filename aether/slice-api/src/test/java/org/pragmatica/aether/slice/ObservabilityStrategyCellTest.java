// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ObservabilityStrategyCell.InvocationStrategy;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityStrategyCellTest {
    private static final String ARTIFACT = "com.example:my-slice";
    private static final String METHOD = "handle";

    @Test
    void strategy_identity_returnsProceedResultUntouched() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        var inner = Promise.success("payload");

        var returned = cell.strategy().around(() -> inner);

        assertThat(returned).isSameAs(inner);
    }

    @Test
    void swap_replacesBehaviour_withDecoratingStrategy() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);
        InvocationStrategy decorating = proceed -> proceed.apply().map(value -> "decorated:" + value);

        cell.swap(decorating);

        cell.strategy()
            .around(() -> Promise.success("payload"))
            .await()
            .onFailure(cause -> Assertions.fail(cause.message()))
            .onSuccess(value -> assertThat(value).isEqualTo("decorated:payload"));
    }

    @Test
    void key_composesArtifactBaseAndMethodName() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);

        assertThat(cell.key()).isEqualTo(ARTIFACT + "/" + METHOD);
    }

    @Test
    void storage_isEmptyByDefault_andHoldsAttachedState() {
        var cell = ObservabilityStrategyCell.observabilityStrategyCell(ARTIFACT, METHOD);

        assertThat(cell.storage().get()).isNull();

        cell.storage().set("accumulator");

        assertThat(cell.storage().get()).isEqualTo("accumulator");
    }
}
