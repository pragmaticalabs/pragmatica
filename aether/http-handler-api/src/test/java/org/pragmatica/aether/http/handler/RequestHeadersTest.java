// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.http.Headers;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #2879: a [RequestHeaders] answers by name case-insensitively (RFC 9110 §5.1), keeps every value of a repeated
/// name in arrival order, and is a value.
class RequestHeadersTest {
    @Test
    void first_findsTheHeader_whateverTheCaseOfTheLookupName() {
        var headers = RequestHeaders.requestHeaders(Map.of("X-Trace-Id", List.of("abc")));

        assertThat(headers.first("x-trace-id")).isEqualTo(Option.some("abc"));
        assertThat(headers.first("X-TRACE-ID")).isEqualTo(Option.some("abc"));
    }

    @Test
    void first_isNone_whenTheRequestDidNotCarryTheHeader() {
        assertThat(RequestHeaders.empty().first("x-missing").isEmpty()).isTrue();
    }

    @Test
    void first_returnsTheFirstValueInArrivalOrder() {
        var headers = RequestHeaders.requestHeaders(Map.of("Accept", List.of("a/1", "b/2")));

        assertThat(headers.first("accept")).isEqualTo(Option.some("a/1"));
    }

    @Test
    void all_returnsEveryValueInArrivalOrder() {
        var headers = RequestHeaders.requestHeaders(Map.of("Accept", List.of("a/1", "b/2", "c/3")));

        assertThat(headers.all("ACCEPT")).containsExactly("a/1", "b/2", "c/3");
    }

    @Test
    void all_isEmpty_whenTheRequestDidNotCarryTheHeader() {
        assertThat(RequestHeaders.empty().all("x-missing")).isEmpty();
    }

    @Test
    void namesDifferingOnlyInCase_areOneField_andNoValueIsLost() {
        var raw = new LinkedHashMap<String, List<String>>();

        raw.put("X-Tag", List.of("one"));
        raw.put("x-tag", List.of("two"));

        assertThat(RequestHeaders.requestHeaders(raw).all("X-TAG")).containsExactly("one", "two");
    }

    @Test
    void lookups_doNotDependOnTheDefaultLocale() {
        var saved = Locale.getDefault();

        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            var headers = RequestHeaders.requestHeaders(Map.of("X-ID", List.of("v")));

            assertThat(headers.all("x-id")).containsExactly("v");
            assertThat(headers.all("X-ID")).containsExactly("v");
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void requestHeaders_isAValue_laterChangesToTheSourceDoNotShow() {
        var values = new ArrayList<>(List.of("v1"));
        var source = new HashMap<String, List<String>>();

        source.put("X-A", values);
        var headers = RequestHeaders.requestHeaders(source);

        values.add("v2");
        source.put("X-Late", List.of("x"));

        assertThat(headers.all("x-a")).containsExactly("v1");
        assertThat(headers.all("x-late")).isEmpty();
    }

    @Test
    void requestHeaders_isImmutable() {
        var headers = RequestHeaders.requestHeaders(Map.of("X-A", List.of("v")));

        assertThatThrownBy(() -> headers.fields().put("x-b", List.of("w")))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> headers.all("x-a").add("w"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requestHeaders_fromTransportHeaders_carriesAllValuesCaseInsensitively() {
        var transport = Headers.headers(Map.of("Set-Thing", List.of("a", "b")));

        assertThat(RequestHeaders.requestHeaders(transport).all("SET-THING")).containsExactly("a", "b");
    }

    @Test
    void empty_carriesNoFieldAtAll() {
        assertThat(RequestHeaders.empty().fields()).isEmpty();
    }
}
