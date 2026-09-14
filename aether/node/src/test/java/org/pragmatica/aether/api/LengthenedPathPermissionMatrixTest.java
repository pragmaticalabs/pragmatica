// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.RoutePermission;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.http.HttpMethod;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// #1101 — privilege escalation by appending a path segment. `resolvePermission` looks up the exact
/// route table and, on no exact match, falls back to the prefix registry — which for
/// `DELETE /api/v1/config/nodes/<id>/<key>/junk` resolved OPERATOR while the exact route
/// `CONFIG_NODE_DELETE` requires ADMIN; the router then dispatched the over-length path to that
/// route's handler anyway. This matrix walks EVERY mutating exact route, lengthens its assembled
/// path in the two shapes the verifier found (a trailing junk segment, and junk inserted before the
/// last segment), and requires the resolved permission to be at least as strict as the route's own.
/// A single `lengthened < own` cell is the escalation.
class LengthenedPathPermissionMatrixTest {
    private static boolean isMutation(ManagementRoute route) {
        return route.method() != HttpMethod.GET && route.method() != HttpMethod.HEAD && route.method() != HttpMethod.OPTIONS;
    }

    private static List<String> values(ManagementRoute route) {
        return IntStream.range(0, route.paramCount()).mapToObj(i -> "v" + i).toList();
    }

    private static boolean weaker(RoutePermission lengthened, RoutePermission own) {
        // ADMIN(0) outranks OPERATOR(1) outranks VIEWER(2): a larger ordinal is a weaker requirement.
        return lengthened.minimumRole().ordinal() > own.minimumRole().ordinal();
    }

    private static List<String> lengthenings(String path) {
        var lastSlash = path.lastIndexOf('/');

        return List.of(path + "/junk", path.substring(0, lastSlash) + "/junk" + path.substring(lastSlash));
    }

    @Test
    void lengthenedPath_neverResolvesWeakerThanTheExactRouteItExtends() {
        var escalations = new ArrayList<String>();

        for (var route : ManagementRoute.values()) {
            if (!isMutation(route)) {
                continue;
            }

            var own = ManagementRoutePermissions.permissionFor(route);
            var path = route.assemble(values(route)).unwrap();

            for (var lengthened : lengthenings(path)) {
                var resolved = ManagementServerImpl.resolvePermission(route.method().name(), lengthened);

                if (weaker(resolved, own)) {
                    escalations.add(route.name() + " own=" + own.minimumRole() + " lengthened=" + resolved.minimumRole() + " via " + lengthened);
                }
            }
        }

        assertThat(escalations).as("every cell where a lengthened path authorises WEAKER than the route it extends")
                               .isEmpty();
    }

    /// The ticket's own reproduction, kept as a named cell so the matrix failure has a face.
    @Test
    void configNodeDelete_withAJunkSegment_stillRequiresAdmin() {
        var path = ManagementRoute.CONFIG_NODE_DELETE.assemble("node-1", "some.key").unwrap() + "/anything";

        assertThat(ManagementServerImpl.resolvePermission("DELETE", path).minimumRole()).isEqualTo(AuthorizationRole.ADMIN);
    }
}
