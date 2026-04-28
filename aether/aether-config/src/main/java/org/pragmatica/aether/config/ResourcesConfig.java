// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record ResourcesConfig(String cpuRequest, String cpuLimit, String memoryRequest, String memoryLimit) {
    public static Result<ResourcesConfig> resourcesConfig(String cpuRequest,
                                                          String cpuLimit,
                                                          String memoryRequest,
                                                          String memoryLimit) {
        return success(new ResourcesConfig(cpuRequest, cpuLimit, memoryRequest, memoryLimit));
    }

    public static ResourcesConfig resourcesConfig() {
        return resourcesConfig("500m", "2", "1Gi", "2Gi").unwrap();
    }

    public static ResourcesConfig resourcesConfig(boolean minimal) {
        return minimal
              ? resourcesConfig("100m", "500m", "256Mi", "512Mi").unwrap()
              : resourcesConfig();
    }
}
