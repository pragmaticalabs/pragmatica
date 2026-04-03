package org.pragmatica.aether.pg.codegen;

import org.pragmatica.aether.pg.schema.model.Column;
import org.pragmatica.aether.pg.schema.model.Table;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;


/// Generates a Java record from a PostgreSQL table definition.
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
            if (typeInfo.isEmpty()) {return new CodegenError.UnsupportedType(col.type().name()).result();}
            var info = typeInfo.unwrap();
            var fieldName = NamingConvention.toFieldName(col.name());
            var fieldType = col.nullable() && config.nullableStyle() == CodegenConfig.NullableStyle.OPTION
                           ? "Option<" + info.boxedTypeName() + ">"
                           : info.typeName();
            imports.addAll(TypeMapper.importsFor(info));
            if (col.nullable() && config.nullableStyle() == CodegenConfig.NullableStyle.OPTION) {imports.add("org.pragmatica.lang.Option");}
            fields.add(new FieldInfo(col.name(), fieldName, fieldType, col.nullable(), info));
        }
        imports.add("org.pragmatica.lang.Result");
        var source = renderRecord(className, fields, imports);
        var path = config.resolveOutputFile(className);
        return Result.success(new GeneratedFile(path, className, source));
    }

    private String renderRecord(String className, List<FieldInfo> fields, TreeSet<String> imports) {
        var sb = new StringBuilder();
        sb.append("package ").append(config.targetPackage())
                 .append(";\n\n");
        for (var imp : imports) {if (!imp.startsWith("java.lang.")) {sb.append("import ").append(imp)
                                                                              .append(";\n");}}
        sb.append("\n");
        sb.append("/// Generated from table: ").append(fields.isEmpty()
                                                       ? "unknown"
                                                       : fields.getFirst().columnName())
                 .append("\n");
        sb.append("public record ").append(className)
                 .append("(\n");
        for (int i = 0;i <fields.size();i++) {
            var f = fields.get(i);
            sb.append("    ").append(f.fieldType())
                     .append(" ")
                     .append(f.fieldName());
            if (i <fields.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append(") {\n");
        if (config.generateStaticFactory()) {
            var factoryName = NamingConvention.toFactoryMethodName(className);
            sb.append("\n    public static ").append(className)
                     .append(" ")
                     .append(factoryName)
                     .append("(\n");
            for (int i = 0;i <fields.size();i++) {
                var f = fields.get(i);
                sb.append("        ").append(f.fieldType())
                         .append(" ")
                         .append(f.fieldName());
                if (i <fields.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("    ) {\n");
            sb.append("        return new ").append(className)
                     .append("(");
            for (int i = 0;i <fields.size();i++) {
                if (i > 0) sb.append(", ");
                sb.append(fields.get(i).fieldName());
            }
            sb.append(");\n");
            sb.append("    }\n");
        }
        if (config.generateRowMapper()) {renderRowMapper(sb, className, fields);}
        sb.append("}\n");
        return sb.toString();
    }

    private void renderRowMapper(StringBuilder sb, String className, List<FieldInfo> fields) {
        sb.append("\n    public static Result<").append(className)
                 .append("> mapRow(RowAccessor row) {\n");
        if (fields.size() <= 11) {renderSimpleRowMapper(sb, className, fields);} else {renderNestedRowMapper(sb,
                                                                                                             className,
                                                                                                             fields);}
        sb.append("    }\n");
    }

    private void renderSimpleRowMapper(StringBuilder sb, String className, List<FieldInfo> fields) {
        sb.append("        return Result.all(\n");
        for (int i = 0;i <fields.size();i++) {
            var f = fields.get(i);
            sb.append("            ").append(renderAccessor(f));
            if (i <fields.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("        ).map(").append(className)
                 .append("::new);\n");
    }

    private void renderNestedRowMapper(StringBuilder sb, String className, List<FieldInfo> fields) {
        var groups = new ArrayList<List<FieldInfo>>();
        for (int i = 0;i <fields.size();i += 9) {groups.add(fields.subList(i,
                                                                           Math.min(i + 9, fields.size())));}
        sb.append("        // ").append(fields.size())
                 .append(" columns — using nested Result.all\n");
        sb.append("        return Result.all(\n");
        for (int g = 0;g <groups.size();g++) {
            var group = groups.get(g);
            sb.append("            Result.all(\n");
            for (int i = 0;i <group.size();i++) {
                sb.append("                ").append(renderAccessor(group.get(i)));
                if (i <group.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("            )");
            if (g <groups.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("        ).map((");
        for (int g = 0;g <groups.size();g++) {
            if (g > 0) sb.append(", ");
            sb.append("g").append(g);
        }
        sb.append(") -> new ").append(className)
                 .append("(\n");
        sb.append("            // TODO: extract tuple fields\n");
        sb.append("        ));\n");
    }

    private String renderAccessor(FieldInfo field) {
        var accessor = field.typeInfo().rowAccessorMethod();
        var typeArg = field.typeInfo().rowAccessorTypeArg();
        var call = typeArg.isPresent()
                  ? "row." + accessor + "(\"" + field.columnName() + "\", " + typeArg.unwrap() + ")"
                  : "row." + accessor + "(\"" + field.columnName() + "\")";
        if (field.nullable()) {if (field.fieldType().startsWith("Option<")) {return call + ".map(Option::present).or(Option.empty())";}}
        return call;
    }

    record FieldInfo(String columnName,
                     String fieldName,
                     String fieldType,
                     boolean nullable,
                     TypeMapper.JavaTypeInfo typeInfo){}

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
