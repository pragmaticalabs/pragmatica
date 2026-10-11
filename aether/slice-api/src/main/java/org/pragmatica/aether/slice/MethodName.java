// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
