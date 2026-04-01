package org.pragmatica.aether.pg.schema.event;

import org.pragmatica.aether.pg.schema.model.*;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;

import java.util.List;

/// Events produced by analyzing DDL statements against the current schema.
/// Each event represents a single schema mutation.
public sealed interface SchemaEvent {

    SourceSpan span();

    // === Table events ===
    record TableCreated(SourceSpan span, String name, String schema, List<Column> columns,
                        List<Constraint> constraints, Option<Table.PartitionBy> partitioning) implements SchemaEvent {}
    record TableDropped(SourceSpan span, String name) implements SchemaEvent {}
    record TableRenamed(SourceSpan span, String oldName, String newName) implements SchemaEvent {}

    // === Column events ===
    record ColumnAdded(SourceSpan span, String table, Column column) implements SchemaEvent {}
    record ColumnDropped(SourceSpan span, String table, String columnName) implements SchemaEvent {}
    record ColumnRenamed(SourceSpan span, String table, String oldName, String newName) implements SchemaEvent {}
    record ColumnTypeChanged(SourceSpan span, String table, String column, PgType newType) implements SchemaEvent {}
    record ColumnDefaultChanged(SourceSpan span, String table, String column, Option<String> newDefault) implements SchemaEvent {}
    record ColumnNullabilityChanged(SourceSpan span, String table, String column, boolean nullable) implements SchemaEvent {}

    // === Constraint events ===
    record ConstraintAdded(SourceSpan span, String table, Constraint constraint) implements SchemaEvent {}
    record ConstraintDropped(SourceSpan span, String table, String constraintName) implements SchemaEvent {}

    // === Index events ===
    record IndexCreated(SourceSpan span, Index index) implements SchemaEvent {}
    record IndexDropped(SourceSpan span, String indexName) implements SchemaEvent {}

    // === Sequence events ===
    record SequenceCreated(SourceSpan span, Sequence sequence) implements SchemaEvent {}
    record SequenceDropped(SourceSpan span, String sequenceName) implements SchemaEvent {}

    // === Type events ===
    record TypeCreated(SourceSpan span, PgType type) implements SchemaEvent {}
    record TypeDropped(SourceSpan span, String typeName) implements SchemaEvent {}
    record EnumValueAdded(SourceSpan span, String typeName, String value,
                          Option<String> before, Option<String> after) implements SchemaEvent {}

    // === Schema namespace events ===
    record SchemaCreated(SourceSpan span, String schemaName) implements SchemaEvent {}
    record SchemaDropped(SourceSpan span, String schemaName) implements SchemaEvent {}

    // === Extension events ===
    record ExtensionCreated(SourceSpan span, String extensionName) implements SchemaEvent {}

    // === Comment events ===
    record CommentSet(SourceSpan span, String targetType, String targetName, Option<String> comment) implements SchemaEvent {}
}
