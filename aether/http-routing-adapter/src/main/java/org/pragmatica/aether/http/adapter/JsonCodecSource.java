// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.pragmatica.http.JsonCodec;


/// Where a slice router takes its JSON codec from. [SliceRouter] asks once per request and uses that one codec to
/// decode the request body and encode the response, so a codec switch never splits a request between two codecs.
@FunctionalInterface
public interface JsonCodecSource {
    /// The codec for the request starting now.
    JsonCodec current();

    /// A source that always answers `codec`.
    static JsonCodecSource constant(JsonCodec codec) {
        return () -> codec;
    }
}
