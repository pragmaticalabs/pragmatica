// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;


public enum NetworkingType {
    MANUAL("manual");
    private static final Cause INVALID_TYPE = Causes.cause("Invalid networking type: must be 'manual'");
    private final String value;
    NetworkingType(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    public static Result<NetworkingType> networkingType(String raw) {
        return Arrays.stream(values()).filter(nt -> nt.value.equals(raw))
                            .findFirst()
                            .map(Result::success)
                            .orElseGet(INVALID_TYPE::result);
    }
}
