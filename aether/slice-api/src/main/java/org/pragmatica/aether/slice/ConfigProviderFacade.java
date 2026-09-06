// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// [ConfigFacade] served from a slice's effective configuration provider.
///
/// This is what `ctx.config()` returns for a DEPLOYED slice (#889). Before it existed every
/// production path handed the generated factory [NoOpConfigFacade], whose `require*` methods all
/// fail — so the `Result.all(...)` chain that `@ResourceQualifier(type = ConfigurationSection.class)`
/// generates could never produce a record, and a slice declaring a config section could not be
/// created at all. Nothing in the repo declared one, which is the likelier reading of that
/// "adoption gap" than disinterest.
///
/// Keys are addressed as `section + "." + key`, matching the flat key space the slice-composite is
/// built in (`SliceStore#flattenSections`) and the convention
/// `NodeDeploymentManager.ConfigServiceConfigFacade` already used on the notification path.
///
/// Two deliberate divergences from that older adapter, both in the direction of working rather than
/// failing:
///
///   - `requireLong`/`requireDouble` parse through [org.pragmatica.config.ConfigSource]'s own safe
///     parsers, so a non-numeric value yields a named missing-key failure instead of throwing
///     `NumberFormatException` out of the factory.
///   - `requireStringList` is implemented rather than refused, and accepts BOTH spellings. The
///     idiomatic one is a native TOML array, `tags = ["a", "b"]`, which reaches every provider as
///     Java's `List.toString()` — `[a, b]` — because `TomlDocument#getSection` stringifies each
///     value before a provider sees it (review S4: the first draft split that on commas and
///     returned `["[a", "b]"]` with no diagnostic). The fallback is the comma-joined scalar
///     `"a, b"` that `ProviderBasedConfigService#splitCommaList` established. A nested array is not
///     a string list and is refused by name rather than split into bracket fragments.
///
/// An absent key is a failure for every `require*` method, list included. That is the word
/// "require" meaning what it says; a caller that wants absence to be legal declares the component
/// as `Option<T>`, which the generator routes to the `get*` methods instead. A PRESENT but EMPTY
/// list (`tags = []` or `tags = ""`) is a value and succeeds as `[]` — "require" pins presence, and
/// emptiness is something a list is allowed to be (review N2, decided here).
///
/// Every refusal names the slice that asked as well as the key. A node loads many slices, and
/// since #889 review S2 a `[slices]` dependency reads through a context of its own, so a
/// missing-key failure raised while loading slice A may belong to its dependency B — the message
/// has to say which.
record ConfigProviderFacade(String sliceId, ConfigurationProvider provider) implements ConfigFacade {
    private static final Fn1<Cause, String> MISSING_KEY = Causes.forOneValue("Required config key not found: %s");
    private static final Fn1<Cause, String> NESTED_ARRAY = Causes.forOneValue("Config %s holds a nested array, which is not a string list");

    static ConfigProviderFacade configProviderFacade(String sliceId, ConfigurationProvider provider) {
        return new ConfigProviderFacade(sliceId, provider);
    }

    @Override
    public Result<String> requireString(String section, String key) {
        return require(section, key, provider::getString);
    }

    @Override
    public Result<Integer> requireInt(String section, String key) {
        return require(section, key, provider::getInt);
    }

    @Override
    public Result<Long> requireLong(String section, String key) {
        return require(section, key, provider::getLong);
    }

    @Override
    public Result<Double> requireDouble(String section, String key) {
        return require(section, key, provider::getDouble);
    }

    @Override
    public Result<Boolean> requireBoolean(String section, String key) {
        return require(section, key, provider::getBoolean);
    }

    @Override
    public Result<List<String>> requireStringList(String section, String key) {
        var fullKey = fullKey(section, key);

        return provider.getString(fullKey)
                       .toResult(MISSING_KEY.apply(describe(fullKey)))
                       .flatMap(raw -> parseStringList(describe(fullKey), raw));
    }

    @Override
    public Option<String> getString(String section, String key) {
        return provider.getString(fullKey(section, key));
    }

    @Override
    public Option<Integer> getInt(String section, String key) {
        return provider.getInt(fullKey(section, key));
    }

    @Override
    public Option<Long> getLong(String section, String key) {
        return provider.getLong(fullKey(section, key));
    }

    @Override
    public Option<Double> getDouble(String section, String key) {
        return provider.getDouble(fullKey(section, key));
    }

    @Override
    public Option<Boolean> getBoolean(String section, String key) {
        return provider.getBoolean(fullKey(section, key));
    }

    private <T> Result<T> require(String section, String key, Fn1<Option<T>, String> reader) {
        var fullKey = fullKey(section, key);

        return reader.apply(fullKey)
                     .toResult(MISSING_KEY.apply(describe(fullKey)));
    }

    private static String fullKey(String section, String key) {
        return section + "." + key;
    }

    private String describe(String fullKey) {
        return "key " + fullKey + " for slice " + sliceId;
    }

    /// Package-private so the two spellings, the mixed and empty forms, and the nested refusal
    /// are pinned directly against the parse rather than through a provider. `subject` is the
    /// already-described key, used only in the refusal.
    static Result<List<String>> parseStringList(String subject, String raw) {
        var trimmed = raw.trim();

        if (!isNativeArray(trimmed)) {
            return Result.success(splitCommaList(trimmed));
        }

        var body = trimmed.substring(1, trimmed.length() - 1);

        if (body.contains("[") || body.contains("]")) {
            return NESTED_ARRAY.apply(subject).result();
        }

        return Result.success(splitCommaList(body));
    }

    private static boolean isNativeArray(String trimmed) {
        return trimmed.length() >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]");
    }

    private static List<String> splitCommaList(String raw) {
        return Arrays.stream(raw.split(","))
                     .map(String::trim)
                     .filter(value -> !value.isEmpty())
                     .toList();
    }
}
