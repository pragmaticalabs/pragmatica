// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


@Codec
public enum SecurityOverridePolicy {
    STRENGTHEN_ONLY,
    FULL,
    NONE,
    /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
    /// Rejects every override -- an unreadable policy must never authorise weakening a route.
    /// Must stay LAST -- a new constant appended after it, or inserted before it, is read as UNKNOWN
    /// by an older node either way.
    UNKNOWN;
    /// The policy a config file names, or empty for a token that names none. Unlike [#fromString], an
    /// unknown token is not mapped to a default: the config parser refuses it (#1455).
    public static Option<SecurityOverridePolicy> securityOverridePolicy(String raw) {
        return switch (raw.toLowerCase()
                          .strip()) {
            case "strengthen_only" -> Option.some(STRENGTHEN_ONLY);
            case "full" -> Option.some(FULL);
            case "none" -> Option.some(NONE);
            default -> Option.none();
        };
    }
    public static SecurityOverridePolicy fromString(String raw) {
        return switch (raw.toLowerCase()
                          .strip()) {
            case "strengthen_only" -> STRENGTHEN_ONLY;
            case "full" -> FULL;
            case "none" -> NONE;
            default -> STRENGTHEN_ONLY;
        };
    }
}
