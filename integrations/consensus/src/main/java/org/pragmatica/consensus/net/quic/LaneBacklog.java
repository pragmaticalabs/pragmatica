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
import java.util.function.Predicate;

import org.pragmatica.lang.Contract;


/// #3168 — the frames of ONE lane stream that backpressure held back, kept in send order.
///
/// A frame that found its stream unwritable used to be re-sent by its own timer 25 ms later, while frames sent after it
/// found the stream writable and were written at once: they overtook it. A burst did that deterministically; a single
/// momentary `isWritable() == false` under host load did it to a paced sender (one frame, one stream, one sender).
///
/// The guarantee, per stream, for callers OFF the stream's event loop: if the send of frame A happens-before the send of
/// frame B on the same stream, A is handed to the stream before B, whether either was held. Mechanism: a frame is written
/// directly only while the stream has no backlog and is writable (the owner keeps at most one backlog per stream and
/// drops it only after it retired); otherwise it joins the backlog under this lock, and ONLY the drain writes held frames,
/// head first, under the same lock. Handing a frame to the stream from off its event loop submits an event-loop task,
/// and those run in submission order. A caller ON the stream's event loop writes inline and can overtake tasks already
/// queued by off-loop callers: no order holds between its frames and those. Not across streams: frames held on a stream
/// are written to that stream or discarded with it, never moved to a replacement stream (reconnect, lane supersede).
///
/// A backlog is retired once the last held frame is written or discarded; an [#admit] that finds a retired backlog
/// reports [Admission#RETIRED] and the caller starts from a fresh one. Not a general queue: one stream, one drainer
/// (the caller that got [Admission#HELD_FIRST]); a caller ON the stream's event loop, which writes inline without
/// admitting, is not a drainer and is excluded from that rule.
final class LaneBacklog<F> {
    /// What [#admit] did with a frame.
    enum Admission {
        /// The frame is the first held one: the caller owns starting the drain.
        HELD_FIRST,
        /// Frames were already held: the frame joined them, the running drain writes it.
        HELD_BEHIND,
        /// This backlog is finished; the caller must obtain a fresh one.
        RETIRED
    }

    private final ArrayDeque<F> held = new ArrayDeque<>();
    private boolean retired;

    /// Holds `frame` behind the frames already held and runs `onHeld` for it under the lock, so a drain can never
    /// account for a frame before its holding was accounted.
    @Contract
    synchronized Admission admit(F frame, Consumer<F> onHeld) {
        if (retired) {
            return Admission.RETIRED;
        }

        var first = held.isEmpty();

        held.addLast(frame);
        onHeld.accept(frame);

        return first
               ? Admission.HELD_FIRST
               : Admission.HELD_BEHIND;
    }

    /// One drain pass: writes held frames, head first, while the stream stays writable, then discards the head frames
    /// `expired` accepts (oldest first, so the head's age bounds the backlog; a frame the stream can take is written,
    /// never discarded). Retires the backlog when none is left and reports whether it did.
    @Contract
    synchronized boolean drain(Predicate<F> expired, Consumer<F> discard, BooleanSupplier writable, Consumer<F> write) {
        while (!held.isEmpty() && writable.getAsBoolean()) {
            write.accept(held.pollFirst());
        }

        while (!held.isEmpty() && expired.test(held.peekFirst())) {
            discard.accept(held.pollFirst());
        }

        retired = held.isEmpty();

        return retired;
    }

    /// Gives up on every held frame, passing each to `discard`, and retires the backlog, so a writer still holding a
    /// reference to it after its owner dropped it gets [Admission#RETIRED] and cannot hold a frame nobody drains.
    @Contract
    synchronized void discardAll(Consumer<F> discard) {
        held.forEach(discard);
        held.clear();
        retired = true;
    }

    synchronized int size() {
        return held.size();
    }
}
