// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #2310: the `trailers` component of the wire payload is never null. The canonical constructor is what the generated
/// codec calls on decode, so the guard is pinned there rather than through the factories.
class HttpRequestContextTrailersTest {
    @Test
    void canonicalConstructor_refusesNullTrailers() {
        assertThatThrownBy(() -> new HttpRequestContext("/p",
                                                        "GET",
                                                        Map.of(),
                                                        Map.of(),
                                                        new byte[0],
                                                        null,
                                                        "req",
                                                        SecurityContext.securityContext(),
                                                        Option.none()))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("trailers");
    }

    @Test
    void factoriesWithoutTrailers_carryAnEmptyMap() {
        var context = HttpRequestContext.httpRequestContext("/p", "GET", Map.of(), Map.of("x", List.of("1")), "req");

        assertThat(context.trailers()).isEmpty();
    }

    @Test
    void canonicalConstructor_refusesNullUpload() {
        assertThatThrownBy(() -> new HttpRequestContext("/p",
                                                        "GET",
                                                        Map.of(),
                                                        Map.of(),
                                                        new byte[0],
                                                        Map.of(),
                                                        "req",
                                                        SecurityContext.securityContext(),
                                                        null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("upload");
    }

    @Test
    void withSecurity_keepsTheUploadOfAnIngestedRequest() {
        var upload = UploadHandle.uploadHandle(List.of(), Map.of("k", List.of("v")));
        var context = HttpRequestContext.httpRequestContext("/p",
                                                            "POST",
                                                            Map.of(),
                                                            Map.of(),
                                                            new byte[0],
                                                            Map.of(),
                                                            "req",
                                                            SecurityContext.securityContext(),
                                                            Option.some(upload));

        assertThat(context.withSecurity(SecurityContext.securityContext()).upload()).isEqualTo(Option.some(upload));
    }
}
