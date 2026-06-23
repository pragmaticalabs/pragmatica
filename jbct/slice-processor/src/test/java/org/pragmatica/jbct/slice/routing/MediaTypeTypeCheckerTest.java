// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.routing;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the strict (D3) media-type/Java-type compatibility checker.
/// Exercised without a compile-fail harness — pure inputs, [org.pragmatica.lang.Option] outputs.
class MediaTypeTypeCheckerTest {

    @Nested
    class Produces {

        @Test
        void checkProduces_ok_forJsonWithAnyType() {
            assertThat(MediaTypeTypeChecker.checkProduces("JSON", "com.example.Response", "create").isEmpty()).isTrue();
        }

        @Test
        void checkProduces_ok_forTextWithString() {
            assertThat(MediaTypeTypeChecker.checkProduces("TEXT", "java.lang.String", "export").isEmpty()).isTrue();
        }

        @Test
        void checkProduces_ok_forTextWithByteArray() {
            assertThat(MediaTypeTypeChecker.checkProduces("TEXT", "byte[]", "export").isEmpty()).isTrue();
        }

        @Test
        void checkProduces_fails_forTextWithRecord() {
            var result = MediaTypeTypeChecker.checkProduces("TEXT", "com.example.Response", "export");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("produces category TEXT").contains("String"));
        }

        @Test
        void checkProduces_ok_forBinaryWithByteArray() {
            assertThat(MediaTypeTypeChecker.checkProduces("BINARY", "byte[]", "download").isEmpty()).isTrue();
        }

        @Test
        void checkProduces_ok_forBinaryWithString() {
            assertThat(MediaTypeTypeChecker.checkProduces("BINARY", "java.lang.String", "download").isEmpty()).isTrue();
        }

        @Test
        void checkProduces_fails_forBinaryWithRecord() {
            var result = MediaTypeTypeChecker.checkProduces("BINARY", "com.example.Response", "download");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("produces category BINARY").contains("byte[]"));
        }

        @Test
        void checkProduces_fails_forMultipartProduces() {
            var result = MediaTypeTypeChecker.checkProduces("MULTIPART", "java.lang.String", "form");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("input-only"));
        }

        @Test
        void checkProduces_fails_forFormUrlencodedProduces() {
            var result = MediaTypeTypeChecker.checkProduces("FORM_URLENCODED", "java.lang.String", "form");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("input-only"));
        }
    }

    @Nested
    class Consumes {

        @Test
        void checkConsumes_ok_forJsonWithAnyType() {
            assertThat(MediaTypeTypeChecker.checkConsumes("JSON", "com.example.Request", "create").isEmpty()).isTrue();
        }

        @Test
        void checkConsumes_ok_forTextWithString() {
            assertThat(MediaTypeTypeChecker.checkConsumes("TEXT", "java.lang.String", "upload").isEmpty()).isTrue();
        }

        @Test
        void checkConsumes_fails_forTextWithByteArray() {
            var result = MediaTypeTypeChecker.checkConsumes("TEXT", "byte[]", "upload");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("consumes category TEXT").contains("String"));
        }

        @Test
        void checkConsumes_ok_forBinaryWithByteArray() {
            assertThat(MediaTypeTypeChecker.checkConsumes("BINARY", "byte[]", "upload").isEmpty()).isTrue();
        }

        @Test
        void checkConsumes_fails_forBinaryWithString() {
            var result = MediaTypeTypeChecker.checkConsumes("BINARY", "java.lang.String", "upload");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("consumes category BINARY").contains("byte[]"));
        }

        @Test
        void checkConsumes_ok_forMultipartWithMultipartRequest() {
            assertThat(MediaTypeTypeChecker.checkConsumes("MULTIPART",
                                                          "org.pragmatica.http.routing.MultipartRequest",
                                                          "form").isEmpty()).isTrue();
        }

        @Test
        void checkConsumes_fails_forMultipartWithRecord() {
            var result = MediaTypeTypeChecker.checkConsumes("MULTIPART", "com.example.Request", "form");

            assertThat(result.isPresent()).isTrue();
            result.onPresent(msg -> assertThat(msg).contains("consumes category MULTIPART").contains("MultipartRequest"));
        }
    }
}
