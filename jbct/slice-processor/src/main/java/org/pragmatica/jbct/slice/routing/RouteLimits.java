// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

/// The ingest limits an inline-table route declares in `routes.toml`: `max_size` (request body bytes) and `max_duration`
/// (total time the body may take). Zero is "not declared": the node's default applies.
///
/// @param maxSizeBytes      `max_size` in bytes, zero when not declared
/// @param maxDurationMillis `max_duration` in milliseconds, zero when not declared
public record RouteLimits(long maxSizeBytes, long maxDurationMillis) {
    public static final RouteLimits NONE = new RouteLimits(0, 0);

    public static RouteLimits routeLimits(long maxSizeBytes, long maxDurationMillis) {
        return new RouteLimits(maxSizeBytes, maxDurationMillis);
    }

    public boolean declared() {
        return maxSizeBytes > 0 || maxDurationMillis > 0;
    }
}
