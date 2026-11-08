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
package org.pragmatica.http.server;

import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The streamed body's pull loop on its own ([BodyStream]), under a [ManualSink]: what a chunk's buffer is owed, whatever
/// the transport above it does.
class BodyStreamTest {
    private final ManualSink sink = new ManualSink();
    private final AtomicInteger holds = new AtomicInteger();
    private final AtomicInteger resumes = new AtomicInteger();
    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final BodyStream stream = BodyStream.bodyStream(channel.eventLoop(),
                                                            channel,
                                                            sink,
                                                            _ -> {},
                                                            BodyStream.Pacing.pacing(holds::incrementAndGet,
                                                                                     resumes::incrementAndGet,
                                                                                     timeSpan(0).millis(),
                                                                                     () -> {}));

    private static ByteBuf bytes(int size) {
        return Unpooled.buffer(size).writeZero(size);
    }

    @Test
    void offer_afterTheStreamEnded_releasesTheBufferAndHandsTheSinkNothing() {
        var late = bytes(10);

        stream.abort(BodySink.Ended.DISCONNECTED);
        stream.offer(late);

        assertThat(late.refCnt()).as("released, not queued behind an ended stream").isZero();
        assertThat(sink.chunkSizes()).isEmpty();
        assertThat(stream.holdsBuffer()).isFalse();
        channel.finishAndReleaseAll();
    }

    @Test
    void offer_ofAnEmptyBuffer_releasesItWithoutStoppingReading() {
        var empty = bytes(0);

        stream.offer(empty);

        assertThat(empty.refCnt()).isZero();
        assertThat(sink.chunkSizes()).isEmpty();
        assertThat(channel.config().isAutoRead()).as("nothing to wait for: reading stays on").isTrue();
        assertThat(holds.get()).isZero();
        channel.finishAndReleaseAll();
    }

    @Test
    void offer_ofACompositeBuffer_hasOneViewPerComponent_andReleasesTheBufferOnceThePartsAreAccepted() {
        var composite = Unpooled.compositeBuffer().addComponents(true, bytes(30), bytes(20));

        stream.offer(composite);

        assertThat(sink.chunkSizes()).as("the first view is with the sink").containsExactly(30);
        assertThat(composite.refCnt()).as("one reference for each of the two views").isEqualTo(2);
        sink.succeedNext();
        channel.runPendingTasks();
        assertThat(sink.chunkSizes()).containsExactly(30, 20);
        assertThat(composite.refCnt()).as("one view accepted, one reference left").isEqualTo(1);
        sink.succeedNext();
        channel.runPendingTasks();
        assertThat(composite.refCnt()).as("released with the last view").isZero();
        channel.finishAndReleaseAll();
    }

    @Test
    void offer_ofASingleComponentBuffer_isReleasedAfterItsOneAccept() {
        var single = bytes(30);

        stream.offer(single);

        assertThat(single.refCnt()).isEqualTo(1);
        sink.succeedNext();
        channel.runPendingTasks();
        assertThat(single.refCnt()).isZero();
        channel.finishAndReleaseAll();
    }
}
