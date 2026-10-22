/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.http;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2310: protocol tokens are case-folded with `Locale.ROOT`. Under a `tr-TR` default, `"i".toUpperCase()` is a dotted
/// `İ` and `"I".toLowerCase()` a dotless `ı`, so a default-locale fold breaks names and methods that contain an `i`.
class LocaleIndependenceTest {
    private Locale saved;

    @BeforeEach
    void turkish() {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
    }

    @AfterEach
    void restore() {
        Locale.setDefault(saved);
    }

    @Test
    void headerLookup_withAnUppercaseI_findsTheField() {
        var headers = Headers.headers(Map.of("Authorization", List.of("Bearer t")));

        assertThat(headers.asMap()).containsOnlyKeys("authorization");
        assertThat(headers.get("AUTHORIZATION").or("")).isEqualTo("Bearer t");
    }

    @Test
    void headerValues_lookedUpByAnUppercaseI_arriveInOrder() {
        var headers = Headers.headers(Map.of("x-id", List.of("1", "2")));

        assertThat(headers.getAll("X-ID")).containsExactly("1", "2");
    }

    @Test
    void methodName_withALowercaseI_parses() {
        assertThat(HttpMethod.httpMethod("options").isSuccess()).isTrue();
    }
}
