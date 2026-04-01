package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.aether.pg.codegen.NamingConvention;
import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.pg.codegen.annotation.Table;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.lang.Option;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// Main annotation processor for PostgreSQL persistence interfaces.
///
/// Processes interfaces annotated with `@PgSql` (or any annotation carrying
/// `@ResourceQualifier(type = PgSqlConnector.class)`) and generates
/// `{Interface}Factory` implementation classes with:
/// - Compile-time SQL validation against migration-derived schema
/// - Named parameter rewriting to positional `$N`
/// - Auto-generated CRUD SQL from method names
/// - Row mapper code using `Result.all()` composition
///
/// Triggered by the presence of `@Query` annotations on interface methods.
@SupportedAnnotationTypes("org.pragmatica.aether.resource.db.PgSql")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class QueryAnnotationProcessor extends AbstractProcessor {

    private SchemaLoader schemaLoader;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.schemaLoader = new SchemaLoader(processingEnv);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return false;
        }

        for (var annotation : annotations) {
            for (var element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element.getKind() == ElementKind.INTERFACE) {
                    processInterface((TypeElement) element);
                }
            }
        }
        return true;
    }

    private void processInterface(TypeElement interfaceElement) {
        var packageName = extractPackageName(interfaceElement);
        var interfaceName = interfaceElement.getSimpleName().toString();

        var configPath = extractConfigPath(interfaceElement);
        var schemaOpt = schemaLoader.loadSchema(configPath);

        if (schemaOpt.isEmpty()) {
            note("No schema available for validation; generating factory without validation", interfaceElement);
        }

        note("Generating " + interfaceName + "Factory", interfaceElement);

        var methods = analyzeMethods(interfaceElement, schemaOpt);
        var additionalImports = collectImports(interfaceElement, methods);

        var source = FactoryGenerator.generate(packageName, interfaceName, methods, additionalImports);
        writeSourceFile(
            packageName.isEmpty() ? interfaceName + "Factory" : packageName + "." + interfaceName + "Factory",
            source,
            interfaceElement
        );
    }

    private List<FactoryGenerator.MethodInfo> analyzeMethods(TypeElement interfaceElement, Option<Schema> schemaOpt) {
        var methods = new ArrayList<FactoryGenerator.MethodInfo>();

        for (var enclosed : interfaceElement.getEnclosedElements()) {
            if (enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }

            var execElement = (ExecutableElement) enclosed;
            var methodInfo = analyzeMethod(execElement, schemaOpt, interfaceElement);

            if (methodInfo != null) {
                methods.add(methodInfo);
            }
        }
        return methods;
    }

    private FactoryGenerator.MethodInfo analyzeMethod(
        ExecutableElement execElement,
        Option<Schema> schemaOpt,
        TypeElement interfaceElement
    ) {
        var methodName = execElement.getSimpleName().toString();
        var returnType = execElement.getReturnType();
        var resolved = TypeMirrorResolver.resolve(returnType, processingEnv.getTypeUtils());

        if (resolved == null) {
            error(ProcessorError.invalidReturnType(methodName), execElement);
            return null;
        }

        var queryAnnotation = execElement.getAnnotation(Query.class);

        if (queryAnnotation != null) {
            return analyzeQueryMethod(execElement, methodName, resolved, queryAnnotation);
        }

        return analyzeCrudMethod(execElement, methodName, resolved, schemaOpt, interfaceElement);
    }

    private FactoryGenerator.MethodInfo analyzeQueryMethod(
        ExecutableElement execElement,
        String methodName,
        TypeMirrorResolver.ResolvedReturn resolved,
        Query queryAnnotation
    ) {
        var sql = queryAnnotation.value();
        var paramNames = extractParamNames(execElement);
        var rewritten = QueryRewriter.rewriteNamedParams(sql, paramNames);
        var params = extractMethodParams(execElement);
        var mapperColumns = resolveMapperColumns(execElement, resolved);

        return new FactoryGenerator.MethodInfo(
            methodName,
            FactoryGenerator.toSqlConstantName(methodName),
            rewritten.sql(),
            resolved.kind(),
            resolved.innerTypeName(),
            resolved.innerTypeName(),
            reorderedParams(params, rewritten.parameterOrder()),
            mapperColumns,
            resolved.needsMapper()
        );
    }

    private FactoryGenerator.MethodInfo analyzeCrudMethod(
        ExecutableElement execElement,
        String methodName,
        TypeMirrorResolver.ResolvedReturn resolved,
        Option<Schema> schemaOpt,
        TypeElement interfaceElement
    ) {
        var parsed = MethodNameParser.parse(methodName);
        if (parsed == null) {
            error(ProcessorError.unrecognizedMethodName(methodName), execElement);
            return null;
        }

        var tableName = resolveTableName(execElement, resolved, schemaOpt, interfaceElement);
        if (tableName == null) {
            error(ProcessorError.cannotInferTable(methodName), execElement);
            return null;
        }

        var params = extractMethodParams(execElement);
        var inputFieldNames = params.stream().map(FactoryGenerator.MethodParam::name).toList();

        if (schemaOpt.isEmpty()) {
            return buildCrudMethodInfoWithoutSchema(execElement, methodName, parsed, tableName, resolved, params, inputFieldNames);
        }

        var schema = schemaOpt.unwrap();
        var tableOpt = schema.table(tableName);

        if (tableOpt.isEmpty()) {
            error(ProcessorError.tableNotFound(tableName), execElement);
            return null;
        }

        var table = tableOpt.unwrap();
        var selectColumns = resolveSelectColumns(execElement, resolved, table);
        var sqlOpt = MethodAnalyzer.generateCrudSql(parsed, table, selectColumns, inputFieldNames, resolved.kind());

        if (sqlOpt.isEmpty()) {
            error(ProcessorError.noPrimaryKey(tableName), execElement);
            return null;
        }

        var mapperColumns = resolved.needsMapper()
            ? FactoryGenerator.resolveMapperColumns(table, extractReturnFieldNames(execElement, resolved))
            : List.<FactoryGenerator.MapperColumn>of();

        return new FactoryGenerator.MethodInfo(
            methodName,
            FactoryGenerator.toSqlConstantName(methodName),
            sqlOpt.unwrap(),
            resolved.kind(),
            resolved.innerTypeName(),
            resolved.innerTypeName(),
            params,
            mapperColumns,
            resolved.needsMapper()
        );
    }

    private FactoryGenerator.MethodInfo buildCrudMethodInfoWithoutSchema(
        ExecutableElement execElement,
        String methodName,
        MethodNameParser.ParsedMethod parsed,
        String tableName,
        TypeMirrorResolver.ResolvedReturn resolved,
        List<FactoryGenerator.MethodParam> params,
        List<String> inputFieldNames
    ) {
        var selectColumns = List.<String>of();
        var sql = generateCrudSqlWithoutSchema(parsed, tableName, selectColumns, inputFieldNames, resolved.kind());
        var mapperColumns = resolveMapperColumns(execElement, resolved);

        return new FactoryGenerator.MethodInfo(
            methodName,
            FactoryGenerator.toSqlConstantName(methodName),
            sql,
            resolved.kind(),
            resolved.innerTypeName(),
            resolved.innerTypeName(),
            params,
            mapperColumns,
            resolved.needsMapper()
        );
    }

    private String generateCrudSqlWithoutSchema(
        MethodNameParser.ParsedMethod parsed,
        String tableName,
        List<String> selectColumns,
        List<String> inputFieldNames,
        MethodAnalyzer.ReturnKind returnKind
    ) {
        // Without schema, generate basic SQL using the parsed method pattern
        return switch (parsed.operation()) {
            case FIND, FIND_ALL -> generateBasicFindSql(parsed, tableName, selectColumns);
            case COUNT -> generateBasicCountSql(parsed, tableName);
            case EXISTS -> generateBasicExistsSql(parsed, tableName);
            case DELETE -> generateBasicDeleteSql(parsed, tableName);
            case INSERT -> generateBasicInsertSql(tableName, inputFieldNames, returnKind);
            case SAVE -> generateBasicInsertSql(tableName, inputFieldNames, returnKind);
        };
    }

    private String resolveTableName(
        ExecutableElement execElement,
        TypeMirrorResolver.ResolvedReturn resolved,
        Option<Schema> schemaOpt,
        TypeElement interfaceElement
    ) {
        // Try @Table annotation first
        var tableAnnotation = execElement.getAnnotation(Table.class);
        if (tableAnnotation != null) {
            return resolveTableFromAnnotation(execElement, schemaOpt);
        }

        // Try inferring from the return type
        if (resolved.needsMapper() && schemaOpt.isPresent()) {
            var inferred = MethodAnalyzer.resolveTableFromTypeName(
                resolved.simpleTypeName(), schemaOpt.unwrap()
            );
            if (inferred.isPresent()) {
                return inferred.unwrap();
            }
        }

        // Fallback: try deriving from inner type name with snake_case conversion
        if (resolved.needsMapper()) {
            return deriveTableNameFromTypeName(resolved.simpleTypeName());
        }

        return null;
    }

    private String resolveTableFromAnnotation(ExecutableElement execElement, Option<Schema> schemaOpt) {
        // @Table(value) references a class; at compile time we catch MirroredTypeException
        // to get the TypeMirror
        try {
            var tableAnnotation = execElement.getAnnotation(Table.class);
            if (tableAnnotation != null) {
                tableAnnotation.value(); // triggers MirroredTypeException
            }
        } catch (MirroredTypeException mte) {
            var typeMirror = mte.getTypeMirror();
            if (typeMirror instanceof DeclaredType dt) {
                var typeElement = (TypeElement) dt.asElement();
                var typeName = typeElement.getSimpleName().toString();
                if (schemaOpt.isPresent()) {
                    var resolved = MethodAnalyzer.resolveTableFromTypeName(typeName, schemaOpt.unwrap());
                    if (resolved.isPresent()) {
                        return resolved.unwrap();
                    }
                }
                return deriveTableNameFromTypeName(typeName);
            }
        }
        return null;
    }

    private static String deriveTableNameFromTypeName(String typeName) {
        var stripped = typeName.endsWith("Row") ? typeName.substring(0, typeName.length() - 3) : typeName;
        return NamingConvention.toSnakeCase(
            Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1)
        );
    }

    private List<String> resolveSelectColumns(
        ExecutableElement execElement,
        TypeMirrorResolver.ResolvedReturn resolved,
        org.pragmatica.aether.pg.schema.model.Table table
    ) {
        if (!resolved.needsMapper()) {
            return List.of();
        }

        var fieldNames = extractReturnFieldNames(execElement, resolved);
        if (fieldNames.isEmpty()) {
            return List.of();
        }

        return fieldNames.stream()
            .map(NamingConvention::toSnakeCase)
            .toList();
    }

    private List<FactoryGenerator.MapperColumn> resolveMapperColumns(
        ExecutableElement execElement,
        TypeMirrorResolver.ResolvedReturn resolved
    ) {
        if (!resolved.needsMapper()) {
            return List.of();
        }

        var fields = extractReturnFields(execElement, resolved);
        return fields.stream()
            .map(QueryAnnotationProcessor::toMapperColumn)
            .toList();
    }

    private List<TypeMirrorResolver.FieldInfo> extractReturnFields(
        ExecutableElement execElement,
        TypeMirrorResolver.ResolvedReturn resolved
    ) {
        var innerElement = TypeMirrorResolver.innerTypeElement(execElement.getReturnType());
        if (innerElement == null) {
            return List.of();
        }
        return TypeMirrorResolver.extractFields(innerElement);
    }

    private List<String> extractReturnFieldNames(ExecutableElement execElement, TypeMirrorResolver.ResolvedReturn resolved) {
        return extractReturnFields(execElement, resolved).stream()
            .map(TypeMirrorResolver.FieldInfo::name)
            .toList();
    }

    private static FactoryGenerator.MapperColumn toMapperColumn(TypeMirrorResolver.FieldInfo field) {
        var columnName = NamingConvention.toSnakeCase(field.name());
        var accessor = accessorInfoForJavaType(field.typeName());
        return new FactoryGenerator.MapperColumn(columnName, accessor.method(), field.name(), accessor.typeArg());
    }

    private record AccessorInfo(String method, String typeArg) {}

    private static AccessorInfo accessorInfoForJavaType(String javaTypeName) {
        return switch (javaTypeName) {
            case "long", "java.lang.Long" -> new AccessorInfo("getLong", "");
            case "int", "java.lang.Integer" -> new AccessorInfo("getInt", "");
            case "double", "java.lang.Double" -> new AccessorInfo("getDouble", "");
            case "boolean", "java.lang.Boolean" -> new AccessorInfo("getBoolean", "");
            case "byte[]" -> new AccessorInfo("getBytes", "");
            case "java.lang.String", "String" -> new AccessorInfo("getString", "");
            case "java.math.BigDecimal" -> new AccessorInfo("getObject", "java.math.BigDecimal.class");
            case "java.time.Instant" -> new AccessorInfo("getObject", "java.time.Instant.class");
            case "java.time.LocalDateTime" -> new AccessorInfo("getObject", "java.time.LocalDateTime.class");
            case "java.time.LocalDate" -> new AccessorInfo("getObject", "java.time.LocalDate.class");
            case "java.util.UUID" -> new AccessorInfo("getObject", "java.util.UUID.class");
            default -> new AccessorInfo("getString", "");
        };
    }

    private static List<String> extractParamNames(ExecutableElement execElement) {
        return execElement.getParameters().stream()
            .map(p -> p.getSimpleName().toString())
            .toList();
    }

    private static List<FactoryGenerator.MethodParam> extractMethodParams(ExecutableElement execElement) {
        return execElement.getParameters().stream()
            .map(p -> new FactoryGenerator.MethodParam(
                p.getSimpleName().toString(),
                p.asType().toString()
            ))
            .toList();
    }

    private static List<FactoryGenerator.MethodParam> reorderedParams(
        List<FactoryGenerator.MethodParam> original,
        List<String> paramOrder
    ) {
        if (paramOrder.isEmpty()) {
            return original;
        }

        var result = new ArrayList<FactoryGenerator.MethodParam>();
        for (var name : paramOrder) {
            for (var param : original) {
                if (param.name().equals(name)) {
                    result.add(param);
                    break;
                }
            }
        }

        // Add any remaining params not in the order (e.g. unused but present for compile compat)
        for (var param : original) {
            if (result.stream().noneMatch(p -> p.name().equals(param.name()))) {
                result.add(param);
            }
        }
        return result;
    }

    private Set<String> collectImports(TypeElement interfaceElement, List<FactoryGenerator.MethodInfo> methods) {
        var imports = new LinkedHashSet<String>();

        for (var enclosed : interfaceElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.RECORD) {
                // Inner records need to be imported via enclosing interface
                // The generated factory references them as InterfaceName.RecordName
            }
        }

        return imports;
    }

    // --- SQL generation without schema (fallback) ---

    private static String generateBasicFindSql(
        MethodNameParser.ParsedMethod parsed,
        String tableName,
        List<String> selectColumns
    ) {
        var select = selectColumns.isEmpty() ? "*" : String.join(", ", selectColumns);
        var sb = new StringBuilder("SELECT ").append(select).append(" FROM ").append(tableName);
        appendWhereClause(sb, parsed.conditions());
        appendOrderBy(sb, parsed.orderBy());
        return sb.toString();
    }

    private static String generateBasicCountSql(MethodNameParser.ParsedMethod parsed, String tableName) {
        var sb = new StringBuilder("SELECT count(*) FROM ").append(tableName);
        appendWhereClause(sb, parsed.conditions());
        return sb.toString();
    }

    private static String generateBasicExistsSql(MethodNameParser.ParsedMethod parsed, String tableName) {
        var sb = new StringBuilder("SELECT EXISTS(SELECT 1 FROM ").append(tableName);
        appendWhereClause(sb, parsed.conditions());
        sb.append(')');
        return sb.toString();
    }

    private static String generateBasicDeleteSql(MethodNameParser.ParsedMethod parsed, String tableName) {
        var sb = new StringBuilder("DELETE FROM ").append(tableName);
        appendWhereClause(sb, parsed.conditions());
        return sb.toString();
    }

    private static String generateBasicInsertSql(String tableName, List<String> fieldNames, MethodAnalyzer.ReturnKind returnKind) {
        var columns = fieldNames.stream().map(NamingConvention::toSnakeCase).toList();
        var sb = new StringBuilder("INSERT INTO ").append(tableName);
        sb.append(" (").append(String.join(", ", columns)).append(")");
        sb.append(" VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('$').append(i + 1);
        }
        sb.append(')');
        if (returnKind != MethodAnalyzer.ReturnKind.UNIT) {
            sb.append(" RETURNING *");
        }
        return sb.toString();
    }

    private static void appendWhereClause(StringBuilder sb, List<MethodNameParser.ColumnCondition> conditions) {
        if (conditions.isEmpty()) {
            return;
        }

        sb.append(" WHERE ");
        var paramIdx = 1;
        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sb.append(" AND ");
            }
            var cond = conditions.get(i);
            sb.append(cond.columnName());

            switch (cond.operator()) {
                case IS_NULL, IS_NOT_NULL -> sb.append(' ').append(cond.operator().sql());
                case BETWEEN -> {
                    sb.append(" BETWEEN $").append(paramIdx).append(" AND $").append(paramIdx + 1);
                    paramIdx += 2;
                }
                case IN -> {
                    sb.append(" IN ($").append(paramIdx).append(')');
                    paramIdx++;
                }
                default -> {
                    sb.append(' ').append(cond.operator().sql()).append(" $").append(paramIdx);
                    paramIdx++;
                }
            }
        }
    }

    private static void appendOrderBy(StringBuilder sb, List<MethodNameParser.OrderByEntry> orderBy) {
        if (orderBy.isEmpty()) {
            return;
        }

        sb.append(" ORDER BY ");
        for (int i = 0; i < orderBy.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            var entry = orderBy.get(i);
            sb.append(entry.columnName());
            if (entry.direction() == MethodNameParser.SortDirection.DESC) {
                sb.append(" DESC");
            } else {
                sb.append(" ASC");
            }
        }
    }

    private void writeSourceFile(String qualifiedName, String source, Element originElement) {
        try {
            var file = processingEnv.getFiler().createSourceFile(qualifiedName, originElement);
            try (var writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (IOException e) {
            error("Failed to write generated source: " + e.getMessage(), originElement);
        }
    }

    private String extractPackageName(TypeElement element) {
        var pkg = processingEnv.getElementUtils().getPackageOf(element);
        return pkg.isUnnamed() ? "" : pkg.getQualifiedName().toString();
    }

    private String extractConfigPath(TypeElement interfaceElement) {
        for (var mirror : interfaceElement.getAnnotationMirrors()) {
            var annotationType = mirror.getAnnotationType().asElement();
            for (var annotationOfAnnotation : annotationType.getAnnotationMirrors()) {
                var metaName = annotationOfAnnotation.getAnnotationType().toString();
                if (metaName.endsWith("ResourceQualifier")) {
                    for (var entry : annotationOfAnnotation.getElementValues().entrySet()) {
                        if (entry.getKey().getSimpleName().toString().equals("config")) {
                            return entry.getValue().getValue().toString();
                        }
                    }
                }
            }
        }
        return "database";
    }

    private void note(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, message, element);
    }

    private void error(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
