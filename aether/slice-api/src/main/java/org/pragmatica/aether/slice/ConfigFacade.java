// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;


/// Facade for typed configuration access within slice context.
///
/// Provides synchronous access to merged configuration values
/// from the 3-layer config hierarchy (bundled defaults, environment,
/// KV-Store overrides). Generated factory code uses these methods
/// to parse config sections into typed records.
///
/// The `require*` methods return `Result.failure` when a key is missing,
/// making them suitable for mandatory config fields composed via `Result.all()`.
///
/// The `get*` methods return `Option`-wrapped values for optional fields.
///
/// Example generated usage:
/// ```{@code
/// Result.all(
///     ctx.config().requireString("app.orders", "host"),
///     ctx.config().requireInt("app.orders", "port"),
///     ctx.config().getBoolean("app.orders", "enable_tls")
/// ).flatMap(ServiceConfig::serviceConfig)
/// }```
public interface ConfigFacade {
    Result<String> requireString(String section, String key);
    Result<Integer> requireInt(String section, String key);
    Result<Long> requireLong(String section, String key);
    Result<Double> requireDouble(String section, String key);
    Result<Boolean> requireBoolean(String section, String key);
    Result<List<String>> requireStringList(String section, String key);
    Option<String> getString(String section, String key);
    Option<Integer> getInt(String section, String key);
    Option<Long> getLong(String section, String key);
    Option<Double> getDouble(String section, String key);
    Option<Boolean> getBoolean(String section, String key);
}
