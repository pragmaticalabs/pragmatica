// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http.handler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;


/// What makes two HTTP routes the SAME route, defined once (#1206).
///
/// The runtime matcher keys a route by its method, its normalized prefix and its shape: how many trailing segments follow
/// the prefix (`arity`), which of them are literal (`spacers`) and where (`spacerSlots`). Blueprint admission, the committed
/// route table's collision announcer and the runtime route extractor all ask the same question, so they all answer it
/// here; a second copy of the normalization would drift from the first, and the drift is exactly a collision that slips past
/// admission or a warning for two routes the runtime tells apart.
///
/// [#ofTemplate] derives the identity from a route's PATH TEMPLATE as the slice generator derives the route it compiles: the
/// prefix is the template up to its first `{` (so `/files/x{a}` has the prefix `/files/x/`), and what follows is, in order, a
/// path parameter per `{...}` and a literal per non-blank `/`-separated chunk between them.
public record RouteIdentity(String method, String prefix, int arity, List<String> spacers, List<Integer> spacerSlots) {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^}]*}");

    public RouteIdentity {
        Objects.requireNonNull(method, "method");
        method = method.toUpperCase();
        prefix = normalizePrefix(prefix);
        spacers = List.copyOf(Objects.requireNonNull(spacers, "spacers"));
        spacerSlots = List.copyOf(Objects.requireNonNull(spacerSlots, "spacerSlots"));
    }

    public static RouteIdentity routeIdentity(String method,
                                              String prefix,
                                              int arity,
                                              List<String> spacers,
                                              List<Integer> spacerSlots) {
        return new RouteIdentity(method, prefix, arity, spacers, spacerSlots);
    }

    /// The identity of a route declared by its path template.
    public static RouteIdentity ofTemplate(String method, String pathTemplate) {
        var prefix = prefixOf(pathTemplate);
        var remainder = pathTemplate.substring(Math.min(prefix.length(), pathTemplate.length()));
        var spacers = new ArrayList<String>();
        var slots = new ArrayList<Integer>();
        var matcher = PLACEHOLDER.matcher(remainder);
        var cursor = 0;
        var slot = 0;

        while (matcher.find()) {
            slot = literalsIn(remainder.substring(cursor, matcher.start()),
                              spacers,
                              slots,
                              slot);
            slot++;
            cursor = matcher.end();
        }

        slot = literalsIn(remainder.substring(cursor), spacers, slots, slot);

        return new RouteIdentity(method, prefix, slot, spacers, slots);
    }

    /// The identity of a route as the runtime holds it.
    public static RouteIdentity ofDefinition(HttpRouteDefinition definition) {
        return new RouteIdentity(definition.httpMethod(),
                                 definition.pathPrefix(),
                                 definition.pathArity(),
                                 definition.spacers(),
                                 definition.spacerSlots());
    }

    /// A template up to its first path-parameter placeholder: the prefix every request for the route starts with.
    public static String prefixOf(String pathTemplate) {
        var index = pathTemplate.indexOf('{');

        return index >= 0
               ? pathTemplate.substring(0, index)
               : pathTemplate;
    }

    /// The prefix as every route lookup compares it: leading and trailing slash, so `startsWith` is a segment-boundary
    /// comparison.
    public static String normalizePrefix(String path) {
        Objects.requireNonNull(path, "path");
        var normalized = path.isBlank()
                         ? "/"
                         : path.strip();

        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }

        if (!normalized.endsWith("/")) {
            normalized = normalized + "/";
        }

        return normalized;
    }

    /// `METHOD prefix`, plus the shape when the route has one, for messages.
    public String describe() {
        return arity == 0
               ? method + " " + prefix
               : method
                + " " + prefix
                + " [arity " + arity + (spacers.isEmpty()
                                        ? ""
                                        : ", literals " + spacers + " at " + spacerSlots)
                + "]";
    }

    private static int literalsIn(String chunk, List<String> spacers, List<Integer> slots, int slot) {
        var next = slot;

        for (var part : chunk.split("/")) {
            if (!part.isBlank()) {
                spacers.add(part);
                slots.add(next);
                next++;
            }
        }

        return next;
    }
}
