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

package org.pragmatica.lang.vo;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UrlTest {

    @Nested
    class ValidUrls {

        @Test
        void url_success_https() {
            Url.url("https://example.com")
               .onFailureRun(Assertions::fail)
               .onSuccess(u -> {
                   assertEquals("https", u.scheme());
                   assertEquals("example.com", u.host());
               });
        }

        @Test
        void url_success_withPathAndQuery() {
            Url.url("http://example.com/path?q=1")
               .onFailureRun(Assertions::fail)
               .onSuccess(u -> {
                   assertEquals("http", u.scheme());
                   assertEquals("example.com", u.host());
                   assertEquals("/path", u.path());
                   assertEquals("q=1", u.query());
               });
        }

        @Test
        void url_success_ftp() {
            Url.url("ftp://files.example.com")
               .onFailureRun(Assertions::fail)
               .onSuccess(u -> {
                   assertEquals("ftp", u.scheme());
                   assertEquals("files.example.com", u.host());
               });
        }

        @Test
        void url_success_withPort() {
            Url.url("https://example.com:8080/api")
               .onFailureRun(Assertions::fail)
               .onSuccess(u -> {
                   assertEquals(8080, u.port());
                   assertEquals("/api", u.path());
               });
        }

        @Test
        void url_success_toStringMatchesInput() {
            Url.url("https://example.com/path")
               .onFailureRun(Assertions::fail)
               .onSuccess(u -> assertEquals("https://example.com/path", u.toString()));
        }
    }

    @Nested
    class InvalidUrls {

        @Test
        void url_failure_noScheme() {
            Url.url("example.com")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void url_failure_noHost() {
            Url.url("file:///path")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void url_failure_null() {
            Url.url(null)
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void url_failure_empty() {
            Url.url("")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertNotNull(cause.message()));
        }

        @Test
        void url_failure_malformed() {
            Url.url("http://[invalid")
               .onSuccessRun(Assertions::fail)
               .onFailure(cause -> assertNotNull(cause.message()));
        }
    }
}
