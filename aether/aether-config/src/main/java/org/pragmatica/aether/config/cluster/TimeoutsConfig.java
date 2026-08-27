// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.aether.config.ConfigKeyLive;

/// `drain` is #693: parsed and defaulted, but no code reads this accessor — every `.drain()` call site
/// in the repo resolves to an unrelated type (`NodeLifecycle`, `ReplicationBatcher`'s accumulator, CTM's
/// `drainNode`/`DrainCommandRegistry`), not this record. `@ConfigKeyLive`-suppressed rather than
/// deleted: #693 owns the fix, not #519's dead-surface guard. (`healthCheck`/`quorumFormation` are
/// genuinely live — read only from `aether/cli`, a module `node` doesn't depend on.)
public record TimeoutsConfig(String healthCheck,
                             String quorumFormation,
                             @ConfigKeyLive("#693: parsed, defaulted — never read; every .drain() call site resolves to an unrelated type") String drain) {
    public static TimeoutsConfig timeoutsConfig(String healthCheck, String quorumFormation, String drain) {
        return new TimeoutsConfig(healthCheck, quorumFormation, drain);
    }

    public static TimeoutsConfig defaultTimeoutsConfig() {
        return new TimeoutsConfig("300s", "600s", "120s");
    }
}
