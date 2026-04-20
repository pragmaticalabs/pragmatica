// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

public record CanaryAnalysisConfig(ComparisonMode mode, int relativeThresholdPercent) {
    public enum ComparisonMode {
        ABSOLUTE_ONLY,
        RELATIVE_ONLY,
        RELATIVE_AND_ABSOLUTE
    }

    @SuppressWarnings("JBCT-VO-02") public static final CanaryAnalysisConfig DEFAULT = new CanaryAnalysisConfig(ComparisonMode.ABSOLUTE_ONLY,
                                                                                                                0);

    public static CanaryAnalysisConfig canaryAnalysisConfig(ComparisonMode mode, int relativeThresholdPercent) {
        return new CanaryAnalysisConfig(mode, relativeThresholdPercent);
    }

    public static CanaryAnalysisConfig relativeComparison(int relativeThresholdPercent) {
        return new CanaryAnalysisConfig(ComparisonMode.RELATIVE_AND_ABSOLUTE, relativeThresholdPercent);
    }
}
