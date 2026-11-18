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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.quic.LaneBacklog.Admission;
import org.pragmatica.messaging.StreamType;

import static org.assertj.core.api.Assertions.assertThat;

/// #3168: [LaneBacklog] in isolation: send order, the head-age discard, and retirement. #3313: the per-lane held
/// slots change with the queue on every mutation, before any callback sees the frame.
class LaneBacklogTest {
    private static final int CONSENSUS = StreamType.CONSENSUS.ordinal();
    private static final int DHT = StreamType.DHT.ordinal();

    @Test
    void admit_firstHeldFrameOwnsTheDrain_laterOnesJoinBehind_andTheDrainWritesHeadFirst() {
        var backlog = backlog();
        var counted = new ArrayList<Integer>();
        var written = new ArrayList<Integer>();

        assertThat(backlog.admit(1, counted::add)).isEqualTo(Admission.HELD_FIRST);
        assertThat(backlog.admit(2, counted::add)).isEqualTo(Admission.HELD_BEHIND);
        assertThat(backlog.admit(3, counted::add)).isEqualTo(Admission.HELD_BEHIND);

        var retired = backlog.drain(_ -> false, _ -> {}, () -> true, written::add);

        assertThat(counted).as("every held frame is accounted when held").containsExactly(1, 2, 3);
        assertThat(written).as("held frames are written head first").containsExactly(1, 2, 3);
        assertThat(retired).as("the pass that writes the last frame retires the backlog").isTrue();
        assertThat(backlog.admit(4, counted::add)).as("a retired backlog takes no frame").isEqualTo(Admission.RETIRED);
    }

    /// N06/L17 (#3179 item 15): giving up on every frame retires the backlog, so a writer that still holds a reference
    /// to it after its owner dropped it cannot hold a frame that nobody will ever drain.
    @Test
    void discardAll_passesEveryFrameToDiscard_andRetiresTheBacklog() {
        var backlog = backlog();
        var discarded = new ArrayList<Integer>();
        var counted = new AtomicInteger();

        backlog.admit(1, _ -> counted.incrementAndGet());
        backlog.admit(2, _ -> counted.incrementAndGet());
        backlog.discardAll(discarded::add);

        assertThat(discarded).containsExactly(1, 2);
        assertThat(backlog.admit(3, _ -> counted.incrementAndGet())).as("an orphaned backlog must refuse the frame")
                                                                     .isEqualTo(Admission.RETIRED);
        assertThat(backlog.size()).as("nothing is held after discardAll").isZero();
        assertThat(counted).as("a refused frame is never accounted as held").hasValue(2);
    }

    /// #3179 item 2: frames the stream takes are written, expired head frames are discarded oldest first, and the
    /// discard stops at the first frame that has not expired.
    @Test
    void drain_writesWhatTheStreamTakes_thenDiscardsOnlyTheExpiredHead() {
        var backlog = backlog();
        var written = new ArrayList<Integer>();
        var discarded = new ArrayList<Integer>();
        var room = new AtomicInteger(1);

        List.of(1, 2, 3, 4).forEach(frame -> backlog.admit(frame, _ -> {}));

        var retired = backlog.drain(frame -> frame <= 3,
                                    discarded::add,
                                    () -> room.get() > 0,
                                    frame -> takeOne(written, room, frame));

        assertThat(written).as("an expired frame the stream can take is written, not discarded").containsExactly(1);
        assertThat(discarded).as("expired head frames, oldest first").containsExactly(2, 3);
        assertThat(backlog.size()).as("an unexpired frame stays held").isEqualTo(1);
        assertThat(retired).isFalse();
    }

