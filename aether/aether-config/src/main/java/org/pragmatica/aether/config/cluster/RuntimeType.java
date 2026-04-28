// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.Arrays;


public enum RuntimeType {
    CONTAINER("container"),
    JVM("jvm"),
    DOCKER("docker"),
    EMBER("ember"),
    MANAGED_CONTAINER("managed-container");
    private static final Cause INVALID_TYPE = Causes.cause("Invalid runtime type: must be 'container', 'jvm', 'docker', 'ember', or 'managed-container'");
    private final String value;
    RuntimeType(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    public static Result<RuntimeType> runtimeType(String raw) {
        return Arrays.stream(values()).filter(rt -> rt.value.equals(raw))
                            .findFirst()
                            .map(Result::success)
                            .orElseGet(INVALID_TYPE::result);
    }
}
