// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// A slice's view of its configuration section. `require*` fails on an absent key; `get*` is the
/// optional twin — but optional in the KEY, not in the parse: the typed `get*` answer
/// `Result<Option<T>>`, where an absent key is `Success(None)` and a PRESENT but UNPARSEABLE value
/// is a failure naming the key and the value (#1098). `Option<T>` alone had no failure channel, so
/// `port = "80x"` read as "not configured" and the slice's default applied silently.
public interface ConfigFacade {
    /// Bind configuration for an explicitly assembled slice, including non-Aether runtimes.
    static ConfigFacade configFacade(String sliceId, org.pragmatica.config.ConfigurationProvider provider) {
        return ConfigProviderFacade.configProviderFacade(sliceId, provider);
    }

    /// Refusal of [#watched] by a facade that cannot deliver changes. A success-shaped no-op would let a slice
    /// believe it is watching: `Watched` would deliver the registration value and never change (#892).
    Cause WATCHED_UNAVAILABLE = Causes.cause("This ConfigFacade cannot deliver Watched/Live configuration: it has no change feed");

    /// The `section` as a [Watched] record. `binder` builds the record from a facade over the current
    /// configuration; it runs at registration and again for each delivery. Facades that cannot deliver changes
    /// refuse, they do not pretend.
    default <T> Result<Watched<T>> watched(String section, Fn1<Result<T>, ConfigFacade> binder) {
        return WATCHED_UNAVAILABLE.result();
    }

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
