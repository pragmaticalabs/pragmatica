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

package org.pragmatica.swim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.pragmatica.swim.SwimMessage.MembershipUpdate;

/// Bounded buffer for membership updates piggybacked on protocol messages.
/// Thread-safe: multiple protocol threads may add/peek updates concurrently.
///
/// [Fix #10] Uses peek-and-age instead of drain-on-read. Updates are returned
/// multiple times (up to dissemination limit) to ensure they reach all members.
/// Each update tracks how many times it was piggybacked — after enough
/// disseminations (lambda * log(N)), it is evicted.
public final class PiggybackBuffer {
    private final ConcurrentLinkedDeque<TrackedUpdate> buffer = new ConcurrentLinkedDeque<>();
    private final int maxSize;
    private final int maxDisseminations;

    private record TrackedUpdate(MembershipUpdate update, int disseminationCount) {
        TrackedUpdate withDissemination() {
            return new TrackedUpdate(update, disseminationCount + 1);
        }
    }

    private PiggybackBuffer(int maxSize) {
        this.maxSize = maxSize;
        // Disseminate each update at least 3 * maxSize times (approximates lambda * log(N))
        this.maxDisseminations = 3 * Math.max(maxSize, 4);
    }

    /// Factory creating a buffer bounded to the given maximum size.
    public static PiggybackBuffer piggybackBuffer(int maxSize) {
        return new PiggybackBuffer(maxSize);
    }

    /// Add an update to the buffer. If the buffer is full, the oldest entry is evicted.
    public void addUpdate(MembershipUpdate update) {
        buffer.addLast(new TrackedUpdate(update, 0));
        trimToSize();
    }

    /// Peek up to {@code max} updates WITHOUT removing them.
    /// Each peeked update increments its dissemination counter.
    /// Updates that have been disseminated enough times are evicted.
    public List<MembershipUpdate> peekUpdates(int max) {
        var result = new ArrayList<MembershipUpdate>(Math.min(max, buffer.size()));
        var toRequeue = new ArrayList<TrackedUpdate>();

        for (int i = 0; i < max; i++) {
            var item = buffer.pollFirst();

            if (item == null) {
                break;
            }

            result.add(item.update());

            var incremented = item.withDissemination();
            if (incremented.disseminationCount() < maxDisseminations) {
                toRequeue.add(incremented);
            }
            // else: evicted — disseminated enough times
        }

        // Re-add non-evicted updates to the back
        toRequeue.forEach(buffer::addLast);

        return Collections.unmodifiableList(result);
    }

    /// Take up to {@code max} updates from the buffer, removing them.
    /// Returns an unmodifiable list.
    @Deprecated
    public List<MembershipUpdate> takeUpdates(int max) {
        var result = new ArrayList<MembershipUpdate>(Math.min(max, buffer.size()));

        for (int i = 0; i < max; i++) {
            var item = buffer.pollFirst();

            if (item == null) {
                break;
            }

            result.add(item.update());
        }

        return Collections.unmodifiableList(result);
    }

    /// Current number of buffered updates.
    public int size() {
        return buffer.size();
    }

    private void trimToSize() {
        while (buffer.size() > maxSize * 2) {
            buffer.pollFirst();
        }
    }
}
