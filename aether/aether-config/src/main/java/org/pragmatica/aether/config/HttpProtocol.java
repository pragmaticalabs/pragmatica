// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;


public enum HttpProtocol {
    H1,
    H3,
    BOTH;
    public static Option<HttpProtocol> httpProtocol(String value) {
        return Option.option(value).map(String::trim)
                            .map(String::toLowerCase)
                            .flatMap(HttpProtocol::fromNormalized);
    }
    public boolean includesH1() {
        return this == H1 || this == BOTH;
    }
    public boolean includesH3() {
        return this == H3 || this == BOTH;
    }
    public boolean requiresTls() {
        return this == H3 || this == BOTH;
    }
    private static Option<HttpProtocol> fromNormalized(String normalized) {
        return switch (normalized){
            case "h1", "http1", "http/1.1" -> Option.some(H1);
            case "h3", "http3", "http/3" -> Option.some(H3);
            case "both", "dual" -> Option.some(BOTH);
            default -> Option.empty();
        };
    }
}
