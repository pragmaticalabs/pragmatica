// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.serialization.json.JsonOptions;


/// The JSON codec a slice's HTTP routes use: the codec's name in the `[json]` configuration, its implementation and
/// the deploy-time options both the generated codecs and the Jackson fallback apply. Immutable, so a router that
/// samples one selection per request reads and writes that request under one consistent setting.
///
/// @param name           the codec's name under `json.codecs`, or [#IMPLICIT_NAME] when none is declared
/// @param implementation generated codecs or Jackson
/// @param options        the deploy-time options
public record JsonCodecSelection(String name, JsonImplementation implementation, JsonOptions options) {
    /// The name of the codec a slice uses when its configuration declares none.
    public static final String IMPLICIT_NAME = "implicit";

    /// No `[json]` configuration: the generated codecs under [JsonOptions#DEFAULTS].
    public static final JsonCodecSelection DEFAULT = new JsonCodecSelection(IMPLICIT_NAME,
                                                                            JsonImplementation.GENERATED,
                                                                            JsonOptions.DEFAULTS);

    public static JsonCodecSelection jsonCodecSelection(String name,
                                                        JsonImplementation implementation,
                                                        JsonOptions options) {
        return new JsonCodecSelection(name, implementation, options);
    }
}
