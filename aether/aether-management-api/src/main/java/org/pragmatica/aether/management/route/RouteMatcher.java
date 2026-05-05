// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;

import org.pragmatica.http.routing.HttpMethod;
import org.pragmatica.lang.Result;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/// Matches incoming HTTP request paths to {@link ManagementRoute} entries.
///
/// Routes are indexed by `(method, prefix, segmentSignature)` where the segment signature
/// is a structural fingerprint (literal text vs. param wildcard) of the segments that come
/// after the prefix. This handles both legacy tail-only-params routes and path templates
/// with literals interleaved between parameters (e.g. spec §12 stream routes).
public final class RouteMatcher {
    private static final RouteMatcher SHARED = buildOrFail(ManagementRoute.values());

    private final Map<HttpMethod, List<ManagementRoute>> routesByMethod;

    private RouteMatcher(Map<HttpMethod, List<ManagementRoute>> routesByMethod) {
        this.routesByMethod = routesByMethod;
    }

    public static RouteMatcher shared() {
        return SHARED;
    }

    static Result<RouteMatcher> build(ManagementRoute[] routes) {
        var byMethod = new HashMap<HttpMethod, List<ManagementRoute>>();
        var dedupe = new HashMap<MatchKey, ManagementRoute>();
        for (var r : routes) {
            var key = new MatchKey(r.method(), r.prefix(), signature(r));
            var existing = dedupe.put(key, r);
            if (existing != null) {return ManagementRouteError.ambiguousRoutes(existing.name(),
                                                                               r.name(),
                                                                               key.toString())
            .result();}
            byMethod.computeIfAbsent(r.method(), _ -> new ArrayList<>()).add(r);
        }
        var copy = new HashMap<HttpMethod, List<ManagementRoute>>();
        byMethod.forEach((m, list) -> copy.put(m, List.copyOf(list)));
        return Result.success(new RouteMatcher(Map.copyOf(copy)));
    }

    private static RouteMatcher buildOrFail(ManagementRoute[] routes) {
        return build(routes).expect("ManagementRoute enum contains ambiguous routes - coding bug");
    }

    public Result<MatchedRoute> match(HttpMethod method, String rawPath) {
        var segments = splitSegments(rawPath);
        var candidates = routesByMethod.get(method);
        if (candidates == null) {return ManagementRouteError.noMatch(method, rawPath).result();}
        // Specificity tie-breakers (in order):
        //   1. longer literal prefix wins (e.g. /repository/info beats /repository for ARTIFACT_INFO)
        //   2. more literal segments in the path template wins (e.g. /api/streams/{ns}/{stream}/latest
        //      beats /api/streams/{ns}/{stream}/{version})
        //   3. more total segments wins
        ManagementRoute best = null;
        List<String> bestValues = null;
        int bestPrefixLen = -1;
        int bestLiterals = -1;
        int bestSegments = -1;
        for (var route : candidates) {
            var values = tryMatch(route, segments);
            if (values == null) {continue;}
            int prefixLen = route.prefix().length();
            int literals = countLiterals(route);
            int segs = route.segmentCount();
            if (isMoreSpecific(prefixLen, literals, segs, bestPrefixLen, bestLiterals, bestSegments)) {
                best = route;
                bestValues = values;
                bestPrefixLen = prefixLen;
                bestLiterals = literals;
                bestSegments = segs;
            }
        }
        if (best != null) {return Result.success(MatchedRoute.matchedRoute(best, bestValues));}
        return ManagementRouteError.noMatch(method, rawPath).result();
    }

    private static boolean isMoreSpecific(int prefixLen, int literals, int segs,
                                          int bestPrefixLen, int bestLiterals, int bestSegments) {
        if (prefixLen != bestPrefixLen) {return prefixLen > bestPrefixLen;}
        if (literals != bestLiterals) {return literals > bestLiterals;}
        return segs > bestSegments;
    }

    private static List<String> tryMatch(ManagementRoute route, List<String> segments) {
        var prefixSegments = splitSegments(route.prefix());
        var totalExpected = prefixSegments.size() + route.segmentCount();
        if (segments.size() != totalExpected) {return null;}
        for (int i = 0; i < prefixSegments.size(); i++) {
            if (!prefixSegments.get(i).equals(segments.get(i))) {return null;}
        }
        var values = new ArrayList<String>(route.paramCount());
        var routeSegments = route.segments();
        for (int i = 0; i < routeSegments.size(); i++) {
            var seg = routeSegments.get(i);
            var actual = segments.get(prefixSegments.size() + i);
            if (seg.isParam()) {
                values.add(URLDecoder.decode(actual, StandardCharsets.UTF_8));
            } else if (!seg.text().equals(URLDecoder.decode(actual, StandardCharsets.UTF_8))) {
                return null;
            }
        }
        return values;
    }

    private static int countLiterals(ManagementRoute route) {
        return (int) route.segments().stream().filter(s -> !s.isParam()).count();
    }

    private static List<String> splitSegments(String path) {
        var queryIdx = path.indexOf('?');
        var trimmed = queryIdx >= 0 ? path.substring(0, queryIdx) : path;
        var segments = new ArrayList<String>();
        for (var seg : trimmed.split("/")) {
            if (!seg.isEmpty()) {segments.add(seg);}
        }
        return segments;
    }

    private static String signature(ManagementRoute route) {
        var sb = new StringBuilder();
        for (var seg : route.segments()) {
            sb.append('/');
            if (seg.isParam()) {
                sb.append('*');
            } else {
                sb.append(seg.text());
            }
        }
        return sb.toString();
    }

    private record MatchKey(HttpMethod method, String prefix, String signature) {}
}
