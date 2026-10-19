// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.http;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// `get` returns the FIRST value of a header / query parameter: none for an empty list, the value for one, the first of many.
class FirstValueLookupTest {
    @Test
    void headersGet_emptyList_isNone() {
        assertTrue(Headers.headers(Map.of("A", List.of())).get("a").isEmpty());
    }

    @Test
    void headersGet_singleValue_isThatValue() {
        assertEquals("1", Headers.headers(Map.of("A", List.of("1"))).get("a").or("none"));
    }

    @Test
    void headersGet_manyValues_isTheFirst() {
        assertEquals("1", Headers.headers(Map.of("A", List.of("1", "2"))).get("a").or("none"));
    }

    @Test
    void queryParamsGet_emptyList_isNone() {
        assertTrue(QueryParams.queryParams(Map.of("p", List.of())).get("p").isEmpty());
    }

    @Test
    void queryParamsGet_singleValue_isThatValue() {
        assertEquals("1", QueryParams.queryParams(Map.of("p", List.of("1"))).get("p").or("none"));
    }

    @Test
    void queryParamsGet_manyValues_isTheFirst() {
        assertEquals("1", QueryParams.queryParams(Map.of("p", List.of("1", "2"))).get("p").or("none"));
    }
}
