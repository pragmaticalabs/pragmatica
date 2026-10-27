// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.jbct.slice.routing;

/// The ingest limits an inline-table route declares in `routes.toml`: `max_size` (request body bytes), `max_duration`
/// (total time the body may take) and the multipart part limits `max_files`, `max_file_size`, `max_fields` and
/// `max_field_size`. Zero is "not declared": the node's default applies.
///
/// @param maxSizeBytes      `max_size` in bytes, zero when not declared
/// @param maxDurationMillis `max_duration` in milliseconds, zero when not declared
/// @param maxFiles          `max_files`, zero when not declared
/// @param maxFileBytes      `max_file_size` in bytes, zero when not declared
/// @param maxFields         `max_fields`, zero when not declared
/// @param maxFieldBytes     `max_field_size` in bytes, zero when not declared
public record RouteLimits(long maxSizeBytes,
                          long maxDurationMillis,
                          long maxFiles,
                          long maxFileBytes,
                          long maxFields,
                          long maxFieldBytes) {
    public static final RouteLimits NONE = new RouteLimits(0, 0, 0, 0, 0, 0);

    public static RouteLimits routeLimits(long maxSizeBytes, long maxDurationMillis) {
        return new RouteLimits(maxSizeBytes, maxDurationMillis, 0, 0, 0, 0);
    }

    public static RouteLimits routeLimits(long maxSizeBytes,
                                          long maxDurationMillis,
                                          long maxFiles,
                                          long maxFileBytes,
                                          long maxFields,
                                          long maxFieldBytes) {
        return new RouteLimits(maxSizeBytes, maxDurationMillis, maxFiles, maxFileBytes, maxFields, maxFieldBytes);
    }

    /// Whether any of the four multipart part limits is declared.
    public boolean hasPartLimits() {
        return maxFiles > 0 || maxFileBytes > 0 || maxFields > 0 || maxFieldBytes > 0;
    }

    public boolean declared() {
        return maxSizeBytes > 0 || maxDurationMillis > 0 || hasPartLimits();
    }
}
