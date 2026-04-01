package org.pragmatica.aether.pg.codegen;

import org.pragmatica.aether.pg.schema.model.PgType;
import org.pragmatica.lang.Result;

/// Generates a Java enum from a PostgreSQL enum type.
public final class EnumGenerator {
    private final CodegenConfig config;

    public EnumGenerator(CodegenConfig config) {
        this.config = config;
    }

    public Result<GeneratedFile> generate(PgType.EnumType enumType) {
        var className = NamingConvention.toTypeName(enumType.name());
        var source = renderEnum(className, enumType);
        var path = config.resolveOutputFile(className);
        return Result.success(new GeneratedFile(path, className, source));
    }

    private String renderEnum(String className, PgType.EnumType enumType) {
        var sb = new StringBuilder();

        sb.append("package ").append(config.targetPackage()).append(";\n\n");
        sb.append("import org.pragmatica.lang.Result;\n\n");

        sb.append("/// Generated from PostgreSQL enum: ").append(enumType.name()).append("\n");
        sb.append("public enum ").append(className).append(" {\n");

        for (int i = 0; i < enumType.values().size(); i++) {
            var value = enumType.values().get(i);
            var constant = NamingConvention.toEnumConstant(value);
            sb.append("    ").append(constant).append("(\"").append(escapeJavaString(value)).append("\")");
            if (i < enumType.values().size() - 1) sb.append(",");
            else sb.append(";");
            sb.append("\n");
        }

        sb.append("\n    private final String pgValue;\n\n");

        sb.append("    ").append(className).append("(String pgValue) {\n");
        sb.append("        this.pgValue = pgValue;\n");
        sb.append("    }\n\n");

        sb.append("    public String pgValue() {\n");
        sb.append("        return pgValue;\n");
        sb.append("    }\n\n");

        sb.append("    public static Result<").append(className).append("> fromPgValue(String value) {\n");
        sb.append("        for (var v : values()) {\n");
        sb.append("            if (v.pgValue.equals(value)) return Result.success(v);\n");
        sb.append("        }\n");
        sb.append("        return Result.failure(() -> \"Unknown ").append(enumType.name()).append(" value: \" + value);\n");
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private static String escapeJavaString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
