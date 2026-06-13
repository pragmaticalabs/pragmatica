// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;

import org.pragmatica.lang.Cause;


/// One semantic failure produced by [StreamResourceValidator].
///
/// Per spec §15.1.2 the on-the-wire transport is a structured triple of `field`/`rule`/`message`
/// — `field` is the offending TOML section (e.g., `[streams.orders]`) or blueprint coordinate
/// pointer, `rule` is a stable machine-readable tag (e.g., `producer-version-must-be-exact`),
/// and `message` is the human-readable diagnostic. Multiple of these are aggregated into a
/// single [StreamValidationFailures] composite.
public record StreamValidationFailure(String field, String rule, String message) implements Cause {
    public static StreamValidationFailure streamValidationFailure(String field, String rule, String message) {
        return new StreamValidationFailure(field, rule, message);
    }
}
