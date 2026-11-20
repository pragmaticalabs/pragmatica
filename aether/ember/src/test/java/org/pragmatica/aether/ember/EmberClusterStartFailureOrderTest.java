// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.utils.Causes.cause;
import static org.assertj.core.api.Assertions.assertThat;


/// #1611 — the abort that `firstFailure` triggers snapshots `startFailures`, so the failing node's entry
/// must be recorded BEFORE `firstFailure` fires, or the start-failure report omits the node that caused it.
///
/// The map's `put` is slowed on purpose: a recorder that raced the signal (or a signal that ran first) would
/// let the abort read the map while the entry is still being written. The signal handler here reads the map
/// exactly as `captureStartFailure` does. The handlers may run off the test thread, so the snapshot is awaited
/// with a bound rather than read at once (a plain read passed or failed on a null, never on the order).
///
/// Red-before is a swap inside the single handler (`EmberCluster.recordThenSignal`): fire the signal before the
/// `startFailures.put` and the snapshot read here is empty, on both the pending and the already-resolved path.
///
/// The already-resolved test pins the inline path (`start` resolved before registration). It is NOT red at the
/// former two-handler head 1ee671a6c9: there both handlers run inline in registration order, and the old window
/// (start resolving between the two registrations) is a few instructions wide with no hook a test can reach
/// (`Promise` is sealed, so it cannot be proxied). It pins the shape, not the former race.
class EmberClusterStartFailureOrderTest {
    private static final long PUT_DELAY_MILLIS = 200;
    private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();

    @Test
    void signal_seesTheFailingNodeEntry_whenTheRecordingPutIsSlow() {
        var failures = new DelayedPutMap();
        var firstFailure = Promise.<Unit> promise();
        var snapshot = Promise.<Map<String, String>> promise();

        firstFailure.onFailure(_ -> snapshot.succeed(Map.copyOf(failures)));
        var start = Promise.<Unit> promise();

        EmberCluster.recordFailureThenSignal(start, "node-2", failures, firstFailure);
        start.fail(cause("bind failed"));
        assertThat(snapshot.await(BOUND).unwrap()).describedAs("the snapshot taken when the abort fires must already name the triggering node")
                  .containsEntry("node-2", "bind failed");
    }

    @Test
    void signal_seesTheFailingNodeEntry_whenStartIsAlreadyResolved() {
        var failures = new DelayedPutMap();
        var firstFailure = Promise.<Unit> promise();
        var snapshot = Promise.<Map<String, String>> promise();

        firstFailure.onFailure(_ -> snapshot.succeed(Map.copyOf(failures)));
        var start = Promise.<Unit> promise();

        start.fail(cause("bind failed"));
        EmberCluster.recordFailureThenSignal(start, "node-3", failures, firstFailure);
        assertThat(snapshot.await(BOUND).unwrap()).describedAs("the inline path must record the node before it signals")
                  .containsEntry("node-3", "bind failed");
    }

    @Test
    void recorder_passesTheStartPromiseThrough_andSignalsOnFailureOnly() {
        var failures = new ConcurrentHashMap<String, String>();
        var firstFailure = Promise.<Unit> promise();
        var start = Promise.<Unit> promise();
        var returned = EmberCluster.recordFailureThenSignal(start, "node-1", failures, firstFailure);

        start.succeed(Unit.unit());
        assertThat(returned.isResolved()).isTrue();
        assertThat(failures).isEmpty();
        assertThat(firstFailure.isResolved()).isFalse();
    }

    private static final class DelayedPutMap extends ConcurrentHashMap<String, String> {
        @Override
        public String put(String key, String value) {
            sleep();

            return super.put(key, value);
        }

        private static void sleep() {
            try {
                Thread.sleep(PUT_DELAY_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
