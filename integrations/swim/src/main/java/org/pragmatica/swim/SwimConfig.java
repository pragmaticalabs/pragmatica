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

package org.pragmatica.swim;

import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.Codec;
import org.pragmatica.serialization.CodecFor;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Configuration for the SWIM protocol.
///
/// @param period          interval between protocol ticks (probe rounds)
/// @param probeTimeout    time to wait for an Ack before escalating to indirect probes
/// @param indirectProbes  number of random members to use for indirect probing (PingReq)
/// @param suspectTimeout  time a member stays in SUSPECT before transitioning to FAULTY
/// @param maxPiggyback    maximum number of membership updates piggybacked per message
/// @param revivalGrace    grace period after markAlive — don't probe recently-revived members
/// @param startupDelay    cooldown after quorum before first probe — allows all TCP connections to establish
@Codec
@CodecFor({TimeSpan.class, java.net.InetSocketAddress.class})
public record SwimConfig(TimeSpan period,
                         TimeSpan probeTimeout,
                         int indirectProbes,
                         TimeSpan suspectTimeout,
                         int maxPiggyback,
                         TimeSpan revivalGrace,
                         TimeSpan startupDelay) {

    /// Default configuration — suitable for Docker and containerized environments.
    public static final SwimConfig DEFAULT = swimConfig(
        timeSpan(1).seconds(),
        timeSpan(800).millis(),
        3,
        timeSpan(15).seconds(),
        8,
        timeSpan(5).seconds(),
        timeSpan(10).seconds()
    );

    /// Factory with all parameters.
    public static SwimConfig swimConfig(TimeSpan period,
                                        TimeSpan probeTimeout,
                                        int indirectProbes,
                                        TimeSpan suspectTimeout,
                                        int maxPiggyback,
                                        TimeSpan revivalGrace,
                                        TimeSpan startupDelay) {
        return new SwimConfig(period, probeTimeout, indirectProbes, suspectTimeout,
                              maxPiggyback, revivalGrace, startupDelay);
    }

    /// Factory with defaults for revivalGrace and startupDelay.
    public static SwimConfig swimConfig(TimeSpan period,
                                        TimeSpan probeTimeout,
                                        int indirectProbes,
                                        TimeSpan suspectTimeout,
                                        int maxPiggyback) {
        return new SwimConfig(period, probeTimeout, indirectProbes, suspectTimeout,
                              maxPiggyback, timeSpan(5).seconds(), timeSpan(10).seconds());
    }

    /// Factory with defaults.
    public static SwimConfig swimConfig() {
        return DEFAULT;
    }
}
