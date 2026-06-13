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
        path.onSuccess(p -> assertThat(p).isEqualTo("/api/cluster/status"));
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
        var path = ManagementRoute.STREAM_READ.assemble("orders", "3");
        path.onSuccess(p -> assertThat(p).isEqualTo("/api/streams/read/orders/3"));
        assertThat(path.isSuccess()).isTrue();
    }

    @Test
    void assemble_urlEncodesSegments() {
        // `/` is preserved so callers can pass pre-joined path fragments (e.g. Maven group paths).
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
            // Generate plausible string values for each param: "v0", "v1", ...
            var values = new ArrayList<String>(route.paramCount());
            for (int i = 0; i < route.paramCount(); i++) {
                values.add("val" + i);
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
                        var expected = "val" + i;
                        assertThat(m.param(name).or((String) null))
                                .as("param %s mismatch for %s", name, route.name())
                                .isEqualTo(expected);
                    }
                });
            });
        }
    }
}