    /// #3313 slot accounting: frames of two lanes (even = CONSENSUS, odd = DHT) go through every mutation: admit, a drain
    /// that writes, a drain that discards expired frames, and discardAll. After each, every slot equals the number of
    /// that lane's frames still held. Inside every callback the slot already counts the frame in (admit) or out (write,
    /// discard): no callback can see, or leave, a slot that disagrees with the queue.
    @Test
    void heldOf_everyMutation_changesTheFramesLaneSlotWithTheQueue_beforeItsCallbackRuns() {
        var backlog = backlog();
        var seenInCallback = new ArrayList<String>();

        List.of(1, 2, 3, 4, 5).forEach(frame -> backlog.admit(frame, held -> seenInCallback.add("admit " + held + " " + slots(backlog))));
        assertSlots(backlog, 2, 3);
        assertThat(seenInCallback).as("the admitted frame is already in its slot when onHeld runs")
                                  .containsExactly("admit 1 0/1", "admit 2 1/1", "admit 3 1/2", "admit 4 2/2", "admit 5 2/3");

        var room = new AtomicInteger(2);
        var _ = backlog.drain(_ -> false,
                              _ -> {},
                              () -> room.get() > 0,
                              frame -> recordThenTake(seenInCallback, "write", frame, backlog, room));
        assertSlots(backlog, 1, 2);

        var _ = backlog.drain(frame -> frame == 3,
                              frame -> seenInCallback.add("discard " + frame + " " + slots(backlog)),
                              () -> false,
                              _ -> {});
        assertSlots(backlog, 1, 1);

        backlog.discardAll(frame -> seenInCallback.add("discardAll " + frame + " " + slots(backlog)));
        assertSlots(backlog, 0, 0);
        assertThat(seenInCallback.subList(5, seenInCallback.size()))
            .as("a frame is out of its slot before write or discard sees it; discardAll empties every slot first")
            .containsExactly("write 1 2/2", "write 2 1/2", "discard 3 1/1", "discardAll 4 0/0", "discardAll 5 0/0");
    }

    /// #3313 (F1's second half): a `discard` that throws for one frame of discardAll leaves no frame held, every slot at
    /// zero, and every OTHER frame still discarded, in take order. discardAll itself returns normally.
    @Test
    void discardAll_discardThrowsForOneFrame_everySlotIsZero_andTheOtherFramesAreStillDiscarded() {
        var backlog = backlog();
        var discarded = new ArrayList<Integer>();

        List.of(1, 2, 3, 4).forEach(frame -> backlog.admit(frame, _ -> {}));
        backlog.discardAll(frame -> discardOrThrowOnTwo(discarded, frame));

        assertThat(discarded).as("every other frame is discarded, head first").containsExactly(1, 3, 4);
        assertThat(backlog.size()).as("nothing is held").isZero();
        assertSlots(backlog, 0, 0);
        assertThat(backlog.admit(5, _ -> {})).as("the backlog retired").isEqualTo(Admission.RETIRED);
        assertSlots(backlog, 0, 0);
    }

    @Test
    void peer_isThePeerTheBacklogWasBuiltFor() {
        var peer = NodeId.randomNodeId();

        assertThat(new LaneBacklog<Integer>(peer, LaneBacklogTest::laneOf).peer()).isEqualTo(peer);
    }

    private static void discardOrThrowOnTwo(List<Integer> discarded, Integer frame) {
        if (frame == 2) {
            throw new IllegalStateException("discard threw for frame 2");
        }

        discarded.add(frame);
    }

    private static void recordThenTake(List<String> seen, String what, Integer frame, LaneBacklog<Integer> backlog, AtomicInteger room) {
        seen.add(what + " " + frame + " " + slots(backlog));
        room.decrementAndGet();
    }

    private static void assertSlots(LaneBacklog<Integer> backlog, int consensus, int dht) {
        assertThat(backlog.heldOf(CONSENSUS)).as("CONSENSUS slot").isEqualTo(consensus);
        assertThat(backlog.heldOf(DHT)).as("DHT slot").isEqualTo(dht);
        assertThat(backlog.size()).as("the queue holds what the slots count").isEqualTo(consensus + dht);
    }

    /// `CONSENSUS/DHT` slot values.
    private static String slots(LaneBacklog<Integer> backlog) {
        return backlog.heldOf(CONSENSUS) + "/" + backlog.heldOf(DHT);
    }

    private static LaneBacklog<Integer> backlog() {
        return new LaneBacklog<>(NodeId.randomNodeId(), LaneBacklogTest::laneOf);
    }

    private static int laneOf(Integer frame) {
        return frame % 2 == 0
               ? CONSENSUS
               : DHT;
    }

    private static void takeOne(List<Integer> written, AtomicInteger room, Integer frame) {
        written.add(frame);
        room.decrementAndGet();
    }
}
