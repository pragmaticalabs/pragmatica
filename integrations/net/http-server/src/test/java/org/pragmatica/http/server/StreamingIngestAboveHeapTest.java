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

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;


/// A streamed request body four times bigger than the heap of the JVM that serves it, over HTTP/1.1 and over HTTP/3.
///
/// The server and its client run in a child JVM with `-Xmx128m` and `-XX:MaxDirectMemorySize=128m`; the body is 512 MiB,
/// so a server that gathers the body, or keeps reading while chunks wait for the sink, cannot finish. The sink hashes what
/// it is handed, and its SHA-256 is compared with the sender's over non-repeating bytes. The test fails on the child's
/// exit code, which is 0 only when the sink completed.
///
/// Mutation: keep every received chunk in `BodyStream` instead of releasing it (an aggregating gate). The child runs out
/// of direct memory, the sink never completes, and this test fails on the exit code.
@Timeout(value = 6, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class StreamingIngestAboveHeapTest {
    private static final long SIZE = 512L * 1024 * 1024;

    @Test
    void overHttp1_aBodyFourTimesTheHeap_streamsIntoTheSink_inAChildJvmWith128MiB() throws Exception {
        streamsAboveHeap("h1");
    }

    @Test
    void overHttp3_aBodyFourTimesTheHeap_streamsIntoTheSink_inAChildJvmWith128MiB() throws Exception {
        streamsAboveHeap("h3");
    }

    private static void streamsAboveHeap(String protocol) throws Exception {
        var outcome = StreamingIngestRun.run(protocol, SIZE, 5);
        var diagnostics = "child output:\n" + outcome.output();

        assertThat(outcome.exitCode()).as(diagnostics).isZero();
        assertThat(Long.parseLong(outcome.get("MAX_HEAP_MB"))).as("the child really ran with a small heap: " + diagnostics)
                                                              .isLessThanOrEqualTo(StreamingIngestRun.HEAP_MB);
        assertThat(SIZE).as("the body must be larger than the heap, or this test proves nothing")
                        .isGreaterThan(4L * StreamingIngestRun.HEAP_MB * 1024 * 1024 - 1);
        assertThat(outcome.get("END")).as(diagnostics).isEqualTo("complete");
        assertThat(outcome.get("SINK_BYTES")).as(diagnostics).isEqualTo(Long.toString(SIZE));
        assertThat(outcome.get("SINK_SHA")).as(diagnostics).isEqualTo(outcome.get("SENT_SHA"));
        assertThat(outcome.get("STATUS")).as(diagnostics).contains("200");
    }
}
