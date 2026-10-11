// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.Arrays;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


public enum NodeRole {
    CORE("core"),
    WORKER("worker"),
    SPOT("spot");
    private static final Cause INVALID_TYPE = Causes.cause("Invalid node role: must be 'core', 'worker', or 'spot'");
    private final String value;
    NodeRole(String value) {
        this.value = value;
    }
    public String value() {
        return value;
    }
    public static Result<NodeRole> nodeRole(String raw) {
        return Arrays.stream(values())
                     .filter(nr -> nr.value.equals(raw))
                     .findFirst()
                     .map(Result::success)
                     .orElseGet(INVALID_TYPE::result);
    }
}
