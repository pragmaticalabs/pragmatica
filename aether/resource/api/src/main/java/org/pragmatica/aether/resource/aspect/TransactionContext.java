// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.aspect;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


public record TransactionContext(String id,
                                 TransactionConfig config,
                                 TransactionStatus status,
                                 Instant startTime,
                                 Option<TransactionContext> parentContext) {
    public enum TransactionStatus {
        ACTIVE,
        COMMITTED,
        ROLLED_BACK,
        SUSPENDED
    }

    public static Result<TransactionContext> transactionContext(TransactionConfig config) {
        return success(new TransactionContext(UUID.randomUUID().toString(),
                                              config,
                                              TransactionStatus.ACTIVE,
                                              Instant.now(),
                                              none()));
    }

    @SuppressWarnings("JBCT-NAM-01")
    public static TransactionContext nestedContext(TransactionConfig config, TransactionContext parent) {
        return new TransactionContext(UUID.randomUUID().toString(),
                                      config,
                                      TransactionStatus.ACTIVE,
                                      Instant.now(),
                                      option(parent));
    }

    public TransactionContext commit() {
        return new TransactionContext(id, config, TransactionStatus.COMMITTED, startTime, parentContext);
    }

    public TransactionContext rollback() {
        return new TransactionContext(id, config, TransactionStatus.ROLLED_BACK, startTime, parentContext);
    }

    public TransactionContext suspend() {
        return new TransactionContext(id, config, TransactionStatus.SUSPENDED, startTime, parentContext);
    }

    public TransactionContext resume() {
        return new TransactionContext(id, config, TransactionStatus.ACTIVE, startTime, parentContext);
    }

    public boolean isActive() {
        return status == TransactionStatus.ACTIVE;
    }

    public boolean isNested() {
        return parentContext.isPresent();
    }

    public boolean isTimedOut() {
        return config.timeout()
                     .filter(this::hasExceededTimeout)
                     .isPresent();
    }

    private boolean hasExceededTimeout(TimeSpan timeout) {
        var elapsed = Duration.between(startTime, Instant.now());

        return elapsed.toMillis() > timeout.millis();
    }
}
