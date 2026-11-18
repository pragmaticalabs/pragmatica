// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


/// A stream's `[hot]` section as declared (#3334): the newest records each partition copy holds in memory. It decides
/// latency and memory only, never retention. Both bounds are PER PARTITION COPY. Every key is optional; an absent key
/// is `none`, and the defaults and refusals are applied by the stream parser, not here.
@Codec
public record HotWindowDeclaration(Option<Long> maxBytes, Option<Long> maxCount) {
    public static HotWindowDeclaration hotWindowDeclaration(Option<Long> maxBytes, Option<Long> maxCount) {
        return new HotWindowDeclaration(maxBytes, maxCount);
    }
}
