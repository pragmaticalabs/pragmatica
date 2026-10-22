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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// #2310: a slice route reads the trailers the request carried, through the real `SliceRouter` dispatch and its
/// `SliceRequestContext`, whether the request was received on this node or forwarded to it.
class SliceRouterTrailersTest {
    private static SliceRouter router() {
        var route = Route.<String>post("/upload")
                         .toImmediate(context -> Result.success(context.trailers().get("x-checksum").or("none")))
                         .asText();
        var names = Route.<String>post("/names")
                         .toImmediate(context -> Result.success(String.join(",", new TreeSet<>(context.trailers()
                                                                                                         .asMap()
                                                                                                         .keySet()))))
                         .asText();
        RouteSource source = () -> Stream.of(route, names);

        return SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), JsonMapper.defaultJsonMapper());
    }

    private static String send(Map<String, List<String>> trailers) {
        return send("/upload", trailers);
    }

    private static String send(String path, Map<String, List<String>> trailers) {
        var request = HttpRequestContext.httpRequestContext(path,
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

    /// The slice-facing view re-applies the forbidden-name policy to whatever arrived, so a forged or buggy peer's
    /// forward cannot hand a slice an `Authorization` trailer.
    @Test
    void sliceRoute_neverSeesForbiddenTrailerNames_evenIfTheWireCarriesThem() {
        var forged = new LinkedHashMap<String, List<String>>();

        forged.put("Authorization", List.of("Bearer forged"));
        forged.put("content-type", List.of("text/evil"));
        forged.put("x-ok", List.of("yes"));

        assertThat(send("/names", forged)).isEqualTo("x-ok");
    }

    @Test
    void sliceRoute_seesNoTrailers_whenTheRequestCarriedNone() {
        assertThat(send(Map.of())).as("a header of the same name is not a trailer").isEqualTo("none");
    }
}
