// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.lang.Option;


/// How a bind or decode target that is not a plain column type crosses the column boundary: lowered to a
/// column value when bound, read and lifted back when decoded.
public sealed interface ColumnRepr permits ValueMappingResolver.Binding, ColumnRepr.JsonDocument, ColumnRepr.Nullable {
    /// The qualified name of the `JsonText` column representation.
    String JSON_TEXT = "org.pragmatica.aether.resource.db.JsonText";

    /// The column-side type, the one checked against the schema.
    String pTypeName();

    /// Bind expression turning `valueExpr` into the column value.
    String lowerExpr(String valueExpr);

    /// Row read of the column-side value.
    JavaTypeAccessor.AccessorInfo pAccessor();

    /// Function applied to the row read with `flatMap`; empty when the read already is the value.
    String liftExpr();

    /// A `@Jsonb` document: bound as `JsonText` encoded by the generated codec in `codecField`, decoded by it.
    record JsonDocument(String codecField) implements ColumnRepr {
        @Override
        public String pTypeName() {
            return JSON_TEXT;
        }

        @Override
        public String lowerExpr(String valueExpr) {
            return JSON_TEXT + ".jsonText(" + codecField + ", " + valueExpr + ")";
        }

        @Override
        public JavaTypeAccessor.AccessorInfo pAccessor() {
            return JavaTypeAccessor.forField(JSON_TEXT);
        }

        @Override
        public String liftExpr() {
            return "text -> text.decode(" + codecField + ")";
        }
    }

    /// An `Option<T>` target of a nullable column: empty binds as SQL NULL and SQL NULL reads as empty. `element`
    /// is `T`'s column-side handling when `T` is not a plain column type.
    record Nullable(String elementTypeName, Option<ColumnRepr> element) implements ColumnRepr {
        // A lambda parameter may not shadow a local, so the bind lambda's name cannot be a Java method parameter's.
        private static final String PRESENT = "present$";

        @Override
        public String pTypeName() {
            return element.map(ColumnRepr::pTypeName)
                          .or(elementTypeName);
        }

        @Override
        public String lowerExpr(String valueExpr) {
            var lowered = element.map(repr -> ".map(" + PRESENT + " -> " + repr.lowerExpr(PRESENT) + ")")
                                 .or("");

            return valueExpr + lowered + ".toOptional().orElse(null)";
        }

        @Override
        public JavaTypeAccessor.AccessorInfo pAccessor() {
            return new JavaTypeAccessor.AccessorInfo("getOptional", pTypeName() + ".class", "");
        }

        @Override
        public String liftExpr() {
            return element.map(ColumnRepr::liftExpr)
                          .filter(lift -> !lift.isEmpty())
                          .map(lift -> "raw -> RowDecodeError.liftOptional(raw, " + lift + ")")
                          .or("");
        }
    }
}
