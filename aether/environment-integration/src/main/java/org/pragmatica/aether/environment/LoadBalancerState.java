// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.List;
import java.util.Set;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record LoadBalancerState(Set<String> activeNodeIps, List<RouteChange> routes) {
    public static Result<LoadBalancerState> loadBalancerState(Set<String> activeNodeIps, List<RouteChange> routes) {
        return success(new LoadBalancerState(Set.copyOf(activeNodeIps), List.copyOf(routes)));
    }
}
