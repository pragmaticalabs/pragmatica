// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import java.time.Duration;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.slice.RateGuardError;
import org.pragmatica.aether.slice.ResourceCapacityExhausted;
import org.pragmatica.dht.DHTError;
import org.pragmatica.http.HttpClientError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.CoreError;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.CircuitBreaker;
import org.pragmatica.lang.utils.RateLimiter;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #280: the retry interceptor's default policy retries only causes that declare themselves
/// transient (`Cause.isTransient()`), so a transient infrastructure failure that is NOT marked is
/// silently never retried. This pins the classification of this module's causes — removing a
/// `Cause.Transient` reddens the row for it.
class TransientClassificationTest {
    private record Capacity(String message) implements ResourceCapacityExhausted {}

    private record CapacityDenied(String message) implements ResourceCapacityExhausted {
        @Override
        public boolean transientCapacity() {
            return false;
        }
    }

    @Test
    void transientInfrastructureCauses_areClassifiedTransient() {
        Cause[] transientCauses = {
            new CoreError.Timeout("t"),
            new CircuitBreaker.CircuitBreakerError.CircuitBreakerOpenError("open", TimeSpan.timeSpan(1).seconds()),
            new RateLimiter.RateLimiterError.LimitExceeded(TimeSpan.timeSpan(1).seconds()),
            new RateGuardError.LimitExceeded(1, 1, 0, 1),
            new Capacity("full"),
            new DatabaseConnectorError.ConnectionFailed("refused", Option.none()),
            new DatabaseConnectorError.TimedOut("query"),
            DatabaseConnectorError.PoolExhausted.INSTANCE,
            new HttpClientError.ConnectionFailed("refused", Option.none()),
            new HttpClientError.Timeout("slow", Option.some(Duration.ofSeconds(1))),
            new DHTError.PeerUnreachable(new NodeId("n1"), "down"),
            new DHTError.QuorumNotReached(2, 1),
            new DHTError.NoAvailableNodes(),
            new DHTError.OperationTimeout(),
            new DHTError.MigrationInProgress(),
            new DatabaseConnectorError.TransactionRolledBack("40001"),
        };

        for (var cause : transientCauses) {
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isTrue();
            assertThat(cause.isTerminal()).as(cause.getClass().getName() + " is not also terminal").isFalse();
        }
    }

    @Test
    void unclassifiedCauses_stayUnclassified() {
        Cause[] unclassified = {
            new CapacityDenied("not capacity"),
            new DatabaseConnectorError.ConstraintViolation("pk", "dup"),
            new HttpClientError.RequestFailed(500, "boom"),
            new DHTError.StaleEpochWrite(1, 1),
        };

        for (var cause : unclassified) {
            assertThat(cause.isTransient()).as(cause.getClass().getName()).isFalse();
        }
    }
}
