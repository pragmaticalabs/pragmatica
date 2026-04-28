// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;

import static org.pragmatica.lang.Result.success;


public record InstanceInfo(InstanceId id,
                           InstanceStatus status,
                           List<String> addresses,
                           InstanceType type,
                           Map<String, String> tags) {
    public static Result<InstanceInfo> instanceInfo(InstanceId id,
                                                    InstanceStatus status,
                                                    List<String> addresses,
                                                    InstanceType type,
                                                    Map<String, String> tags) {
        return success(new InstanceInfo(id, status, List.copyOf(addresses), type, Map.copyOf(tags)));
    }

    public static Result<InstanceInfo> instanceInfo(InstanceId id,
                                                    InstanceStatus status,
                                                    List<String> addresses,
                                                    InstanceType type) {
        return instanceInfo(id, status, addresses, type, Map.of());
    }
}
