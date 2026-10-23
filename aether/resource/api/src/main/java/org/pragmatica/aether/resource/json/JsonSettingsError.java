// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.json;

import java.util.Set;
import java.util.TreeSet;

import org.pragmatica.lang.Cause;


/// Why a `[json]` section was refused. The binder reports every fault of one read, each naming its key.
public sealed interface JsonSettingsError extends Cause {
    /// `default` names a codec no `codecs.<name>` table declares, including a `default` with no codecs at all.
    record UnknownDefaultCodec(String key, String name, Set<String> declared) implements JsonSettingsError {
        @Override
        public String message() {
            return key + " = '" + name + "' names no declared codec; declared: " + new TreeSet<>(declared);
        }
    }

    /// `implementation` is neither `generated` nor `jackson`.
    record UnknownImplementation(String key, String value) implements JsonSettingsError {
        @Override
        public String message() {
            return key + " = '" + value + "' is not a JSON implementation; expected generated or jackson";
        }
    }

    /// A codec table without `implementation`.
    record MissingImplementation(String key) implements JsonSettingsError {
        @Override
        public String message() {
            return key + " is missing; every declared codec names its implementation (generated or jackson)";
        }
    }

    /// Two or more codecs declared and no `default` choosing one.
    record MissingDefault(String key, Set<String> declared) implements JsonSettingsError {
        @Override
        public String message() {
            return key + " is missing; it must choose one of the declared codecs " + new TreeSet<>(declared);
        }
    }

    /// `naming` is compiled into the generated codecs and cannot change at deploy time.
    record NamingIsBuildTime(String key, String value) implements JsonSettingsError {
        @Override
        public String message() {
            return key
                 + " = '" + value
                 + "' is refused: JSON naming is fixed at build time, by [json] naming in the"
                 + " slice's routes.toml";
        }
    }

    /// A key under the section that is none of the above.
    record UnknownKey(String key) implements JsonSettingsError {
        @Override
        public String message() {
            return key + " is not a JSON setting";
        }
    }

    /// An option value that does not parse, or that the options refuse.
    record InvalidOption(String key, String value, String reason) implements JsonSettingsError {
        @Override
        public String message() {
            return key + " = '" + value + "' is refused: " + reason;
        }
    }
}
