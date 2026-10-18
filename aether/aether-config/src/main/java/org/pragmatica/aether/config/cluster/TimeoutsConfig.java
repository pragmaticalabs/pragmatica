// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

public record TimeoutsConfig(String healthCheck, String quorumFormation) {
    public static TimeoutsConfig timeoutsConfig(String healthCheck, String quorumFormation) {
        return new TimeoutsConfig(healthCheck, quorumFormation);
    }

    public static TimeoutsConfig defaultTimeoutsConfig() {
        return new TimeoutsConfig("300s", "600s");
    }
}
