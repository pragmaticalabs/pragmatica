// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

/// Three-valued comparison of two process tokens; token 0 is "unknown" and never compares equal or different.
public enum TokenCompare {
    KNOWN_EQUAL,
    KNOWN_DIFFERENT,
    UNKNOWN;
    /// UNKNOWN if either side is 0, otherwise equal or different by value.
    public static TokenCompare compare(long a, long b) {
        if (a == 0 || b == 0) {
            return UNKNOWN;
        }

        return a == b
               ? KNOWN_EQUAL
               : KNOWN_DIFFERENT;
    }
}
