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

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class ResponseSerializerTest {
    private static final JsonCodec JSON = new JsonCodec() {
        @Override
        public Result<byte[]> serialize(Object value) {
            return Result.success(("json:" + value).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public <T> Result<T> deserialize(byte[] bytes, TypeToken<T> token) {
            return fail("deserialize must not be called by the serializer");
        }
    };

    @Nested
    class JsonCategory {
        @Test
        void serialize_delegatesToJsonCodec_forJsonCategory() {
            ResponseSerializer.serialize("payload", CommonContentType.APPLICATION_JSON, JSON)
                              .onFailure(cause -> fail(cause.message()))
                              .onSuccess(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("json:payload"));
        }

        @Test
        void serialize_delegatesToJsonCodec_forProblemJsonCategory() {
            ResponseSerializer.serialize("problem", CommonContentType.APPLICATION_PROBLEM_JSON, JSON)
                              .onFailure(cause -> fail(cause.message()))
                              .onSuccess(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("json:problem"));
        }
    }

    @Nested
    class TextCategories {
        @Test
        void serialize_rendersStringBytes_forTextCategory() {
            ResponseSerializer.serialize("hello", CommonContentType.TEXT_PLAIN, JSON)
                              .onFailure(cause -> fail(cause.message()))
                              .onSuccess(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("hello"));
        }

        @Test
        void serialize_rendersStringBytes_forHtmlCategory() {
            ResponseSerializer.serialize("<p/>", CommonContentType.TEXT_HTML, JSON)
                              .onFailure(cause -> fail(cause.message()))
                              .onSuccess(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("<p/>"));
        }

        @Test
        void serialize_rendersToString_forNonStringTextValue() {
            ResponseSerializer.serialize(42, CommonContentType.TEXT_PLAIN, JSON)
                              .onFailure(cause -> fail(cause.message()))
                              .onSuccess(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("42"));
        }

        @Test
        void serialize_rendersStringBytes_forXmlCategory() {
            ResponseSerializer.serialize("<x/>", CommonContentType.APPLICATION_XML, JSON)
                              .onFailure(cause -> fail(cause.message()))
                              .onSuccess(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("<x/>"));
        }
    }

    @Nested
    class BinaryCategory {
        @Test
        void serialize_passesBytesThroughVerbatim_forByteArray() {
            var payload = new byte[]{1, 2, 3, 4};

            ResponseSerializer.serialize(payload, CommonContentType.APPLICATION_OCTET_STREAM, JSON)
                              .onFailure(cause -> fail(cause.message()))
                              .onSuccess(bytes -> assertThat(bytes).isSameAs(payload));
        }

        @Test
        void serialize_rendersStringBytes_forBinaryStringValue() {
            ResponseSerializer.serialize("raw", CommonContentType.APPLICATION_OCTET_STREAM, JSON)
                              .onFailure(cause -> fail(cause.message()))
                              .onSuccess(bytes -> assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("raw"));
        }

        @Test
        void serialize_fails_forBinaryNonBytesNonStringValue() {
            ResponseSerializer.serialize(42, CommonContentType.APPLICATION_OCTET_STREAM, JSON)
                              .onSuccess(bytes -> fail("expected failure for non-binary value"));
        }
    }

    @Nested
    class InputOnlyCategories {
        @Test
        void serialize_fails_forFormUrlEncodedOutput() {
            ResponseSerializer.serialize("a=b", CommonContentType.APPLICATION_FORM_URLENCODED, JSON)
                              .onSuccess(bytes -> fail("form-urlencoded is input-only"));
        }

        @Test
        void serialize_fails_forMultipartOutput() {
            ResponseSerializer.serialize("part", CommonContentType.MULTIPART_FORM_DATA, JSON)
                              .onSuccess(bytes -> fail("multipart is input-only"));
        }
    }
}
