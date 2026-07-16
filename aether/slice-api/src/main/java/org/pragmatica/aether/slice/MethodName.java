// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Verify.ensure;


public record MethodName(String name) {
    public static Result<MethodName> methodName(String name) {
        return Result.all(ensure(name, Verify.Is::matches, METHOD_NAME_PATTERN)).map(MethodName::new);
    }

    @Override
    public String toString() {
        return name;
    }

    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("^[a-z][a-zA-Z0-9]+$");
}
