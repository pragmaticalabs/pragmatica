// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;


/// Shared parsers for `?state=<x>[+<y>+...]`-style query filters used by management routes.
///
/// The wire contract: a single-state filter (`?state=ACTIVE`) is the common case; multi-state
/// union (`?state=LOADED+ACTIVE`) lets operators ask for the membership of a set in one call,
/// eliminating the LOADED+ACTIVE union grep that the integration-test shell helpers used to
/// run client-side. The parser is liberal in what it accepts: case-insensitive, whitespace-
/// tolerant, drops empty parts. An empty resulting set (e.g. `?state=+`) deliberately matches
/// no instance — callers can use it as a fail-closed sentinel.
///
/// Two callsites currently use this:
///   1. `SliceRoutes.buildClusterSlicesResponse` — filters `instances[]` per slice
///   2. `NodeLifecycleRoutes.getAllLifecycleStates` — filters the node lifecycle list
///
/// Add the next callsite here too rather than re-inlining.
public final class RouteFilters {
    private RouteFilters() {}

    /// Parse a `+`-separated state filter into an uppercase set of state names.
    /// Accepts `+`, whitespace, or commas as separators — robust to URL form encoding,
    /// which decodes a literal `+` in query strings to a space (so the wire receives
    /// `LOADED ACTIVE` when the operator typed `--state LOADED+ACTIVE`). Empty / blank
    /// parts are dropped. The resulting set is the membership predicate applied per
    /// record. Empty set (e.g. `?state=+`) matches nothing.
    public static Set<String> parseStateFilter(String input) {
        return Arrays.stream(input.split("[+,\\s]+"))
                     .map(s -> s.trim()
                                .toUpperCase(Locale.ROOT))
                     .filter(s -> !s.isEmpty())
                     .collect(Collectors.toUnmodifiableSet());
    }
}
