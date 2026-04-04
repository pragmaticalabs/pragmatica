package org.pragmatica.aether.pg.schema.model;

import org.pragmatica.lang.Option;


/// A table column definition.
public record Column(String name,
                     PgType type,
                     boolean nullable,
                     Option<String> defaultExpr,
                     Option<String> generatedExpr,
                     Option<IdentitySpec> identity,
                     Option<String> comment) {
    public record IdentitySpec(IdentityKind kind){}

    public enum IdentityKind {
        ALWAYS,
        BY_DEFAULT
    }

    public static Column column(String name, PgType type, boolean nullable) {
        return new Column(name, type, nullable, Option.empty(), Option.empty(), Option.empty(), Option.empty());
    }

    public Column withDefault(String expr) {
        return new Column(name, type, nullable, Option.present(expr), generatedExpr, identity, comment);
    }

    public Column withNullable(boolean nullable) {
        return new Column(name, type, nullable, defaultExpr, generatedExpr, identity, comment);
    }

    public Column withType(PgType newType) {
        return new Column(name, newType, nullable, defaultExpr, generatedExpr, identity, comment);
    }

    public Column withComment(String text) {
        return new Column(name, type, nullable, defaultExpr, generatedExpr, identity, Option.present(text));
    }

    public Column withoutDefault() {
        return new Column(name, type, nullable, Option.empty(), generatedExpr, identity, comment);
    }

    public Column renamed(String newName) {
        return new Column(newName, type, nullable, defaultExpr, generatedExpr, identity, comment);
    }
}
