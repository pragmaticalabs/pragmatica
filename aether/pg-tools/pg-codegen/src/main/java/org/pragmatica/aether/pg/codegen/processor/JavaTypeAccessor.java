// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.lang.Option;

import java.util.Map;


public sealed interface JavaTypeAccessor {
    record AccessorInfo(String method, String typeArg, String importFqn) {
        public static AccessorInfo simple(String method) {
            return new AccessorInfo(method, "", "");
        }

        public static AccessorInfo object(String fqn) {
            return new AccessorInfo("getObject", fqn + ".class", fqn);
        }

        public Option<String> importStatement() {
            return importFqn.isEmpty()
                  ? Option.empty()
                  : Option.present(importFqn);
        }
    }

    Map<String, AccessorInfo> SCALARS = Map.ofEntries(Map.entry("long", AccessorInfo.simple("getLong")),
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
                                                      Map.entry("short",
                                                                new AccessorInfo("getObject", "Short.class", "")),
                                                      Map.entry("java.lang.Short",
                                                                new AccessorInfo("getObject", "Short.class", "")),
                                                      Map.entry("Short",
                                                                new AccessorInfo("getObject", "Short.class", "")),
                                                      Map.entry("float",
                                                                new AccessorInfo("getObject", "Float.class", "")),
                                                      Map.entry("java.lang.Float",
                                                                new AccessorInfo("getObject", "Float.class", "")),
                                                      Map.entry("Float",
                                                                new AccessorInfo("getObject", "Float.class", "")),
                                                      Map.entry("java.math.BigDecimal",
                                                                AccessorInfo.object("java.math.BigDecimal")),
                                                      Map.entry("java.time.Instant",
                                                                AccessorInfo.object("java.time.Instant")),
                                                      Map.entry("java.time.LocalDate",
                                                                AccessorInfo.object("java.time.LocalDate")),
                                                      Map.entry("java.time.LocalDateTime",
                                                                AccessorInfo.object("java.time.LocalDateTime")),
                                                      Map.entry("java.time.LocalTime",
                                                                AccessorInfo.object("java.time.LocalTime")),
                                                      Map.entry("java.time.OffsetDateTime",
                                                                AccessorInfo.object("java.time.OffsetDateTime")),
                                                      Map.entry("java.time.OffsetTime",
                                                                AccessorInfo.object("java.time.OffsetTime")),
                                                      Map.entry("java.time.Duration",
                                                                AccessorInfo.object("java.time.Duration")),
                                                      Map.entry("java.util.UUID", AccessorInfo.object("java.util.UUID")));

    static AccessorInfo forField(String javaTypeName) {
        if (javaTypeName.endsWith("[]") && !javaTypeName.equals("byte[]")) {return arrayAccessor(javaTypeName);}
        var info = SCALARS.get(javaTypeName);
        return info != null
              ? info
              : new AccessorInfo("getString", "", "");
    }

    static Option<AccessorInfo> forScalarReturn(String javaTypeName) {
        var info = SCALARS.get(javaTypeName);
        return info != null
              ? Option.present(info)
              : Option.empty();
    }

    private static AccessorInfo arrayAccessor(String javaTypeName) {
        var elementType = javaTypeName.substring(0, javaTypeName.length() - 2);
        var boxed = boxedElement(elementType);
        return new AccessorInfo("getObject", boxed + "[].class", elementImport(boxed));
    }

    private static String boxedElement(String elementType) {
        return switch (elementType){
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
        return boxedElement.contains(".")
              ? boxedElement
              : "";
    }

    record unused() implements JavaTypeAccessor{}
}
