// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.serialization.Codec;

/// The retention dimensions a stream DECLARED (#2201 Q6 = B, know 59dfb9e86c): sealed-tier retention bounds exactly these.
/// A [RetentionPolicy] carries values for all three dimensions, because the ring is sized from them, so the values alone
/// cannot say which of them the stream asked for: a `time` stream carries a filled-in count and size it never declared.
@Codec
public record RetentionDimensions(boolean count, boolean bytes, boolean age) {
    /// Nothing declared: the sealed tier is ruled by the node default age alone.
    public static final RetentionDimensions NONE = new RetentionDimensions(false, false, false);
    public static final RetentionDimensions COUNT = new RetentionDimensions(true, false, false);
    public static final RetentionDimensions BYTES = new RetentionDimensions(false, true, false);
    public static final RetentionDimensions AGE = new RetentionDimensions(false, false, true);

    public static RetentionDimensions retentionDimensions(boolean count, boolean bytes, boolean age) {
        return new RetentionDimensions(count, bytes, age);
    }

    public boolean any() {
        return count || bytes || age;
    }
}
