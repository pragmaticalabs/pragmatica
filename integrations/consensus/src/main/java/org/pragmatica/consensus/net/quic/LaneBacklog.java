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
package org.pragmatica.consensus.net.quic;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.pragmatica.lang.Contract;


/// #3168 — the frames of ONE lane stream that backpressure held back, kept in send order.
///
/// A frame that found its stream unwritable used to be re-sent by its own timer 25 ms later, while frames sent after it
/// found the stream writable and were written at once: they overtook it. A burst did that deterministically; a single
/// momentary `isWritable() == false` under host load did it to a paced sender (one frame, one stream, one sender).
///
/// The rule here is one decision, taken under one lock: a frame is written directly only if nothing is held for the
/// stream and the stream is writable; otherwise it joins the held frames, and ONLY the held frames' drain writes them,
/// head first. So the order frames reach the stream is the order [#admit] saw them, whichever thread called it.
/// The write callback only hands the bytes to Netty (an event-loop task submission), which does not block, so holding
/// the lock across it is cheap and is what makes the order the stream's order.
///
/// A backlog is retired once the last held frame is written or discarded; an [#admit] that finds a retired backlog
/// reports [Admission#RETIRED] and the caller starts from a fresh one. Not a general queue: one stream, one drainer.
final class LaneBacklog {
    /// What [#admit] did with a frame.
    enum Admission {
        /// Nothing was held and the stream was writable: the frame was written directly.
        WRITTEN,
        /// The frame is the first held one: the caller owns starting the drain.
        HELD_FIRST,
        /// Frames were already held: the frame joined them, the running drain writes it.
        HELD_BEHIND,
        /// This backlog is finished; the caller must obtain a fresh one.
        RETIRED
    }

    private final ArrayDeque<byte[]> held = new ArrayDeque<>();
    private boolean retired;

    @Contract
    synchronized Admission admit(byte[] bytes, BooleanSupplier writable, Consumer<byte[]> write) {
        if (retired) {
            return Admission.RETIRED;
        }

        if (!held.isEmpty()) {
            held.addLast(bytes);

            return Admission.HELD_BEHIND;
        }

        if (writable.getAsBoolean()) {
            write.accept(bytes);

            return Admission.WRITTEN;
        }

        held.addLast(bytes);

        return Admission.HELD_FIRST;
    }

    /// Writes held frames, head first, while the stream stays writable. Retires the backlog when none is left.
    /// Returns how many frames were written.
    @Contract
    synchronized int drain(BooleanSupplier writable, Consumer<byte[]> write) {
        var written = 0;

        while (!held.isEmpty() && writable.getAsBoolean()) {
            write.accept(held.pollFirst());
            written++;
        }

        retired = held.isEmpty();

        return written;
    }

    /// Gives up on every held frame and retires the backlog. Returns how many were discarded.
    @Contract
    synchronized int discardAll() {
        var discarded = held.size();

        held.clear();
        retired = true;

        return discarded;
    }

    synchronized boolean isRetired() {
        return retired;
    }
}
