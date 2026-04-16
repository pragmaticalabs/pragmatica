// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.Route.ParameterBuilder;


/// Adapter that bridges the compile-time [ManagementRoute] registry with the
/// existing [Route] DSL used by every server-side `*Routes.java` file.
///
/// Usage is a straight textual replacement for the current pattern:
/// ```{@code
/// // before
/// Route.<DeploymentResponse>post("/api/deploy/promote")
///      .withPath(aString())
///      .toResult(this::promoteDeployment)
///      .asJson()
///
/// // after
/// ManagementRoutes.<DeploymentResponse>route(DEPLOY_PROMOTE)
///                 .withPath(aString())
///                 .toResult(this::promoteDeployment)
///                 .asJson()
/// }```
///
/// The enum value determines both the HTTP method and the static path prefix.
/// Path parameters are still declared explicitly in the caller (matching the enum's
/// `paramNames()` count) because the underlying [Route] DSL requires typed
/// [org.pragmatica.http.routing.PathParameter] instances — the adapter is a pure
/// syntactic bridge that eliminates string literals, not a re-implementation of the
/// routing DSL.
public final class ManagementRoutes {
    private ManagementRoutes() {}

    public static <R> ParameterBuilder<R> route(ManagementRoute mr) {
        return Route.<R>method(mr.method(), mr.prefix(), mr.name());
    }
}
