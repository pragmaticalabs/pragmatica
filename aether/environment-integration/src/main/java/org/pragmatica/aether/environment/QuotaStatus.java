// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

public record QuotaStatus(boolean sufficient, int requested, int availableInRegion, String limitingResource) {
    public static QuotaStatus quotaStatus(boolean sufficient,
                                          int requested,
                                          int availableInRegion,
                                          String limitingResource) {
        return new QuotaStatus(sufficient, requested, availableInRegion, limitingResource);
    }

    public static QuotaStatus unknown(int requested) {
        return new QuotaStatus(true, requested, -1, "unknown");
    }
}
