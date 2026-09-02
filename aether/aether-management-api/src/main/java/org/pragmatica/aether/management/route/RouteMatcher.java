// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.management.route;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.Result;


public final class RouteMatcher {
    private static final RouteMatcher SHARED = buildOrFail(ManagementRoute.values());

    private final Map<BucketKey, List<ManagementRoute>> buckets;

    private RouteMatcher(Map<BucketKey, List<ManagementRoute>> buckets) {
        this.buckets = buckets;
    }

    public static RouteMatcher shared() {
        return SHARED;
    }

    /// Groups routes by `(method, token count)` -- a concrete path has a fixed segment count, so
    /// two routes can only ever compete for the same request if their token counts match. Within a
    /// bucket, every newly-inserted route is checked against every route already there; a
    /// collision fails the build (see [#ambiguous]).
    static Result<RouteMatcher> build(ManagementRoute[] routes) {
        var buckets = new HashMap<BucketKey, List<ManagementRoute>>();

        for (var r : routes) {
            var key = new BucketKey(r.method(),
                                    r.tokens().size());
            var bucket = buckets.computeIfAbsent(key, _ -> new ArrayList<ManagementRoute>());

            for (var existing : bucket) {
                if (ambiguous(existing.tokens(), r.tokens())) {
                    return ManagementRouteError.ambiguousRoutes(existing.name(),
                                                                r.name(),
                                                                key.toString())
                                               .result();
                }
            }

            bucket.add(r);
        }

        var frozen = new HashMap<BucketKey, List<ManagementRoute>>();

        buckets.forEach((key, bucket) -> frozen.put(key, List.copyOf(bucket)));

        return Result.success(new RouteMatcher(Map.copyOf(frozen)));
    }

    private static RouteMatcher buildOrFail(ManagementRoute[] routes) {
        return build(routes).expect("ManagementRoute enum contains ambiguous routes - coding bug");
    }

    /// Among the routes whose literal-token positions all match the incoming segments, the one
    /// with the most literal tokens wins. This is a total order within any bucket that passed
    /// [#build] -- any two routes that both structurally match a given path are, by construction,
    /// either incompatible (impossible, since both matched) or one properly dominates the other
    /// (see [#dominates]) -- so the maximum by literal count always exists and is unique.
    public Result<MatchedRoute> match(HttpMethod method, String rawPath) {
        var segments = splitSegments(rawPath);
        var bucket = buckets.getOrDefault(new BucketKey(method, segments.size()), List.of());
        ManagementRoute best = null;
        var bestLiteralCount = -1;

        for (var route : bucket) {
            var literalCount = matchLiteralCount(route.tokens(), segments);

            if (literalCount > bestLiteralCount) {
                best = route;
                bestLiteralCount = literalCount;
            }
        }

        if (best == null) {
            return ManagementRouteError.noMatch(method, rawPath).result();
        }

        var values = decode(extractParamValues(best.tokens(), segments));

        return Result.success(MatchedRoute.matchedRoute(best, values));
    }

    /// Returns the number of literal tokens if every literal (`Spacer`) position in `tokens`
    /// matches the identically-indexed segment, or -1 if any literal position mismatches (no
    /// structural match). Package-private and pure so the mechanism is directly pinnable against
    /// synthetic token sequences, without needing real `ManagementRoute` enum entries.
    static int matchLiteralCount(List<PathToken> tokens, List<String> segments) {
        var count = 0;

        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i) instanceof PathToken.Spacer(var text)) {
                if (!text.equals(segments.get(i))) {
                    return -1;
                }

                count++;
            }
        }

        return count;
    }

    static List<String> extractParamValues(List<PathToken> tokens, List<String> segments) {
        var values = new ArrayList<String>();

        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i) instanceof PathToken.Param) {
                values.add(segments.get(i));
            }
        }

        return values;
    }

    /// Domination-based ambiguity check (see [PathToken]). Two same-`(method, length)` routes are
    /// genuinely ambiguous only if some concrete path could satisfy both (`compatible`) and
    /// neither's literal placement is a proper superset of the other's (`properlyDominates`).
    /// Mutual domination -- identical literal placement, differing only in param names -- is
    /// ambiguous too (a route dominating itself both ways is a duplicate, not a specificity
    /// ordering), which is why this rejects on "neither properly dominates" rather than "not
    /// pairwise compatible at every position": a naive "ambiguous iff every position is pairwise
    /// compatible" rule would false-positive on legitimate specificity pairs, e.g.
    /// `[streams,namespaces,{ns}]` vs `[streams,{ns},{stream}]` -- compatible at every position,
    /// but the first properly dominates the second, so today's longest-literal-prefix-wins
    /// behavior already resolves it without collision.
    static boolean ambiguous(List<PathToken> a, List<PathToken> b) {
        return compatible(a, b)
               && !properlyDominates(a, b)
               && !properlyDominates(b, a);
    }

    private static boolean compatible(List<PathToken> a, List<PathToken> b) {
        for (int i = 0; i < a.size(); i++) {
            if (a.get(i) instanceof PathToken.Spacer(var ta) && b.get(i) instanceof PathToken.Spacer(var tb) && !ta.equals(tb)) {
                return false;
            }
        }

        return true;
    }

    private static boolean properlyDominates(List<PathToken> a, List<PathToken> b) {
        return dominates(a, b) && !dominates(b, a);
    }

    /// `a` dominates `b` iff, at every position where `b` has a literal, `a` has the identical
    /// literal -- `a` may have MORE literals elsewhere, making it the more specific route.
    private static boolean dominates(List<PathToken> a, List<PathToken> b) {
        for (int i = 0; i < b.size(); i++) {
            if (b.get(i) instanceof PathToken.Spacer(var tb) && !(a.get(i) instanceof PathToken.Spacer(var ta) && ta.equals(tb))) {
                return false;
            }
        }

        return true;
    }

    private static List<String> splitSegments(String path) {
        var queryIdx = path.indexOf('?');
        var trimmed = queryIdx >= 0
                      ? path.substring(0, queryIdx)
                      : path;
        var segments = new ArrayList<String>();

        for (var seg : trimmed.split("/")) {
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }

        return segments;
    }

    private static List<String> decode(List<String> segments) {
        var decoded = new ArrayList<String>(segments.size());

        for (var seg : segments) {
            decoded.add(URLDecoder.decode(seg, StandardCharsets.UTF_8));
        }

        return decoded;
    }

    private record BucketKey(HttpMethod method, int length) {}
}
