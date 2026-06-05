// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.validation;


/// Non-blocking advisory produced by [StreamResourceValidator] (spec §11.1.3 multi-version case).
///
/// Same `field`/`rule`/`message` shape as [StreamValidationFailure] but emitted alongside a
/// successful validation result rather than a failing one.
public record StreamValidationWarning(String field, String rule, String message) {
    public static StreamValidationWarning streamValidationWarning(String field, String rule, String message) {
        return new StreamValidationWarning(field, rule, message);
    }
}
