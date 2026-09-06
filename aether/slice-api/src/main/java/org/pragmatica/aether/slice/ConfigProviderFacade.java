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
///   - `requireStringList` is implemented rather than refused. Values are comma-joined scalars, the
///     same encoding `ProviderBasedConfigService#splitCommaList` reads, because `TomlDocument`
///     flattens every value through `toString()` before a provider ever sees it.
///
/// An absent key is a failure for every `require*` method, list included. That is the word
/// "require" meaning what it says; a caller that wants absence to be legal declares the component
/// as `Option<T>`, which the generator routes to the `get*` methods instead.
record ConfigProviderFacade(ConfigurationProvider provider) implements ConfigFacade {
    private static final Fn1<Cause, String> MISSING_KEY = Causes.forOneValue("Required config key not found: %s");

    static ConfigProviderFacade configProviderFacade(ConfigurationProvider provider) {
        return new ConfigProviderFacade(provider);
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
        return require(section, key, this::readStringList);
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

    private static <T> Result<T> require(String section, String key, Fn1<Option<T>, String> reader) {
        var fullKey = fullKey(section, key);

        return reader.apply(fullKey)
                     .toResult(MISSING_KEY.apply(fullKey));
    }

    private static String fullKey(String section, String key) {
        return section + "." + key;
    }

    private Option<List<String>> readStringList(String fullKey) {
        return provider.getString(fullKey)
                       .map(ConfigProviderFacade::splitCommaList);
    }

    private static List<String> splitCommaList(String raw) {
        return Arrays.stream(raw.split(","))
                     .map(String::trim)
                     .filter(value -> !value.isEmpty())
                     .toList();
    }
}
