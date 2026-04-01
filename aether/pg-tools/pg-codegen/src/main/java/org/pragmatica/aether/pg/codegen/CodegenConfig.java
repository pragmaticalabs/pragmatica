package org.pragmatica.aether.pg.codegen;

import java.nio.file.Path;

/// Configuration for Java code generation from PostgreSQL schema.
public record CodegenConfig(
    String targetPackage,
    Path outputDirectory,
    NullableStyle nullableStyle,
    boolean generateStaticFactory,
    boolean generateRowMapper,
    String rowSuffix
) {
    public enum NullableStyle { OPTION, NULLABLE_ANNOTATION }

    public static CodegenConfig defaults(String targetPackage, Path outputDirectory) {
        return new CodegenConfig(targetPackage, outputDirectory, NullableStyle.OPTION, true, true, "Row");
    }

    public CodegenConfig withNullableStyle(NullableStyle style) {
        return new CodegenConfig(targetPackage, outputDirectory, style, generateStaticFactory, generateRowMapper, rowSuffix);
    }

    public CodegenConfig withRowSuffix(String suffix) {
        return new CodegenConfig(targetPackage, outputDirectory, nullableStyle, generateStaticFactory, generateRowMapper, suffix);
    }

    /// Resolve the output file path for a given class name.
    public Path resolveOutputFile(String className) {
        var packagePath = targetPackage.replace('.', '/');
        return outputDirectory.resolve(packagePath).resolve(className + ".java");
    }
}
