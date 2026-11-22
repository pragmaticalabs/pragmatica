// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Locale;

import org.pragmatica.lang.Verify;


public enum DeploymentAtomicity {
    BEST_EFFORT,
    ALL_OR_NOTHING;
    public static DeploymentAtomicity parse(String value) {
        if (!Verify.Is.present(value)) {
            return ALL_OR_NOTHING;
        }

        return switch (value.trim()
                            .toLowerCase(Locale.ROOT)
                            .replace("-", "_")) {
            case "best_effort" -> BEST_EFFORT;
            default -> ALL_OR_NOTHING;
        };
    }
}
