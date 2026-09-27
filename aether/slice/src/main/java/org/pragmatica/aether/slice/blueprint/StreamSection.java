// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// One `[streams.<alias>]` section's own keys, whatever holds them (#1549).
///
/// The section is read in two places: deploy validation parses the blueprint's `resources.toml` text,
/// and slice activation binds the stream resource from the slice's configuration provider. Until #1549
/// the second place used the generic record binder, which reads snake_case component names and falls
/// back to `StreamConfig.DEFAULT` for anything it does not find — so a validated `min-sync-replicas = 3`
/// was provisioned as `0`. Both places now run [StreamConfigParser#parseStreamConfig] over this view,
/// so the key set, the value formats and the defaults have one authority.
///
/// [#keys()] is only the keys directly in the section: a `[streams.<alias>.consumers.<group>]`
/// sub-section belongs to the consumer parser and is never listed here.
public sealed interface StreamSection {
    String alias();
    Set<String> keys();
    Option<String> string(String key);
    /// Absent → `Success(None)`; present but not an integer → failure naming the key, never a default.
    Result<Option<Integer>> integer(String key);

    static StreamSection tomlSection(TomlDocument doc, String section, String alias) {
        return new TomlStreamSection(doc, section, alias);
    }

    /// `section` is the full provider path of the section (e.g. `streams.orders`); the alias is its last
    /// segment, the same derivation the record binder used for the `name` component.
    static StreamSection providerSection(ConfigurationProvider provider, String section) {
        return new ProviderStreamSection(provider,
                                         section,
                                         section.substring(section.lastIndexOf('.') + 1));
    }

    record TomlStreamSection(TomlDocument doc, String section, String alias) implements StreamSection {
        @Override
        public Set<String> keys() {
            return doc.keys(section);
        }

        @Override
        public Option<String> string(String key) {
            return doc.getString(section, key);
        }

        @Override
        public Result<Option<Integer>> integer(String key) {
            return doc.getString(section, key)
                      .map(raw -> doc.getInt(section, key)
                                     .toResult(new StreamDeclarationError.NotAnInteger(alias, key, raw))
                                     .map(Option::some))
                      .or(Result.success(Option.none()));
        }
    }

    /// Keys come from [ConfigurationProvider#staticKeys()], the file-backed layers only, for the reason
    /// `StrictKeys` gives: an environment variable or KV overlay landing under the section was not
    /// written by the blueprint and must not fail a bind the file alone would accept.
    record ProviderStreamSection(ConfigurationProvider provider, String section, String alias) implements StreamSection {
        @Override
        public Set<String> keys() {
            var prefix = section + ".";

            return provider.staticKeys()
                           .stream()
                           .filter(key -> key.startsWith(prefix))
                           .map(key -> key.substring(prefix.length()))
                           .filter(key -> key.indexOf('.') < 0)
                           .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public Option<String> string(String key) {
            return provider.getString(section + "." + key);
        }

        @Override
        public Result<Option<Integer>> integer(String key) {
            return provider.getInt(section + "." + key);
        }
    }
}
