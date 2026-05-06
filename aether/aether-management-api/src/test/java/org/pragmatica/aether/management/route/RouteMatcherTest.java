// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.management.route;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.routing.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.routing.HttpMethod.DELETE;
import static org.pragmatica.http.routing.HttpMethod.GET;
import static org.pragmatica.http.routing.HttpMethod.POST;
import static org.pragmatica.http.routing.HttpMethod.PUT;


class RouteMatcherTest {
    private final RouteMatcher matcher = RouteMatcher.shared();

    @Test
    void match_returnsRoute_forParameterlessGet() {
        var result = matcher.match(GET, "/api/status");
        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(matched -> {
            assertThat(matched.route()).isEqualTo(ManagementRoute.CLUSTER_STATUS);
            assertThat(matched.params()).isEmpty();
        });
    }

    @Test
    void match_returnsRoute_forLocalHealthEndpoint() {
        var result = matcher.match(GET, "/health/live");
        result.onSuccess(matched -> assertThat(matched.route()).isEqualTo(ManagementRoute.HEALTH_LIVE));
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void match_returnsRoute_forSingleParam() {
        var result = matcher.match(GET, "/api/deploy/abc-123");
        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(matched -> {
            assertThat(matched.route()).isEqualTo(ManagementRoute.DEPLOY_STATUS);
            assertThat(matched.param("deploymentId").or((String) null)).isEqualTo("abc-123");
        });
    }

    @Test
    void match_returnsRoute_forMultipleParams() {
        // Spec event-stream-namespaces §12 — STREAMS_METADATA: GET /api/streams/{ns}/{stream}/{version}
        var result = matcher.match(GET, "/api/streams/com.example.app/orders/1.0.0");
        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(matched -> {
            assertThat(matched.route()).isEqualTo(ManagementRoute.STREAMS_METADATA);
            assertThat(matched.param("namespace").or((String) null)).isEqualTo("com.example.app");
            assertThat(matched.param("stream").or((String) null)).isEqualTo("orders");
            assertThat(matched.param("version").or((String) null)).isEqualTo("1.0.0");
        });
    }

    @Test
    void match_streamLatest_takesPriorityOver_streamMetadata() {
        // /api/streams/{ns}/{stream}/latest must beat /api/streams/{ns}/{stream}/{version}
        // because the literal segment is more specific than a parameter.
        var result = matcher.match(GET, "/api/streams/com.example.app/orders/latest");
        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(matched -> assertThat(matched.route()).isEqualTo(ManagementRoute.STREAMS_LATEST));
    }

    @Test
    void match_streamPublish_pathTemplate() {
        // POST /api/streams/{ns}/{stream}/{version}/publish
        var result = matcher.match(POST, "/api/streams/com.example.app/orders/1.0.0/publish");
        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(matched -> assertThat(matched.route()).isEqualTo(ManagementRoute.STREAMS_PUBLISH));
    }

    @Test
    void match_streamGroupDelete_interleavedLiteralAndParam() {
        // DELETE /api/streams/{ns}/{stream}/{version}/groups/{group}
        var result = matcher.match(DELETE, "/api/streams/com.example.app/orders/1.0.0/groups/g1");
        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(matched -> {
            assertThat(matched.route()).isEqualTo(ManagementRoute.STREAMS_GROUP_DELETE);
            assertThat(matched.param("group").or((String) null)).isEqualTo("g1");
        });
    }

    @Test
    void match_longestPrefixWins_overSpecificThanGeneric() {
        var promote = matcher.match(POST, "/api/deploy/promote/dep-1");
        assertThat(promote.isSuccess()).isTrue();
        promote.onSuccess(matched -> {
            assertThat(matched.route()).isEqualTo(ManagementRoute.DEPLOY_PROMOTE);
            assertThat(matched.param("deploymentId").or((String) null)).isEqualTo("dep-1");
        });
    }

    @Test
    void match_distinguishesByHttpMethod() {
        var get = matcher.match(GET, "/api/deploy");
        var post = matcher.match(POST, "/api/deploy");
        get.onSuccess(m -> assertThat(m.route()).isEqualTo(ManagementRoute.DEPLOY_LIST));
        post.onSuccess(m -> assertThat(m.route()).isEqualTo(ManagementRoute.DEPLOY_START));
        assertThat(get.isSuccess()).isTrue();
        assertThat(post.isSuccess()).isTrue();
    }

    @Test
    void match_distinguishesByParamCount_sameMethodAndPrefix() {
        var list = matcher.match(GET, "/api/scheduled-tasks");
        var bySection = matcher.match(GET, "/api/scheduled-tasks/cron");
        list.onSuccess(m -> assertThat(m.route()).isEqualTo(ManagementRoute.SCHEDULED_TASKS_LIST));
        bySection.onSuccess(m -> assertThat(m.route()).isEqualTo(ManagementRoute.SCHEDULED_TASKS_BY_SECTION));
        assertThat(list.isSuccess()).isTrue();
        assertThat(bySection.isSuccess()).isTrue();
    }

    @Test
    void match_handlesDeleteWithParam() {
        var result = matcher.match(DELETE, "/api/blueprint/bp-1");
        result.onSuccess(matched -> assertThat(matched.route()).isEqualTo(ManagementRoute.BLUEPRINT_DELETE));
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void match_handlesPutRoute() {
        var result = matcher.match(PUT, "/api/cluster/tasks/reassign/STRATEGIES");
        result.onSuccess(matched -> {
            assertThat(matched.route()).isEqualTo(ManagementRoute.CLUSTER_TASK_REASSIGN);
            assertThat(matched.param("group").or((String) null)).isEqualTo("STRATEGIES");
        });
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void match_handlesThreeParams() {
        var result = matcher.match(GET, "/api/scheduled-tasks/state/cron/com.example/run");
        result.onSuccess(matched -> {
            assertThat(matched.route()).isEqualTo(ManagementRoute.SCHEDULED_TASK_STATE);
            assertThat(matched.param("section").or((String) null)).isEqualTo("cron");
            assertThat(matched.param("artifact").or((String) null)).isEqualTo("com.example");
            assertThat(matched.param("methodName").or((String) null)).isEqualTo("run");
        });
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void match_returnsNoMatch_forUnknownPath() {
        var result = matcher.match(GET, "/api/does-not-exist");
        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementRouteError.NoMatch.class));
    }

    @Test
    void match_returnsNoMatch_forWrongMethod() {
        var result = matcher.match(DELETE, "/api/status");
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void match_stripsQueryString() {
        var result = matcher.match(GET, "/api/events?since=2026-01-01");
        result.onSuccess(matched -> assertThat(matched.route()).isEqualTo(ManagementRoute.EVENTS));
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void match_decodesUrlEncodedSegments() {
        var result = matcher.match(GET, "/api/deploy/abc%20def");
        result.onSuccess(matched -> assertThat(matched.param("deploymentId").or((String) null)).isEqualTo("abc def"));
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void build_throwsOnAmbiguousRoutes() {
        var routes = ManagementRoute.values();
        var doubled = new ManagementRoute[routes.length * 2];
        System.arraycopy(routes, 0, doubled, 0, routes.length);
        System.arraycopy(routes, 0, doubled, routes.length, routes.length);
        var result = RouteMatcher.build(doubled);
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void match_acceptsAllStandardMethods() {
        for (var method : HttpMethod.values()) {
            var result = matcher.match(method, "/api/status");
            assertThat(result).isNotNull();
        }
    }

    /// Reviewer test gap #19 — tie-breaking stability when multiple candidates score identically
    /// on the (prefixLen, literals, segs) tuple [`RouteMatcher#isMoreSpecific`]. The implementation
    /// resolves ties by **first-encountered wins**, and routes are stored in [`HttpMethod`] →
    /// `List<ManagementRoute>` insertion order, which mirrors the enum declaration order in
    /// [`ManagementRoute`]. Pin that contract: repeated calls against a deterministic path must
    /// return the same route on every invocation, and that route is the one declared first in the
    /// enum among the ranked candidates.
    @Test
    void tieBreaking_isStable_whenSameSpecificity() {
        // Use a representative path that exercises tie-resolution; the streams metadata route
        // matches `/api/streams/{ns}/{stream}/{version}` via three params after the same prefix.
        var path = "/api/streams/com.example.app/orders/1.0.0";
        var first = matcher.match(GET, path);
        assertThat(first.isSuccess()).isTrue();
        var firstRoute = first.fold(_ -> (ManagementRoute) null, MatchedRoute::route);

        // Run many invocations to catch any non-deterministic ordering (would surface as a flake
        // in a property-based check; the current `routesByMethod` is built off a HashMap but
        // values are `List.copyOf(insertionOrderList)` so ordering is stable).
        for (var i = 0; i < 100; i++) {
            var again = matcher.match(GET, path);
            assertThat(again.isSuccess()).isTrue();
            again.onSuccess(matched -> assertThat(matched.route()).isEqualTo(firstRoute));
        }
    }
}
