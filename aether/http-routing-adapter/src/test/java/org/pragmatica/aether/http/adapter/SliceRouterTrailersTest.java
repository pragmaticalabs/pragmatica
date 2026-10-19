// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #2310: a slice route reads the trailers the request carried, through the real `SliceRouter` dispatch and its
/// `SliceRequestContext`, whether the request was received on this node or forwarded to it.
class SliceRouterTrailersTest {
    private static SliceRouter router() {
        var route = Route.<String>post("/upload")
                         .toImmediate(context -> Result.success(context.trailers().get("x-checksum").or("none")))
                         .asText();
        RouteSource source = () -> Stream.of(route);

        return SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), JsonMapper.defaultJsonMapper());
    }

    private static String send(Map<String, List<String>> trailers) {
        var request = HttpRequestContext.httpRequestContext("/upload",
                                                            "POST",
                                                            Map.of(),
                                                            Map.of("x-checksum", List.of("in-the-headers")),
                                                            new byte[0],
                                                            trailers,
                                                            "req_trailers",
                                                            SecurityContext.securityContext());
        var response = router().handle(request).await().unwrap();

        assertThat(response.statusCode()).isEqualTo(200);

        return new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void sliceRoute_readsTheRequestsTrailers() {
        assertThat(send(Map.of("x-checksum", List.of("abc123")))).isEqualTo("abc123");
    }

    @Test
    void sliceRoute_seesNoTrailers_whenTheRequestCarriedNone() {
        assertThat(send(Map.of())).as("a header of the same name is not a trailer").isEqualTo("none");
    }
}
