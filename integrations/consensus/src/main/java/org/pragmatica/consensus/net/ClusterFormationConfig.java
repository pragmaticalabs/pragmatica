/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pragmatica.consensus.net;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Cluster formation timing — network-layer timeouts governing quorum establishment and churn absorption.
///
/// @param stabilizationWindow   Duration the topology must remain unchanged before quorum is established.
///                              Absorbs bursts of peer-add/remove events that arrive during startup.
/// @param postEstablishGrace    After initial quorum establishment, REMOVE events for single peers that
///                              still leave quorum intact are buffered for this long. Reconnect within the
///                              window cancels the removal; otherwise the peer is dropped from topology.
/// @param quorumLossHysteresis  After quorum drops below threshold, wait this long before firing
///                              QUORUM_LOST — a reconnect within the window keeps quorum alive.
public record ClusterFormationConfig(TimeSpan stabilizationWindow,
                                     TimeSpan postEstablishGrace,
                                     TimeSpan quorumLossHysteresis) {
    public static final TimeSpan DEFAULT_STABILIZATION_WINDOW = timeSpan(5).seconds();
    public static final TimeSpan DEFAULT_POST_ESTABLISH_GRACE = timeSpan(5).seconds();
    public static final TimeSpan DEFAULT_QUORUM_LOSS_HYSTERESIS = timeSpan(5).seconds();

    public static ClusterFormationConfig defaults() {
        return new ClusterFormationConfig(DEFAULT_STABILIZATION_WINDOW,
                                          DEFAULT_POST_ESTABLISH_GRACE,
                                          DEFAULT_QUORUM_LOSS_HYSTERESIS);
    }

    public static Result<ClusterFormationConfig> clusterFormationConfig(TimeSpan stabilizationWindow,
                                                                        TimeSpan postEstablishGrace,
                                                                        TimeSpan quorumLossHysteresis) {
        return Result.all(validatePositive(stabilizationWindow, "stabilizationWindow"),
                          validatePositive(postEstablishGrace, "postEstablishGrace"),
                          validatePositive(quorumLossHysteresis, "quorumLossHysteresis")).map(ClusterFormationConfig::new);
    }

    private static Result<TimeSpan> validatePositive(TimeSpan value, String name) {
        return Option.option(value)
                     .filter(v -> v.nanos() > 0)
                     .toResult(ConfigError.invalidTimeSpan(name));
    }

    public sealed interface ConfigError extends Cause {
        record InvalidTimeSpan(String field) implements ConfigError {
            @Override
            public String message() {
                return field + " must be a positive duration";
            }
        }

        static ConfigError invalidTimeSpan(String field) {
            return new InvalidTimeSpan(field);
        }
    }
}
