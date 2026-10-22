// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.http.HttpMethod;
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

    /// The rebuilt request re-applies the forbidden-name policy to the decoded map, so a forged forward cannot hand a
    /// management route an `Authorization` trailer.
    @Test
    void forwardedRequest_neverExposesForbiddenTrailerNames_evenIfTheWireCarriesThem() {
        var forged = new LinkedHashMap<String, List<String>>();

        forged.put("Authorization", List.of("Bearer forged"));
        forged.put("content-type", List.of("text/evil"));
        forged.put("x-ok", List.of("yes"));

        var source = HttpRequestContext.httpRequestContext("/api/v1/status",
                                                           "POST",
                                                           Map.of(),
                                                           Map.of(),
                                                           new byte[0],
                                                           forged,
                                                           "req-fwd",
                                                           SecurityContext.securityContext());

        assertThat(ForwardedRequestContext.forwardedRequestContext(source).trailers().asMap()).containsOnlyKeys("x-ok");
    }

    /// #2310: the forwarded method is folded with `Locale.ROOT`; under a `tr-TR` default `options` would fold to
    /// `OPTİONS` (dotted İ) and name no method.
    @Test
    void forwardedRequest_methodWithAnI_parses_underATurkishDefaultLocale() {
        var saved = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            var source = HttpRequestContext.httpRequestContext("/api/v1/status", "options", Map.of(), Map.of(), "req-fwd");

            assertThat(ForwardedRequestContext.forwardedRequestContext(source).method()).isEqualTo(HttpMethod.OPTIONS);
        } finally {
            Locale.setDefault(saved);
        }
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
