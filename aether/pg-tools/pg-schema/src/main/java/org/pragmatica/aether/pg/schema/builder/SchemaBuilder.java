package org.pragmatica.aether.pg.schema.builder;

import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.model.*;
import org.pragmatica.lang.Result;

import java.util.List;

/// Builds a Schema snapshot by folding a sequence of SchemaEvents.
/// Pure function: no side effects, no mutation.
public final class SchemaBuilder {
    private SchemaBuilder() {}

    /// Apply a list of events to build a schema from scratch.
    public static Result<Schema> build(List<SchemaEvent> events) {
        return apply(Schema.empty(), events);
    }

    /// Apply a list of events to an existing schema.
    public static Result<Schema> apply(Schema schema, List<SchemaEvent> events) {
        var current = Result.success(schema);
        for (var event : events) {
            current = current.flatMap(s -> applyEvent(s, event));
        }
        return current;
    }

    /// Apply a single event to a schema.
    public static Result<Schema> applyEvent(Schema schema, SchemaEvent event) {
        return switch (event) {
            case SchemaEvent.TableCreated e -> Result.success(schema.withTable(
                Table.table(e.name(), e.schema(), e.columns(), e.constraints())
            ));

            case SchemaEvent.TableDropped e -> Result.success(schema.withoutTable(e.name()));

            case SchemaEvent.TableRenamed e -> {
                var table = schema.table(e.oldName());
                yield table.isPresent()
                    ? Result.success(schema.withTableReplaced(e.oldName(), table.unwrap().renamed(e.newName())))
                    : Result.failure(SchemaErrors.tableNotFound(e.oldName(), e.span()));
            }

            case SchemaEvent.ColumnAdded e -> applyToTable(schema, e.table(), e.span(),
                t -> Result.success(t.withColumn(e.column())));

            case SchemaEvent.ColumnDropped e -> applyToTable(schema, e.table(), e.span(),
                t -> Result.success(t.withoutColumn(e.columnName())));

            case SchemaEvent.ColumnRenamed e -> applyToTable(schema, e.table(), e.span(), t -> {
                var col = t.column(e.oldName());
                return col.isPresent()
                    ? Result.success(t.withColumnReplaced(e.oldName(), col.unwrap().renamed(e.newName())))
                    : Result.failure(SchemaErrors.columnNotFound(e.table(), e.oldName(), e.span()));
            });

            case SchemaEvent.ColumnTypeChanged e -> applyToColumn(schema, e.table(), e.column(), e.span(),
                col -> col.withType(e.newType()));

            case SchemaEvent.ColumnDefaultChanged e -> applyToColumn(schema, e.table(), e.column(), e.span(),
                col -> e.newDefault().isPresent() ? col.withDefault(e.newDefault().unwrap()) :
                    new Column(col.name(), col.type(), col.nullable(), e.newDefault(), col.generatedExpr(), col.identity(), col.comment()));

            case SchemaEvent.ColumnNullabilityChanged e -> applyToColumn(schema, e.table(), e.column(), e.span(),
                col -> col.withNullable(e.nullable()));

            case SchemaEvent.ConstraintAdded e -> applyToTable(schema, e.table(), e.span(),
                t -> Result.success(t.withConstraint(e.constraint())));

            case SchemaEvent.ConstraintDropped e -> applyToTable(schema, e.table(), e.span(),
                t -> Result.success(t.withoutConstraint(e.constraintName())));

            case SchemaEvent.IndexCreated e -> {
                var tableName = e.index().table();
                var table = schema.table(tableName);
                yield table.isPresent()
                    ? Result.success(schema.withTableReplaced(tableName, table.unwrap().withIndex(e.index())))
                    : Result.success(schema); // index on unknown table — may be created before table reference
            }

            case SchemaEvent.IndexDropped e -> Result.success(schema); // TODO: remove from table

            case SchemaEvent.SequenceCreated e -> Result.success(schema.withSequence(e.sequence()));
            case SchemaEvent.SequenceDropped e -> Result.success(schema.withoutSequence(e.sequenceName()));

            case SchemaEvent.TypeCreated e -> switch (e.type()) {
                case PgType.EnumType et -> Result.success(schema.withEnumType(et));
                case PgType.CompositeType ct -> Result.success(schema.withCompositeType(ct));
                case PgType.DomainType dt -> Result.success(schema.withDomainType(dt));
                default -> Result.success(schema);
            };

            case SchemaEvent.TypeDropped e -> Result.success(schema); // TODO: remove from type maps

            case SchemaEvent.EnumValueAdded e -> {
                var enumKey = e.typeName();
                var existing = schema.enumTypes().get(enumKey);
                if (existing == null) {
                    yield Result.failure(SchemaErrors.typeNotFound(enumKey, e.span()));
                }
                var updated = e.before().isPresent() ? existing.withValueBefore(e.value(), e.before().unwrap())
                            : e.after().isPresent() ? existing.withValueAfter(e.value(), e.after().unwrap())
                            : existing.withValue(e.value());
                yield Result.success(schema.withEnumType(updated));
            }

            case SchemaEvent.SchemaCreated e -> Result.success(schema.withSchema(e.schemaName()));
            case SchemaEvent.SchemaDropped e -> Result.success(schema); // TODO: cascade

            case SchemaEvent.ExtensionCreated e -> Result.success(schema.withExtension(e.extensionName()));

            case SchemaEvent.CommentSet e -> {
                if ("TABLE".equalsIgnoreCase(e.targetType())) {
                    var table = schema.table(e.targetName());
                    yield table.isPresent() && e.comment().isPresent()
                        ? Result.success(schema.withTableReplaced(e.targetName(), table.unwrap().withComment(e.comment().unwrap())))
                        : Result.success(schema);
                }
                yield Result.success(schema); // TODO: handle column/index comments
            }
        };
    }

    // === Helpers ===

    private static Result<Schema> applyToTable(Schema schema, String tableName,
                                                org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan span,
                                                java.util.function.Function<Table, Result<Table>> fn) {
        var table = schema.table(tableName);
        if (table.isEmpty()) {
            return Result.failure(SchemaErrors.tableNotFound(tableName, span));
        }
        return fn.apply(table.unwrap()).map(t -> schema.withTableReplaced(tableName, t));
    }

    private static Result<Schema> applyToColumn(Schema schema, String tableName, String columnName,
                                                 org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan span,
                                                 java.util.function.Function<Column, Column> fn) {
        return applyToTable(schema, tableName, span, table -> {
            var col = table.column(columnName);
            if (col.isEmpty()) {
                return Result.failure(SchemaErrors.columnNotFound(tableName, columnName, span));
            }
            return Result.success(table.withColumnReplaced(columnName, fn.apply(col.unwrap())));
        });
    }
}
