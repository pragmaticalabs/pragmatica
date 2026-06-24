// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;


/// Cluster-level #198 API-version detection mode (§7).
///
/// Selects how a deployed slice's versioned routes are exposed on the wire:
///   - [#PATH] (the default): the version travels in the URL (`{apiPrefix}/v{N}/{path}`).
///   - [#HEADER]: the version travels in the API-version request header; routes mount at the bare
///     `{apiPrefix}/{path}`.
///
/// Parsed from the `[app-http] api_versioning_detection` TOML key. Per-slice override is a
/// documented follow-up; cluster-level is the rc2 scope.
public enum ApiVersioningDetection {
    PATH,
    HEADER;

    public static Option<ApiVersioningDetection> apiVersioningDetection(String value) {
        return Option.option(value)
                     .map(String::trim)
                     .map(String::toLowerCase)
                     .flatMap(ApiVersioningDetection::fromNormalized);
    }

    public boolean isHeaderMode() {
        return this == HEADER;
    }

    private static Option<ApiVersioningDetection> fromNormalized(String normalized) {
        return switch (normalized) {
            case "path", "url", "uri" -> Option.some(PATH);
            case "header", "headers" -> Option.some(HEADER);
            default -> Option.empty();
        };
    }
}
