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

    @Test
    void trailers_dropsEachListedName() {
        for (var name : Headers.FORBIDDEN_TRAILER_FIELDS) {
            assertThat(Headers.trailers(Map.of(name, List.of("v"))).asMap()).as(name).isEmpty();
        }
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
