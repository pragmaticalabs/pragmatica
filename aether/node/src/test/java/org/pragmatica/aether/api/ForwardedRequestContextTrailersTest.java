// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #2310: a management request forwarded to its owner is rebuilt as an [org.pragmatica.http.HttpRequest] from the
/// decoded [HttpRequestContext]; the trailers it carried are part of that request.
class ForwardedRequestContextTrailersTest {
    @Test
    void forwardedRequest_exposesTheTrailersItCarried() {
        var source = HttpRequestContext.httpRequestContext("/api/v1/status",
                                                           "POST",
                                                           Map.of(),
                                                           Map.of(),
                                                           new byte[0],
                                                           Map.of("x-checksum", List.of("abc123")),
                                                           "req-fwd",
                                                           SecurityContext.securityContext());
        var request = ForwardedRequestContext.forwardedRequestContext(source);

        assertThat(request.trailers().get("x-checksum")).isEqualTo(Option.some("abc123"));
        assertThat(request.headers().get("x-checksum").isEmpty()).isTrue();
    }

    @Test
    void forwardedRequest_withoutTrailers_exposesNone() {
        var source = HttpRequestContext.httpRequestContext("/api/v1/status",
                                                           "GET",
                                                           Map.of(),
                                                           Map.of("x-client", List.of("probe")),
                                                           "req-fwd");

        assertThat(ForwardedRequestContext.forwardedRequestContext(source).trailers().asMap()).isEmpty();
    }
}
