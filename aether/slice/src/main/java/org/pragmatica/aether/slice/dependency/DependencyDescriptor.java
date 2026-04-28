// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.dependency;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.option;


@SuppressWarnings("JBCT-UTIL-02") public record DependencyDescriptor(String sliceClassName,
                                                                     VersionPattern versionPattern,
                                                                     Option<String> parameterName) {
    public static Result<DependencyDescriptor> dependencyDescriptor(String line) {
        var trimmed = line.trim();
        if (trimmed.isEmpty()) {return EMPTY_LINE.result();}
        if (trimmed.startsWith("#")) {return COMMENT_LINE.result();}
        var parts = trimmed.split(":", - 1);
        if (parts.length <2) {return INVALID_FORMAT.apply(line).result();}
        if (parts.length > 3) {return TOO_MANY_PARTS.apply(line).result();}
        var className = parts[0].trim();
        var versionStr = parts[1].trim();
        var paramName = parts.length == 3
                       ? option(parts[2].trim())
                       : Option.<String>none();
        if (className.isEmpty()) {return EMPTY_CLASS_NAME.apply(line).result();}
        if (versionStr.isEmpty()) {return EMPTY_VERSION_PATTERN.apply(line).result();}
        return VersionPattern.parse(versionStr).map(pattern -> new DependencyDescriptor(className, pattern, paramName));
    }

    public String asString() {
        var base = sliceClassName + ":" + versionPattern.asString();
        return parameterName.map(name -> base + ":" + name).or(base);
    }

    private static final Cause EMPTY_LINE = Causes.cause("Dependency descriptor line is empty");

    private static final Cause COMMENT_LINE = Causes.cause("Dependency descriptor line is a comment");

    private static final Fn1<Cause, String> INVALID_FORMAT = Causes.forOneValue("Invalid dependency descriptor format: %s");

    private static final Fn1<Cause, String> TOO_MANY_PARTS = Causes.forOneValue("Too many parts in dependency descriptor: %s");

    private static final Fn1<Cause, String> EMPTY_CLASS_NAME = Causes.forOneValue("Empty class name in dependency descriptor: %s");

    private static final Fn1<Cause, String> EMPTY_VERSION_PATTERN = Causes.forOneValue("Empty version pattern in dependency descriptor: %s");
}
