// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.management.route;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;


public record MatchedRoute(ManagementRoute route, Map<String, String> params) {
    public static MatchedRoute matchedRoute(ManagementRoute route, List<String> values) {
        var paramMap = new LinkedHashMap<String, String>();
        var names = route.paramNames();

        for (int i = 0; i < names.size(); i++) {
            paramMap.put(names.get(i), values.get(i));
        }

        return new MatchedRoute(route, Map.copyOf(paramMap));
    }

    public Option<String> param(String name) {
        return Option.option(params.get(name));
    }
}
