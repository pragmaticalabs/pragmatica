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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
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
/// A backlog is retired once no frame is held and no discarded frame is still owed a writable stream; an [#admit] that
/// finds a retired backlog reports [Admission#RETIRED] and the caller starts from a fresh one. Not a general queue: one
/// stream, one drainer (the caller that got [Admission#HELD_FIRST]); a caller ON the stream's event loop, which writes
/// inline without admitting, is not a drainer and is excluded from that rule.
///
/// #3313 — owed keys. A discarded frame was never written, so whatever its holding started (the owner's backpressure
/// episode of the frame's lane, `K`) can only end when the stream is next seen writable, and no event reports that for
/// a stream with nothing left to write. So the backlog keeps the key of every frame [#drain] discards, and does not
/// retire while it owes one and the stream is unwritable: the drainer keeps passing, and the pass that finds the stream
/// writable settles every owed key and retires. A frame of an owed lane that is written later settles that key itself
/// (its write ends what its holding started), so the key is dropped then. [#discardAll] settles at once if the stream
/// is writable at that moment and otherwise drops the keys: it ends the backlog's drainer, so nothing would settle
/// them later (the owner's next write on the lane does, as for any write).
final class LaneBacklog<F, K> {
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
    private final Set<K> owed = new LinkedHashSet<>();
    private final Function<F, K> keyOf;
    private boolean retired;

    private LaneBacklog(Function<F, K> keyOf) {
        this.keyOf = keyOf;
    }

    /// A backlog whose discarded frames owe `keyOf(frame)` a writable stream (see the class doc).
    static <F, K> LaneBacklog<F, K> laneBacklog(Function<F, K> keyOf) {
        return new LaneBacklog<>(keyOf);
    }

    /// Holds `frame` behind the frames already held and runs `onHeld` for it under the lock, so a drain can never
    /// account for a frame before its holding was accounted.
    @Contract
    synchronized Admission admit(F frame, Consumer<F> onHeld) {
        if (retired) {
            return Admission.RETIRED;
        }
        // A backlog that owes a key has a drainer still passing: the frame joins behind it, never starts a second one.
        var first = held.isEmpty() && owed.isEmpty();

        held.addLast(frame);
        onHeld.accept(frame);

        return first
               ? Admission.HELD_FIRST
               : Admission.HELD_BEHIND;
    }

    /// One drain pass: writes held frames, head first, while the stream stays writable, then discards the head frames
    /// `expired` accepts (oldest first, so the head's age bounds the backlog; a frame the stream can take is written,
    /// never discarded), keeping each discarded frame's key as owed. Retires the backlog when no frame is left and it
    /// owes no key or the stream is writable, passing every owed key to `settle` first, and reports whether it did.
    @Contract
    synchronized boolean drain(Predicate<F> expired,
                               Consumer<F> discard,
                               BooleanSupplier writable,
                               Consumer<F> write,
                               Consumer<K> settle) {
        while (!held.isEmpty() && writable.getAsBoolean()) {
            writeHeld(held.pollFirst(), write);
        }

        while (!held.isEmpty() && expired.test(held.peekFirst())) {
            discardOwing(held.pollFirst(), discard);
        }

        retired = held.isEmpty() && (owed.isEmpty() || writable.getAsBoolean());
        if (retired) {
            settleOwed(settle);
        }

        return retired;
    }

    /// Gives up on every held frame, passing each to `discard`, and retires the backlog, so a writer still holding a
    /// reference to it after its owner dropped it gets [Admission#RETIRED] and cannot hold a frame nobody drains. The
    /// owed keys, these frames' included, are settled if the stream is `writable` now and dropped otherwise (class doc).
    @Contract
    synchronized void discardAll(Consumer<F> discard, BooleanSupplier writable, Consumer<K> settle) {
        held.forEach(frame -> discardOwing(frame, discard));
        held.clear();
        retired = true;
        if (writable.getAsBoolean()) {
            settleOwed(settle);
        }

        owed.clear();
    }

    @Contract
    private void writeHeld(F frame, Consumer<F> write) {
        owed.remove(keyOf.apply(frame));
        write.accept(frame);
    }

    @Contract
    private void discardOwing(F frame, Consumer<F> discard) {
        owed.add(keyOf.apply(frame));
        discard.accept(frame);
    }

    @Contract
    private void settleOwed(Consumer<K> settle) {
        owed.forEach(settle);
        owed.clear();
    }

    synchronized int size() {
        return held.size();
    }
}
