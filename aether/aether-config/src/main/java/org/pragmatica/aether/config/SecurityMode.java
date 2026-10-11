// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.Option;


public enum SecurityMode {
    NONE,
    API_KEY,
    JWT;
    public static Option<SecurityMode> securityMode(String value) {
        return Option.option(value)
                     .map(String::trim)
                     .map(String::toLowerCase)
                     .flatMap(SecurityMode::fromNormalized);
    }
    private static Option<SecurityMode> fromNormalized(String normalized) {
        return switch (normalized) {
            case "none" -> Option.some(NONE);
            case "api-key", "api_key", "apikey" -> Option.some(API_KEY);
            case "jwt" -> Option.some(JWT);
            default -> Option.empty();
        };
    }
}
