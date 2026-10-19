// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// Only the FIRST value of the API-key header is the credential, on both the exact-case and the case-insensitive lookup.
/// A later value must never be promoted, and an empty value list must neither authenticate nor shadow another spelling.
class ApiKeyHeaderValueSelectionTest {
    private static final String GOOD = "test-api-key-12345";

    private static boolean accepts(Map<String, List<String>> headers) {
        var holder = new boolean[1];
        var request = HttpRequestContext.httpRequestContext("/t", "GET", Map.of(), headers, new byte[0], "rid");

        HttpAuthenticator.apiKeyValidator(Set.of(GOOD))
                         .validate(request, SecurityPolicy.apiKeyRequired())
                         .onSuccess(context -> holder[0] = true);
        return holder[0];
    }

    @Test
    void validate_rejects_emptyValueList_exactCase() {
        assertThat(accepts(Map.of("X-API-Key", List.of()))).isFalse();
    }

    @Test
    void validate_accepts_firstValueGood_exactCase() {
        assertThat(accepts(Map.of("X-API-Key", List.of(GOOD, "x")))).isTrue();
    }

    @Test
    void validate_rejects_goodKeyInSecondValue_exactCase() {
        assertThat(accepts(Map.of("X-API-Key", List.of("bad", GOOD)))).isFalse();
    }

    @Test
    void validate_rejects_emptyValueList_caseInsensitive() {
        assertThat(accepts(Map.of("x-api-key", List.of()))).isFalse();
    }

    @Test
    void validate_rejects_goodKeyInSecondValue_caseInsensitive() {
        assertThat(accepts(Map.of("x-api-key", List.of("bad", GOOD)))).isFalse();
    }

    @Test
    void validate_accepts_emptyListThenGoodKeyUnderAnotherCase() {
        var headers = new LinkedHashMap<String, List<String>>();

        headers.put("x-api-key", List.of());
        headers.put("X-API-KEY", List.of(GOOD));
        assertThat(accepts(headers)).isTrue();
    }
}
