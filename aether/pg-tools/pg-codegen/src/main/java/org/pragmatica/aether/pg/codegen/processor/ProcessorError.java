// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

public final class ProcessorError {
    private ProcessorError() {}

    private static final String PREFIX = "[PG-VALIDATE] ";

    public static String columnNotFound(String column, String table) {
        return PREFIX + "Column '" + column + "' not found in table '" + table + "'";
    }

    public static String tableNotFound(String table) {
        return PREFIX + "Table '" + table + "' not found in schema";
    }

    public static String parameterNotInMethod(String param) {
        return PREFIX + "Parameter ':" + param + "' has no matching method parameter";
    }

    public static String unusedMethodParameter(String param) {
        return PREFIX + "Method parameter '" + param + "' is not used in the query";
    }

    public static String typeMismatch(String param, String javaType, String column, String table, String pgType) {
        return PREFIX
             + "Type mismatch: parameter '" + param
             + "' is " + javaType
             + " but column '" + table
             + "." + column
             + "' is " + pgType;
    }

    public static String notNullColumnMissing(String column, String table, String recordType) {
        return PREFIX + "NOT NULL column '" + column + "' has no DEFAULT and is not in " + recordType;
    }

    public static String noPrimaryKey(String table) {
        return PREFIX + "Table '" + table + "' has no PRIMARY KEY, required for save()";
    }

    public static String invalidReturnType(String methodName) {
        return PREFIX
             + "Method '" + methodName
             + "' must return Promise<T>, Promise<Option<T>>, Promise<List<T>>, Promise<Unit>, Promise<Long>, or Promise<Boolean>";
    }

    public static String unsupportedScalarReturn(String methodName, String typeName) {
        return PREFIX
             + "Method '" + methodName
             + "' returns unsupported scalar type '" + typeName
             + "'. Expected a record, Long, Boolean, String, Unit, or one of: BigDecimal, Instant, LocalDate, "
             + "LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, UUID, Duration, byte[], Short, Integer, "
             + "Double, Float.";
    }

    public static String cannotInferTable(String methodName) {
        return PREFIX + "Cannot infer table for method '" + methodName + "'; add @Table annotation";
    }

    public static String unrecognizedMethodName(String methodName) {
        return PREFIX + "Cannot parse method name '" + methodName + "' into a CRUD operation";
    }

    public static String schemaLoadFailed(String path, String detail) {
        return PREFIX + "Failed to load schema from '" + path + "': " + detail;
    }

    public static String unlistedMigration(String fileName, String schemaPath) {
        return PREFIX + "Migration '" + fileName + "' in '" + schemaPath
             + "' is not listed in migrations.list (kept via auto-discovery)";
    }

    public static String returnFieldNotInSelect(String field, String recordType) {
        return PREFIX + "Field '" + field + "' in return type " + recordType + " has no matching SELECT column";
    }

    public static String missingValueMappingForParam(String param, String type) {
        return PREFIX
             + "Parameter '" + param + "' has type '" + type
             + "' which is neither a supported column type nor a value object exposing "
             + "'static ValueMapping<" + type + ", P> valueMapping()'. Add a ValueMapping or use a raw column type.";
    }

    public static String missingValueMappingForField(String field, String type, String recordType) {
        return PREFIX
             + "Field '" + field + "' of return type " + recordType + " has type '" + type
             + "' which is neither a supported column type nor a value object exposing "
             + "'static ValueMapping<" + type + ", P> valueMapping()'. Add a ValueMapping or use a raw column type.";
    }

    public static String sqlConnectorWithQueryAnnotation(String interfaceName) {
        return PREFIX
             + "Interface '" + interfaceName
             + "' uses @Query but its qualifier references SqlConnector, "
             + "not PgSqlConnector. Use a PgSqlConnector-based qualifier.";
    }

    public static String sqlParseFailed(String methodName, String detail) {
        return PREFIX + "SQL parse failed in '" + methodName + "': " + detail;
    }

    public static String dataModifyingCteNotSupported(String methodName) {
        return PREFIX
             + "Data-modifying CTEs are not supported in '" + methodName
             + "': a WITH clause whose body is INSERT/UPDATE/DELETE (e.g. "
             + "WITH x AS (INSERT ... RETURNING ...) SELECT ...) cannot be validated or generated. "
             + "Split the write and the read into separate methods.";
    }

    public static String columnNotFoundInQuery(String column, String table, int line, int col) {
        return PREFIX + "Column '" + column + "' not found in table '" + table + "' at SQL " + line + ":" + col;
    }

    public static String tableNotFoundInQuery(String table, int line, int col) {
        return PREFIX + "Table '" + table + "' not found in schema at SQL " + line + ":" + col;
    }

    public static String tableOrAliasNotFound(String name, int line, int col) {
        return PREFIX + "Table or alias '" + name + "' not found at SQL " + line + ":" + col;
    }

    public static String columnNotResolved(String column, int line, int col) {
        return PREFIX + "Column '" + column + "' cannot be resolved at SQL " + line + ":" + col;
    }

    public static String lintFinding(String ruleId, String message, int line, int col) {
        return "[" + ruleId + "] " + message + " at SQL " + line + ":" + col;
    }
}
