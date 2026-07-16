// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

/// Per-community sizing policy (worker-membership-spec §3.3 / §4.1 / §7) read by the cluster
/// deployment leader. Two knobs that travel together:
///
///   - `targetSize`: the desired per-community worker count stamped on a minted FORMING
///     [org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue] at WORKER role-assignment
///     time. Consumed by the Phase-C community-growth comparator; until then it is the declared
///     per-source target carried on the committed community record.
///   - `viabilityFloor`: the minimum OBSERVED live membership for a community to be VIABLE = the DHT
///     replication factor. The per-community FSM promotes FORMING/DEGRADED → ACTIVE at or above the
///     floor and demotes ACTIVE → DEGRADED below it. An override below 3 weakens redundancy, so the
///     default stays 3 — lower it only for small test/dev topologies.
///
/// `DEFAULT` preserves the historical hardcoded values (target 100, floor 3) so existing call sites
/// keep their behaviour unchanged. Non-positive inputs normalize to the defaults in the compact
/// constructor (mirrors [WorkerConfig]).
public record CommunitySizing(int targetSize, int viabilityFloor) {
    public static final int DEFAULT_TARGET_SIZE = 100;
    public static final int DEFAULT_VIABILITY_FLOOR = 3;
    public static final CommunitySizing DEFAULT = new CommunitySizing(DEFAULT_TARGET_SIZE, DEFAULT_VIABILITY_FLOOR);

    public CommunitySizing {
        if (targetSize < 1) {
            targetSize = DEFAULT_TARGET_SIZE;
        }

        if (viabilityFloor < 1) {
            viabilityFloor = DEFAULT_VIABILITY_FLOOR;
        }
    }

    public static CommunitySizing communitySizing(int targetSize, int viabilityFloor) {
        return new CommunitySizing(targetSize, viabilityFloor);
    }
}
