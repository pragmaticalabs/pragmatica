// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.HttpRouteRegistry.NodeRouteSecurity;
import org.pragmatica.aether.http.HttpRouteRegistry.RouteInfo;
import org.pragmatica.aether.http.HttpRouteRegistry.RouteSource;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;


/// #755 / #1103, the GATEWAY half. `RequestRouter` (the slice's own router) matches spacers by position since
/// #755, because a [org.pragmatica.http.routing.Route] knows where it declared them. A node that does not host the
/// route selects over REPLICATED shapes (`pathArity`, `spacers`, #1678), and those carry no positions, so the
/// gateway still matches a spacer by set membership: `GET /api/users/edit/42` against
/// `withPath(aLong(), spacer("edit"))` is selected here and then dies at the hosting node.
///
/// Closing this needs positions carried through `AetherValue.HttpRoute`, `HttpRouteDefinition`,
/// `HttpRoutePublisher`, `HttpRouteRegistry` and `RouteSource`: a wire change, deliberately not part of #755's
/// first step.
///
/// The enabled test is a TRIPWIRE: it asserts today's WRONG behaviour, so it reddens the moment the gateway becomes
/// positional. The real assertion is disabled beside it, because until then it would fail. Disabled alone would
/// stay silent forever.
class HttpRouteRegistryGatewaySpacerPositionTest {
    private static final NodeId NODE = NodeId.nodeId("node-1").unwrap();

    private static RouteInfo editUserRoute() {
        var source = RouteSource.routeSource(NODE, "org.example:users:1.0.0", 2, List.of("edit"));

        return new RouteInfo("GET", "/api/users/", Map.of(source, NodeRouteSecurity.nodeRouteSecurity("PUBLIC", "PUBLIC")));
    }

    /// Control: the declared position resolves, before and after the wire change.
    @Test
    void gateway_spacerAtItsDeclaredSlot_resolves() {
        assertThat(editUserRoute().matchingShape("/api/users/42/edit").isPresent()).isTrue();
    }

    @Test
    void gatewayStillMatchesSpacersByMembership_tripwireFor755() {
        assertThat(editUserRoute().matchingShape("/api/users/edit/42").isPresent())
            .as("gateway path now positional: delete this tripwire and enable the real assertion")
            .isTrue();
    }

    @Test
    @Disabled("#755 follow-up: positions are not carried through the replicated shape yet; enable when the tripwire above reddens")
    void gateway_spacerAtTheWrongSlot_isNoMatch() {
        assertThat(editUserRoute().matchingShape("/api/users/edit/42").isEmpty()).isTrue();
    }
}
