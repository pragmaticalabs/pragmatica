// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.jbct.config;

import org.pragmatica.lang.Option;

/// Blueprint build-time validation configuration.
///
/// Controls whether `GenerateBlueprintMojo` validates the presence of schema migration
/// files when the blueprint declares database resources.
///
/// @param schema Schema validation mode:
///               `REQUIRED` — fail if `[database]` declared but no `schema/*.sql` found (default)
///               `EXTERNAL` — skip validation; schema managed outside the project
///               `OPTIONAL` — warn but don't fail
public record BlueprintConfig(SchemaMode schema) {
    public static final BlueprintConfig DEFAULT = new BlueprintConfig(SchemaMode.REQUIRED);

    public enum SchemaMode {
        REQUIRED,
        EXTERNAL,
        OPTIONAL;
        public static final String ALLOWED = "one of: required, external, optional";

        /// The mode a config file names, or empty for a token that names none; the config parser refuses
        /// it (#1455) instead of falling back to [#REQUIRED].
        public static Option<SchemaMode> schemaMode(String value) {
            return switch (value.toLowerCase()
                                .trim()) {
                case "required" -> Option.some(REQUIRED);
                case "external" -> Option.some(EXTERNAL);
                case "optional" -> Option.some(OPTIONAL);
                default -> Option.none();
            };
        }

        public static SchemaMode fromString(String value) {
            return switch (value.toLowerCase()
                                .trim()) {
                case "required" -> REQUIRED;
                case "external" -> EXTERNAL;
                case "optional" -> OPTIONAL;
                default -> REQUIRED;
            };
        }
    }
}
