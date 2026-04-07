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

import org.pragmatica.http.routing.HttpMethod;
import org.pragmatica.lang.Result;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/// Matches incoming `(method, path)` tuples against [ManagementRoute] templates.
///
/// The matcher relies on the invariant enforced by [ManagementRoute]: every template has the shape
/// `<static prefix>/<param1>/.../<paramN>` — all path parameters follow a fixed static prefix.
/// This allows `O(1)` lookup by `(method, prefix, paramCount)` after splitting the incoming path
/// into segments and iterating longest-prefix-first.
public final class RouteMatcher {
    private static final RouteMatcher SHARED = buildOrFail(ManagementRoute.values());

    private final Map<MatchKey, ManagementRoute> index;

    private RouteMatcher(Map<MatchKey, ManagementRoute> index) {
        this.index = index;
    }

    public static RouteMatcher shared() {
        return SHARED;
    }

    static Result<RouteMatcher> build(ManagementRoute[] routes) {
        var index = new HashMap<MatchKey, ManagementRoute>();
        for (var r : routes) {
            var key = new MatchKey(r.method(), r.prefix(), r.paramCount());
            var existing = index.put(key, r);
            if (existing != null) {return ManagementRouteError.ambiguousRoutes(existing.name(),
                                                                               r.name(),
                                                                               key.toString())
            .result();}
        }
        return Result.success(new RouteMatcher(Map.copyOf(index)));
    }

    private static RouteMatcher buildOrFail(ManagementRoute[] routes) {
        return build(routes).unwrap();
    }

    public Result<MatchedRoute> match(HttpMethod method, String rawPath) {
        var segments = splitSegments(rawPath);
        for (int prefixLen = segments.size();prefixLen >= 0;prefixLen--) {
            var prefix = buildPrefix(segments, prefixLen);
            var paramCount = segments.size() - prefixLen;
            var route = index.get(new MatchKey(method, prefix, paramCount));
            if (route != null) {
                var values = decode(segments.subList(prefixLen, segments.size()));
                return Result.success(MatchedRoute.matchedRoute(route, values));
            }
        }
        return ManagementRouteError.noMatch(method, rawPath).result();
    }

    private static List<String> splitSegments(String path) {
        var queryIdx = path.indexOf('?');
        var trimmed = queryIdx >= 0
                     ? path.substring(0, queryIdx)
                     : path;
        var segments = new ArrayList<String>();
        for (var seg : trimmed.split("/")) {if (!seg.isEmpty()) {segments.add(seg);}}
        return segments;
    }

    private static String buildPrefix(List<String> segments, int count) {
        if (count == 0) {return "";}
        var sb = new StringBuilder();
        for (int i = 0;i <count;i++) {sb.append('/').append(segments.get(i));}
        return sb.toString();
    }

    private static List<String> decode(List<String> segments) {
        var decoded = new ArrayList<String>(segments.size());
        for (var seg : segments) {decoded.add(URLDecoder.decode(seg, StandardCharsets.UTF_8));}
        return decoded;
    }

    private record MatchKey(HttpMethod method, String prefix, int paramCount){}
}
