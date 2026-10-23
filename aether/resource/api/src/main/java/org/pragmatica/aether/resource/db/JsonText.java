// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.nio.charset.StandardCharsets;

import org.pragmatica.lang.Result;
import org.pragmatica.serialization.json.JsonTypeCodec;


/// The text of a JSON document stored in a `json` or `jsonb` column.
///
/// A connector binds it as text with no declared parameter type, so the server reads it as whatever the
/// target column is: one value binds to a `json` column and a `jsonb` column alike. A `String` parameter is
/// declared `varchar` instead, which a `jsonb` column refuses. Results come back as text too, so a value read
/// from either column type is the document's text with no format prefix.
///
/// Generated persistence factories encode and decode it with a generated codec ([#jsonText(JsonTypeCodec,
/// Object)], [#decode(JsonTypeCodec)]); a hand-written `ValueMapping<T, JsonText>` is the escape hatch for a
/// type the codec generator cannot handle.
///
/// @param value the document text
public record JsonText(String value) {
    public static JsonText jsonText(String value) {
        return new JsonText(value);
    }

    /// The document `codec` writes for `value`.
    public static <T> JsonText jsonText(JsonTypeCodec<T> codec, T value) {
        return new JsonText(new String(codec.encode(value), StandardCharsets.UTF_8));
    }

    /// The value `codec` reads from this document, or the codec's failure.
    public <T> Result<T> decode(JsonTypeCodec<T> codec) {
        return codec.decode(value);
    }
}
