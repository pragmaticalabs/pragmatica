// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.pragmatica.lang.Option;


/// Configuration for a single API version of a slice (#198 §4).
///
/// Produced from a `[vN.routes]` block plus its sibling `[vN]` metadata block. Each bind key
/// in `[vN.routes]` is resolved to a concrete slice method (decision D8: `get` → `getV{N}`,
/// unless an inline-table `method = "..."` override is given) and the route path is mounted at
/// `{api.prefix}/v{N}/{route path}` (path-mode routing, #198 §4.3).
///
/// The metadata fields (`deprecated`, `sunset`, `defaultIfMissing`) are parsed and stored now
/// and consumed by later phases (header emission, version-registry endpoint); only `sunset`
/// is validated at compile time (must be an RFC3339/ISO date).
///
/// @param version          the numeric version (the `N` in `[vN.routes]`)
/// @param routes           map of resolved slice method name to parsed RouteDsl (path already carries `/vN`)
/// @param routeSecurity    per-route security overrides keyed by resolved slice method name
/// @param bindKeyToMethod  map of declared bind key (e.g. `get`) to resolved slice method name (e.g. `getV1`)
/// @param deprecated       whether this version is marked deprecated (stored for later header emission)
/// @param sunset           optional RFC3339/ISO sunset date (validated now, emitted later)
/// @param defaultIfMissing whether this version is the default when no version is specified
public record VersionConfig(int version,
                            Map<String, RouteDsl> routes,
                            Map<String, RouteSecurityLevel> routeSecurity,
                            Map<String, String> bindKeyToMethod,
                            boolean deprecated,
                            Option<String> sunset,
                            boolean defaultIfMissing) {
    public VersionConfig {
        routes = Collections.unmodifiableMap(new LinkedHashMap<>(routes));
        routeSecurity = Collections.unmodifiableMap(new LinkedHashMap<>(routeSecurity));
        bindKeyToMethod = Collections.unmodifiableMap(new LinkedHashMap<>(bindKeyToMethod));
    }

    /// The path segment this version mounts under (e.g. `/v1`).
    public String versionSegment() {
        return "/v" + version;
    }
}
