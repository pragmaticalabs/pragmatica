package org.pragmatica.aether.pg.schema.linter.rules;

import org.pragmatica.aether.pg.schema.model.PgType;

import java.util.Set;

/// Encodes knowledge about which ALTER COLUMN TYPE operations are safe (no table rewrite).
public final class SafeTypeChanges {
    private SafeTypeChanges() {}

    /// Types that are binary-coercible to each other (no rewrite needed)
    private static final Set<Set<String>> COERCIBLE_GROUPS = Set.of(Set.of("text", "varchar", "character varying"),
                                                                    Set.of("timestamp", "timestamp without time zone"),
                                                                    Set.of("timestamptz", "timestamp with time zone"),
                                                                    Set.of("time", "time without time zone"),
                                                                    Set.of("timetz", "time with time zone"),
                                                                    Set.of("bit", "bit varying", "varbit"));

    public static boolean isSafe(PgType oldType, PgType newType) {
        var oldName = baseTypeName(oldType).toLowerCase();
        var newName = baseTypeName(newType).toLowerCase();
        if ( oldName.equals(newName)) {
        // Same base type — check if modifiers are safe
        return isModifierChangeSafe(oldType, newType);}
        // Check binary coercibility
        for ( var group : COERCIBLE_GROUPS) {
        if ( group.contains(oldName) && group.contains(newName)) {
        return true;}}
        // Removing length limit: varchar(N) -> varchar, varchar(N) -> text
        if ( isStringType(oldName) && isStringType(newName)) {
        return true;}
        // All string-to-string conversions are safe
        return false;
    }

    private static boolean isModifierChangeSafe(PgType oldType, PgType newType) {
        var oldMods = modifiers(oldType);
        var newMods = modifiers(newType);
        // No modifiers change — always safe
        if ( oldMods.isEmpty() && newMods.isEmpty()) return true;
        // Adding modifiers (constraint) — always safe
        if ( oldMods.isEmpty()) return true;
        // Removing modifiers (removing constraint) — safe for most types
        if ( newMods.isEmpty()) return true;
        // Increasing precision/length — safe
        // varchar(100) -> varchar(200): safe
        // numeric(5,2) -> numeric(10,2): safe
        if ( !oldMods.isEmpty() && !newMods.isEmpty()) {
        if ( newMods.getFirst() >= oldMods.getFirst()) {
        return true;}}
        return false;
    }

    private static java.util.List<Integer> modifiers(PgType type) {
        return switch (type) {case PgType.BuiltinType bt -> bt.modifiers();default -> java.util.List.of();};
    }

    private static String baseTypeName(PgType type) {
        return switch (type) {case PgType.ArrayType at -> baseTypeName(at.elementType());default -> type.name();};
    }

    private static boolean isStringType(String name) {
        return Set.of("text", "varchar", "character varying", "char", "character", "name", "citext").contains(name);
    }
}
