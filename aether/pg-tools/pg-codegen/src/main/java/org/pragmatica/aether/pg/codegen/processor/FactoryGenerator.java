package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.aether.pg.codegen.NamingConvention;
import org.pragmatica.aether.pg.codegen.TypeMapper;
import org.pragmatica.aether.pg.schema.model.Column;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.aether.pg.schema.model.Table;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;


/// Generates `{Interface}Factory.java` source code for a persistence interface.
///
/// The generated factory contains:
/// - A static factory method returning a local record implementation
/// - SQL string constants for each method
/// - Row mapper methods using `Result.all()` composition
/// - Method implementations delegating to `PgSqlConnector`
public final class FactoryGenerator {
    private FactoryGenerator() {}

    public record MethodInfo(String methodName,
                             String sqlConstantName,
                             String sql,
                             MethodAnalyzer.ReturnKind returnKind,
                             String returnTypeName,
                             String innerTypeName,
                             List<MethodParam> signatureParams,
                             List<MethodParam> params,
                             List<MapperColumn> mapperColumns,
                             boolean needsMapper) {
        public static MethodInfo withSharedParams(String methodName,
                                                  String sqlConstantName,
                                                  String sql,
                                                  MethodAnalyzer.ReturnKind returnKind,
                                                  String returnTypeName,
                                                  String innerTypeName,
                                                  List<MethodParam> params,
                                                  List<MapperColumn> mapperColumns,
                                                  boolean needsMapper) {
            return new MethodInfo(methodName,
                                  sqlConstantName,
                                  sql,
                                  returnKind,
                                  returnTypeName,
                                  innerTypeName,
                                  params,
                                  params,
                                  mapperColumns,
                                  needsMapper);
        }
    }

    public record MethodParam(String name, String typeName, String accessor) {
        public static MethodParam simple(String name, String typeName) {
            return new MethodParam(name, typeName, name);
        }
    }

    public record MapperColumn(String columnName, String accessorMethod, String fieldName, String typeArg) {
        public static MapperColumn withoutTypeArg(String columnName, String accessorMethod, String fieldName) {
            return new MapperColumn(columnName, accessorMethod, fieldName, "");
        }
    }

    public static String generate(String packageName,
                                  String interfaceName,
                                  List<MethodInfo> methods,
                                  Set<String> additionalImports) {
        var sb = new StringBuilder();
        var factoryClassName = interfaceName + "Factory";
        var factoryMethodName = NamingConvention.toFactoryMethodName(interfaceName);
        appendHeader(sb, packageName, interfaceName, methods, additionalImports);
        appendClassStart(sb, factoryClassName, factoryMethodName, interfaceName);
        appendScalarMapperConstants(sb, methods);
        appendSqlConstants(sb, methods);
        appendMethodImplementations(sb, methods, factoryClassName);
        appendRecordClose(sb, factoryMethodName);
        appendMapperMethods(sb, methods, factoryClassName);
        appendClassEnd(sb);
        return sb.toString();
    }

    public static List<MapperColumn> resolveMapperColumns(Table table, List<String> fieldNames) {
        var columns = new ArrayList<MapperColumn>();
        for (var fieldName : fieldNames) {
            var columnName = NamingConvention.toSnakeCase(fieldName);
            var column = table.column(columnName);
            if (column.isPresent()) {
                var typeInfo = TypeMapper.map(column.unwrap().type());
                var accessor = typeInfo.isPresent()
                              ? typeInfo.unwrap().rowAccessorMethod()
                              : "getString";
                var typeArg = typeInfo.flatMap(ti -> ti.rowAccessorTypeArg()).fold(() -> "", t -> t);
                columns.add(new MapperColumn(columnName, accessor, fieldName, typeArg));
            }
        }
        return columns;
    }

    public static String toSqlConstantName(String methodName) {
        return NamingConvention.toSnakeCase(methodName).toUpperCase();
    }

