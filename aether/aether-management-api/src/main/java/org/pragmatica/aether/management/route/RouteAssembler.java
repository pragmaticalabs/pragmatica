// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.pragmatica.lang.Result;


public final class RouteAssembler {
    private RouteAssembler() {}

    public static Result<String> assemble(ManagementRoute route, List<String> values) {
        if (values.size() != route.paramCount()) {
            return ManagementRouteError.wrongParamCount(route.name(),
                                                        route.paramCount(),
                                                        values.size())
                                       .result();
        }

        var paramIndex = 0;

        for (var value : values) {
            if (value == null) {
                return ManagementRouteError.missingParam(route.name(),
                                                         route.paramNames().get(paramIndex))
                                           .result();
            }

            paramIndex++;
        }

        return Result.success(render(route.tokens(), values));
    }

    /// Walks the token sequence, appending each `Spacer`'s literal text verbatim and encoding
    /// param values only at `Param` positions -- generalizing the old "prefix, then every value
    /// appended trailing" assembly to arbitrary literal/param interleaving. For a tail-only route
    /// (every existing route before this generalization) `tokens` is exactly literal-run followed
    /// by param-run, so this produces byte-for-byte the same output as the old
    /// `prefix + trailing values` construction. Package-private and pure so the mechanism is
    /// directly pinnable against synthetic token sequences, without needing real `ManagementRoute`
    /// enum entries.
    static String render(List<PathToken> tokens, List<String> values) {
        var sb = new StringBuilder();
        var paramIndex = 0;

        for (var token : tokens) {
            if (token instanceof PathToken.Spacer(var text)) {
                sb.append('/').append(text);
            } else {
                sb.append('/').append(encodeSegment(values.get(paramIndex)));
                paramIndex++;
            }
        }

        return sb.toString();
    }

    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                         .replace("+", "%20")
                         .replace("%2F", "/");
    }
}
