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
import org.pragmatica.consensus.net.quic.LaneBacklog.Admission;

import static org.assertj.core.api.Assertions.assertThat;

/// #3168: [LaneBacklog] in isolation: send order, the head-age discard, and retirement.
class LaneBacklogTest {

    @Test
    void admit_firstHeldFrameOwnsTheDrain_laterOnesJoinBehind_andTheDrainWritesHeadFirst() {
        var backlog = LaneBacklog.<Integer, Integer>laneBacklog(frame -> frame);
        var counted = new ArrayList<Integer>();
        var written = new ArrayList<Integer>();

        assertThat(backlog.admit(1, counted::add)).isEqualTo(Admission.HELD_FIRST);
        assertThat(backlog.admit(2, counted::add)).isEqualTo(Admission.HELD_BEHIND);
        assertThat(backlog.admit(3, counted::add)).isEqualTo(Admission.HELD_BEHIND);

        var retired = backlog.drain(_ -> false, _ -> {}, () -> true, written::add, _ -> {});

        assertThat(counted).as("every held frame is accounted when held").containsExactly(1, 2, 3);
        assertThat(written).as("held frames are written head first").containsExactly(1, 2, 3);
        assertThat(retired).as("the pass that writes the last frame retires the backlog").isTrue();
        assertThat(backlog.admit(4, counted::add)).as("a retired backlog takes no frame").isEqualTo(Admission.RETIRED);
    }

    /// N06/L17 (#3179 item 15): giving up on every frame retires the backlog, so a writer that still holds a reference
    /// to it after its owner dropped it cannot hold a frame that nobody will ever drain.
    @Test
    void discardAll_passesEveryFrameToDiscard_andRetiresTheBacklog() {
        var backlog = LaneBacklog.<Integer, Integer>laneBacklog(frame -> frame);
        var discarded = new ArrayList<Integer>();
        var counted = new AtomicInteger();

        backlog.admit(1, _ -> counted.incrementAndGet());
        backlog.admit(2, _ -> counted.incrementAndGet());
        backlog.discardAll(discarded::add, () -> false, _ -> {});

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
        var backlog = LaneBacklog.<Integer, Integer>laneBacklog(frame -> frame);
        var written = new ArrayList<Integer>();
        var discarded = new ArrayList<Integer>();
        var room = new AtomicInteger(1);

        List.of(1, 2, 3, 4).forEach(frame -> backlog.admit(frame, _ -> {}));

        var retired = backlog.drain(frame -> frame <= 3,
                                    discarded::add,
                                    () -> room.get() > 0,
                                    frame -> takeOne(written, room, frame),
                                    _ -> {});

        assertThat(written).as("an expired frame the stream can take is written, not discarded").containsExactly(1);
        assertThat(discarded).as("expired head frames, oldest first").containsExactly(2, 3);
        assertThat(backlog.size()).as("an unexpired frame stays held").isEqualTo(1);
        assertThat(retired).isFalse();
    }

    // --- #3313: a discarded frame's key is owed a writable stream ---

    /// Frames discarded while the stream is unwritable leave their keys owed: the backlog does not retire, a frame sent
    /// meanwhile joins behind the running drainer, and the pass that finds the stream writable settles each owed key
    /// once, except the key a later written frame already settled, and retires.
    @Test
    void drain_discardsOnAnUnwritableStream_owesTheKeysUntilAPassFindsItWritable() {
        var backlog = LaneBacklog.<Integer, String>laneBacklog(frame -> frame % 2 == 0
                                                                        ? "even"
                                                                        : "odd");
        var writable = new AtomicInteger(0);
        var written = new ArrayList<Integer>();
        var settled = new ArrayList<String>();

        List.of(1, 2).forEach(frame -> backlog.admit(frame, _ -> {}));

        var first = backlog.drain(_ -> true, _ -> {}, () -> writable.get() > 0, written::add, settled::add);

        assertThat(first).as("keys owed on an unwritable stream: not retired").isFalse();
        assertThat(settled).isEmpty();
        assertThat(backlog.admit(4, _ -> {})).as("the drainer still runs: a new frame joins behind it")
                                             .isEqualTo(Admission.HELD_BEHIND);

        writable.set(1);

        var second = backlog.drain(_ -> false, _ -> {}, () -> writable.get() > 0, written::add, settled::add);

        assertThat(written).containsExactly(4);
        assertThat(settled).as("the written even frame settled its own key; the odd key is settled here")
                           .containsExactly("odd");
        assertThat(second).isTrue();
        assertThat(backlog.admit(5, _ -> {})).isEqualTo(Admission.RETIRED);
    }

    @Test
    void drain_streamTurnsWritableMidPass_settlesTheOwedKeysAndRetiresInThatPass() {
        var backlog = LaneBacklog.<Integer, Integer>laneBacklog(frame -> frame);
        var checks = new AtomicInteger();
        var settled = new ArrayList<Integer>();

        backlog.admit(1, _ -> {});

        // unwritable for the write loop, writable for the retirement check: the stream turned writable mid-pass
        var retired = backlog.drain(_ -> true, _ -> {}, () -> checks.incrementAndGet() > 1, _ -> {}, settled::add);

        assertThat(retired).isTrue();
        assertThat(settled).containsExactly(1);
    }

    @Test
    void discardAll_onAWritableStream_settlesEveryOwedKey_onAnUnwritableOne_dropsThem() {
        var settledWritable = new ArrayList<Integer>();
        var settledUnwritable = new ArrayList<Integer>();
        var writableBacklog = LaneBacklog.<Integer, Integer>laneBacklog(frame -> frame);
        var unwritableBacklog = LaneBacklog.<Integer, Integer>laneBacklog(frame -> frame);

        List.of(1, 2).forEach(frame -> writableBacklog.admit(frame, _ -> {}));
        List.of(1, 2).forEach(frame -> unwritableBacklog.admit(frame, _ -> {}));

        writableBacklog.discardAll(_ -> {}, () -> true, settledWritable::add);
        unwritableBacklog.discardAll(_ -> {}, () -> false, settledUnwritable::add);

        assertThat(settledWritable).containsExactly(1, 2);
        assertThat(settledUnwritable).isEmpty();
        assertThat(unwritableBacklog.admit(3, _ -> {})).isEqualTo(Admission.RETIRED);
    }

    private static void takeOne(List<Integer> written, AtomicInteger room, Integer frame) {
        written.add(frame);
        room.decrementAndGet();
    }
}