    private static void appendHeader(StringBuilder sb,
                                     String packageName,
                                     String interfaceName,
                                     List<MethodInfo> methods,
                                     Set<String> additionalImports) {
        sb.append("package ").append(packageName)
                 .append(";\n\n");
        var imports = new LinkedHashSet<String>();
        imports.add("org.pragmatica.aether.resource.db.PgSqlConnector");
        imports.add("org.pragmatica.aether.resource.db.RowMapper");
        imports.add("org.pragmatica.lang.Result");
        imports.add("org.pragmatica.lang.Promise");
        for (var method : methods) {
            if (method.returnKind == MethodAnalyzer.ReturnKind.OPTIONAL) {imports.add("org.pragmatica.lang.Option");}
            if (method.returnKind == MethodAnalyzer.ReturnKind.LIST) {imports.add("java.util.List");}
            if (method.returnKind == MethodAnalyzer.ReturnKind.UNIT) {imports.add("org.pragmatica.lang.Unit");}
        }
        imports.addAll(additionalImports);
        for (var imp : imports) {sb.append("import ").append(imp)
                                          .append(";\n");}
        sb.append('\n');
    }

    private static void appendClassStart(StringBuilder sb,
                                         String className,
                                         String factoryMethod,
                                         String interfaceName) {
        sb.append("public final class ").append(className)
                 .append(" {\n\n");
        sb.append("    public static ").append(interfaceName)
                 .append(' ')
                 .append(factoryMethod);
        sb.append("(PgSqlConnector db) {\n\n");
        sb.append("        record ").append(factoryMethod)
                 .append("(PgSqlConnector db) implements ");
        sb.append(interfaceName).append(" {\n\n");
    }

    private static void appendScalarMapperConstants(StringBuilder sb, List<MethodInfo> methods) {}

    private static void appendSqlConstants(StringBuilder sb, List<MethodInfo> methods) {
        for (var method : methods) {
            sb.append("            private static final String ").append(method.sqlConstantName);
            sb.append(" =\n                \"").append(escapeSql(method.sql))
                     .append("\";\n\n");
        }
    }

    private static void appendMethodImplementations(StringBuilder sb,
                                                    List<MethodInfo> methods,
                                                    String factoryClassName) {
        for (var method : methods) {
            sb.append("            @Override\n");
            sb.append("            public ").append(formatReturnType(method))
                     .append(' ');
            sb.append(method.methodName).append('(');
            appendParams(sb, method.signatureParams);
            sb.append(") {\n");
            appendMethodBody(sb, method, factoryClassName);
            sb.append("            }\n\n");
        }
    }

    private static void appendMethodBody(StringBuilder sb, MethodInfo method, String factoryClassName) {
        if (method.returnKind == MethodAnalyzer.ReturnKind.UNIT) {
            appendUnitMethodBody(sb, method);
            return;
        }
        var connectorMethod = MethodAnalyzer.connectorMethod(method.returnKind);
        sb.append("                return db.").append(connectorMethod)
                 .append('(');
        sb.append(method.sqlConstantName);
        if (method.needsMapper) {
            sb.append(",\n                    ").append(factoryClassName)
                     .append("::map");
            sb.append(toMapperMethodSuffix(method.innerTypeName));
        } else if (method.returnKind == MethodAnalyzer.ReturnKind.LONG || method.returnKind == MethodAnalyzer.ReturnKind.BOOLEAN) {appendScalarMapper(sb,
                                                                                                                                                      method.returnKind,
                                                                                                                                                      method.sql);}
        for (var param : method.params) {sb.append(", ").append(param.accessor());}
        sb.append(");\n");
    }

    private static void appendUnitMethodBody(StringBuilder sb, MethodInfo method) {
        sb.append("                return db.update(");
        sb.append(method.sqlConstantName);
        for (var param : method.params) {sb.append(", ").append(param.accessor());}
        sb.append(").mapToUnit();\n");
    }

    private static void appendScalarMapper(StringBuilder sb, MethodAnalyzer.ReturnKind kind, String sql) {
        var columnName = inferScalarColumnName(sql, kind);
        switch (kind){
            case LONG -> sb.append(",\n                    row -> row.getLong(\"").append(columnName)
                                  .append("\")");
            case BOOLEAN -> sb.append(",\n                    row -> row.getBoolean(\"").append(columnName)
                                     .append("\")");
            default -> {}
        }
    }

