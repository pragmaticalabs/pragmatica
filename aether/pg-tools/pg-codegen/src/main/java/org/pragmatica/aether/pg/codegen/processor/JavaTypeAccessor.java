// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.lang.Option;

import java.util.Map;

/// Central registry mapping Java type names (FQN or simple) to their
/// `RowMapper.RowAccessor` invocation (method + class-literal type argument, when needed).
///
/// Used by both the record-field path and the scalar-return path in the generated
/// factory. Keeping a single source of truth prevents the two call sites from drifting.
public sealed interface JavaTypeAccessor {
    /// Describes how to read a column of the given Java type from a row.
    ///
    /// `method`  - `RowMapper.RowAccessor` method name (e.g. `getLong`, `getObject`).
    /// `typeArg` - extra argument to pass after the column name (empty for typed getters,
    ///             a `X.class` literal for `getObject`).
    /// `importFqn` - fully-qualified type name to add to the factory imports (empty when
    ///               the type is in `java.lang` or primitive).
    record AccessorInfo(String method, String typeArg, String importFqn) {
        public static AccessorInfo simple(String method) {
            return new AccessorInfo(method, "", "");
        }

        public static AccessorInfo object(String fqn) {
            return new AccessorInfo("getObject", fqn + ".class", fqn);
        }

        public Option<String> importStatement() {
            return importFqn.isEmpty() ? Option.empty() : Option.present(importFqn);
        }
    }

    /// Canonical scalar Java types supported both as record fields and as direct
    /// scalar return types of `@Query` methods.
    ///
    /// Keys are the type-name strings produced by `javax.lang.model.type.TypeMirror#toString`
    /// (FQN form for non-java.lang types, simple form for java.lang types).
    Map<String, AccessorInfo> SCALARS = Map.ofEntries(
        Map.entry("long", AccessorInfo.simple("getLong")),
        Map.entry("java.lang.Long", AccessorInfo.simple("getLong")),
        Map.entry("Long", AccessorInfo.simple("getLong")),
        Map.entry("int", AccessorInfo.simple("getInt")),
        Map.entry("java.lang.Integer", AccessorInfo.simple("getInt")),
        Map.entry("Integer", AccessorInfo.simple("getInt")),
        Map.entry("double", AccessorInfo.simple("getDouble")),
        Map.entry("java.lang.Double", AccessorInfo.simple("getDouble")),
        Map.entry("Double", AccessorInfo.simple("getDouble")),
        Map.entry("boolean", AccessorInfo.simple("getBoolean")),
        Map.entry("java.lang.Boolean", AccessorInfo.simple("getBoolean")),
        Map.entry("Boolean", AccessorInfo.simple("getBoolean")),
        Map.entry("java.lang.String", AccessorInfo.simple("getString")),
        Map.entry("String", AccessorInfo.simple("getString")),
        Map.entry("byte[]", AccessorInfo.simple("getBytes")),
        Map.entry("short", new AccessorInfo("getObject", "Short.class", "")),
        Map.entry("java.lang.Short", new AccessorInfo("getObject", "Short.class", "")),
        Map.entry("Short", new AccessorInfo("getObject", "Short.class", "")),
        Map.entry("float", new AccessorInfo("getObject", "Float.class", "")),
        Map.entry("java.lang.Float", new AccessorInfo("getObject", "Float.class", "")),
        Map.entry("Float", new AccessorInfo("getObject", "Float.class", "")),
        Map.entry("java.math.BigDecimal", AccessorInfo.object("java.math.BigDecimal")),
        Map.entry("java.time.Instant", AccessorInfo.object("java.time.Instant")),
        Map.entry("java.time.LocalDate", AccessorInfo.object("java.time.LocalDate")),
        Map.entry("java.time.LocalDateTime", AccessorInfo.object("java.time.LocalDateTime")),
        Map.entry("java.time.LocalTime", AccessorInfo.object("java.time.LocalTime")),
        Map.entry("java.time.OffsetDateTime", AccessorInfo.object("java.time.OffsetDateTime")),
        Map.entry("java.time.OffsetTime", AccessorInfo.object("java.time.OffsetTime")),
        Map.entry("java.time.Duration", AccessorInfo.object("java.time.Duration")),
        Map.entry("java.util.UUID", AccessorInfo.object("java.util.UUID"))
    );

    /// Looks up an accessor for a field-level Java type (used by record mappers).
    ///
    /// Arrays are handled separately (boxed element class); unknown types fall back to
    /// `getString` without an import.
    static AccessorInfo forField(String javaTypeName) {
        if (javaTypeName.endsWith("[]") && !javaTypeName.equals("byte[]")) {
            return arrayAccessor(javaTypeName);
        }
        var info = SCALARS.get(javaTypeName);
        return info != null ? info : new AccessorInfo("getString", "", "");
    }

    /// Looks up an accessor for a scalar return type (direct `Promise<T>`, `Promise<Option<T>>`,
    /// or `Promise<List<T>>` where `T` is not a record).
    ///
    /// Returns `None` for types that do not map to a driver-supported scalar: the caller is
    /// expected to emit a compile-time error in that case.
    static Option<AccessorInfo> forScalarReturn(String javaTypeName) {
        var info = SCALARS.get(javaTypeName);
        return info != null ? Option.present(info) : Option.empty();
    }

    /// Resolves the row accessor for an array-typed Java field.
    ///
    /// The generator emits `row.getObject(column, Element[].class)` using boxed element types
    /// (Integer, Long, etc.), matching the array decoding contract exposed by the
    /// postgres-async driver through the `RowAccessor` facade.
    private static AccessorInfo arrayAccessor(String javaTypeName) {
        var elementType = javaTypeName.substring(0, javaTypeName.length() - 2);
        var boxed = boxedElement(elementType);
        return new AccessorInfo("getObject", boxed + "[].class", elementImport(boxed));
    }

    private static String boxedElement(String elementType) {
        return switch (elementType) {
            case "int", "java.lang.Integer", "Integer" -> "Integer";
            case "long", "java.lang.Long", "Long" -> "Long";
            case "short", "java.lang.Short", "Short" -> "Short";
            case "double", "java.lang.Double", "Double" -> "Double";
            case "float", "java.lang.Float", "Float" -> "Float";
            case "boolean", "java.lang.Boolean", "Boolean" -> "Boolean";
            case "java.lang.String", "String" -> "String";
            case "java.math.BigDecimal" -> "java.math.BigDecimal";
            case "java.util.UUID" -> "java.util.UUID";
            case "java.time.Instant" -> "java.time.Instant";
            case "java.time.LocalDate" -> "java.time.LocalDate";
            case "java.time.LocalDateTime" -> "java.time.LocalDateTime";
            default -> elementType;
        };
    }

    private static String elementImport(String boxedElement) {
        // Only FQN-form elements need imports. `Integer`, `Long`, etc. live in java.lang.
        return boxedElement.contains(".") ? boxedElement : "";
    }

    /// Placeholder record to satisfy the sealed-interface utility pattern (no instances expected).
    record unused() implements JavaTypeAccessor {}
}
