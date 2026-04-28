// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.Route.ParameterBuilder;


public final class ManagementRoutes {
    private ManagementRoutes() {}

    public static <R> ParameterBuilder<R> route(ManagementRoute mr) {
        return Route.<R>method(mr.method(), mr.prefix(), mr.name());
    }
}
