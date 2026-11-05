/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.dht;

import java.util.List;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Operator-visible sink for a graceful-departure push (issue #427, D4; #2034). The aether layer wires it to cluster events
/// so the signal reaches `/api/events`; this Apache-2.0 module stays free of any `ClusterEvent` dependency by accepting only
/// primitives (same seam as `DrainProcedure`'s `Consumer<DrainReason>`).
///
/// Every #2034 addition is a `default` method, so a lambda over [#onIncomplete] still implements it. Each returns a promise
/// that settles when the event it raised has landed, been given up or been suppressed: the push awaits them, bounded by its
/// deadline, before it resolves, so the events are likely to reach the cluster before the node halts.
@FunctionalInterface
public interface DeparturePushObserver {
    /// Report that `keysAtRisk` chunks were still unacknowledged when the push window ended.
    ///
    /// @param keysAtRisk total number of chunks not confirmed on a surviving replica
    /// @param sampleKeys bounded first-N sample of the at-risk keys (hex-encoded) for triage
    @Contract
    void onIncomplete(int keysAtRisk, List<String> sampleKeys);

    /// Sole-copy keys (no surviving replica holds them) are not yet acknowledged by any target (#2034). Called at most once
    /// per push. `count` 0 means the inventory could not be enumerated before the drain deadline: the at-risk set is unknown.
    ///
    /// @param count      sole-copy keys not yet acknowledged, or 0 when the inventory is unknown
    /// @param sampleKeys bounded first-N sample of those keys (hex-encoded)
    default Promise<Unit> onSoleCopyAtRisk(int count, List<String> sampleKeys) {
        return Promise.unitPromise();
    }

    /// Every sole-copy key reported by [#onSoleCopyAtRisk] was acknowledged before the push ended (#2034): the recovery.
    ///
    /// @param count the number of sole-copy keys [#onSoleCopyAtRisk] reported
    default Promise<Unit> onSoleCopyDelivered(int count) {
        return Promise.unitPromise();
    }

    /// The push ended with keys unacknowledged (#2034): `soleCopyAtRisk` of the `keysAtRisk` have no surviving replica, so
    /// the drain completed with data at risk when it is above zero. Defaults to [#onIncomplete].
    ///
    /// @param keysAtRisk     every unacknowledged key, sole-copy and replicated
    /// @param soleCopyAtRisk the sole-copy share of `keysAtRisk`
    /// @param sampleKeys     bounded first-N sample of the at-risk keys (hex-encoded), sole-copy keys first
    default Promise<Unit> onIncompleteReport(int keysAtRisk, int soleCopyAtRisk, List<String> sampleKeys) {
        onIncomplete(keysAtRisk, sampleKeys);

        return Promise.unitPromise();
    }

    /// No-op observer for non-cluster DHT paths and tests.
    static DeparturePushObserver noop() {
        return (keysAtRisk, sampleKeys) -> {};
    }
}
