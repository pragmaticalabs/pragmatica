// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// The refusal a slice gets when it asks for configuration and no composite was ever attached.
///
/// Exists so that absence fails by NAME rather than by degradation (#889 review). The obvious
/// implementation of the fix falls back to [NoOpConfigFacade], whose failures say only "Config
/// service not available" — so a slice that genuinely needed configuration surfaced as a chain of
/// missing-KEY errors, pointing the reader at their `resources.toml` when the real fault was one
/// level up and node-wide: the node was started without a configuration provider at all.
///
/// That is the shape this project keeps paying for — a fallback quietly converting a refusal into a
/// confusing failure. Here the cause names the slice, names the key that was asked for, and states
/// which of the two things is actually missing.
///
/// KNOWN LIMIT, stated rather than hidden: the `get*` half of [ConfigFacade] returns [Option] and so
/// has no channel to refuse, and the generated code wraps those reads in `Result.success(...)`.
/// A config record whose components are ALL optional will therefore still be constructed, with every
/// value absent, against a missing composite. Any record carrying at least one required component —
/// the overwhelmingly common case, and every example in the books — fails loudly and by name. Closing
/// the all-optional case means changing what the generator emits, which is a different blast radius.
record AbsentCompositeConfigFacade(String sliceId) implements ConfigFacade {
    private static final Fn1<Cause, String> NO_COMPOSITE = Causes.forOneValue("No configuration composite is attached to this slice, so %s cannot be read. "
                                                                             + "This is a missing configuration SOURCE, not a missing key: the node has no "
                                                                             + "configuration provider, so nothing was layered for the slice to read from.");

    static AbsentCompositeConfigFacade absentCompositeConfigFacade(String sliceId) {
        return new AbsentCompositeConfigFacade(sliceId);
    }

    @Override
    public Result<String> requireString(String section, String key) {
        return refuse(section, key);
    }

    @Override
    public Result<Integer> requireInt(String section, String key) {
        return refuse(section, key);
    }

    @Override
    public Result<Long> requireLong(String section, String key) {
        return refuse(section, key);
    }

    @Override
    public Result<Double> requireDouble(String section, String key) {
        return refuse(section, key);
    }

    @Override
    public Result<Boolean> requireBoolean(String section, String key) {
        return refuse(section, key);
    }

    @Override
    public Result<List<String>> requireStringList(String section, String key) {
        return refuse(section, key);
    }

    @Override
    public Option<String> getString(String section, String key) {
        return Option.none();
    }

    @Override
    public Option<Integer> getInt(String section, String key) {
        return Option.none();
    }

    @Override
    public Option<Long> getLong(String section, String key) {
        return Option.none();
    }

    @Override
    public Option<Double> getDouble(String section, String key) {
        return Option.none();
    }

    @Override
    public Option<Boolean> getBoolean(String section, String key) {
        return Option.none();
    }

    private <T> Result<T> refuse(String section, String key) {
        return NO_COMPOSITE.apply(describe(section, key)).result();
    }

    /// Names the slice as well as the key, because the operator reading this failure has a cluster
    /// of slices and needs to know which one asked.
    private String describe(String section, String key) {
        return "key " + section + "." + key + " for slice " + sliceId;
    }
}
