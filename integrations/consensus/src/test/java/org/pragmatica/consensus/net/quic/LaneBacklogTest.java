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
        var backlog = new LaneBacklog<Integer>();
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
        var backlog = new LaneBacklog<Integer>();
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
        var backlog = new LaneBacklog<Integer>();
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

    private static void takeOne(List<Integer> written, AtomicInteger room, Integer frame) {
        written.add(frame);
        room.decrementAndGet();
    }
}
