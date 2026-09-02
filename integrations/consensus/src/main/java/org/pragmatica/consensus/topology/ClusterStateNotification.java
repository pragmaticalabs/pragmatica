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
package org.pragmatica.consensus.topology;

import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.messaging.Message;


/// Authoritative cluster-state notifications with monotonic sequencing for stale-delivery
/// rejection. Emitted by the consensus bridge in response to `RabiaEngine` phase transitions
/// into / out of the `Active` engine state. Replaces the legacy QUIC-count-based
/// `QuorumStateNotification` (E2 Phase 2c.0, 2026-05-28): the old signal fired on local
/// connected-peer threshold crossings — necessary but not sufficient for "cluster is
/// operational", because it could fire while consensus was still `Syncing`, `Paused`, or
/// `Stopped`.
///
/// Subscribers can rely on `ACTIVE` to mean "consensus engine is genuinely operational" and
/// on `PASSIVE` to mean "consensus engine is NOT operational" (i.e. any state other than
/// `Active`: `Syncing`, `Paused`, `Stopped`, `Observing`).
public record ClusterStateNotification(State state, long sequence) implements Message.Local {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public enum State {
        ACTIVE,
        PASSIVE
    }

    public static ClusterStateNotification active() {
        return new ClusterStateNotification(State.ACTIVE, SEQUENCE.incrementAndGet());
    }

    public static ClusterStateNotification passive() {
        return new ClusterStateNotification(State.PASSIVE, SEQUENCE.incrementAndGet());
    }

    /// Atomically advance the tracker if this notification is newer.
    /// Returns false if stale (should be ignored).
    public boolean advanceSequence(AtomicLong tracker) {
        long prev;

        do {
            prev = tracker.get();
            if (sequence <= prev) {
                return false;
            }
        } while (!tracker.compareAndSet(prev, sequence));

        return true;
    }
}
