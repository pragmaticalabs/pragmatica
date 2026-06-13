// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;


public enum SourceType {
    CLOUD("cloud"),
    SSH("ssh"),
    FORGE("forge"),
    DOCKER("docker");
    private static final Cause INVALID_TYPE = Causes.cause("Invalid source type: must be 'cloud', 'ssh', 'forge', or 'docker'");
    private final String value;
    SourceType(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    public static Result<SourceType> sourceType(String raw) {
        return Arrays.stream(values())
                     .filter(st -> st.value.equals(raw))
                     .findFirst()
                     .map(Result::success)
                     .orElseGet(INVALID_TYPE::result);
    }
}
