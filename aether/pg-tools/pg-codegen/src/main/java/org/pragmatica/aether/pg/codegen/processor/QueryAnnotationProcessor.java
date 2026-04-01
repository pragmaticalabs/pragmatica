package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.aether.pg.codegen.NamingConvention;
import org.pragmatica.aether.pg.codegen.TypeMapper;
import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.pg.codegen.annotation.Table;
import org.pragmatica.aether.pg.schema.model.BuiltinTypes;
import org.pragmatica.aether.pg.schema.model.PgType;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.lang.Contract;
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
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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
@Contract
@SupportedAnnotationTypes("org.pragmatica.aether.resource.db.PgSql")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class QueryAnnotationProcessor extends AbstractProcessor {
    private SchemaLoader schemaLoader;

    @Override public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.schemaLoader = new SchemaLoader(processingEnv);
    }

    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if ( roundEnv.processingOver()) {
        return false;}
        for ( var annotation : annotations) {
        for ( var element : roundEnv.getElementsAnnotatedWith(annotation)) {
        if ( element.getKind() == ElementKind.INTERFACE) {
        processInterface((TypeElement) element);}}}
        return true;
    }

    private void processInterface(TypeElement interfaceElement) {
        var packageName = extractPackageName(interfaceElement);
        var interfaceName = interfaceElement.getSimpleName().toString();
        var configPath = extractConfigPath(interfaceElement);
        var schemaOpt = schemaLoader.loadSchema(configPath);
        if ( schemaOpt.isEmpty()) {
        note("No schema available for validation; generating factory without validation", interfaceElement);}
        note("Generating " + interfaceName + "Factory", interfaceElement);
        var methods = analyzeMethods(interfaceElement, schemaOpt);
        var additionalImports = collectImports(interfaceElement, methods);
        var source = FactoryGenerator.generate(packageName, interfaceName, methods, additionalImports);
        writeSourceFile(packageName.isEmpty()
                        ? interfaceName + "Factory"
                        : packageName + "." + interfaceName + "Factory",
                        source,
                        interfaceElement);
    }

    private List<FactoryGenerator.MethodInfo> analyzeMethods(TypeElement interfaceElement, Option<Schema> schemaOpt) {
        var methods = new ArrayList<FactoryGenerator.MethodInfo>();
        for ( var enclosed : interfaceElement.getEnclosedElements()) {
            if ( enclosed.getKind() != ElementKind.METHOD) {
            continue;}
            var execElement = (ExecutableElement) enclosed;
            var methodInfo = analyzeMethod(execElement, schemaOpt, interfaceElement);
            if ( methodInfo != null) {
            methods.add(methodInfo);}
        }
        return methods;
    }

    private FactoryGenerator.MethodInfo analyzeMethod(
    ExecutableElement execElement,
    Option<Schema> schemaOpt,
    TypeElement interfaceElement) {
        var methodName = execElement.getSimpleName().toString();
        var returnType = execElement.getReturnType();
        var resolved = TypeMirrorResolver.resolve(returnType, processingEnv.getTypeUtils());
        if ( resolved == null) {
            error(ProcessorError.invalidReturnType(methodName), execElement);
            return null;
        }
        var queryAnnotation = execElement.getAnnotation(Query.class);
        if ( queryAnnotation != null) {
        return analyzeQueryMethod(execElement, methodName, resolved, queryAnnotation, schemaOpt);}
        return analyzeCrudMethod(execElement, methodName, resolved, schemaOpt, interfaceElement);
    }

    private FactoryGenerator.MethodInfo analyzeQueryMethod(
    ExecutableElement execElement,
    String methodName,
    TypeMirrorResolver.ResolvedReturn resolved,
    Query queryAnnotation,
    Option<Schema> schemaOpt) {
        var sql = queryAnnotation.value();
        var originalParams = extractMethodParams(execElement);
        var expansion = expandRecordParams(execElement, sql, originalParams);
        validateQueryParams(execElement, methodName, expansion.sql(), expansion.allParamNames());
        schemaOpt.onPresent(schema -> validateQueryParamTypes(execElement,
                                                              expansion.sql(),
                                                              expansion.allParamNames(),
                                                              expansion.bodyParams(),
                                                              schema));
        schemaOpt.onPresent(schema -> validateReturnTypeMapping(execElement, resolved, expansion.sql()));
        var rewritten = QueryRewriter.rewriteNamedParams(expansion.sql(), expansion.allParamNames());
        var mapperColumns = resolveMapperColumns(execElement, resolved);
        var bodyParams = reorderedParams(expansion.bodyParams(), rewritten.parameterOrder());
        if ( expansion.hasExpansion()) {
        return new FactoryGenerator.MethodInfo(methodName,
                                               FactoryGenerator.toSqlConstantName(methodName),
                                               rewritten.sql(),
                                               resolved.kind(),
                                               resolved.innerTypeName(),
                                               resolved.innerTypeName(),
                                               originalParams,
                                               bodyParams,
                                               mapperColumns,
                                               resolved.needsMapper());}
        return FactoryGenerator.MethodInfo.withSharedParams(methodName,
                                               FactoryGenerator.toSqlConstantName(methodName),
                                               rewritten.sql(),
                                               resolved.kind(),
                                               resolved.innerTypeName(),
                                               resolved.innerTypeName(),
                                               reorderedParams(originalParams, rewritten.parameterOrder()),
                                               mapperColumns,
                                               resolved.needsMapper());
    }

    private FactoryGenerator.MethodInfo analyzeCrudMethod(
    ExecutableElement execElement,
    String methodName,
    TypeMirrorResolver.ResolvedReturn resolved,
    Option<Schema> schemaOpt,
    TypeElement interfaceElement) {
        var parsed = MethodNameParser.parse(methodName);
        if ( parsed == null) {
            error(ProcessorError.unrecognizedMethodName(methodName), execElement);
            return null;
        }
        var tableName = resolveTableName(execElement, resolved, schemaOpt, interfaceElement);
        if ( tableName == null) {
            error(ProcessorError.cannotInferTable(methodName), execElement);
            return null;
        }
        var params = extractMethodParams(execElement);
        var recordExpansion = resolveCrudInputFields(execElement, params, parsed.operation());
        var inputFieldNames = recordExpansion.fieldNames();
        if ( schemaOpt.isEmpty()) {
        return buildCrudMethodInfoWithoutSchema(execElement,
                                                methodName,
                                                parsed,
                                                tableName,
                                                resolved,
                                                params,
                                                inputFieldNames,
                                                recordExpansion);}
        var schema = schemaOpt.unwrap();
        var tableOpt = schema.table(tableName);
        if ( tableOpt.isEmpty()) {
            error(ProcessorError.tableNotFound(tableName), execElement);
            return null;
        }
        var table = tableOpt.unwrap();
        validateCrudColumns(execElement, parsed, table);
        validateInsertCoverage(execElement, parsed, table, inputFieldNames);
        validateCrudParamTypes(execElement, parsed, table);
        var selectColumns = resolveSelectColumns(execElement, resolved, table);
        var sqlOpt = MethodAnalyzer.generateCrudSql(parsed, table, selectColumns, inputFieldNames, resolved.kind());
        if ( sqlOpt.isEmpty()) {
            error(ProcessorError.noPrimaryKey(tableName), execElement);
            return null;
        }
        var mapperColumns = resolved.needsMapper()
                            ? FactoryGenerator.resolveMapperColumns(table,
                                                                    extractReturnFieldNames(execElement, resolved))
                            : List.<FactoryGenerator.MapperColumn>of();
        if ( recordExpansion.hasExpansion()) {
        return new FactoryGenerator.MethodInfo(methodName,
                                               FactoryGenerator.toSqlConstantName(methodName),
                                               sqlOpt.unwrap(),
                                               resolved.kind(),
                                               resolved.innerTypeName(),
                                               resolved.innerTypeName(),
                                               params,
                                               recordExpansion.bodyParams(),
                                               mapperColumns,
                                               resolved.needsMapper());}
        return FactoryGenerator.MethodInfo.withSharedParams(methodName,
                                               FactoryGenerator.toSqlConstantName(methodName),
                                               sqlOpt.unwrap(),
                                               resolved.kind(),
                                               resolved.innerTypeName(),
                                               resolved.innerTypeName(),
                                               params,
                                               mapperColumns,
                                               resolved.needsMapper());
    }

    private FactoryGenerator.MethodInfo buildCrudMethodInfoWithoutSchema(
    ExecutableElement execElement,
    String methodName,
    MethodNameParser.ParsedMethod parsed,
    String tableName,
    TypeMirrorResolver.ResolvedReturn resolved,
    List<FactoryGenerator.MethodParam> params,
    List<String> inputFieldNames,
    RecordExpansionResult recordExpansion) {
        var selectColumns = List.<String>of();
        var sql = generateCrudSqlWithoutSchema(parsed, tableName, selectColumns, inputFieldNames, resolved.kind());
        var mapperColumns = resolveMapperColumns(execElement, resolved);
        if ( recordExpansion.hasExpansion()) {
        return new FactoryGenerator.MethodInfo(methodName,
                                               FactoryGenerator.toSqlConstantName(methodName),
                                               sql,
                                               resolved.kind(),
                                               resolved.innerTypeName(),
                                               resolved.innerTypeName(),
                                               params,
                                               recordExpansion.bodyParams(),
                                               mapperColumns,
                                               resolved.needsMapper());}
        return FactoryGenerator.MethodInfo.withSharedParams(methodName,
                                               FactoryGenerator.toSqlConstantName(methodName),
                                               sql,
                                               resolved.kind(),
                                               resolved.innerTypeName(),
                                               resolved.innerTypeName(),
                                               params,
                                               mapperColumns,
                                               resolved.needsMapper());
    }

    private String generateCrudSqlWithoutSchema(
    MethodNameParser.ParsedMethod parsed,
    String tableName,
    List<String> selectColumns,
    List<String> inputFieldNames,
    MethodAnalyzer.ReturnKind returnKind) {
        // Without schema, generate basic SQL using the parsed method pattern
        return switch (parsed.operation()) {case FIND, FIND_ALL -> generateBasicFindSql(parsed, tableName, selectColumns);case COUNT -> generateBasicCountSql(parsed,
                                                                                                                                                              tableName);case EXISTS -> generateBasicExistsSql(parsed,
                                                                                                                                                                                                               tableName);case DELETE -> generateBasicDeleteSql(parsed,
                                                                                                                                                                                                                                                                tableName);case INSERT -> generateBasicInsertSql(tableName,
                                                                                                                                                                                                                                                                                                                 inputFieldNames,
                                                                                                                                                                                                                                                                                                                 returnKind);case SAVE -> generateBasicInsertSql(tableName,
                                                                                                                                                                                                                                                                                                                                                                 inputFieldNames,
                                                                                                                                                                                                                                                                                                                                                                 returnKind);};
    }

    private String resolveTableName(
    ExecutableElement execElement,
    TypeMirrorResolver.ResolvedReturn resolved,
    Option<Schema> schemaOpt,
    TypeElement interfaceElement) {
        // Try @Table annotation first
        var tableAnnotation = execElement.getAnnotation(Table.class);
        if ( tableAnnotation != null) {
        return resolveTableFromAnnotation(execElement, schemaOpt);}
        // Try inferring from the return type
        if ( resolved.needsMapper() && schemaOpt.isPresent()) {
            var inferred = MethodAnalyzer.resolveTableFromTypeName(resolved.simpleTypeName(), schemaOpt.unwrap());
            if ( inferred.isPresent()) {
            return inferred.unwrap();}
        }
        // Fallback: try deriving from inner type name with snake_case conversion
        if ( resolved.needsMapper()) {
        return deriveTableNameFromTypeName(resolved.simpleTypeName());}
        return null;
    }

    private String resolveTableFromAnnotation(ExecutableElement execElement, Option<Schema> schemaOpt) {
        // @Table(value) references a class; at compile time we catch MirroredTypeException
        // to get the TypeMirror
        try {
            var tableAnnotation = execElement.getAnnotation(Table.class);
            if ( tableAnnotation != null) {
            tableAnnotation.value();}
        } catch (MirroredTypeException mte) {
            var typeMirror = mte.getTypeMirror();
            if ( typeMirror instanceof DeclaredType dt) {
                var typeElement = (TypeElement) dt.asElement();
                var typeName = typeElement.getSimpleName().toString();
                if ( schemaOpt.isPresent()) {
                    var resolved = MethodAnalyzer.resolveTableFromTypeName(typeName, schemaOpt.unwrap());
                    if ( resolved.isPresent()) {
                    return resolved.unwrap();}
                }
                return deriveTableNameFromTypeName(typeName);
            }
        }
        return null;
    }

    private static String deriveTableNameFromTypeName(String typeName) {
        var stripped = typeName.endsWith("Row")
                       ? typeName.substring(0, typeName.length() - 3)
                       : typeName;
        return NamingConvention.toSnakeCase(Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1));
    }

    private List<String> resolveSelectColumns(
    ExecutableElement execElement,
    TypeMirrorResolver.ResolvedReturn resolved,
    org.pragmatica.aether.pg.schema.model.Table table) {
        if ( !resolved.needsMapper()) {
        return List.of();}
        var fieldNames = extractReturnFieldNames(execElement, resolved);
        if ( fieldNames.isEmpty()) {
        return List.of();}
        return fieldNames.stream().map(NamingConvention::toSnakeCase)
                                .toList();
    }

    private List<FactoryGenerator.MapperColumn> resolveMapperColumns(
    ExecutableElement execElement,
    TypeMirrorResolver.ResolvedReturn resolved) {
        if ( !resolved.needsMapper()) {
        return List.of();}
        var fields = extractReturnFields(execElement, resolved);
        return fields.stream().map(QueryAnnotationProcessor::toMapperColumn)
                            .toList();
    }

    private List<TypeMirrorResolver.FieldInfo> extractReturnFields(
    ExecutableElement execElement,
    TypeMirrorResolver.ResolvedReturn resolved) {
        var innerElement = TypeMirrorResolver.innerTypeElement(execElement.getReturnType());
        if ( innerElement == null) {
        return List.of();}
        return TypeMirrorResolver.extractFields(innerElement);
    }

    private List<String> extractReturnFieldNames(ExecutableElement execElement,
                                                 TypeMirrorResolver.ResolvedReturn resolved) {
        return extractReturnFields(execElement, resolved).stream()
                                  .map(TypeMirrorResolver.FieldInfo::name)
                                  .toList();
    }

    private static FactoryGenerator.MapperColumn toMapperColumn(TypeMirrorResolver.FieldInfo field) {
        var columnName = NamingConvention.toSnakeCase(field.name());
        var accessor = accessorInfoForJavaType(field.typeName());
        return new FactoryGenerator.MapperColumn(columnName, accessor.method(), field.name(), accessor.typeArg());
    }

    private record AccessorInfo(String method, String typeArg){}

    private static AccessorInfo accessorInfoForJavaType(String javaTypeName) {
        return switch (javaTypeName) {case "long", "java.lang.Long" -> new AccessorInfo("getLong", "");case "int", "java.lang.Integer" -> new AccessorInfo("getInt",
                                                                                                                                                           "");case "double", "java.lang.Double" -> new AccessorInfo("getDouble",
                                                                                                                                                                                                                     "");case "boolean", "java.lang.Boolean" -> new AccessorInfo("getBoolean",
                                                                                                                                                                                                                                                                                 "");case "byte[]" -> new AccessorInfo("getBytes",
                                                                                                                                                                                                                                                                                                                       "");case "java.lang.String", "String" -> new AccessorInfo("getString",
                                                                                                                                                                                                                                                                                                                                                                                 "");case "java.math.BigDecimal" -> new AccessorInfo("getObject",
                                                                                                                                                                                                                                                                                                                                                                                                                                     "java.math.BigDecimal.class");case "java.time.Instant" -> new AccessorInfo("getObject",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                "java.time.Instant.class");case "java.time.LocalDateTime" -> new AccessorInfo("getObject",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              "java.time.LocalDateTime.class");case "java.time.LocalDate" -> new AccessorInfo("getObject",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              "java.time.LocalDate.class");case "java.util.UUID" -> new AccessorInfo("getObject",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     "java.util.UUID.class");default -> new AccessorInfo("getString",
                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         "");};
    }

    private static List<String> extractParamNames(ExecutableElement execElement) {
        return execElement.getParameters().stream()
                                        .map(p -> p.getSimpleName().toString())
                                        .toList();
    }

    private static List<FactoryGenerator.MethodParam> extractMethodParams(ExecutableElement execElement) {
        return execElement.getParameters().stream()
                                        .map(p -> FactoryGenerator.MethodParam.simple(p.getSimpleName().toString(),
                                                                                   p.asType().toString()))
                                        .toList();
    }

    private static List<FactoryGenerator.MethodParam> reorderedParams(
    List<FactoryGenerator.MethodParam> original,
    List<String> paramOrder) {
        if ( paramOrder.isEmpty()) {
        return original;}
        var result = new ArrayList<FactoryGenerator.MethodParam>();
        for ( var name : paramOrder) {
        for ( var param : original) {
        if ( param.name().equals(name)) {
            result.add(param);
            break;
        }}}
        // Add any remaining params not in the order (e.g. unused but present for compile compat)
        for ( var param : original) {
        if ( result.stream().noneMatch(p -> p.name().equals(param.name()))) {
        result.add(param);}}
        return result;
    }

    // --- Record expansion and validation ---
    /// Result of expanding record parameters into individual fields.
    private record RecordExpansionResult(List<String> fieldNames,
                                         List<FactoryGenerator.MethodParam> bodyParams,
                                         boolean hasExpansion) {
        static RecordExpansionResult noExpansion(List<FactoryGenerator.MethodParam> params) {
            return new RecordExpansionResult(params.stream().map(FactoryGenerator.MethodParam::name)
                                                          .toList(),
                                             params,
                                             false);
        }
    }

    /// For CRUD INSERT/SAVE with a single record parameter, extracts record component names
    /// and builds body params with accessor expressions (e.g., `user.id()`).
    private RecordExpansionResult resolveCrudInputFields(
    ExecutableElement execElement,
    List<FactoryGenerator.MethodParam> params,
    MethodNameParser.Operation operation) {
        if ( !isInsertOrSave(operation) || params.size() != 1) {
        return RecordExpansionResult.noExpansion(params);}
        var paramElement = execElement.getParameters().getFirst();
        if ( !isRecordType(paramElement)) {
        return RecordExpansionResult.noExpansion(params);}
        var recordParamName = paramElement.getSimpleName().toString();
        var fields = extractRecordComponentFields(paramElement);
        var fieldNames = fields.stream().map(TypeMirrorResolver.FieldInfo::name)
                                      .toList();
        var bodyParams = fields.stream().map(f -> new FactoryGenerator.MethodParam(f.name(),
                                                                                   f.typeName(),
                                                                                   recordParamName + "." + f.name() + "()"))
                                      .toList();
        return new RecordExpansionResult(fieldNames, bodyParams, true);
    }

    /// Expands record parameters in @Query SQL (INSERT VALUES and UPDATE SET patterns).
    private record QueryExpansionResult(String sql,
                                        List<String> allParamNames,
                                        List<FactoryGenerator.MethodParam> bodyParams,
                                        boolean hasExpansion){}

    private QueryExpansionResult expandRecordParams(
    ExecutableElement execElement,
    String sql,
    List<FactoryGenerator.MethodParam> originalParams) {
        var expandedSql = sql;
        var allParamNames = new ArrayList<String>();
        var bodyParams = new ArrayList<FactoryGenerator.MethodParam>();
        var existingPositions = new LinkedHashMap<String, Integer>();
        var hasExpansion = false;
        for ( int paramIdx = 0; paramIdx < originalParams.size(); paramIdx++) {
            var param = originalParams.get(paramIdx);
            var paramElement = execElement.getParameters().get(paramIdx);
            if ( !isRecordType(paramElement)) {
                allParamNames.add(param.name());
                bodyParams.add(param);
                continue;
            }
            var recordParamName = param.name();
            var fields = extractRecordComponentFields(paramElement);
            var recordFields = QueryRewriter.fieldsToRecordFields(fields.stream().map(TypeMirrorResolver.FieldInfo::name)
                                                                               .toList());
            var expanded = tryExpandRecord(expandedSql, recordParamName, recordFields, existingPositions, fields);
            if ( expanded != null) {
                expandedSql = expanded.sql();
                hasExpansion = true;
                for ( int i = 0; i < expanded.fieldNames().size(); i++) {
                    var fieldName = expanded.fieldNames().get(i);
                    allParamNames.add(fieldName);
                    var fieldType = findFieldType(fields, fieldName);
                    bodyParams.add(new FactoryGenerator.MethodParam(fieldName,
                                                                    fieldType,
                                                                    recordParamName + "." + fieldName + "()"));
                }
            } else








            {
                allParamNames.add(param.name());
                bodyParams.add(param);
            }
        }
        return new QueryExpansionResult(expandedSql, allParamNames, bodyParams, hasExpansion);
    }

    private record ExpandedRecord(String sql, List<String> fieldNames){}

    private static ExpandedRecord tryExpandRecord(
    String sql,
    String recordParamName,
    List<QueryRewriter.RecordField> recordFields,
    LinkedHashMap<String, Integer> existingPositions,
    List<TypeMirrorResolver.FieldInfo> fields) {
        var insertResult = QueryRewriter.expandInsertRecord(sql, recordParamName, recordFields, existingPositions);
        if ( !insertResult.parameterOrder().isEmpty()) {
        return new ExpandedRecord(insertResult.sql(), insertResult.parameterOrder());}
        var updateResult = QueryRewriter.expandUpdateRecord(sql, recordParamName, recordFields, existingPositions);
        if ( !updateResult.parameterOrder().isEmpty()) {
        return new ExpandedRecord(updateResult.sql(), updateResult.parameterOrder());}
        return null;
    }

    private static String findFieldType(List<TypeMirrorResolver.FieldInfo> fields, String fieldName) {
        return fields.stream().filter(f -> f.name().equals(fieldName))
                            .map(TypeMirrorResolver.FieldInfo::typeName)
                            .findFirst()
                            .orElse("Object");
    }

    private static boolean isInsertOrSave(MethodNameParser.Operation operation) {
        return operation == MethodNameParser.Operation.INSERT || operation == MethodNameParser.Operation.SAVE;
    }

    private boolean isRecordType(VariableElement paramElement) {
        var typeMirror = paramElement.asType();
        if ( typeMirror instanceof DeclaredType dt) {
            var element = dt.asElement();
            return element.getKind() == ElementKind.RECORD;
        }
        return false;
    }

    private List<TypeMirrorResolver.FieldInfo> extractRecordComponentFields(VariableElement paramElement) {
        var typeMirror = paramElement.asType();
        if ( typeMirror instanceof DeclaredType dt) {
            var typeElement = (TypeElement) dt.asElement();
            return TypeMirrorResolver.extractFields(typeElement);
        }
        return List.of();
    }

    // --- Compile-time validation (Bug 4) ---
    /// Validates that all named params in the SQL have matching method parameters,
    /// and that all method parameters are used in the query.
    private void validateQueryParams(
    ExecutableElement execElement,
    String methodName,
    String sql,
    List<String> availableParamNames) {
        var sqlParams = QueryRewriter.extractNamedParams(sql);
        var availableSet = new HashSet<>(availableParamNames);
        for ( var sqlParam : sqlParams) {
        if ( !availableSet.contains(sqlParam)) {
        warning(ProcessorError.parameterNotInMethod(sqlParam), execElement);}}
        var sqlParamSet = new HashSet<>(sqlParams);
        for ( var paramName : availableParamNames) {
        if ( !sqlParamSet.contains(paramName)) {
        warning(ProcessorError.unusedMethodParameter(paramName), execElement);}}
    }

    // --- Stage 3: Schema-aware validation ---
    /// Validates parameter types against schema column types for @Query methods.
    /// Extracts column references from WHERE/SET clauses and matches against param types.
    private void validateQueryParamTypes(
    ExecutableElement execElement,
    String sql,
    List<String> paramNames,
    List<FactoryGenerator.MethodParam> bodyParams,
    Schema schema) {
        var tableName = extractTableNameFromSql(sql);
        if ( tableName == null) {
        return;}
        var tableOpt = schema.table(tableName);
        if ( tableOpt.isEmpty()) {
        return;}
        var table = tableOpt.unwrap();
        var columnRefs = extractColumnParamPairs(sql, paramNames);
        for ( var pair : columnRefs) {
            var colOpt = table.column(pair.columnName());
            if ( colOpt.isEmpty()) {
            continue;}
            var javaType = findParamType(bodyParams, pair.paramName());
            if ( javaType == null) {
            continue;}
            validateTypeCompatibility(execElement,
                                      pair.paramName(),
                                      javaType,
                                      pair.columnName(),
                                      table.name(),
                                      colOpt.unwrap().type());
        }
    }

    /// Validates return type field mapping against SELECT output for @Query methods.
    /// Emits warnings since SELECT parsing is heuristic.
    private void validateReturnTypeMapping(
    ExecutableElement execElement,
    TypeMirrorResolver.ResolvedReturn resolved,
    String sql) {
        if ( !resolved.needsMapper()) {
        return;}
        var selectColumns = extractSelectColumns(sql);
        if ( selectColumns.isEmpty()) {
        return;}
        var returnFields = extractReturnFields(execElement, resolved);
        for ( var field : returnFields) {
            var columnName = NamingConvention.toSnakeCase(field.name());
            if ( !selectColumns.contains(columnName)) {
            warning(ProcessorError.returnFieldNotInSelect(field.name(), resolved.innerTypeName()),
                    execElement);}
        }
    }

    /// Validates that columns referenced in CRUD method name conditions and orderBy exist in the table.
    private void validateCrudColumns(
    ExecutableElement execElement,
    MethodNameParser.ParsedMethod parsed,
    org.pragmatica.aether.pg.schema.model.Table table) {
        for ( var condition : parsed.conditions()) {
        if ( table.column(condition.columnName()).isEmpty()) {
        error(ProcessorError.columnNotFound(condition.columnName(), table.name()),
              execElement);}}
        for ( var orderEntry : parsed.orderBy()) {
        if ( table.column(orderEntry.columnName()).isEmpty()) {
        error(ProcessorError.columnNotFound(orderEntry.columnName(), table.name()),
              execElement);}}
    }

    /// Validates NOT NULL column coverage for INSERT/SAVE operations.
    private void validateInsertCoverage(
    ExecutableElement execElement,
    MethodNameParser.ParsedMethod parsed,
    org.pragmatica.aether.pg.schema.model.Table table,
    List<String> inputFieldNames) {
        if ( !isInsertOrSave(parsed.operation())) {
        return;}
        var inputColumnNames = inputFieldNames.stream().map(NamingConvention::toSnakeCase)
                                                     .toList();
        var missing = MethodAnalyzer.findMissingRequiredColumns(table, inputColumnNames);
        var recordTypeName = inferRecordTypeName(execElement);
        for ( var col : missing) {
        error(ProcessorError.notNullColumnMissing(col, table.name(), recordTypeName),
              execElement);}
    }

    /// Validates parameter types for CRUD method conditions against table column types.
    private void validateCrudParamTypes(
    ExecutableElement execElement,
    MethodNameParser.ParsedMethod parsed,
    org.pragmatica.aether.pg.schema.model.Table table) {
        var params = extractMethodParams(execElement);
        var paramIdx = 0;
        for ( var condition : parsed.conditions()) {
            if ( condition.operator().paramCount() == 0) {
            continue;}
            if ( paramIdx >= params.size()) {
            break;}
            var colOpt = table.column(condition.columnName());
            if ( colOpt.isEmpty()) {
                paramIdx += condition.operator().paramCount();
                continue;
            }
            var param = params.get(paramIdx);
            validateTypeCompatibility(execElement,
                                      param.name(),
                                      param.typeName(),
                                      condition.columnName(),
                                      table.name(),
                                      colOpt.unwrap().type());
            paramIdx += condition.operator().paramCount();
        }
    }

    /// Checks if a Java type is compatible with a PostgreSQL column type.
    private void validateTypeCompatibility(
    ExecutableElement execElement,
    String paramName,
    String javaType,
    String columnName,
    String tableName,
    PgType pgType) {
        var mappedOpt = TypeMapper.map(pgType);
        if ( mappedOpt.isEmpty()) {
        return;}
        var mapped = mappedOpt.unwrap();
        if ( isTypeCompatible(javaType, mapped, pgType)) {
        return;}
        error(ProcessorError.typeMismatch(paramName, javaType, columnName, tableName, pgType.name()),
              execElement);
    }

    /// Determines if a Java type is compatible with the mapped PostgreSQL type,
    /// including the safe coercions defined in the spec.
    private static boolean isTypeCompatible(String javaType, TypeMapper.JavaTypeInfo mapped, PgType pgType) {
        var normalized = normalizeJavaType(javaType);
        if ( normalized.equals(mapped.typeName()) || normalized.equals(mapped.boxedTypeName())) {
        return true;}
        var pgName = canonicalPgTypeName(pgType);
        return isSafeCoercion(normalized, pgName);
    }

    private static boolean isSafeCoercion(String javaType, String pgType) {
        return switch (pgType) {case "bigint" -> javaType.equals("long") || javaType.equals("Long") || javaType.equals("java.lang.Long") || javaType.equals("java.math.BigDecimal");case "integer" -> javaType.equals("int") || javaType.equals("Integer") || javaType.equals("java.lang.Integer") || javaType.equals("long") || javaType.equals("Long") || javaType.equals("java.lang.Long");case "numeric" -> javaType.equals("java.math.BigDecimal") || javaType.equals("double") || javaType.equals("Double") || javaType.equals("java.lang.Double");case "text", "varchar", "char", "citext", "name" -> javaType.equals("String") || javaType.equals("java.lang.String");case "boolean" -> javaType.equals("boolean") || javaType.equals("Boolean") || javaType.equals("java.lang.Boolean");case "timestamptz", "timestamp with time zone" -> javaType.equals("java.time.Instant");default -> false;};
    }

    private static String normalizeJavaType(String javaType) {
        return switch (javaType) {case "java.lang.String" -> "String"; case "java.lang.Long" -> "Long"; case "java.lang.Integer" -> "Integer"; case "java.lang.Boolean" -> "Boolean"; case "java.lang.Double" -> "Double"; default -> javaType;};
    }

    private static String canonicalPgTypeName(PgType pgType) {
        if ( pgType instanceof PgType.BuiltinType bt) {
        return BuiltinTypes.canonicalize(bt.name());}
        return pgType.name();
    }

    // --- SQL parsing helpers for validation ---
    private static final Pattern TABLE_FROM_PATTERN =
    Pattern.compile("\\bFROM\\s+([a-zA-Z_][a-zA-Z0-9_.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_INTO_PATTERN =
    Pattern.compile("\\bINTO\\s+([a-zA-Z_][a-zA-Z0-9_.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_UPDATE_PATTERN =
    Pattern.compile("\\bUPDATE\\s+([a-zA-Z_][a-zA-Z0-9_.]*)", Pattern.CASE_INSENSITIVE);

    /// Extracts the main table name from SQL (heuristic).
    private static String extractTableNameFromSql(String sql) {
        var fromMatcher = TABLE_FROM_PATTERN.matcher(sql);
        if ( fromMatcher.find()) {
        return fromMatcher.group(1);}
        var intoMatcher = TABLE_INTO_PATTERN.matcher(sql);
        if ( intoMatcher.find()) {
        return intoMatcher.group(1);}
        var updateMatcher = TABLE_UPDATE_PATTERN.matcher(sql);
        if ( updateMatcher.find()) {
        return updateMatcher.group(1);}
        return null;
    }

    private static final Pattern WHERE_COLUMN_PATTERN =
    Pattern.compile("(?:WHERE|AND|OR|SET)\\s+(?:[a-zA-Z_][a-zA-Z0-9_.]*\\.)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*[=<>!]+\\s*:([a-zA-Z][a-zA-Z0-9]*)",
                    Pattern.CASE_INSENSITIVE);

    private record ColumnParamPair(String columnName, String paramName){}

    /// Extracts column-name to param-name pairs from WHERE/SET clauses.
    private static List<ColumnParamPair> extractColumnParamPairs(String sql, List<String> paramNames) {
        var pairs = new ArrayList<ColumnParamPair>();
        var matcher = WHERE_COLUMN_PATTERN.matcher(sql);
        while ( matcher.find()) {
            var columnName = matcher.group(1);
            var paramName = matcher.group(2);
            if ( paramNames.contains(paramName)) {
            pairs.add(new ColumnParamPair(columnName, paramName));}
        }
        return pairs;
    }

    private static final Pattern SELECT_COLUMN_PATTERN =
    Pattern.compile("^\\s*SELECT\\s+(.+?)\\s+FROM\\s", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /// Extracts column names from a SELECT clause (heuristic).
    /// Handles aliases (AS name), qualified names (t.col), and expressions.
    private static Set<String> extractSelectColumns(String sql) {
        var matcher = SELECT_COLUMN_PATTERN.matcher(sql);
        if ( !matcher.find()) {
        return Set.of();}
        var selectPart = matcher.group(1).trim();
        if ( selectPart.equals("*")) {
        return Set.of();}
        var columns = new LinkedHashSet<String>();
        var parts = splitSelectColumns(selectPart);
        for ( var part : parts) {
            var colName = extractColumnAlias(part.trim());
            if ( colName != null) {
            columns.add(colName);}
        }
        return columns;
    }

    /// Splits SELECT column list on commas, respecting parentheses.
    private static List<String> splitSelectColumns(String selectPart) {
        var parts = new ArrayList<String>();
        var depth = 0;
        var start = 0;
        for ( int i = 0; i < selectPart.length(); i++) {
            var ch = selectPart.charAt(i);
            if ( ch == '(') { depth++;} else
            if ( ch == ')') { depth--;} else if ( ch == ',' && depth == 0) {
                parts.add(selectPart.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(selectPart.substring(start));
        return parts;
    }

    /// Extracts the effective column name from a SELECT expression.
    /// Handles: `col`, `t.col`, `expr AS alias`, `count(x) AS alias`.
    private static String extractColumnAlias(String expr) {
        var asPattern = Pattern.compile("(?i)\\bAS\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*$");
        var asMatcher = asPattern.matcher(expr);
        if ( asMatcher.find()) {
        return asMatcher.group(1);}
        var simplePattern = Pattern.compile("^(?:[a-zA-Z_][a-zA-Z0-9_.]*\\.)?([a-zA-Z_][a-zA-Z0-9_]*)$");
        var simpleMatcher = simplePattern.matcher(expr.trim());
        if ( simpleMatcher.matches()) {
        return simpleMatcher.group(1);}
        return null;
    }

    private static String findParamType(List<FactoryGenerator.MethodParam> params, String paramName) {
        for ( var param : params) {
        if ( param.name().equals(paramName)) {
        return param.typeName();}}
        return null;
    }

    private static String inferRecordTypeName(ExecutableElement execElement) {
        if ( execElement.getParameters().size() == 1) {
            var paramType = execElement.getParameters().getFirst()
                                                     .asType();
            if ( paramType instanceof DeclaredType dt) {
            return ((TypeElement) dt.asElement()).getSimpleName()
                                                 .toString();}
        }
        return "input record";
    }

    private Set<String> collectImports(TypeElement interfaceElement, List<FactoryGenerator.MethodInfo> methods) {
        var imports = new LinkedHashSet<String>();
        for ( var enclosed : interfaceElement.getEnclosedElements()) {
        if ( enclosed.getKind() == ElementKind.RECORD) {}}
        return imports;
    }

    // --- SQL generation without schema (fallback) ---
    private static String generateBasicFindSql(
    MethodNameParser.ParsedMethod parsed,
    String tableName,
    List<String> selectColumns) {
        var select = selectColumns.isEmpty()
                     ? "*"
                     : String.join(", ", selectColumns);
        var sb = new StringBuilder("SELECT ").append(select)
                                             .append(" FROM ")
                                             .append(tableName);
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

    private static String generateBasicInsertSql(String tableName,
                                                 List<String> fieldNames,
                                                 MethodAnalyzer.ReturnKind returnKind) {
        var columns = fieldNames.stream().map(NamingConvention::toSnakeCase)
                                       .toList();
        var sb = new StringBuilder("INSERT INTO ").append(tableName);
        sb.append(" (").append(String.join(", ", columns))
                 .append(")");
        sb.append(" VALUES (");
        for ( int i = 0; i < columns.size(); i++) {
            if ( i > 0) {
            sb.append(", ");}
            sb.append('$').append(i + 1);
        }
        sb.append(')');
        if ( returnKind != MethodAnalyzer.ReturnKind.UNIT) {
        sb.append(" RETURNING *");}
        return sb.toString();
    }

    private static void appendWhereClause(StringBuilder sb, List<MethodNameParser.ColumnCondition> conditions) {
        if ( conditions.isEmpty()) {
        return;}
        sb.append(" WHERE ");
        var paramIdx = 1;
        for ( int i = 0; i < conditions.size(); i++) {
            if ( i > 0) {
            sb.append(" AND ");}
            var cond = conditions.get(i);
            sb.append(cond.columnName());
            switch ( cond.operator()) {
                case IS_NULL, IS_NOT_NULL -> sb.append(' ').append(cond.operator().sql());
                case BETWEEN -> {
                    sb.append(" BETWEEN $").append(paramIdx)
                             .append(" AND $")
                             .append(paramIdx + 1);
                    paramIdx += 2;
                }
                case IN -> {
                    sb.append(" IN ($").append(paramIdx)
                             .append(')');
                    paramIdx++;
                }
                default -> {
                    sb.append(' ').append(cond.operator().sql())
                             .append(" $")
                             .append(paramIdx);
                    paramIdx++;
                }
            }
        }
    }

    private static void appendOrderBy(StringBuilder sb, List<MethodNameParser.OrderByEntry> orderBy) {
        if ( orderBy.isEmpty()) {
        return;}
        sb.append(" ORDER BY ");
        for ( int i = 0; i < orderBy.size(); i++) {
            if ( i > 0) {
            sb.append(", ");}
            var entry = orderBy.get(i);
            sb.append(entry.columnName());
            if ( entry.direction() == MethodNameParser.SortDirection.DESC) {
            sb.append(" DESC");} else
            {
            sb.append(" ASC");}
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
        return pkg.isUnnamed()
               ? ""
               : pkg.getQualifiedName().toString();
    }

    private String extractConfigPath(TypeElement interfaceElement) {
        for ( var mirror : interfaceElement.getAnnotationMirrors()) {
            var annotationType = mirror.getAnnotationType().asElement();
            for ( var annotationOfAnnotation : annotationType.getAnnotationMirrors()) {
                var metaName = annotationOfAnnotation.getAnnotationType().toString();
                if ( metaName.endsWith("ResourceQualifier")) {
                for ( var entry : annotationOfAnnotation.getElementValues().entrySet()) {
                if ( entry.getKey().getSimpleName()
                                 .toString()
                                 .equals("config")) {
                return entry.getValue().getValue()
                                     .toString();}}}
            }
        }
        return "database";
    }

    private void note(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE, message, element);
    }

    private void warning(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, message, element);
    }

    private void error(String message, Element element) {
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
