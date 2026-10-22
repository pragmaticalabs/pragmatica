// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.nio.charset.StandardCharsets;

import org.pragmatica.aether.resource.db.JsonText;
import org.pragmatica.postgres.net.Converter;
import org.pragmatica.postgres.net.PgValue;
import org.pragmatica.postgres.net.PgWriter;


/// Binds and reads [JsonText] as text, both ways.
///
/// A [JsonText] parameter is written as text, and the driver declares its type as unspecified (it has no type
/// of its own in `DataConverter.assumeTypes`), so the server types it from the target column: one value binds to
/// `json` and `jsonb` alike. Results arrive as text because neither `json` nor `jsonb` supports binary in
/// `Oid.supportsBinary`; a binary `jsonb` value would start with a format version byte this converter does not
/// strip (`JsonTextConverterTest` fails if either type becomes binary).
final class JsonTextConverter implements Converter<JsonText> {
    static final JsonTextConverter INSTANCE = new JsonTextConverter();

    private JsonTextConverter() {}

    @Override
    public Class<JsonText> type() {
        return JsonText.class;
    }

    @Override
    public void from(JsonText value, PgWriter writer) {
        writer.writeText(value.value());
    }

    @Override
    public JsonText to(PgValue value) {
        return JsonText.jsonText(new String(value.asBytes(), StandardCharsets.UTF_8));
    }
}
