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
package org.pragmatica.consensus.rabia;

import java.util.concurrent.ScheduledFuture;


/// Explicit state machine for Rabia engine node lifecycle.
/// Replaces 4 independent atomic variables (active, isInPhase, pendingSyncTask, phaseStallTask)
/// with a single AtomicReference, making invalid state combinations unrepresentable.
///
/// Membership-architecture-spec §4.5 / §7.3: `Paused` is the canonical quorum-loss state.
/// Distinct from `Stopped` — Paused retains in-memory protocol state (phases map,
/// currentPhase, pendingBatches, lockedValue, correlationMap, bufferedDecisions). On quorum
/// return (`ESTABLISHED`), resume to `Idle` without a state reset. The only path to a full
/// reset is the explicit `reconfigure(ClusterConfig)` API; quorum-loss never resets.
sealed interface EngineState {
    record Stopped() implements EngineState {}

    record Syncing(ScheduledFuture<?> syncTask) implements EngineState {}

    record Idle() implements EngineState {}

    record InPhase(ScheduledFuture<?> stallDetector) implements EngineState {}

    record Observing() implements EngineState {}

    /// Paused: quorum is currently unavailable. All in-memory protocol state is retained;
    /// inbound `Decision` messages are still applied so the engine catches up transparently
    /// when quorum returns. New `apply()` submissions are rejected. This state is exited
    /// only by a quorum `ESTABLISHED` notification (→ `Idle`) or by `reconfigure` (→ full reset).
    record Paused() implements EngineState {}

    default boolean isActive() {
        return this instanceof Idle || this instanceof InPhase;
    }

    default boolean isInPhase() {
        return this instanceof InPhase;
    }

    default boolean isObserving() {
        return this instanceof Observing;
    }

    default boolean isPaused() {
        return this instanceof Paused;
    }
}
