// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge.load;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

import static org.pragmatica.lang.Result.success;


public record LoadConfig(List<LoadTarget> targets) {
    private static final Cause EMPTY_CONFIG = Causes.cause("Load config must have at least one target");

    public static Result<LoadConfig> loadConfig(List<LoadTarget> targets) {
        return success(targets).filter(EMPTY_CONFIG,
                                       list -> !list.isEmpty())
                      .map(List::copyOf)
                      .map(LoadConfig::new);
    }

    public static Result<LoadConfig> loadConfig() {
        return success(new LoadConfig(List.of()));
    }

    public boolean isEmpty() {
        return targets.isEmpty();
    }

    public int totalRequestsPerSecond() {
        return targets.stream().mapToInt(LoadConfig::targetRequestsPerSecond)
                             .sum();
    }

    private static int targetRequestsPerSecond(LoadTarget t) {
        return t.rate().requestsPerSecond();
    }

    public static Result<LoadConfig> loadConfig(LoadConfig config, double multiplier) {
        var scaledTargets = config.targets().stream()
                                          .map(t -> t.withScaledRate(multiplier))
                                          .toList();
        return success(new LoadConfig(scaledTargets));
    }
}
