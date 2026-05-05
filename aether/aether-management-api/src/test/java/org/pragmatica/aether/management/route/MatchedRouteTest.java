// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.management.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


class MatchedRouteTest {

    @Test
    void matchedRoute_withZeroValues_emptyParams() {
        var matched = MatchedRoute.matchedRoute(ManagementRoute.CLUSTER_STATUS, List.of());
        assertThat(matched.params()).isEmpty();
    }

    @Test
    void matchedRoute_withSingleValue_storesByName() {
        var matched = MatchedRoute.matchedRoute(ManagementRoute.DEPLOY_STATUS, List.of("dep-42"));
        assertThat(matched.params()).hasSize(1);
        assertThat(matched.param("deploymentId").or((String) null)).isEqualTo("dep-42");
    }

    @Test
    void matchedRoute_withMultipleValues_preservesOrder() {
        // Spec event-stream-namespaces §12 STREAMS_METADATA — params: namespace, stream, version
        var matched = MatchedRoute.matchedRoute(ManagementRoute.STREAMS_METADATA,
                                                List.of("com.example.app", "orders", "1.0.0"));
        assertThat(matched.param("namespace").or((String) null)).isEqualTo("com.example.app");
        assertThat(matched.param("stream").or((String) null)).isEqualTo("orders");
        assertThat(matched.param("version").or((String) null)).isEqualTo("1.0.0");
    }

    @Test
    void param_returnsEmpty_forUnknownName() {
        var matched = MatchedRoute.matchedRoute(ManagementRoute.DEPLOY_STATUS, List.of("dep-1"));
        assertThat(matched.param("noSuchKey").isEmpty()).isTrue();
    }

    @Test
    void matchedRoute_paramsAreImmutable() {
        var matched = MatchedRoute.matchedRoute(ManagementRoute.STREAMS_METADATA,
                                                List.of("com.example", "orders", "1.0.0"));
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                                                      () -> matched.params().put("x", "y"));
    }
}
