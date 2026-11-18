package org.pragmatica.jbct.cli.skeleton;

import java.util.List;
import java.util.stream.Collectors;


/// The JSON rendering of a skeleton. A declaration is an object with `kind`, `name`, `signature`, `startRow`,
/// `startColumn`, `endRow`, `endColumn` (1-based, the end inclusive), `docRow` (null when there is no doc comment),
/// `annotations` and `children`; a file is an object with `file` and `declarations`.
public final class SkeletonJson {
    private SkeletonJson() {}

    /// One file's object.
    public static String file(String path, Skeleton skeleton) {
        return "{\"file\": " + string(path) + ", \"declarations\": " + array(skeleton.declarations()) + "}";
    }

    /// The document for a run: an array with one object per file that parsed.
    public static String document(List<String> files) {
        return "[" + String.join(",\n ", files) + "]\n";
    }

    private static String array(List<Declaration> declarations) {
        return declarations.stream()
                           .map(SkeletonJson::declaration)
                           .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String declaration(Declaration declaration) {
        var range = declaration.range();

        return "{\"kind\": " + string(declaration.kind().label())
             + ", \"name\": " + string(declaration.name())
             + ", \"signature\": " + string(declaration.signature())
             + ", \"startRow\": " + range.startRow()
             + ", \"startColumn\": " + range.startColumn()
             + ", \"endRow\": " + range.endRow()
             + ", \"endColumn\": " + range.endColumn()
             + ", \"docRow\": " + declaration.docRow()
                                             .map(String::valueOf)
                                             .or("null")
             + ", \"annotations\": " + strings(declaration.annotations())
             + ", \"children\": " + array(declaration.children())
             + "}";
    }

    private static String strings(List<String> values) {
        return values.stream()
                     .map(SkeletonJson::string)
                     .collect(Collectors.joining(", ", "[", "]"));
    }

    private static String string(String value) {
        return value.chars()
                    .mapToObj(SkeletonJson::escape)
                    .collect(Collectors.joining("", "\"", "\""));
    }

    private static String escape(int character) {
        return switch (character) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> character < 0x20
                       ? "\\u%04x".formatted(character)
                       : Character.toString(character);
        };
    }
}
