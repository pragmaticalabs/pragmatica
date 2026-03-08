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

/// Bounded FIFO buffer for membership updates piggybacked on protocol messages.
/// Thread-safe: multiple protocol threads may add/take updates concurrently.
public final class PiggybackBuffer {
    private final ConcurrentLinkedDeque<MembershipUpdate> buffer = new ConcurrentLinkedDeque<>();
    private final int maxSize;

    private PiggybackBuffer(int maxSize) {
        this.maxSize = maxSize;
    }

    /// Factory creating a buffer bounded to the given maximum size.
    public static PiggybackBuffer piggybackBuffer(int maxSize) {
        return new PiggybackBuffer(maxSize);
    }

    /// Add an update to the buffer. If the buffer is full, the oldest entry is evicted.
    public void addUpdate(MembershipUpdate update) {
        buffer.addLast(update);
        trimToSize();
    }

    /// Take up to {@code max} updates from the buffer, removing them.
    /// Returns an unmodifiable list.
    public List<MembershipUpdate> takeUpdates(int max) {
        var result = new ArrayList<MembershipUpdate>(Math.min(max, buffer.size()));

        for (int i = 0; i < max; i++) {
            var item = buffer.pollFirst();

            if (item == null) {
                break;
            }

            result.add(item);
        }

        return Collections.unmodifiableList(result);
    }

    /// Current number of buffered updates.
    public int size() {
        return buffer.size();
    }

    private void trimToSize() {
        while (buffer.size() > maxSize) {
            buffer.pollFirst();
        }
    }
}
