// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.aspect;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public record TransactionConfig(TransactionPropagation propagation,
                                IsolationLevel isolation,
                                Option<TimeSpan> timeout,
                                boolean readOnly,
                                Class<?>[] rollbackFor) {
    private static final TransactionPropagation DEFAULT_PROPAGATION = TransactionPropagation.REQUIRED;
    private static final IsolationLevel DEFAULT_ISOLATION = IsolationLevel.DEFAULT;
    private static final Class<?>[] EMPTY_ROLLBACK_FOR = new Class<?>[0];

    public static Result<TransactionConfig> transactionConfig() {
        return success(new TransactionConfig(DEFAULT_PROPAGATION, DEFAULT_ISOLATION, none(), false, EMPTY_ROLLBACK_FOR));
    }

    public static Result<TransactionConfig> transactionConfig(TransactionPropagation propagation) {
        return option(propagation).toResult(TransactionError.invalidConfig("Propagation cannot be null"))
                     .map(TransactionConfig::withDefaultsFrom);
    }

    public static Result<TransactionConfig> transactionConfig(TransactionPropagation propagation,
                                                              IsolationLevel isolation) {
        var validPropagation = option(propagation).toResult(TransactionError.invalidConfig("Propagation cannot be null"));
        var validIsolation = option(isolation).toResult(TransactionError.invalidConfig("Isolation cannot be null"));

        return Result.all(validPropagation, validIsolation).map(TransactionConfig::withPropagationAndIsolation);
    }

    @SuppressWarnings("JBCT-NAM-01")
    private static TransactionConfig withDefaultsFrom(TransactionPropagation p) {
        return new TransactionConfig(p, DEFAULT_ISOLATION, none(), false, EMPTY_ROLLBACK_FOR);
    }

    @SuppressWarnings("JBCT-NAM-01")
    private static TransactionConfig withPropagationAndIsolation(TransactionPropagation p, IsolationLevel i) {
        return new TransactionConfig(p, i, none(), false, EMPTY_ROLLBACK_FOR);
    }
}
