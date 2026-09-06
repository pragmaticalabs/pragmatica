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
/// level up: no configuration SOURCE was ever attached.
///
/// That is the shape this project keeps paying for — a fallback quietly converting a refusal into a
/// confusing failure. Here the cause names the slice, names the key that was asked for, and states
/// that a source (not a key) is missing.
///
/// The composite is absent in FOUR load-time conditions, and the wording is true in every one of
/// them — the first draft named only the first and was wrong in the other three (review S1):
///
///   - the node runs without a configuration provider (`AetherNode.createResourceProviderFacade`);
///   - the node has one but node.toml secret resolution failed at boot (same method, second arm);
///   - the slice's `META-INF/resources.toml` failed to parse (`SliceStore#parseToFlatMap`);
///   - one of that file's `${secrets:...}` placeholders failed to resolve
///     (`SliceStore#resolveIntrinsicSecrets`).
///
/// The last two drop the WHOLE composite, node keys included — `SliceStore` layers the slice file
/// over the node composite in one all-or-nothing step — so a slice whose own file is broken cannot
/// read node.toml either. This facade cannot tell the four apart (it sees only "no composite"), so
/// it lists them and points at the load log, which names the one that fired.
///
/// KNOWN LIMIT, stated rather than hidden: the `get*` half of [ConfigFacade] returns [Option] and so
/// has no channel to refuse, and the generated code wraps those reads in `Result.success(...)`.
/// A config record whose components are ALL optional will therefore still be constructed, with every
/// value absent, against a missing composite. Any record carrying at least one required component —
/// the overwhelmingly common case, and every example in the books — fails loudly and by name. Closing
/// the all-optional case means changing what the generator emits, which is a different blast radius.
record AbsentCompositeConfigFacade(String sliceId) implements ConfigFacade {
    private static final Fn1<Cause, String> NO_COMPOSITE = Causes.forOneValue("No configuration composite is attached to this slice, so %s cannot be read. "
                                                                             + "This is a missing configuration SOURCE, not a missing key. One of two layers was never built: "
                                                                             + "the node composite (no configuration provider configured, or node.toml secret resolution failed at boot) "
                                                                             + "or the slice's own layer (META-INF/resources.toml failed to parse, or one of its ${secrets:...} "
                                                                             + "placeholders failed to resolve; a dropped slice layer takes the node keys with it). "
                                                                             + "The slice load log names which.");

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
