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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2310: the RFC 9110 §6.5.1 trailer policy, shared by every transport through [Headers#trailers(Map)].
class HeadersTrailersTest {
    @Test
    void trailers_discardsEveryForbiddenField_caseInsensitively() {
        var trailers = Headers.trailers(Map.of("Authorization", List.of("Bearer forged"),
                                               "CONTENT-TYPE", List.of("text/evil"),
                                               "host", List.of("evil"),
                                               "x-checksum", List.of("abc123")));

        assertThat(trailers.asMap()).containsOnlyKeys("x-checksum");
        assertThat(trailers.get("X-Checksum").or("")).isEqualTo("abc123");
    }

    /// The policy written out independently of the set under test, so removing a name from
    /// [Headers#FORBIDDEN_TRAILER_FIELDS] fails here instead of shrinking the loop.
    private static final List<String> RFC_9110_FORBIDDEN = List.of("content-length",
                                                                   "transfer-encoding",
                                                                   "trailer",
                                                                   "te",
                                                                   "connection",
                                                                   "keep-alive",
                                                                   "proxy-connection",
                                                                   "upgrade",
                                                                   "host",
                                                                   "authorization",
                                                                   "proxy-authorization",
                                                                   "cookie",
                                                                   "expect",
                                                                   "max-forwards",
                                                                   "cache-control",
                                                                   "pragma",
                                                                   "range",
                                                                   "if-match",
                                                                   "if-none-match",
                                                                   "if-modified-since",
                                                                   "if-unmodified-since",
                                                                   "if-range",
                                                                   "content-type",
                                                                   "content-encoding",
                                                                   "content-language",
                                                                   "content-location",
                                                                   "content-range");

    @Test
    void forbiddenSet_isExactlyTheDocumentedPolicy() {
        assertThat(Headers.FORBIDDEN_TRAILER_FIELDS).containsExactlyInAnyOrderElementsOf(RFC_9110_FORBIDDEN);
    }

    @Test
    void trailers_dropsEachListedName() {
        for (var name : RFC_9110_FORBIDDEN) {
            assertThat(Headers.trailers(Map.of(name, List.of("v"))).asMap()).as(name).isEmpty();
            assertThat(Headers.trailers(Map.of(name.toUpperCase(Locale.ROOT), List.of("v"))).asMap()).as(name).isEmpty();
        }
    }

    /// Under `tr-TR` the default-locale lower case of `AUTHORIZATION` is `authorızatıon` (dotless ı), which no set
    /// entry matches; names are folded with `Locale.ROOT`.
    @Test
    void trailers_dropsForbiddenNames_underATurkishDefaultLocale() {
        var saved = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            var raw = new LinkedHashMap<String, List<String>>();

            raw.put("AUTHORIZATION", List.of("Bearer forged"));
            raw.put("IF-MATCH", List.of("*"));
            raw.put("X-OK", List.of("yes"));

            var trailers = Headers.trailers(raw);

            assertThat(trailers.asMap().keySet()).containsExactly("x-ok");
            assertThat(trailers.get("X-OK").or("")).isEqualTo("yes");
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void trailers_namesDifferingOnlyInCase_mergeEveryValue_inArrivalOrder() {
        var raw = new LinkedHashMap<String, List<String>>();

        raw.put("X-A", List.of("1"));
        raw.put("x-a", List.of("2", "3"));
        raw.put("X-a", List.of("4"));

        assertThat(Headers.trailers(raw).getAll("x-a")).containsExactly("1", "2", "3", "4");
    }

    @Test
    void headers_namesDifferingOnlyInCase_mergeEveryValue_inArrivalOrder() {
        var raw = new LinkedHashMap<String, List<String>>();

        raw.put("Accept", List.of("a"));
        raw.put("ACCEPT", List.of("b"));

        assertThat(Headers.headers(raw).getAll("accept")).containsExactly("a", "b");
    }

    @Test
    void trailers_keepsEveryValueOfAPermittedField_inOrder() {
        var trailers = Headers.trailers(Map.of("x-multi", List.of("one", "two")));

        assertThat(trailers.getAll("x-multi")).containsExactly("one", "two");
    }

    @Test
    void trailers_ofNoFields_isEmpty() {
        assertThat(Headers.trailers(Map.of()).asMap()).isEmpty();
    }

    @Test
    void httpRequest_withoutATransport_hasEmptyTrailers() {
        var request = new HttpRequestTest.TestHttpRequest("req_1",
                                                          HttpMethod.GET,
                                                          "/path",
                                                          Headers.empty(),
                                                          QueryParams.empty(),
                                                          new byte[0]);

        assertThat(request.trailers().asMap()).isEmpty();
    }
}
