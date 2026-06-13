// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.config;


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

        public static SchemaMode fromString(String value) {
            return switch (value.toLowerCase().trim()) {
                case "required" -> REQUIRED;
                case "external" -> EXTERNAL;
                case "optional" -> OPTIONAL;
                default -> REQUIRED;
            };
        }
    }
}
