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

import org.pragmatica.messaging.Message;

import java.util.concurrent.atomic.AtomicLong;

/// Quorum state notifications with monotonic sequencing for stale-delivery rejection.
public record QuorumStateNotification(State state, long sequence) implements Message.Local {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public enum State { ESTABLISHED, DISAPPEARED }

    public static QuorumStateNotification established() {
        return new QuorumStateNotification(State.ESTABLISHED, SEQUENCE.incrementAndGet());
    }

    public static QuorumStateNotification disappeared() {
        return new QuorumStateNotification(State.DISAPPEARED, SEQUENCE.incrementAndGet());
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
