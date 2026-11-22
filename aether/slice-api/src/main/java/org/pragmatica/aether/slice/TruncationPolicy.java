// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.serialization.Codec;


/// A stream's `[truncation] policy` (#3334): `window` reclaims a prefix once it leaves the replay window, read or not;
/// `until_consumed` reclaims a prefix once every holding group has committed past it, with the replay window as the cap.
@Codec
public enum TruncationPolicy {
    WINDOW,
    UNTIL_CONSUMED,
    /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
    /// Must stay LAST — a new constant appended after it, or inserted before it, is read as UNKNOWN
    /// by an older node either way.
    UNKNOWN
}
