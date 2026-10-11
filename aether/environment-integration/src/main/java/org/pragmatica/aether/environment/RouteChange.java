// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment;

import java.util.Set;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record RouteChange(String httpMethod, String pathPrefix, Set<String> nodeIps) {
    public static Result<RouteChange> routeChange(String httpMethod, String pathPrefix, Set<String> nodeIps) {
        return success(new RouteChange(httpMethod, pathPrefix, Set.copyOf(nodeIps)));
    }
}
