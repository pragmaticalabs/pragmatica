// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;

import org.pragmatica.lang.Result;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;


public final class RouteAssembler {
    private RouteAssembler() {}

    public static Result<String> assemble(ManagementRoute route, List<String> values) {
        if (values.size() != route.paramCount()) {return ManagementRouteError.wrongParamCount(route.name(),
                                                                                              route.paramCount(),
                                                                                              values.size())
        .result();}
        var sb = new StringBuilder(route.prefix());
        for (int i = 0;i <values.size();i++) {
            var value = values.get(i);
            if (value == null) {return ManagementRouteError.missingParam(route.name(),
                                                                         route.paramNames().get(i))
            .result();}
            sb.append('/').append(encodeSegment(value));
        }
        return Result.success(sb.toString());
    }

    private static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
                                .replace("%2F", "/");
    }
}
