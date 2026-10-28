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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// [BodyGuard] on its own, on a frozen clock: once it has stopped, whatever its callers do next is inert, and a
/// stopped guard leaves nothing scheduled behind. [RequestGate] and the HTTP/3 handler both clear their reference after
/// stopping, so these paths are not reachable through them today; the guard keeps the property itself.
class BodyGuardTest {
    private final AtomicInteger releases = new AtomicInteger();
    private final List<BodyGuard.Refusal> refusals = new ArrayList<>();
    private final EmbeddedChannel channel = new EmbeddedChannel();

    private BodyGuard guard(long maxBytes) {
        channel.freezeTime();

        return BodyGuard.start(channel.eventLoop(),
                               maxBytes,
                               timeSpan(30).seconds(),
                               BodyRateLimit.bodyRateLimit(1024, timeSpan(30).seconds(), timeSpan(30).seconds()),
                               timeSpan(5).minutes(),
                               releases::incrementAndGet,
                               refusals::add);
    }

    @Test
    void stoppedTwice_releasesOnce() {
        var guard = guard(100);

        guard.stop();
        guard.stop();

        assertThat(releases.get()).isEqualTo(1);
    }

    @Test
    void afterStop_aDeclaredLengthOverTheLimit_isNotRefused() {
        var guard = guard(10);

        guard.stop();
        guard.refuseIfDeclaredTooLarge(100);

        assertThat(refusals).as("a refusal after the body is over is dropped").isEmpty();
    }

    @Test
    void afterStop_bytesAreNotCounted_andReportThatTheGuardIsDone() {
        var guard = guard(10);

        guard.stop();

        assertThat(guard.onBytes(1)).isFalse();
    }

    @Test
    void stop_leavesNoTimerScheduled() {
        var guard = guard(100);

        assertThat(channel.runScheduledPendingTasks()).as("control: idle, rate and duration timers are scheduled").isGreaterThanOrEqualTo(0);
        guard.stop();

        assertThat(channel.runScheduledPendingTasks()).as("-1: nothing left scheduled").isEqualTo(-1);
    }
}
