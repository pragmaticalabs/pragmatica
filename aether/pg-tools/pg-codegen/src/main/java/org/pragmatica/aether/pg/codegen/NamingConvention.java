package org.pragmatica.aether.pg.codegen;

public final class NamingConvention {
    private NamingConvention() {}

    public static String tableToClassName(String tableName, String suffix) {
        return toPascalCase(tableName) + suffix;
    }

    public static String toTypeName(String pgName) {
        return toPascalCase(pgName);
    }

    public static String toFieldName(String columnName) {
        return toCamelCase(columnName);
    }

    public static String toFactoryMethodName(String className) {
        if (className.isEmpty()) return className;
        return Character.toLowerCase(className.charAt(0)) + className.substring(1);
    }

    public static String toSnakeCase(String camelCase) {
        var sb = new StringBuilder();
        for (int i = 0;i <camelCase.length();i++) {
            char c = camelCase.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {sb.append(c);}
        }
        return sb.toString();
    }

    public static String toEnumConstant(String value) {
        return value.toUpperCase().replace('-', '_')
                                .replace(' ', '_')
                                .replaceAll("[^A-Z0-9_]", "_");
    }

    static String toPascalCase(String snake) {
        var sb = new StringBuilder();
        boolean capitalize = true;
        for (int i = 0;i <snake.length();i++) {
            char c = snake.charAt(i);
            if (c == '_' || c == '-') {capitalize = true;} else if (capitalize) {
                sb.append(Character.toUpperCase(c));
                capitalize = false;
            } else {sb.append(c);}
        }
        return sb.toString();
    }

    static String toCamelCase(String snake) {
        var pascal = toPascalCase(snake);
        if (pascal.isEmpty()) return pascal;
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }
}