    private static String inferScalarColumnName(String sql, MethodAnalyzer.ReturnKind kind) {
        var upper = sql.toUpperCase();
        var asIdx = upper.indexOf(" AS ");
        if (asIdx >= 0) {
            var afterAs = sql.substring(asIdx + 4).trim();
            var spaceIdx = afterAs.indexOf(' ');
            return spaceIdx >= 0
                  ? afterAs.substring(0, spaceIdx).toLowerCase()
                  : afterAs.toLowerCase();
        }
        if (kind == MethodAnalyzer.ReturnKind.BOOLEAN && upper.contains("EXISTS")) {return "exists";}
        if (kind == MethodAnalyzer.ReturnKind.LONG && upper.contains("COUNT")) {return "count";}
        return kind == MethodAnalyzer.ReturnKind.BOOLEAN
              ? "exists"
              : "count";
    }

    private static void appendRecordClose(StringBuilder sb, String factoryMethod) {
        sb.append("        }\n\n");
        sb.append("        return new ").append(factoryMethod)
                 .append("(db);\n");
        sb.append("    }\n");
    }

    private static void appendMapperMethods(StringBuilder sb, List<MethodInfo> methods, String factoryClassName) {
        var generatedMappers = new LinkedHashSet<String>();
        for (var method : methods) {
            var mapperSuffix = toMapperMethodSuffix(method.innerTypeName);
            if (!method.needsMapper || !generatedMappers.add(mapperSuffix)) {continue;}
            sb.append("\n    private static Result<").append(method.innerTypeName);
            sb.append("> map").append(mapperSuffix)
                     .append("(RowMapper.RowAccessor row) {\n");
            sb.append("        return Result.all(\n");
            for (int i = 0;i <method.mapperColumns.size();i++) {
                var col = method.mapperColumns.get(i);
                sb.append("            row.").append(col.accessorMethod())
                         .append("(\"")
                         .append(col.columnName())
                         .append("\"");
                if (!col.typeArg().isEmpty()) {sb.append(", ").append(col.typeArg());}
                sb.append(')');
                if (i <method.mapperColumns.size() - 1) {sb.append(',');}
                sb.append('\n');
            }
            sb.append("        ).map(").append(method.innerTypeName)
                     .append("::new);\n");
            sb.append("    }\n");
        }
    }

    private static void appendClassEnd(StringBuilder sb) {
        sb.append("}\n");
    }

    private static void appendParams(StringBuilder sb, List<MethodParam> params) {
        for (int i = 0;i <params.size();i++) {
            if (i > 0) {sb.append(", ");}
            sb.append(simplifyTypeName(params.get(i).typeName())).append(' ')
                     .append(params.get(i).name());
        }
    }

    private static String simplifyTypeName(String typeName) {
        if (typeName.startsWith("java.lang.") && !typeName.substring("java.lang.".length()).contains(".")) {return typeName.substring("java.lang.".length());}
        var lastDot = typeName.lastIndexOf('.');
        if (lastDot <0) {return typeName;}
        var secondLastDot = typeName.lastIndexOf('.', lastDot - 1);
        if (secondLastDot >= 0) {
            var potentialOuter = typeName.substring(secondLastDot + 1, lastDot);
            if (!potentialOuter.isEmpty() && Character.isUpperCase(potentialOuter.charAt(0))) {return potentialOuter + "." + typeName.substring(lastDot + 1);}
        }
        return typeName.substring(lastDot + 1);
    }

    private static String formatReturnType(MethodInfo method) {
        return switch (method.returnKind){
            case SINGLE -> "Promise<" + method.innerTypeName + ">";
            case OPTIONAL -> "Promise<Option<" + method.innerTypeName + ">>";
            case LIST -> "Promise<List<" + method.innerTypeName + ">>";
            case UNIT -> "Promise<Unit>";
            case LONG -> "Promise<Long>";
            case BOOLEAN -> "Promise<Boolean>";
        };
    }

    private static String escapeSql(String sql) {
        return sql.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toMapperMethodSuffix(String innerTypeName) {
        return innerTypeName.replace(".", "");
    }
}
