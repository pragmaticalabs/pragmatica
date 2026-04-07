/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.pragmatica.aether.management.route;

import org.pragmatica.lang.Result;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;


/// Reverse path construction: given a [ManagementRoute] enum value and parameter values,
/// produce the concrete path string with URL-encoded segments.
///
/// Layout enforced by [ManagementRoute]: `prefix + "/" + value1 + "/" + value2 + ...`.
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
