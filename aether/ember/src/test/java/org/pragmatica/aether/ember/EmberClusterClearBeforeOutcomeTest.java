// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.Unit.unit;
import static org.assertj.core.api.Assertions.assertThat;


/// #1112: the registry clear must be ordered BEFORE the outcome the caller awaits (#913 contract).
/// `onSuccess` is dispatched to a virtual thread and races the caller's own `onResult` continuation;
/// the investigation measured 34–83 of 20,000 iterations where the caller still saw the aborted
/// nodes. The loop below runs the product's exact chain ([EmberCluster#clearThenSettle]) with the
/// stops resolved from another thread, as the last node's stop callback does, and demands zero.
class EmberClusterClearBeforeOutcomeTest {
    private static final int ITERATIONS = 20_000;
    private static final Cause START_FAILED = Causes.cause("start failed");

    @Test
    void registryIsEmptyBeforeTheCallerSeesTheOutcome_everyTime() {
        var nonEmpty = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            if (!callerObservesEmptyRegistry()) {
                nonEmpty++;
            }
        }

        assertThat(nonEmpty).as("iterations where the caller saw a populated registry (of %d)", ITERATIONS).isZero();
    }

    private static boolean callerObservesEmptyRegistry() {
        var registry = new ConcurrentHashMap<String, String>();

        registry.put("la-sf-1", "inactive");
        registry.put("la-sf-2", "inactive");
        registry.put("la-sf-3", "inactive");
        var stopsSettled = Promise.<Unit> promise();
        var outcome = Promise.<Unit> promise();

        EmberCluster.clearThenSettle(stopsSettled, u -> clear(registry, u), START_FAILED::promise).onResult(outcome::resolve);
        Thread.startVirtualThread(() -> stopsSettled.succeed(unit()));
        outcome.await();

        return registry.isEmpty();
    }

    private static Unit clear(ConcurrentHashMap<String, String> registry, Unit unit) {
        registry.clear();

        return unit;
    }
}
