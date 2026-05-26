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
        var result = matcher.match(GET, "/api/nodes/status");
        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(matched -> {
            assertThat(matched.route()).isEqualTo(ManagementRoute.NODE_STATUS);
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
            assertThat(matched.param("id").or((String) null)).isEqualTo("abc-123");
        });
    }

    @Test
    void match_returnsRoute_forMultipleParams() {
        var result = matcher.match(GET, "/api/streams/orders/4");
        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(matched -> {
            assertThat(matched.route()).isEqualTo(ManagementRoute.STREAM_PARTITION);
            assertThat(matched.param("name").or((String) null)).isEqualTo("orders");
            assertThat(matched.param("partition").or((String) null)).isEqualTo("4");
        });
    }

    @Test
    void match_longestPrefixWins_overSpecificThanGeneric() {
        // /api/deploy/promote/{id} (specific) must win over /api/deploy/{id} (general)
        var promote = matcher.match(POST, "/api/deploy/promote/dep-1");
        assertThat(promote.isSuccess()).isTrue();
        promote.onSuccess(matched -> {
            assertThat(matched.route()).isEqualTo(ManagementRoute.DEPLOY_PROMOTE);
            assertThat(matched.param("id").or((String) null)).isEqualTo("dep-1");
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
        var result = matcher.match(DELETE, "/api/blueprints/bp-1");
        result.onSuccess(matched -> assertThat(matched.route()).isEqualTo(ManagementRoute.BLUEPRINT_DELETE));
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void match_handlesPutRoute() {
        var result = matcher.match(PUT, "/repository/com/example/1.0/artifact.jar");
        result.onSuccess(matched -> {
            assertThat(matched.route()).isEqualTo(ManagementRoute.ARTIFACT_PUT);
            assertThat(matched.param("groupPath").or((String) null)).isEqualTo("com");
            assertThat(matched.param("file").or((String) null)).isEqualTo("artifact.jar");
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
        var result = matcher.match(DELETE, "/api/nodes/status");
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
        result.onSuccess(matched -> assertThat(matched.param("id").or((String) null)).isEqualTo("abc def"));
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void build_throwsOnAmbiguousRoutes() {
        // Synthetic test: build a tiny matcher with two intentionally-conflicting "fake" routes
        // by re-using two existing enum values that share signature would be impossible (the
        // shared() instance proves it). Instead, we verify that build() rejects duplicates by
        // calling it with values() twice over.
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
            // Just verify no NPE — most will return NoMatch
            var result = matcher.match(method, "/api/nodes/status");
            assertThat(result).isNotNull();
        }
    }
}
