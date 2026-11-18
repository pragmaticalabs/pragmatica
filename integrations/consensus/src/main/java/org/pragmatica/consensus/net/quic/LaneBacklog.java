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
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.StreamType;


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
///
/// #3313 slot contract: the backlog belongs to one peer ([#peer]) and keeps how many of its held frames belong to each
/// lane, one slot per [StreamType] ([#heldOf]). A frame enters and leaves the queue only through the two private
/// primitives [#put] and [#take], each of which changes the queue and the frame's slot together, with nothing that can
/// throw between the two changes (the lane index is computed before the queue change). Every callback ([#admit]'s
/// `onHeld`, [#drain]'s `write` and `discard`, [#discardAll]'s `discard`) runs after the slot already agrees with the
/// queue, so no callback, whatever it throws, can leave a slot wrong. Slots are read lock-free (an
/// [AtomicIntegerArray]): a reader holding another lock, such as the transport's episode monitor, never waits for this
/// backlog's lock. Each read is linearizable; reads of two slots, or of a slot and the queue, are not one snapshot.
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
    private final AtomicIntegerArray heldPerLane = new AtomicIntegerArray(StreamType.values().length);
    private final NodeId peer;
    private final ToIntFunction<F> laneIndex;
    private boolean retired;

    /// `laneIndex`: the [StreamType#ordinal] of a frame's OWN lane (a DHT frame can be held on the CONSENSUS stream).
    LaneBacklog(NodeId peer, ToIntFunction<F> laneIndex) {
        this.peer = peer;
        this.laneIndex = laneIndex;
    }

    /// The peer every frame of this backlog is sent to.
    NodeId peer() {
        return peer;
    }

    /// How many held frames belong to the lane with ordinal `laneIndex`. Lock-free; see the slot contract above.
    int heldOf(int laneIndex) {
        return heldPerLane.get(laneIndex);
    }

    /// Holds `frame` behind the frames already held and runs `onHeld` for it under the lock, so a drain can never
    /// account for a frame before its holding was accounted.
    @Contract
    synchronized Admission admit(F frame, Consumer<F> onHeld) {
        if (retired) {
            return Admission.RETIRED;
        }

        var first = held.isEmpty();

        put(frame);
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
            write.accept(take());
        }

        while (!held.isEmpty() && expired.test(held.peekFirst())) {
            discard.accept(take());
        }

        retired = held.isEmpty();

        return retired;
    }

    /// Gives up on every held frame, head first, passing each to `discard`, and retires the backlog, so a writer still
    /// holding a reference to it after its owner dropped it gets [Admission#RETIRED] and cannot hold a frame nobody
    /// drains. #3313: every frame is taken out first, and only then is each passed to `discard`, in take order (#3168:
    /// head first), so every slot is zero before any callback runs.
    ///
    /// A `discard` that throws is contained to its own frame, recovery FER (degrade forward): every other taken frame is
    /// still passed to `discard`, so none is left counted nowhere. The failing frame's own accounting is what its
    /// callback finished before the throw; the transport's callback lifts its own metric and log, so a throw reaching
    /// here is a defect of the callback, and this containment is defence in depth that keeps it to one frame.
    @Contract
    synchronized void discardAll(Consumer<F> discard) {
        retired = true;
        var taken = new ArrayList<F>(held.size());

        while (!held.isEmpty()) {
            taken.add(take());
        }

        taken.forEach(frame -> discardOne(discard, frame));
    }

    @Contract
    private static <F> void discardOne(Consumer<F> discard, F frame) {
        var _ = Result.lift(() -> discard.accept(frame));
    }

    /// The one way a frame enters the queue: the queue and the frame's slot change together.
    private void put(F frame) {
        var lane = laneIndex.applyAsInt(frame);

        held.addLast(frame);
        heldPerLane.incrementAndGet(lane);
    }

    /// The one way a frame leaves the queue (callers check it is not empty): the queue and the frame's slot change
    /// together, before the frame is handed to any callback.
    private F take() {
        var lane = laneIndex.applyAsInt(held.getFirst());
        var frame = held.removeFirst();

        heldPerLane.decrementAndGet(lane);

        return frame;
    }

    synchronized int size() {
        return held.size();
    }
}
