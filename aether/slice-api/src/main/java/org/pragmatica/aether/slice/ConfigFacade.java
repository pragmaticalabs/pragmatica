// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// A slice's view of its configuration section. `require*` fails on an absent key; `get*` is the
/// optional twin — but optional in the KEY, not in the parse: the typed `get*` answer
/// `Result<Option<T>>`, where an absent key is `Success(None)` and a PRESENT but UNPARSEABLE value
/// is a failure naming the key and the value (#1098). `Option<T>` alone had no failure channel, so
/// `port = "80x"` read as "not configured" and the slice's default applied silently.
public interface ConfigFacade {
    Result<String> requireString(String section, String key);
    Result<Integer> requireInt(String section, String key);
    Result<Long> requireLong(String section, String key);
    Result<Double> requireDouble(String section, String key);
    Result<Boolean> requireBoolean(String section, String key);
    Result<List<String>> requireStringList(String section, String key);
    Option<String> getString(String section, String key);
    Result<Option<Integer>> getInt(String section, String key);
    Result<Option<Long>> getLong(String section, String key);
    Result<Option<Double>> getDouble(String section, String key);
    Result<Option<Boolean>> getBoolean(String section, String key);
}
