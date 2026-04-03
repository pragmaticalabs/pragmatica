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
        return PREFIX + "Type mismatch: parameter '" + param + "' is " + javaType + " but column '" + table + "." + column + "' is " + pgType;
    }

    public static String notNullColumnMissing(String column, String table, String recordType) {
        return PREFIX + "NOT NULL column '" + column + "' has no DEFAULT and is not in " + recordType;
    }

    public static String noPrimaryKey(String table) {
        return PREFIX + "Table '" + table + "' has no PRIMARY KEY, required for save()";
    }

    public static String invalidReturnType(String methodName) {
        return PREFIX + "Method '" + methodName + "' must return Promise<T>, Promise<Option<T>>, Promise<List<T>>, Promise<Unit>, Promise<Long>, or Promise<Boolean>";
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

    public static String returnFieldNotInSelect(String field, String recordType) {
        return PREFIX + "Field '" + field + "' in return type " + recordType + " has no matching SELECT column";
    }

    public static String sqlConnectorWithQueryAnnotation(String interfaceName) {
        return PREFIX + "Interface '" + interfaceName + "' uses @Query but its qualifier references SqlConnector, " + "not PgSqlConnector. Use a PgSqlConnector-based qualifier.";
    }
}
