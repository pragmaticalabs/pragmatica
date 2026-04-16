// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

public record TimeoutsConfig(String healthCheck, String quorumFormation, String drain) {
    public static TimeoutsConfig timeoutsConfig(String healthCheck, String quorumFormation, String drain) {
        return new TimeoutsConfig(healthCheck, quorumFormation, drain);
    }

    public static TimeoutsConfig defaultTimeoutsConfig() {
        return new TimeoutsConfig("300s", "600s", "120s");
    }
}
