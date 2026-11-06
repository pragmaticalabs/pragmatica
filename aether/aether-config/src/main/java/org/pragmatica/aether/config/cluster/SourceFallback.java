// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.Arrays;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Whether capacity may fall back to another bound source when this one cannot serve a request
/// (`[source.<name>] fallback`, rc6 spec §8.1). `forbid` stops selection at the first eligible source.
public enum SourceFallback {
    ALLOW("allow"),
    FORBID("forbid");
    private static final Cause INVALID_FALLBACK = Causes.cause("Invalid fallback: must be 'allow' or 'forbid'");
    private final String value;
    SourceFallback(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    public static Result<SourceFallback> sourceFallback(String raw) {
        return Arrays.stream(values())
                     .filter(fallback -> fallback.value.equals(raw))
                     .findFirst()
                     .map(Result::success)
                     .orElseGet(INVALID_FALLBACK::result);
    }
}
