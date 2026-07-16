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

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.nio.charset.StandardCharsets;

/// Unified, category-driven response body serializer.
///
/// Every output path delegates here so serialization stays consistent and binary payloads
/// pass through verbatim. Dispatch is on [ContentType#category]:
/// - [ContentCategory#JSON] → the injected [JsonCodec]
/// - [ContentCategory#TEXT]/[ContentCategory#HTML]/[ContentCategory#XML] → UTF-8 text bytes
/// - [ContentCategory#BINARY] → `byte[]` verbatim, `String` as UTF-8 bytes, otherwise an error
/// - [ContentCategory#FORM_URLENCODED]/[ContentCategory#MULTIPART] → input-only, never a valid
///   `produces` category, so serialization returns an error
///
/// HTTP-level concerns (`null`→204, status selection, error→`ProblemDetail`) stay in the
/// adapters; this function only turns a value into bytes.
public sealed interface ResponseSerializer {
    Fn1<Cause, ContentCategory> NON_SERIALIZABLE_CATEGORY =
        Causes.forOneValue("Content category %s is an input-only type and cannot be serialized as a response body");
    Fn1<Cause, String> BINARY_TYPE_MISMATCH =
        Causes.forOneValue("Binary content requires byte[] or String, got: %s");

    static Result<byte[]> serialize(Object value, ContentType ct, JsonCodec json) {
        return switch (ct.category()) {
            case JSON -> json.serialize(value);
            case TEXT, HTML, XML -> Result.success(textBytes(value));
            case BINARY -> binaryBytes(value);
            case FORM_URLENCODED, MULTIPART -> NON_SERIALIZABLE_CATEGORY.apply(ct.category()).result();
        };
    }

    private static byte[] textBytes(Object value) {
        return (value instanceof String s ? s : value.toString()).getBytes(StandardCharsets.UTF_8);
    }

    private static Result<byte[]> binaryBytes(Object value) {
        return switch (value) {
            case byte[] bytes -> Result.success(bytes);
            case String s -> Result.success(s.getBytes(StandardCharsets.UTF_8));
            default -> BINARY_TYPE_MISMATCH.apply(value.getClass().getName()).result();
        };
    }

    record unused() implements ResponseSerializer {}
}
