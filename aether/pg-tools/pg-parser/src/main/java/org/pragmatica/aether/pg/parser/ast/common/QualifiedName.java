package org.pragmatica.aether.pg.parser.ast.common;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;

import java.util.List;


/// A potentially schema-qualified name: schema.name or just name.
public record QualifiedName(SourceSpan span, List<Identifier> parts) {
    public Identifier name() {
        return parts.getLast();
    }

    public Option<Identifier> schema() {
        return parts.size() > 1
              ? Option.present(parts.getFirst())
              : Option.empty();
    }

    public String normalized() {
        return String.join(".",
                           parts.stream().map(Identifier::normalized)
                                       .toList());
    }

    @Override public String toString() {
        return String.join(".",
                           parts.stream().map(Identifier::toString)
                                       .toList());
    }
}
