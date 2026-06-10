// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen;

import java.nio.file.Path;


public record CodegenConfig(String targetPackage,
                            Path outputDirectory,
                            NullableStyle nullableStyle,
                            boolean generateStaticFactory,
                            boolean generateRowMapper,
                            String rowSuffix) {
    public enum NullableStyle {
        OPTION,
        NULLABLE_ANNOTATION
    }

    public static CodegenConfig defaults(String targetPackage, Path outputDirectory) {
        return new CodegenConfig(targetPackage, outputDirectory, NullableStyle.OPTION, true, true, "Row");
    }

    public CodegenConfig withNullableStyle(NullableStyle style) {
        return new CodegenConfig(targetPackage,
                                 outputDirectory,
                                 style,
                                 generateStaticFactory,
                                 generateRowMapper,
                                 rowSuffix);
    }

    public CodegenConfig withRowSuffix(String suffix) {
        return new CodegenConfig(targetPackage,
                                 outputDirectory,
                                 nullableStyle,
                                 generateStaticFactory,
                                 generateRowMapper,
                                 suffix);
    }

    public Path resolveOutputFile(String className) {
        var packagePath = targetPackage.replace('.', '/');

        return outputDirectory.resolve(packagePath)
                              .resolve(className + ".java");
    }
}
