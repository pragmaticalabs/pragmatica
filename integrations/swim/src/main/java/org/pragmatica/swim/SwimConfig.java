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

import java.time.Duration;

import org.pragmatica.serialization.Codec;

/// Configuration for the SWIM protocol.
///
/// @param period          interval between protocol ticks (probe rounds)
/// @param probeTimeout    time to wait for an Ack before escalating to indirect probes
/// @param indirectProbes  number of random members to use for indirect probing (PingReq)
/// @param suspectTimeout  time a member stays in SUSPECT before transitioning to FAULTY
/// @param maxPiggyback    maximum number of membership updates piggybacked per message
@Codec
public record SwimConfig(Duration period,
                         Duration probeTimeout,
                         int indirectProbes,
                         Duration suspectTimeout,
                         int maxPiggyback) {

    /// Factory with all parameters.
    public static SwimConfig swimConfig(Duration period,
                                        Duration probeTimeout,
                                        int indirectProbes,
                                        Duration suspectTimeout,
                                        int maxPiggyback) {
        return new SwimConfig(period, probeTimeout, indirectProbes, suspectTimeout, maxPiggyback);
    }

    /// Factory with sensible defaults.
    public static SwimConfig swimConfig() {
        return new SwimConfig(
            Duration.ofSeconds(1),
            Duration.ofMillis(500),
            3,
            Duration.ofSeconds(5),
            8
        );
    }
}
