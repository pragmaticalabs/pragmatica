package org.pragmatica.aether.pg.parser.ast.common;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;

import java.util.List;


/// A parsed PostgreSQL data type reference.
public record DataTypeName(SourceSpan span,
                           String baseName,
                           List<Integer> modifiers,
                           int arrayDimensions,
                           Option<QualifiedName> customTypeName) {
    public boolean isArray() {
        return arrayDimensions > 0;
    }

    public String normalized() {
        return baseName.toLowerCase();
    }

    @Override public String toString() {
        var sb = new StringBuilder();
        if (customTypeName.isPresent()) {sb.append(customTypeName.unwrap());} else {sb.append(baseName);}
        if (!modifiers.isEmpty()) {
            sb.append("(");
            sb.append(String.join(",",
                                  modifiers.stream().map(String::valueOf)
                                                  .toList()));
            sb.append(")");
        }
        for (int i = 0;i <arrayDimensions;i++) {sb.append("[]");}
        return sb.toString();
    }

    public static DataTypeName builtin(SourceSpan span, String name) {
        return new DataTypeName(span, name, List.of(), 0, Option.empty());
    }

    public static DataTypeName builtin(SourceSpan span, String name, List<Integer> modifiers) {
        return new DataTypeName(span, name, modifiers, 0, Option.empty());
    }

    public static DataTypeName array(DataTypeName element, int dimensions) {
        return new DataTypeName(element.span, element.baseName, element.modifiers, dimensions, element.customTypeName);
    }

    public static DataTypeName custom(SourceSpan span, QualifiedName name) {
        return new DataTypeName(span, name.normalized(), List.of(), 0, Option.present(name));
    }
}
