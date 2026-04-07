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

import org.pragmatica.lang.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


/// Result of matching an incoming HTTP request path against [ManagementRoute] templates.
///
/// @param route  the matched route enum value
/// @param params ordered parameter name → value map (insertion order preserved)
public record MatchedRoute(ManagementRoute route, Map<String, String> params) {
    public static MatchedRoute matchedRoute(ManagementRoute route, List<String> values) {
        var paramMap = new LinkedHashMap<String, String>();
        var names = route.paramNames();
        for (int i = 0;i <names.size();i++) {paramMap.put(names.get(i), values.get(i));}
        return new MatchedRoute(route, Map.copyOf(paramMap));
    }

    public Option<String> param(String name) {
        return Option.option(params.get(name));
    }
}
