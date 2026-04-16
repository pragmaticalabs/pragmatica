// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.Set;

import static org.pragmatica.lang.Result.success;


/// Describes a single route mapping: an HTTP method + path prefix → set of node IPs that serve it.
public record RouteChange(String httpMethod, String pathPrefix, Set<String> nodeIps) {
    public static Result<RouteChange> routeChange(String httpMethod, String pathPrefix, Set<String> nodeIps) {
        return success(new RouteChange(httpMethod, pathPrefix, Set.copyOf(nodeIps)));
    }
}
