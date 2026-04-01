package org.pragmatica.aether.pg.schema.model;

import org.pragmatica.lang.Option;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/// Top-level container for a PostgreSQL schema snapshot.
/// Immutable — all mutation methods return new instances.
public record Schema(
 Map<String, Table> tables,
 Map<String, PgType.EnumType> enumTypes,
 Map<String, PgType.CompositeType> compositeTypes,
 Map<String, PgType.DomainType> domainTypes,
 Map<String, Sequence> sequences,
 Set<String> schemas,
 Set<String> extensions) {
    public static Schema empty() {
        return new Schema(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of());
    }

    // === Table operations ===
    public Option<Table> table(String qualifiedName) {
        var t = tables.get(qualifiedName);
        return t != null
               ? Option.present(t)
               : Option.empty();
    }

    public Schema withTable(Table table) {
        var key = qualifiedKey(table.schema(), table.name());
        var newTables = new HashMap<>(tables);
        newTables.put(key, table);
        return new Schema(Map.copyOf(newTables), enumTypes, compositeTypes, domainTypes, sequences, schemas, extensions);
    }

    public Schema withoutTable(String qualifiedName) {
        var newTables = new HashMap<>(tables);
        newTables.remove(qualifiedName);
        return new Schema(Map.copyOf(newTables), enumTypes, compositeTypes, domainTypes, sequences, schemas, extensions);
    }

    public Schema withTableReplaced(String qualifiedName, Table table) {
        var newTables = new HashMap<>(tables);
        newTables.remove(qualifiedName);
        newTables.put(qualifiedKey(table.schema(), table.name()),
                      table);
        return new Schema(Map.copyOf(newTables), enumTypes, compositeTypes, domainTypes, sequences, schemas, extensions);
    }

    // === Type operations ===
    public Schema withEnumType(PgType.EnumType enumType) {
        var key = qualifiedKey(enumType.schema(), enumType.name());
        var newEnums = new HashMap<>(enumTypes);
        newEnums.put(key, enumType);
        return new Schema(tables, Map.copyOf(newEnums), compositeTypes, domainTypes, sequences, schemas, extensions);
    }

    public Schema withCompositeType(PgType.CompositeType type) {
        var key = qualifiedKey(type.schema(), type.name());
        var newTypes = new HashMap<>(compositeTypes);
        newTypes.put(key, type);
        return new Schema(tables, enumTypes, Map.copyOf(newTypes), domainTypes, sequences, schemas, extensions);
    }

    public Schema withDomainType(PgType.DomainType type) {
        var key = qualifiedKey(type.schema(), type.name());
        var newTypes = new HashMap<>(domainTypes);
        newTypes.put(key, type);
        return new Schema(tables, enumTypes, compositeTypes, Map.copyOf(newTypes), sequences, schemas, extensions);
    }

    // === Sequence operations ===
    public Schema withSequence(Sequence seq) {
        var key = qualifiedKey(seq.schema(), seq.name());
        var newSeqs = new HashMap<>(sequences);
        newSeqs.put(key, seq);
        return new Schema(tables, enumTypes, compositeTypes, domainTypes, Map.copyOf(newSeqs), schemas, extensions);
    }

    public Schema withoutSequence(String qualifiedName) {
        var newSeqs = new HashMap<>(sequences);
        newSeqs.remove(qualifiedName);
        return new Schema(tables, enumTypes, compositeTypes, domainTypes, Map.copyOf(newSeqs), schemas, extensions);
    }

    // === Schema namespace operations ===
    public Schema withSchema(String schemaName) {
        var newSchemas = new HashSet<>(schemas);
        newSchemas.add(schemaName);
        return new Schema(tables, enumTypes, compositeTypes, domainTypes, sequences, Set.copyOf(newSchemas), extensions);
    }

    // === Extension operations ===
    public Schema withExtension(String extensionName) {
        var newExt = new HashSet<>(extensions);
        newExt.add(extensionName);
        return new Schema(tables, enumTypes, compositeTypes, domainTypes, sequences, schemas, Set.copyOf(newExt));
    }

    // === Helpers ===
    private static String qualifiedKey(String schema, String name) {
        return schema.isEmpty()
               ? name
               : schema + "." + name;
    }
}
