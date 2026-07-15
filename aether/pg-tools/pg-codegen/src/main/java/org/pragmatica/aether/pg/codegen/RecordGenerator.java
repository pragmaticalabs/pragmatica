// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen;

import org.pragmatica.aether.pg.schema.model.Column;
import org.pragmatica.aether.pg.schema.model.Table;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;


public final class RecordGenerator {
    private final CodegenConfig config;

    public RecordGenerator(CodegenConfig config) {
        this.config = config;
    }

    public Result<GeneratedFile> generate(Table table) {
        var className = NamingConvention.tableToClassName(table.name(), config.rowSuffix());
        var imports = new TreeSet<String>();
        var fields = new ArrayList<FieldInfo>();

        for (var col : table.columns()) {
            var typeInfo = col.nullable()
                           ? TypeMapper.mapNullable(col.type(), config.nullableStyle())
                           : TypeMapper.map(col.type());

            if (typeInfo.isEmpty()) {
                return new CodegenError.UnsupportedType(col.type().name()).result();
            }

            var info = typeInfo.expect("checked with isEmpty above");
            var fieldName = NamingConvention.toFieldName(col.name());
            var fieldType = col.nullable() && config.nullableStyle() == CodegenConfig.NullableStyle.OPTION
                            ? "Option<" + info.boxedTypeName() + ">"
                            : info.typeName();

            imports.addAll(TypeMapper.importsFor(info));
            if (col.nullable() && config.nullableStyle() == CodegenConfig.NullableStyle.OPTION) {
                imports.add("org.pragmatica.lang.Option");
            }

            fields.add(new FieldInfo(col.name(), fieldName, fieldType, col.nullable(), info));
        }

        imports.add("org.pragmatica.lang.Result");
        var source = renderRecord(className, fields, imports);
        var path = config.resolveOutputFile(className);

        return Result.success(new GeneratedFile(path, className, source));
    }

    private String renderRecord(String className, List<FieldInfo> fields, TreeSet<String> imports) {
        var sb = new StringBuilder();

        sb.append("package ").append(config.targetPackage()).append(";\n\n");
        for (var imp : imports) {
            if (!imp.startsWith("java.lang.")) {
                sb.append("import ").append(imp).append(";\n");
            }
        }

        sb.append("\n");
        sb.append("/// Generated from table: ").append(fields.isEmpty()
                                                       ? "unknown"
                                                       : fields.getFirst().columnName()).append("\n");
        sb.append("public record ").append(className).append("(\n");
        for (int i = 0; i < fields.size(); i++) {
            var f = fields.get(i);

            sb.append("    ").append(f.fieldType()).append(" ").append(f.fieldName());
            if (i < fields.size() - 1) sb.append(",");

            sb.append("\n");
        }

        sb.append(") {\n");
        if (config.generateStaticFactory()) {
            var factoryName = NamingConvention.toFactoryMethodName(className);

            sb.append("\n    public static ").append(className).append(" ").append(factoryName).append("(\n");
            for (int i = 0; i < fields.size(); i++) {
                var f = fields.get(i);

                sb.append("        ").append(f.fieldType()).append(" ").append(f.fieldName());
                if (i < fields.size() - 1) sb.append(",");

                sb.append("\n");
            }

            sb.append("    ) {\n");
            sb.append("        return new ").append(className).append("(");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) sb.append(", ");

                sb.append(fields.get(i).fieldName());
            }

            sb.append(");\n");
            sb.append("    }\n");
        }

        if (config.generateRowMapper()) {
            renderRowMapper(sb, className, fields);
        }

        sb.append("}\n");

        return sb.toString();
    }

    private void renderRowMapper(StringBuilder sb, String className, List<FieldInfo> fields) {
        sb.append("\n    public static Result<").append(className).append("> mapRow(RowAccessor row) {\n");
        var exprs = new ArrayList<String>();

        for (var field : fields) {
            exprs.add(renderAccessor(field));
        }

        BatchedAllRenderer.appendReturn(sb, "Result", className, exprs, "        ");
        sb.append("    }\n");
    }

    private String renderAccessor(FieldInfo field) {
        var accessor = field.typeInfo().rowAccessorMethod();
        var typeArg = field.typeInfo().rowAccessorTypeArg();
        var call = typeArg.isPresent()
                   ? "row." + accessor
                    + "(\"" + field.columnName()
                    + "\", " + typeArg.expect("checked with isPresent above")
                    + ")"
                   : "row." + accessor + "(\"" + field.columnName() + "\")";

        if (field.nullable()) {
            if (field.fieldType().startsWith("Option<")) {
                return call + ".map(Option::present).or(Option.empty())";
            }
        }

        return call;
    }

    record FieldInfo(String columnName,
                     String fieldName,
                     String fieldType,
                     boolean nullable,
                     TypeMapper.JavaTypeInfo typeInfo) {}

    public interface RowAccessor {
        Result<String> getString(String column);
        Result<Integer> getInt(String column);
        Result<Long> getLong(String column);
        Result<Double> getDouble(String column);
        Result<Boolean> getBoolean(String column);
        Result<byte[]> getBytes(String column);
        <V> Result<V> getObject(String column, Class<V> type);
    }
}
