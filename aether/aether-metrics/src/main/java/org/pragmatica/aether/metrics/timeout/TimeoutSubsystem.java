// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.timeout;

/// Enumerates the 13 subsystem timeout groups defined in
/// `org.pragmatica.aether.config.TimeoutsConfig`. Used by
/// `TimeoutMetricsRegistry` to count timeout-fired events per subsystem.
/// Wire identifiers (`id()`) are lowercase to match the TimeoutsConfig
/// TOML section names operators see in `aether.toml` (`[timeouts.consensus]`,
/// `[timeouts.dht]`, etc.). The enum membership MUST stay in lockstep with
/// `TimeoutsConfig` — adding a new subsystem there requires a new entry here
/// and a counter increment in the subsystem's timeout-fire path.
///
/// MVP NOTE (P-NEW-A, 2026-05-21): the registry + endpoint + CLI ship in this
/// session. Per-subsystem `recordTimeout(...)` instrumentation hooks at each
/// of the 13 subsystem timeout-fire sites are a follow-up — tracked as TODOs
/// inline at the subsystem timeout paths. Counters start at 0 and remain 0
/// until those hooks land.
public enum TimeoutSubsystem {
    INVOCATION("invocation"),
    FORWARDING("forwarding"),
    DEPLOYMENT("deployment"),
    ROLLING_UPDATE("rollingUpdate"),
    CLUSTER("cluster"),
    CONSENSUS("consensus"),
    ELECTION("election"),
    SWIM("swim"),
    OBSERVABILITY("observability"),
    DHT("dht"),
    WORKER("worker"),
    SECURITY("security"),
    REPOSITORY("repository"),
    SCALING("scaling");
    private final String id;
    TimeoutSubsystem(String id) {
        this.id = id;
    }
    public String id() {
        return id;
    }
}
