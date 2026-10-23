// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.aether.pg.codegen.NamingConvention;
import org.pragmatica.aether.pg.codegen.TypeMapper;
import org.pragmatica.aether.pg.codegen.annotation.Jsonb;
import org.pragmatica.aether.pg.codegen.annotation.Query;
import org.pragmatica.aether.pg.codegen.annotation.Table;
import org.pragmatica.aether.pg.parser.PostgresParser;
import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.transform.CstNavigator;
import org.pragmatica.aether.pg.schema.model.BuiltinTypes;
import org.pragmatica.aether.pg.schema.model.PgType;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.aether.pg.schema.validator.QueryValidator;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
/// Activation is TYPE-driven, not method-driven: the processor claims all annotations
/// (`@SupportedAnnotationTypes("*")`) and, per root interface, {@link #shouldProcess} triggers on the
/// interface-level `@PgSql` qualifier (or any annotation meta-annotated with
/// `@ResourceQualifier(type = PgSqlConnector.class)`) — independent of whether any method carries `@Query`.
@Contract
@SupportedAnnotationTypes("*")
@SupportedOptions({"pg.lint.severity", "pg.lint.disabled"})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class QueryAnnotationProcessor extends AbstractProcessor {
    /// Fully-qualified name of the built-in `@PgSql` annotation.
    private static final String PG_SQL_ANNOTATION = "org.pragmatica.aether.resource.db.PgSql";

    /// Fully-qualified name of the `@ResourceQualifier` meta-annotation.
    private static final String RESOURCE_QUALIFIER_ANNOTATION =
        "org.pragmatica.aether.slice.annotation.ResourceQualifier";

    /// Canonical name (source form) of `PgSqlConnector` — used to recognize qualifiers tied to
    /// PostgreSQL data sources. Kept as a string so that the processor does not require
    /// the resource-api classes to be on its own classpath at annotation-processor discovery
    /// time.
    private static final String PG_SQL_CONNECTOR_TYPE =
        "org.pragmatica.aether.resource.db.PgSqlConnector";
    private SchemaLoader schemaLoader;
    private LintRunner.LintOptions lintOptions;
    private final Set<String> migrationsLinted = new java.util.HashSet<>();
    private final PostgresParser sqlParser = PostgresParser.create();
    private final Map<String, Result<CstNode>> sqlParseCache = new HashMap<>();
    /// The `@Jsonb` codecs of the interface being processed.
    private JsonbCodecs jsonbCodecs;
    /// Record components mapped as JSON documents (marked or inferred) in the interface being processed.
    private final Set<Element> documentElements = new HashSet<>();
    /// A failure already reported to the messager.
    private static final org.pragmatica.lang.Cause REPORTED = org.pragmatica.lang.utils.Causes.cause("reported");
    private static final String OPTION_TYPE = "org.pragmatica.lang.Option";

    @Override public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.schemaLoader = new SchemaLoader(processingEnv);
        this.lintOptions = LintRunner.OptionsReader.from(processingEnv.getOptions());
    }

    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if ( roundEnv.processingOver()) {
        return false;}
        var processed = new java.util.HashSet<String>();
        for ( var element : roundEnv.getRootElements()) {
            if ( element.getKind() != ElementKind.INTERFACE) {
                continue;
            }
            var interfaceElement = (TypeElement) element;
            if ( !shouldProcess(interfaceElement)) {
                continue;
            }
            var fqn = interfaceElement.getQualifiedName().toString();
            if ( processed.add(fqn)) {
                processInterface(interfaceElement);
            }
        }
        return false;
    }

    /// Returns true if the given interface should be picked up by the processor.
    ///
    /// An interface is eligible when it carries:
    ///   - `@PgSql` directly, OR
    ///   - a user-defined annotation whose own type is meta-annotated with
    ///     `@ResourceQualifier(type = PgSqlConnector.class, ...)` (e.g. `@AnalyticsPgSql`).
    private boolean shouldProcess(TypeElement interfaceElement) {
        for ( var annotationMirror : interfaceElement.getAnnotationMirrors()) {
            var annotationTypeName = annotationMirror.getAnnotationType()
                                                     .asElement()
                                                     .toString();
            if ( PG_SQL_ANNOTATION.equals(annotationTypeName)) {
                return true;
            }
            if ( hasPgSqlResourceQualifier(annotationMirror)) {
                return true;
            }
        }
        return false;
    }

    /// Checks whether the given annotation carries a `@ResourceQualifier` meta-annotation whose
    /// `type()` attribute points at `PgSqlConnector`.
    private boolean hasPgSqlResourceQualifier(javax.lang.model.element.AnnotationMirror annotationMirror) {
        var annotationType = annotationMirror.getAnnotationType()
                                             .asElement();
        for ( var meta : annotationType.getAnnotationMirrors()) {
            var metaTypeName = meta.getAnnotationType()
                                   .asElement()
                                   .toString();
            if ( !RESOURCE_QUALIFIER_ANNOTATION.equals(metaTypeName)) {
                continue;
            }
            var elementValues = processingEnv.getElementUtils()
                                             .getElementValuesWithDefaults(meta);
            for ( var entry : elementValues.entrySet()) {
                if ( !"type".equals(entry.getKey().getSimpleName().toString())) {
                    continue;
                }
                var value = entry.getValue().getValue();
                if ( value instanceof javax.lang.model.type.TypeMirror tm
                     && PG_SQL_CONNECTOR_TYPE.equals(tm.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void processInterface(TypeElement interfaceElement) {
        var packageName = extractPackageName(interfaceElement);
        var interfaceName = interfaceElement.getSimpleName().toString();
        var configPath = extractConfigPath(interfaceElement);
        var schemaOpt = schemaLoader.loadSchema(configPath);
        if ( schemaOpt.isEmpty()) {
        note("No schema available for validation; generating factory without validation", interfaceElement);}
        runMigrationLintOnce(configPath);
        note("Generating " + interfaceName + "Factory", interfaceElement);
        jsonbCodecs = new JsonbCodecs(processingEnv, packageName, interfaceName);
        documentElements.clear();
        var methods = analyzeMethods(interfaceElement, schemaOpt);
        jsonbCodecs.write(interfaceElement)
                   .onFailure(cause -> error(cause.message(), interfaceElement));
        var additionalImports = collectImports(interfaceElement, methods);
        var source = FactoryGenerator.generate(packageName,
                                               interfaceName,
                                               methods,
                                               additionalImports,
                                               jsonbCodecs.fields(),
                                               jsonbCodecs.holderName());
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
        if (isMarked(execElement)) {
            error(ProcessorError.jsonbMethodNeedsQuery(methodName), execElement);
            return null;
        }
        return analyzeCrudMethod(execElement, methodName, resolved, schemaOpt, interfaceElement);
    }

    private FactoryGenerator.MethodInfo analyzeQueryMethod(
    ExecutableElement execElement,
    String methodName,
    TypeMirrorResolver.ResolvedReturn declared,
    Query queryAnnotation,
    Option<Schema> schemaOpt) {
        var document = documentReturn(execElement, methodName, declared);
        if (document.isFailure()) {
            return null;
        }
        var documentRepr = document.expect("checked with isFailure above");
        // A document method reads one column, so it has no row record to map or check against the SELECT list.
        var resolved = documentRepr.isPresent()
                       ? new TypeMirrorResolver.ResolvedReturn(declared.kind(),
                                                               declared.innerTypeName(),
                                                               declared.simpleTypeName(),
                                                               false,
                                                               Option.empty(),
                                                               declared.rawInnerTypeName())
                       : declared;
        if (!validateMapperShape(execElement, methodName, resolved)) {
            return null;
        }
        var sql = queryAnnotation.value();
        var originalParams = extractMethodParams(execElement);
        // Validate parameter usage BEFORE record expansion so that the pre-expansion SQL's :record
        // references match the Java method param names; after expansion `:request` is replaced with
        // $N positional placeholders and virtual field names would otherwise be flagged as unused.
        var originalParamNames = originalParams.stream().map(FactoryGenerator.MethodParam::name).toList();
        if (rejectsDataModifyingCte(execElement, methodName, sql, originalParamNames)) {
            return null;
        }
        if (rejectsParamMismatch(execElement, sql, originalParamNames)) {
            return null;
        }
        var expansion = expandRecordParams(execElement, sql, originalParams);
        var jsonTargets = schemaOpt.map(schema -> queryJsonBindTargets(expansion, schema))
                                   .or(Set.of());
        var boundBody = boundBodyParams(execElement, expansion.bodyParams(), jsonTargets);
        schemaOpt.onPresent(schema -> validateQueryParamTypes(execElement,
                                                              expansion.sql(),
                                                              expansion.allParamNames(),
                                                              boundBody,
                                                              schema));
        schemaOpt.onPresent(schema -> validateExpandedJsonBinds(execElement, expansion, boundBody, schema));
        var rewritten = QueryRewriter.rewriteNamedParams(expansion.sql(), expansion.allParamNames());
        var parsedCst = sqlParseCache.computeIfAbsent(rewritten.sql(), sqlParser::parseCst);
        schemaOpt.onPresent(schema -> validateReturnTypeMapping(execElement, resolved, parsedCst));
        validateRewrittenSql(execElement, methodName, rewritten.sql(), schemaOpt);
        var mapperColumns = boundMapperColumns(execElement, resolved, resolveMapperColumns(execElement, resolved), Set.of());
        var documentColumn = documentRepr.map(repr -> documentColumn(repr, rewritten.sql(), resolved.kind()));
        var bodyParams = reorderedParams(boundBody, rewritten.parameterOrder());
        // #648: the generated method OVERRIDES the interface method, so its signature is the
        // declaration order, always. Only the bind list follows placeholder order — it is emitted by
        // parameter NAME, so declaration order and placeholder order are independent. Reordering
        // the signature too was "does not override" for a differently typed pair and, for a
        // same-typed pair, compiled and bound the caller's arguments to the wrong placeholders.
        return new FactoryGenerator.MethodInfo(methodName,
                                               FactoryGenerator.toSqlConstantName(methodName),
                                               rewritten.sql(),
                                               resolved.kind(),
                                               resolved.innerTypeName(),
                                               resolved.innerTypeName(),
                                               originalParams,
                                               bodyParams,
                                               mapperColumns,
                                               resolved.needsMapper(),
                                               resolved.scalarAccessor(),
                                               documentColumn);
    }

    /// The document handling of a `@Jsonb` method's result: empty for an unmarked method, a failure (already
    /// reported) when the return type cannot be a document.
    private Result<Option<ColumnRepr>> documentReturn(ExecutableElement execElement,
                                                      String methodName,
                                                      TypeMirrorResolver.ResolvedReturn resolved) {
        if (!isMarked(execElement)) {
            return Result.success(Option.empty());
        }
        var kind = resolved.kind();
        if (kind != MethodAnalyzer.ReturnKind.SINGLE && kind != MethodAnalyzer.ReturnKind.OPTIONAL
            && kind != MethodAnalyzer.ReturnKind.LIST) {
            error(ProcessorError.jsonbMethodReturn(methodName), execElement);
            return REPORTED.result();
        }
        var inner = documentType(execElement.getReturnType(), kind);
        return jsonbCodecs.field(inner)
                          .map(field -> Option.<ColumnRepr>some(new ColumnRepr.JsonDocument(field)))
                          .onFailure(cause -> error(ProcessorError.jsonbUnsupportedType(methodName,
                                                                                        inner.toString(),
                                                                                        cause.message()),
                                                    execElement))
                          .mapError(_ -> REPORTED);
    }

    // `T` of `Promise<T>`, `Promise<Option<T>>` or `Promise<List<T>>`.
    private static TypeMirror documentType(TypeMirror returnType, MethodAnalyzer.ReturnKind kind) {
        var inner = ((DeclaredType) returnType).getTypeArguments().getFirst();
        return kind == MethodAnalyzer.ReturnKind.SINGLE
               ? inner
               : ((DeclaredType) inner).getTypeArguments().getFirst();
    }

    private static FactoryGenerator.MapperColumn documentColumn(ColumnRepr repr,
                                                                String sql,
                                                                MethodAnalyzer.ReturnKind kind) {
        var columnName = FactoryGenerator.inferScalarColumnName(sql, kind);
        var accessor = repr.pAccessor();
        return new FactoryGenerator.MapperColumn(columnName,
                                                 accessor.method(),
                                                 columnName,
                                                 accessor.typeArg(),
                                                 repr.liftExpr());
    }

    /// A positional `$n` placeholder (#1139).
    private static final Pattern POSITIONAL_PARAM_PATTERN = Pattern.compile("\\$(\\d+)");
    private static final Pattern CTE_HINT_PATTERN = Pattern.compile("(?i)\\bWITH\\b");

    /// Rejects data-modifying CTEs — a `WITH` clause whose body is `INSERT`/`UPDATE`/`DELETE`
    /// (e.g. `WITH x AS (INSERT ... RETURNING ...) SELECT ...`) — with a clear, located error
    /// instead of silently mis-validating the outer statement. Cheaply gated on the presence of a
    /// `WITH` keyword, then confirmed against the parsed CST so occurrences of INSERT/UPDATE/DELETE
    /// inside string literals or comments never trigger a false positive. Schema-independent; genuine
    /// parse failures are left to `validateRewrittenSql` to report. Returns true when rejected.
    private boolean rejectsDataModifyingCte(ExecutableElement execElement,
                                            String methodName,
                                            String sql,
                                            List<String> paramNames) {
        if (!CTE_HINT_PATTERN.matcher(sql).find()) {
            return false;
        }
        var rewritten = QueryRewriter.rewriteNamedParams(sql, paramNames).sql();
        var parsed = sqlParseCache.computeIfAbsent(rewritten, sqlParser::parseCst);
        if (parsed.isFailure()) {
            return false;
        }
        if (QueryValidator.hasDataModifyingCte(parsed.expect("checked with isFailure above"))) {
            error(ProcessorError.dataModifyingCteNotSupported(methodName), execElement);
            return true;
        }
        return false;
    }

    /// Verifies that the return type is usable: either a record, a known scalar, or a scalar ReturnKind
    /// (LONG/BOOLEAN/UNIT). Emits a descriptive compile error for unknown non-record inner types (e.g.
    /// user classes that aren't records and aren't in `JavaTypeAccessor.SCALARS`).
    private boolean validateMapperShape(ExecutableElement execElement,
                                        String methodName,
                                        TypeMirrorResolver.ResolvedReturn resolved) {
        if (!resolved.needsMapper()) {
            return true;
        }
        var returnType = execElement.getReturnType();
        var innerElement = TypeMirrorResolver.innerTypeElement(returnType);
        if (innerElement != null && innerElement.getKind() == ElementKind.RECORD) {
            return true;
        }
        // No inner element (e.g. byte[]) or not a record: only allow if it's a scalar (handled elsewhere).
        // Already handled if scalarAccessor present.
        if (resolved.scalarAccessor().isPresent()) {
            return true;
        }
        error(ProcessorError.unsupportedScalarReturn(methodName, resolved.innerTypeName()), execElement);
        return false;
    }

    private FactoryGenerator.MethodInfo analyzeCrudMethod(
    ExecutableElement execElement,
    String methodName,
    TypeMirrorResolver.ResolvedReturn resolved,
    Option<Schema> schemaOpt,
    TypeElement interfaceElement) {
        if (!validateMapperShape(execElement, methodName, resolved)) {
            return null;
        }
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
        var schema = schemaOpt.expect("checked with isEmpty above");
        var tableOpt = schema.table(tableName);
        if ( tableOpt.isEmpty()) {
            error(ProcessorError.tableNotFound(tableName), execElement);
            return null;
        }
        var table = tableOpt.expect("checked with isEmpty above");
        validateCrudColumns(execElement, parsed, table);
        validateInsertCoverage(execElement, parsed, table, inputFieldNames);
        var rawBody = recordExpansion.hasExpansion()
                      ? recordExpansion.bodyParams()
                      : params;
        var boundBody = boundBodyParams(execElement,
                                        rawBody,
                                        crudJsonBindTargets(parsed, table, rawBody));
        validateCrudParamTypes(execElement, parsed, table, boundBody);
        if (recordExpansion.hasExpansion()) {
            validateJsonbComponents(execElement, table, recordParamElement(execElement));
        }
        if (isInsertOrSave(parsed.operation())) {
            validateInsertJsonBinds(execElement, table, boundBody);
        }
        if (resolved.needsMapper()) {
            validateJsonbComponents(execElement, table, TypeMirrorResolver.innerTypeElement(execElement.getReturnType()));
        }
        var selectColumns = resolveSelectColumns(execElement, resolved, table);
        var sqlOpt = MethodAnalyzer.generateCrudSql(parsed, table, selectColumns, inputFieldNames, resolved.kind());
        if ( sqlOpt.isEmpty()) {
            error(ProcessorError.noPrimaryKey(tableName), execElement);
            return null;
        }
        var crudSql = sqlOpt.expect("checked with isEmpty above");
        validateRewrittenSql(execElement, methodName, crudSql, schemaOpt);
        var mapperColumns = boundMapperColumns(execElement,
                                               resolved,
                                               resolved.needsMapper()
                                               ? FactoryGenerator.resolveMapperColumns(table,
                                                                                       extractReturnFieldNames(execElement, resolved))
                                               : List.<FactoryGenerator.MapperColumn>of(),
                                               jsonColumnNames(extractReturnFieldNames(execElement, resolved), table));
        return new FactoryGenerator.MethodInfo(methodName,
                                               FactoryGenerator.toSqlConstantName(methodName),
                                               crudSql,
                                               resolved.kind(),
                                               resolved.innerTypeName(),
                                               resolved.innerTypeName(),
                                               params,
                                               boundBody,
                                               mapperColumns,
                                               resolved.needsMapper(),
                                               resolved.scalarAccessor(),
                                               Option.empty());
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
        var mapperColumns = boundMapperColumns(execElement, resolved, resolveMapperColumns(execElement, resolved), Set.of());
        var boundBody = boundBodyParams(execElement,
                                        recordExpansion.hasExpansion()
                                        ? recordExpansion.bodyParams()
                                        : params,
                                        Set.of());
        return new FactoryGenerator.MethodInfo(methodName,
                                               FactoryGenerator.toSqlConstantName(methodName),
                                               sql,
                                               resolved.kind(),
                                               resolved.innerTypeName(),
                                               resolved.innerTypeName(),
                                               params,
                                               boundBody,
                                               mapperColumns,
                                               resolved.needsMapper(),
                                               resolved.scalarAccessor(),
                                               Option.empty());
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
            var inferred = MethodAnalyzer.resolveTableFromTypeName(resolved.simpleTypeName(),
                                                                   schemaOpt.expect("checked with isPresent above"));
            if ( inferred.isPresent()) {
            return inferred.expect("checked with isPresent above");}
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
                    var resolved = MethodAnalyzer.resolveTableFromTypeName(typeName,
                                                                           schemaOpt.expect("checked with isPresent above"));
                    if ( resolved.isPresent()) {
                    return resolved.expect("checked with isPresent above");}
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
        var accessor = JavaTypeAccessor.forField(field.typeName());
        return FactoryGenerator.MapperColumn.plain(columnName, accessor.method(), field.name(), accessor.typeArg());
    }

    // --- Column representations: value objects, @Jsonb documents, Option<T> ---

    /// Whether the param is bound as one column value rather than expanded into its record components.
    private boolean isBoundWhole(VariableElement paramElement) {
        return isMarked(paramElement) || ValueMappingResolver.resolve(paramElement.asType()).isPresent();
    }

    private static boolean isMarked(Element element) {
        return element.getAnnotation(Jsonb.class) != null;
    }

    private static boolean isScalarType(String typeName) {
        return JavaTypeAccessor.SCALARS.containsKey(typeName) || typeName.endsWith("[]");
    }

    /// How `element` (a param or record component) crosses the column boundary when its type is not a plain column
    /// type: a `@Jsonb` document, a value object's `ValueMapping`, a document inferred from a json/jsonb column the
    /// schema knows (`onJsonColumn`), or an `Option` of a column type or value object. Empty when it is none of these.
    private Option<ColumnRepr> reprFor(Element element, ExecutableElement site, boolean onJsonColumn) {
        var type = element.asType();
        if (isMarked(element)) {
            return Option.some(documentRepr(element, type, site, false));
        }
        var binding = ValueMappingResolver.resolve(type);
        if (binding.isPresent()) {
            return binding.map(ColumnRepr.class::cast);
        }
        if (onJsonColumn && isInferredDocument(type)) {
            return Option.some(documentRepr(element, type, site, true));
        }
        return optionElement(type).flatMap(QueryAnnotationProcessor::nullableRepr);
    }

    /// A record (or `Option` of one) without a `ValueMapping` is a document when its column is json/jsonb. An enum is
    /// not inferred: it may as well be text, so a JSON enum document stays marked.
    private static boolean isInferredDocument(TypeMirror type) {
        var target = optionElement(type).or(type);
        return target instanceof DeclaredType declared && declared.asElement()
                                                                  .getKind() == ElementKind.RECORD && ValueMappingResolver.resolve(target)
                                                                                                                           .isEmpty();
    }

    // An unsupported document type is reported here; the placeholder keeps the generated source well-formed while the
    // reported error fails the compilation.
    private ColumnRepr documentRepr(Element element, TypeMirror type, ExecutableElement site, boolean inferred) {
        var optionElement = optionElement(type);
        var documentType = optionElement.or(type);
        var target = element.getSimpleName().toString();
        if (element.getKind() == ElementKind.RECORD_COMPONENT) {
            documentElements.add(element);
        }
        var document = jsonbCodecs.field(documentType)
                                  .onFailure(cause -> error(inferred
                                                            ? ProcessorError.jsonColumnUnsupportedType(target,
                                                                                                       documentType.toString(),
                                                                                                       cause.message())
                                                            : ProcessorError.jsonbUnsupportedType(target,
                                                                                                  documentType.toString(),
                                                                                                  cause.message()),
                                                            site))
                                  .fold(_ -> new ColumnRepr.JsonDocument("null"), ColumnRepr.JsonDocument::new);
        return optionElement.isPresent()
               ? new ColumnRepr.Nullable(documentType.toString(), Option.some(document))
               : document;
    }

    private static Option<ColumnRepr> nullableRepr(TypeMirror element) {
        var elementName = element.toString();
        if (isScalarType(elementName)) {
            return Option.some(new ColumnRepr.Nullable(elementName, Option.empty()));
        }
        return ValueMappingResolver.resolve(element)
                                   .map(binding -> new ColumnRepr.Nullable(elementName, Option.some(binding)));
    }

    // `T` of an `Option<T>` type.
    private static Option<TypeMirror> optionElement(TypeMirror type) {
        if (type instanceof DeclaredType declared && declared.getTypeArguments().size() == 1
            && ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(OPTION_TYPE)) {
            return Option.some(declared.getTypeArguments().getFirst());
        }
        return Option.empty();
    }

    /// Validates and lowers bind targets: emits a compile error for any target that is neither a supported column
    /// type nor one with a column representation (value object, `@Jsonb` document, `Option`), then rewrites each
    /// represented target to lower it to its column value (exposing the column-side type for schema validation and
    /// the lowering expression for binding).
    private List<FactoryGenerator.MethodParam> boundBodyParams(ExecutableElement execElement,
                                                               List<FactoryGenerator.MethodParam> rawBodyParams,
                                                               Set<String> jsonTargets) {
        var reprs = collectBindReprs(execElement, jsonTargets);

        validateBindReprs(execElement, rawBodyParams, reprs);

        return applyBindReprs(rawBodyParams, reprs);
    }

    /// Maps each bind target (a method param, or a component of an expanded payload record) to its column
    /// representation when it has one.
    private Map<String, ColumnRepr> collectBindReprs(ExecutableElement execElement, Set<String> jsonTargets) {
        var reprs = new HashMap<String, ColumnRepr>();

        for (var param : execElement.getParameters()) {
            var name = param.getSimpleName().toString();
            var direct = reprFor(param, execElement, jsonTargets.contains(name));

            if (direct.isPresent()) {
                reprs.put(name, direct.expect("checked with isPresent above"));
            } else if (isRecordType(param)) {
                collectRecordFieldReprs(execElement, param, reprs, jsonTargets);
            }
        }

        return reprs;
    }

    private void collectRecordFieldReprs(ExecutableElement execElement,
                                         VariableElement recordParam,
                                         Map<String, ColumnRepr> reprs,
                                         Set<String> jsonTargets) {
        for (var component : ((TypeElement) ((DeclaredType) recordParam.asType()).asElement()).getEnclosedElements()) {
            if (component.getKind() == ElementKind.RECORD_COMPONENT) {
                var name = component.getSimpleName().toString();
                reprFor(component, execElement, jsonTargets.contains(name)).onPresent(repr -> reprs.put(name, repr));
            }
        }
    }

    private void validateBindReprs(ExecutableElement execElement,
                                   List<FactoryGenerator.MethodParam> bodyParams,
                                   Map<String, ColumnRepr> reprs) {
        for (var param : bodyParams) {
            if (!reprs.containsKey(param.name()) && !isScalarType(param.typeName())) {
                error(ProcessorError.missingValueMappingForParam(param.name(), param.typeName()), execElement);
            }
        }
    }

    private static List<FactoryGenerator.MethodParam> applyBindReprs(List<FactoryGenerator.MethodParam> bodyParams,
                                                                     Map<String, ColumnRepr> reprs) {
        return reprs.isEmpty()
               ? bodyParams
               : bodyParams.stream().map(param -> loweredParam(param, reprs)).toList();
    }

    private static FactoryGenerator.MethodParam loweredParam(FactoryGenerator.MethodParam param,
                                                             Map<String, ColumnRepr> reprs) {
        var repr = reprs.get(param.name());

        return repr == null
               ? param
               : new FactoryGenerator.MethodParam(param.name(), repr.pTypeName(), repr.lowerExpr(param.accessor()));
    }

    /// Validates and lifts decode targets: emits a compile error for any return-record field that is neither a
    /// supported column type nor one with a column representation, then rewrites each represented column to read
    /// its column-side value and decode it (a value object's `lift` or a document's codec, guarded by a typed
    /// `RowDecode` cause; an `Option` through the nullable read).
    private List<FactoryGenerator.MapperColumn> boundMapperColumns(ExecutableElement execElement,
                                                                   TypeMirrorResolver.ResolvedReturn resolved,
                                                                   List<FactoryGenerator.MapperColumn> rawColumns,
                                                                   Set<String> jsonTargets) {
        if (!resolved.needsMapper()) {
            return rawColumns;
        }

        var reprs = collectDecodeReprs(execElement, jsonTargets);

        validateDecodeReprs(execElement, resolved, reprs);

        return applyDecodeReprs(rawColumns, reprs);
    }

    private Map<String, ColumnRepr> collectDecodeReprs(ExecutableElement execElement, Set<String> jsonTargets) {
        var reprs = new HashMap<String, ColumnRepr>();
        var innerElement = TypeMirrorResolver.innerTypeElement(execElement.getReturnType());

        if (innerElement == null) {
            return reprs;
        }

        for (var component : innerElement.getEnclosedElements()) {
            if (component.getKind() == ElementKind.RECORD_COMPONENT) {
                var name = component.getSimpleName().toString();
                reprFor(component, execElement, jsonTargets.contains(name)).onPresent(repr -> reprs.put(name, repr));
            }
        }

        return reprs;
    }

    private void validateDecodeReprs(ExecutableElement execElement,
                                     TypeMirrorResolver.ResolvedReturn resolved,
                                     Map<String, ColumnRepr> reprs) {
        var innerElement = TypeMirrorResolver.innerTypeElement(execElement.getReturnType());

        if (innerElement == null) {
            return;
        }

        for (var component : innerElement.getEnclosedElements()) {
            if (component.getKind() != ElementKind.RECORD_COMPONENT) {
                continue;
            }

            var fieldName = component.getSimpleName().toString();

            if (!reprs.containsKey(fieldName) && !isScalarType(component.asType().toString())) {
                error(ProcessorError.missingValueMappingForField(fieldName, component.asType().toString(), resolved.innerTypeName()),
                      execElement);
            }
        }
    }

    private static List<FactoryGenerator.MapperColumn> applyDecodeReprs(List<FactoryGenerator.MapperColumn> columns,
                                                                        Map<String, ColumnRepr> reprs) {
        return reprs.isEmpty()
               ? columns
               : columns.stream().map(col -> liftedColumn(col, reprs)).toList();
    }

    private static FactoryGenerator.MapperColumn liftedColumn(FactoryGenerator.MapperColumn col,
                                                              Map<String, ColumnRepr> reprs) {
        var repr = reprs.get(col.fieldName());

        if (repr == null) {
            return col;
        }

        var accessor = repr.pAccessor();

        return col.withLift(accessor.method(), accessor.typeArg(), repr.liftExpr());
    }

    /// Every `@Jsonb` component of `record` whose column the table has must map to a json or jsonb column.
    private void validateJsonbComponents(ExecutableElement execElement,
                                         org.pragmatica.aether.pg.schema.model.Table table,
                                         TypeElement record) {
        for (var component : record.getEnclosedElements()) {
            if (component.getKind() != ElementKind.RECORD_COMPONENT || !isMarked(component)) {
                continue;
            }
            var name = component.getSimpleName().toString();
            var columnName = NamingConvention.toSnakeCase(name);
            table.column(columnName)
                 .map(column -> column.type())
                 .filter(pgType -> !isJsonColumn(pgType))
                 .onPresent(pgType -> error(ProcessorError.jsonbColumnMismatch(name, columnName, table.name(), pgType.name()),
                                            execElement));
        }
    }

    private static boolean isJsonColumn(PgType pgType) {
        var name = canonicalPgTypeName(pgType);
        return name.equals("json") || name.equals("jsonb");
    }

    // --- Columns the schema knows to be json/jsonb: inferred documents, String binds refused ---

    /// The names among `fieldNames` (camelCase) whose snake_case column in `table` is json/jsonb.
    private static Set<String> jsonColumnNames(List<String> fieldNames, org.pragmatica.aether.pg.schema.model.Table table) {
        var names = new HashSet<String>();
        for (var fieldName : fieldNames) {
            jsonColumn(table, NamingConvention.toSnakeCase(fieldName)).onPresent(_ -> names.add(fieldName));
        }
        return names;
    }

    private static Option<PgType> jsonColumn(org.pragmatica.aether.pg.schema.model.Table table, String columnName) {
        return table.column(columnName)
                    .map(column -> column.type())
                    .filter(QueryAnnotationProcessor::isJsonColumn);
    }

    /// CRUD bind targets on json/jsonb columns: the values of an insert or save (a record's components, or the params
    /// themselves), each named after its column, or the condition params.
    private static Set<String> crudJsonBindTargets(MethodNameParser.ParsedMethod parsed,
                                                   org.pragmatica.aether.pg.schema.model.Table table,
                                                   List<FactoryGenerator.MethodParam> rawBody) {
        if (isInsertOrSave(parsed.operation())) {
            return jsonColumnNames(rawBody.stream().map(FactoryGenerator.MethodParam::name).toList(), table);
        }
        var names = new HashSet<String>();
        var paramIdx = 0;
        for (var condition : parsed.conditions()) {
            var count = condition.operator().paramCount();
            if (count > 0 && paramIdx < rawBody.size()) {
                var name = rawBody.get(paramIdx).name();
                jsonColumn(table, condition.columnName()).onPresent(_ -> names.add(name));
            }
            paramIdx += count;
        }
        return names;
    }

    /// `@Query` bind targets on json/jsonb columns: `column <op> :param` pairs without a cast in the SQL, and components
    /// of a record expanded into `VALUES (:record)` / `SET :record`, against the statement's table.
    private static Set<String> queryJsonBindTargets(QueryExpansionResult expansion, Schema schema) {
        var tableName = extractTableNameFromSql(expansion.sql());
        if (tableName == null) {
            return Set.of();
        }
        return schema.table(tableName)
                     .map(table -> queryJsonBindTargets(expansion, table))
                     .or(Set.of());
    }

    private static Set<String> queryJsonBindTargets(QueryExpansionResult expansion,
                                                    org.pragmatica.aether.pg.schema.model.Table table) {
        var names = jsonColumnNames(expansion.expandedFieldNames(), table);
        for (var pair : extractColumnParamPairs(expansion.sql(), expansion.allParamNames())) {
            if (!pair.cast()) {
                jsonColumn(table, pair.columnName()).onPresent(_ -> names.add(pair.paramName()));
            }
        }
        return names;
    }

    /// The values of an insert or save, checked against their json/jsonb columns (a String is refused).
    private void validateInsertJsonBinds(ExecutableElement execElement,
                                         org.pragmatica.aether.pg.schema.model.Table table,
                                         List<FactoryGenerator.MethodParam> boundBody) {
        for (var param : boundBody) {
            var columnName = NamingConvention.toSnakeCase(param.name());
            jsonColumn(table, columnName).onPresent(pgType -> validateTypeCompatibility(execElement,
                                                                                         param.name(),
                                                                                         param.typeName(),
                                                                                         columnName,
                                                                                         table.name(),
                                                                                         pgType,
                                                                                         false));
        }
    }

    /// The components of a record expanded in `@Query` SQL, checked against their json/jsonb columns.
    private void validateExpandedJsonBinds(ExecutableElement execElement,
                                           QueryExpansionResult expansion,
                                           List<FactoryGenerator.MethodParam> boundBody,
                                           Schema schema) {
        var tableName = extractTableNameFromSql(expansion.sql());
        if (tableName == null) {
            return;
        }
        schema.table(tableName)
              .onPresent(table -> validateInsertJsonBinds(execElement,
                                                          table,
                                                          boundBody.stream()
                                                                   .filter(param -> expansion.expandedFieldNames()
                                                                                             .contains(param.name()))
                                                                   .toList()));
    }

    private static TypeElement recordParamElement(ExecutableElement execElement) {
        return (TypeElement) ((DeclaredType) execElement.getParameters().getFirst().asType()).asElement();
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
        if ( !isRecordType(paramElement) || isBoundWhole(paramElement)) {
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
                                        boolean hasExpansion,
                                        List<String> expandedFieldNames){}

    private QueryExpansionResult expandRecordParams(
    ExecutableElement execElement,
    String sql,
    List<FactoryGenerator.MethodParam> originalParams) {
        var expandedSql = sql;
        var allParamNames = new ArrayList<String>();
        var bodyParams = new ArrayList<FactoryGenerator.MethodParam>();
        var existingPositions = new LinkedHashMap<String, Integer>();
        var expandedFieldNames = new ArrayList<String>();
        var hasExpansion = false;
        for ( int paramIdx = 0; paramIdx < originalParams.size(); paramIdx++) {
            var param = originalParams.get(paramIdx);
            var paramElement = execElement.getParameters().get(paramIdx);
            if ( !isRecordType(paramElement) || isBoundWhole(paramElement)) {
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
                    expandedFieldNames.add(fieldName);
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
        return new QueryExpansionResult(expandedSql, allParamNames, bodyParams, hasExpansion, expandedFieldNames);
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

    // --- Compile-time validation (Bug 4, #1139) ---
    /// Refuses a query whose placeholders and method parameters disagree. These are ERRORS, not warnings (#1139): a
    /// named placeholder with no parameter, or a parameter the SQL never names, generates a bind list whose length
    /// differs from the placeholder count, which Postgres rejects at the first execution. A query written with
    /// positional `$n` placeholders is checked by count instead: its highest `$n` must equal the parameter count,
    /// since `$n` binds the n-th declared parameter.
    private boolean rejectsParamMismatch(ExecutableElement execElement, String sql, List<String> availableParamNames) {
        var sqlParams = QueryRewriter.extractNamedParams(sql);

        var positional = POSITIONAL_PARAM_PATTERN.matcher(QueryRewriter.sqlCodeOnly(sql))
                                                 .results()
                                                 .mapToInt(match -> Integer.parseInt(match.group(1)))
                                                 .max();

        if (sqlParams.isEmpty()) {
            return rejectsPositionalCountMismatch(execElement, positional.orElse(0), availableParamNames.size());
        }
        if (positional.isPresent()) {
            // #1707 review: `:name` is rewritten to `$n` while an existing `$n` is left as is, so a query mixing the two
            // styles binds by an order neither check sees.
            error(ProcessorError.mixedPlaceholderStyles(), execElement);
            return true;
        }
        var availableSet = new HashSet<>(availableParamNames);
        var sqlParamSet = new HashSet<>(sqlParams);
        var rejected = false;

        for (var sqlParam : sqlParams) {
            if (!availableSet.contains(sqlParam)) {
                error(ProcessorError.parameterNotInMethod(sqlParam), execElement);
                rejected = true;
            }
        }
        for (var paramName : availableParamNames) {
            if (!sqlParamSet.contains(paramName)) {
                error(ProcessorError.unusedMethodParameter(paramName), execElement);
                rejected = true;
            }
        }
        return rejected;
    }

    private boolean rejectsPositionalCountMismatch(ExecutableElement execElement, int highest, int parameterCount) {
        if (highest != parameterCount) {
            error(ProcessorError.positionalParameterCountMismatch(highest, parameterCount), execElement);
            return true;
        }
        return false;
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
        var table = tableOpt.expect("checked with isEmpty above");
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
                                      colOpt.expect("checked with isEmpty above").type(),
                                      pair.cast());
        }
    }

    /// Validates return type field mapping against SELECT output for @Query methods, reusing the
    /// already-parsed CST for precise, CST-based output-column resolution (via
    /// `QueryValidator.selectOutputColumnNames`). Emits warnings — never errors — since the mapping
    /// is a best-effort aid. When the SELECT output set cannot be determined precisely (parse
    /// failure, `SELECT *`, unaliased expressions, or multiple/zero SELECT cores) the check is
    /// skipped rather than warning spuriously.
    private void validateReturnTypeMapping(
    ExecutableElement execElement,
    TypeMirrorResolver.ResolvedReturn resolved,
    Result<CstNode> parsedCst) {
        if ( !resolved.needsMapper()) {
        return;}
        if ( parsedCst.isFailure()) {
        return;}
        var navOpt = CstNavigator.wrap(parsedCst.expect("checked with isFailure above"));
        if ( navOpt.isEmpty()) {
        return;}
        var selectColumnsOpt = QueryValidator.selectOutputColumnNames(navOpt.expect("checked with isEmpty above"));
        if ( selectColumnsOpt.isEmpty()) {
        return;}
        var selectColumns = selectColumnsOpt.expect("checked with isEmpty above");
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

    /// Validates parameter types for CRUD method conditions against table column types. Receives
    /// the bound body params so value-object params validate as their lowered column type.
    private void validateCrudParamTypes(
    ExecutableElement execElement,
    MethodNameParser.ParsedMethod parsed,
    org.pragmatica.aether.pg.schema.model.Table table,
    List<FactoryGenerator.MethodParam> params) {
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
                                      colOpt.expect("checked with isEmpty above").type(),
                                      false);
            paramIdx += condition.operator().paramCount();
        }
    }

    /// Checks if a Java type is compatible with a PostgreSQL column type. A String on a json/jsonb column is refused (it
    /// is sent as varchar, which json/jsonb refuse) unless the SQL casts the parameter (`castInSql`).
    private void validateTypeCompatibility(
    ExecutableElement execElement,
    String paramName,
    String javaType,
    String columnName,
    String tableName,
    PgType pgType,
    boolean castInSql) {
        if (isJsonColumn(pgType) && !castInSql && "String".equals(normalizeJavaType(javaType))) {
            error(ProcessorError.stringOnJsonColumn(paramName, columnName, tableName, pgType.name()), execElement);
            return;
        }
        if (ColumnRepr.JSON_TEXT.equals(javaType)) {
            if (!isJsonColumn(pgType)) {
                error(ProcessorError.jsonbColumnMismatch(paramName, columnName, tableName, pgType.name()), execElement);
            }
            return;
        }
        var mappedOpt = TypeMapper.map(pgType);
        if ( mappedOpt.isEmpty()) {
        return;}
        var mapped = mappedOpt.expect("checked with isEmpty above");
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

    /// `cast`: the parameter is followed by a `::type` cast in the SQL.
    private record ColumnParamPair(String columnName, String paramName, boolean cast){}

    /// Extracts column-name to param-name pairs from WHERE/SET clauses.
    private static List<ColumnParamPair> extractColumnParamPairs(String sql, List<String> paramNames) {
        var pairs = new ArrayList<ColumnParamPair>();
        var matcher = WHERE_COLUMN_PATTERN.matcher(sql);
        while ( matcher.find()) {
            var columnName = matcher.group(1);
            var paramName = matcher.group(2);
            if ( paramNames.contains(paramName)) {
            pairs.add(new ColumnParamPair(columnName, paramName, sql.startsWith("::", matcher.end())));}
        }
        return pairs;
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
            if ( enclosed.getKind() != ElementKind.METHOD) {
                continue;
            }
            var method = (ExecutableElement) enclosed;
            collectTypeImports(method.getReturnType(), imports);
            collectReturnFieldImports(method, imports);
            for ( var param : method.getParameters()) {
                collectTypeImports(param.asType(), imports);
                if ( isRecordType(param)) {
                    collectComponentTypeImports((TypeElement) ((DeclaredType) param.asType()).asElement(), imports);
                }
            }
        }
        // Scalar return types: ensure the accessor's imported FQN is present (covers
        // Promise<BigDecimal>, Promise<Option<UUID>>, Promise<List<Instant>>, etc.).
        for (var methodInfo : methods) {
            methodInfo.scalarAccessor().onPresent(accessor ->
                accessor.importStatement().onPresent(imports::add));
        }
        return imports;
    }

    /// Walks a type mirror, adding import FQNs for any declared types that are not in java.lang
    /// and not members of the interface itself. Also descends into type arguments so that
    /// `Promise<Option<BigDecimal>>` produces an import for java.math.BigDecimal.
    private void collectTypeImports(javax.lang.model.type.TypeMirror mirror, Set<String> imports) {
        if ( ! (mirror instanceof DeclaredType dt)) {
            return;
        }
        var element = (TypeElement) dt.asElement();
        addImportForQualifiedName(element.getQualifiedName().toString(), imports);
        for ( var arg : dt.getTypeArguments()) {
            collectTypeImports(arg, imports);
        }
    }

    /// Imports the field types of a return record, so a value-object field's `Vo.valueMapping().lift()`
    /// reference resolves in the generated factory even when the value object lives in another
    /// package. Non-importable types (primitives, java.lang, core types) are skipped downstream.
    private void collectReturnFieldImports(ExecutableElement method, Set<String> imports) {
        var innerElement = TypeMirrorResolver.innerTypeElement(method.getReturnType());
        if ( innerElement == null) {
            return;
        }
        collectComponentTypeImports(innerElement, imports);
    }

    /// Imports a record's component types and their type arguments, so a value-object component's
    /// `Vo.valueMapping()` reference (also inside `Option<Vo>`) resolves. A document component (marked or inferred)
    /// is skipped: its codec constant names the type by its qualified name, and importing it could clash with another
    /// type of the same simple name.
    private void collectComponentTypeImports(TypeElement record, Set<String> imports) {
        for ( var component : record.getEnclosedElements()) {
            if ( component.getKind() != ElementKind.RECORD_COMPONENT || isMarked(component) || documentElements.contains(component)) {
                continue;
            }
            addImportForQualifiedName(component.asType().toString(), imports);
            if ( component.asType() instanceof DeclaredType dt) {
                for ( var arg : dt.getTypeArguments()) {
                    collectTypeImports(arg, imports);
                }
            }
        }
    }

    private static void addImportForQualifiedName(String typeName, Set<String> imports) {
        if ( typeName == null || typeName.isEmpty()) {
            return;
        }
        // Strip generic type arguments: "java.util.List<java.util.UUID>" -> walked separately via DeclaredType path
        var baseName = typeName;
        var generic = baseName.indexOf('<');
        if ( generic >= 0) {
            baseName = baseName.substring(0, generic);
        }
        // Strip array suffix: "java.util.UUID[]" -> "java.util.UUID"
        while ( baseName.endsWith("[]")) {
            baseName = baseName.substring(0, baseName.length() - 2);
        }
        baseName = baseName.trim();
        if ( baseName.isEmpty()) {
            return;
        }
        // Skip primitives and types already imported by FactoryGenerator header
        if ( !baseName.contains(".")) {
            return;
        }
        if ( baseName.startsWith("java.lang.") && baseName.indexOf('.', "java.lang.".length()) < 0) {
            return;
        }
        // Skip pragmatica core types already provided by FactoryGenerator header
        if ( baseName.equals("org.pragmatica.lang.Option")
             || baseName.equals("org.pragmatica.lang.Unit")
             || baseName.equals("org.pragmatica.lang.Result")
             || baseName.equals("org.pragmatica.lang.Promise")
             || baseName.equals("java.util.List")) {
            return;
        }
        imports.add(baseName);
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

    private void runMigrationLintOnce(String configPath) {
        if (migrationsLinted.contains(configPath)) {return;}
        migrationsLinted.add(configPath);
        if (lintOptions.isOff()) {return;}
        var scripts = schemaLoader.loadMigrations(configPath);
        if (scripts.isEmpty()) {return;}
        LintRunner.runOnMigrations(processingEnv.getMessager(), scripts, lintOptions);
    }

    /// Parses and validates the rewritten SQL against the given schema. Emits ERROR
    /// diagnostics for each validation failure. Returns true when validation passed
    /// (or was skipped because no schema was available); false on parse or validation failure.
    private boolean validateRewrittenSql(ExecutableElement execElement,
                                          String methodName,
                                          String rewrittenSql,
                                          Option<Schema> schemaOpt) {
        if (schemaOpt.isEmpty()) {return true;}
        var parsed = sqlParseCache.computeIfAbsent(rewrittenSql, sqlParser::parseCst);
        if (parsed.isFailure()) {
            var detail = parsed.fold(cause -> cause.message(), _ -> "");
            error(ProcessorError.sqlParseFailed(methodName, detail), execElement);
            return false;
        }
        var cst = parsed.expect("checked with isFailure above");
        var result = QueryValidator.queryValidator(schemaOpt.expect("checked with isEmpty above")).validate(cst);
        if (result.hasErrors()) {
            for (var err : result.errors()) {
                ValidationErrorBridge.emit(processingEnv.getMessager(), execElement, err);
            }
            return false;
        }
        LintRunner.runOnQuery(processingEnv.getMessager(), execElement, cst, lintOptions);
        return true;
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
