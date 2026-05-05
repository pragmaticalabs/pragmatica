// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.management.route;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


class RouteAssemblerTest {

    @Test
    void assemble_parameterlessRoute_returnsPrefixUnchanged() {
        var path = ManagementRoute.CLUSTER_STATUS.assemble(List.of());
        path.onSuccess(p -> assertThat(p).isEqualTo("/api/status"));
        assertThat(path.isSuccess()).isTrue();
    }

    @Test
    void assemble_singleParam_appendsToTail() {
        var path = ManagementRoute.DEPLOY_PROMOTE.assemble("dep-1");
        path.onSuccess(p -> assertThat(p).isEqualTo("/api/deploy/promote/dep-1"));
        assertThat(path.isSuccess()).isTrue();
    }

    @Test
    void assemble_multipleParams_appendsInOrder() {
        // Spec event-stream-namespaces §12 — STREAMS_METADATA: GET /api/streams/{ns}/{stream}/{version}
        var path = ManagementRoute.STREAMS_METADATA.assemble("com.example.app", "orders", "1.0.0");
        path.onSuccess(p -> assertThat(p).isEqualTo("/api/streams/com.example.app/orders/1.0.0"));
        assertThat(path.isSuccess()).isTrue();
    }

    @Test
    void assemble_pathTemplateWithLiteralSuffix() {
        // Spec event-stream-namespaces §12 — STREAMS_PUBLISH: literal `publish` after three params.
        var path = ManagementRoute.STREAMS_PUBLISH.assemble("com.example.app", "orders", "1.0.0");
        path.onSuccess(p -> assertThat(p).isEqualTo("/api/streams/com.example.app/orders/1.0.0/publish"));
        assertThat(path.isSuccess()).isTrue();
    }

    @Test
    void assemble_pathTemplateWithInterleavedLiteralAndParam() {
        // Spec event-stream-namespaces §12 — STREAMS_GROUP_DELETE has `groups` literal between
        // version and group params.
        var path = ManagementRoute.STREAMS_GROUP_DELETE.assemble("com.example.app", "orders", "1.0.0", "g1");
        path.onSuccess(p -> assertThat(p).isEqualTo("/api/streams/com.example.app/orders/1.0.0/groups/g1"));
        assertThat(path.isSuccess()).isTrue();
    }

    @Test
    void assemble_urlEncodesSegments() {
        var path = ManagementRoute.DEPLOY_STATUS.assemble("a b/c");
        path.onSuccess(p -> assertThat(p).isEqualTo("/api/deploy/a%20b/c"));
        assertThat(path.isSuccess()).isTrue();
    }

    @Test
    void assemble_failsOnWrongParamCount() {
        var path = ManagementRoute.DEPLOY_PROMOTE.assemble(List.of());
        assertThat(path.isFailure()).isTrue();
        path.onFailure(c -> assertThat(c).isInstanceOf(ManagementRouteError.WrongParamCount.class));
    }

    @Test
    void assemble_failsOnNullParam() {
        var values = new ArrayList<String>();
        values.add(null);
        var path = ManagementRoute.DEPLOY_PROMOTE.assemble(values);
        assertThat(path.isFailure()).isTrue();
        path.onFailure(c -> assertThat(c).isInstanceOf(ManagementRouteError.MissingParam.class));
    }

    @Test
    void roundTrip_assembleThenMatch_preservesParams() {
        var matcher = RouteMatcher.shared();
        for (var route : ManagementRoute.values()) {
            var values = new ArrayList<String>(route.paramCount());
            for (int i = 0; i < route.paramCount(); i++) {
                // Use safe per-param values to avoid colliding with reserved literal segments
                // (e.g. "latest" which is also the STREAMS_LATEST literal). We pick a value that
                // includes a digit to avoid collision and stays URL-safe.
                values.add("v" + i + "x");
            }
            var assembled = route.assemble(values);
            assertThat(assembled.isSuccess())
                    .as("assemble must succeed for %s", route.name())
                    .isTrue();
            assembled.onSuccess(path -> {
                var matched = matcher.match(route.method(), path);
                assertThat(matched.isSuccess())
                        .as("match must succeed for %s assembled to %s", route.name(), path)
                        .isTrue();
                matched.onSuccess(m -> {
                    assertThat(m.route())
                            .as("matched route mismatch for %s -> %s", route.name(), path)
                            .isEqualTo(route);
                    for (int i = 0; i < route.paramCount(); i++) {
                        var name = route.paramNames().get(i);
                        var expected = "v" + i + "x";
                        assertThat(m.param(name).or((String) null))
                                .as("param %s mismatch for %s", name, route.name())
                                .isEqualTo(expected);
                    }
                });
            });
        }
    }
}
