// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// One `[streams.<alias>]` section's own keys, whatever holds them (#1549).
///
/// The section is read in two places: deploy validation parses the blueprint's `resources.toml` text,
/// and slice activation binds the stream resource from the slice's configuration provider. Until #1549
/// the second place used the generic record binder, which reads snake_case component names and falls
/// back to `StreamConfig.DEFAULT` for anything it does not find — so a validated `confirmation_factor = 3`
/// was provisioned as `0`. Both places now run [StreamConfigParser#parseStreamConfig] over this view,
/// so the key set, the value formats and the defaults have one authority.
///
/// [#keys()] is only the keys directly in the section: a `[streams.<alias>.consumers.<group>]`
/// sub-section belongs to the consumer parser and is never listed here.
public sealed interface StreamSection {
    String alias();
    Set<String> keys();
    Option<String> string(String key);

    /// Absent → `Success(None)`; present but not an integer → [StreamDeclarationError.NotAnInteger], never a
    /// default. Read from the value's TEXT on both sides, so `3.0` or `2147483648` fails the same way at deploy
    /// (TOML) and at activation (provider), instead of one side returning a TOML type and the other a
    /// provider type mismatch.
    default Result<Option<Integer>> integer(String key) {
        return string(key).map(raw -> StreamValues.integer(alias(),
                                                           key,
                                                           raw).map(Option::some))
                     .or(Result.success(Option.none()));
    }

    /// Whether the section declares the sub-table `name` (`[streams.<alias>.<name>]` or a dotted `<name>.<key>`): ONE
    /// rule for both views, by keys. A sub-table is declared by the keys it carries; an empty `[streams.<alias>.<name>]`
    /// header declares nothing and reads as absent. The rule is the provider view's, because the provider flattens a
    /// TOML document to keys and an empty table leaves none, so deploy (TOML) and activation (provider) read an empty
    /// sub-table alike only by keys.
    default boolean hasTable(String name) {
        return keys().stream()
                   .anyMatch(key -> key.startsWith(name + "."));
    }

    /// `consumers.<group>...` belongs to the consumer parser, never to this section.
    private static boolean isConsumerTable(String relative) {
        return relative.startsWith("consumers.");
    }

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
        /// Direct keys, plus the keys of any sub-table other than `consumers.*` written as `<sub>.<key>` — a
        /// `[streams.x.retention]` table or a quoted `"retention.value"` key is a key of this section nothing
        /// reads, and must be refused here exactly as the provider view refuses its flattened form.
        @Override
        public Set<String> keys() {
            var prefix = section + ".";
            var nested = doc.sectionNames()
                            .stream()
                            .filter(name -> name.startsWith(prefix))
                            .map(name -> name.substring(prefix.length()))
                            .filter(sub -> !isConsumerTable(sub))
                            .flatMap(sub -> doc.keys(section + "." + sub)
                                               .stream()
                                               .map(key -> sub + "." + key));

            return Stream.concat(doc.keys(section).stream(),
                                 nested)
                         .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public Option<String> string(String key) {
            return doc.getString(section, key)
                      .orElse(() -> subTableValue(key));
        }

        /// `<sub>.<key>` read from the `[streams.<alias>.<sub>]` table, the form [#keys] reports it in.
        private Option<String> subTableValue(String dotted) {
            var split = dotted.indexOf('.');

            return split < 0
                   ? Option.none()
                   : doc.getString(section + "." + dotted.substring(0, split), dotted.substring(split + 1));
        }
    }

    /// Keys come from [ConfigurationProvider#staticKeys()], the file-backed layers only, for the reason
    /// `StrictKeys` gives: an environment variable or KV overlay landing under the section was not
    /// written by the blueprint and must not fail a bind the file alone would accept.
    record ProviderStreamSection(ConfigurationProvider provider, String section, String alias) implements StreamSection {
        /// Every key under the section except the consumer sub-tables — including dotted ones, which is how a
        /// quoted `"retention.value"` or a `[streams.x.retention]` table arrives once flattened.
        @Override
        public Set<String> keys() {
            var prefix = section + ".";

            return provider.staticKeys()
                           .stream()
                           .filter(key -> key.startsWith(prefix))
                           .map(key -> key.substring(prefix.length()))
                           .filter(key -> !isConsumerTable(key))
                           .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public Option<String> string(String key) {
            return provider.getString(section + "." + key);
        }
    }
}
