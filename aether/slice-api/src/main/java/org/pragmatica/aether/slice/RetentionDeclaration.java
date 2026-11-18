// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


/// A stream's `[retention]` section as declared (#3334): the replay window under `window`, the cap under
/// `until_consumed`. Bytes and counts are WHOLE-STREAM values (one copy, summed over partitions); the age is in
/// milliseconds, the form [RetentionPolicy#maxAgeMs] uses. Every key is optional; an absent key is `none`, and the
/// defaults and refusals are applied by the stream parser, not here.
@Codec
public record RetentionDeclaration(Option<Long> maxAgeMs,
                                   Option<Long> maxBytes,
                                   Option<Long> maxCount,
                                   Option<RetentionMode> mode) {
    public static RetentionDeclaration retentionDeclaration(Option<Long> maxAgeMs,
                                                            Option<Long> maxBytes,
                                                            Option<Long> maxCount,
                                                            Option<RetentionMode> mode) {
        return new RetentionDeclaration(maxAgeMs, maxBytes, maxCount, mode);
    }
}
