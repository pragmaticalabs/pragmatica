// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.regex.Pattern;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The label values the apply builds provider selectors from (the cluster name, the apply id, a node id), checked BEFORE any
/// provider list or terminate call. A selector is joined by `,` and cut by `=`, `!`, `(`, `)`, `<`, `>` and spaces on the
/// provider's side, so a value carrying any of them can rewrite the query; an empty value matches nothing or everything.
/// Only the provider-safe set `[a-zA-Z0-9_.-]`, 1 to 63 characters, passes; anything else is refused typed.
@SuppressWarnings("JBCT-UTIL-02")
final class ApplyLabels {
    private static final Pattern SAFE = Pattern.compile("^[a-zA-Z0-9_.-]{1,63}$");

    private ApplyLabels() {}

    static Result<Unit> requireSafe(String what, String value) {
        return Option.option(value)
                     .filter(candidate -> SAFE.matcher(candidate).matches())
                     .isPresent()
               ? Result.unitResult()
               : new UnsafeLabelValue(what, value).result();
    }

    /// The two values every deletion selector is built from.
    static Result<Unit> requireSafe(ClusterName cluster, String applyId) {
        return requireSafe("cluster name", cluster.value()).flatMap(_ -> requireSafe("apply id", applyId));
    }

    record UnsafeLabelValue(String what, String value) implements Cause {
        @Override
        public String message() {
            return "Refusing to query or touch any provider: the " + what
                 + " '" + value
                 + "' is empty or contains characters outside [a-zA-Z0-9_.-] (max 63), so a label selector built from it could match more than this apply's servers."
                 + " Nothing was listed, created or terminated.";
        }
    }
}
