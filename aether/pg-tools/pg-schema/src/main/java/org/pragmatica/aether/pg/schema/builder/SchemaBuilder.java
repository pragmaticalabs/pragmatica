package org.pragmatica.aether.pg.schema.builder;

import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;
import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.model.*;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.function.Function;


/// Builds a Schema snapshot by folding a sequence of SchemaEvents.
/// Pure function: no side effects, no mutation.
public final class SchemaBuilder {
    private SchemaBuilder() {}

    public static Result<Schema> build(List<SchemaEvent> events) {
        return apply(Schema.empty(), events);
    }

    public static Result<Schema> apply(Schema schema, List<SchemaEvent> events) {
        var current = Result.success(schema);
        for (var event : events) {current = current.flatMap(s -> applyEvent(s, event));}
        return current;
    }

    public static Result<Schema> applyEvent(Schema schema, SchemaEvent event) {
        return switch (event){
            case SchemaEvent.TableCreated e -> Result.success(schema.withTable(Table.table(e.name(),
                                                                                           e.schema(),
                                                                                           e.columns(),
                                                                                           e.constraints())));
            case SchemaEvent.TableDropped e -> Result.success(schema.withoutTable(e.name()));
            case SchemaEvent.TableRenamed e -> {
                var table = schema.table(e.oldName());
                yield table.isPresent()
                     ? Result.success(schema.withTableReplaced(e.oldName(),
                                                               table.unwrap().renamed(e.newName())))
                     : SchemaErrors.tableNotFound(e.oldName(), e.span()).result();
            }
            case SchemaEvent.ColumnAdded e -> applyToTable(schema,
                                                           e.table(),
                                                           e.span(),
                                                           t -> Result.success(t.withColumn(e.column())));
            case SchemaEvent.ColumnDropped e -> applyToTable(schema,
                                                             e.table(),
                                                             e.span(),
                                                             t -> Result.success(t.withoutColumn(e.columnName())));
            case SchemaEvent.ColumnRenamed e -> applyToTable(schema,
                                                             e.table(),
                                                             e.span(),
                                                             t -> renameColumn(t,
                                                                               e.table(),
                                                                               e.oldName(),
                                                                               e.newName(),
                                                                               e.span()));
            case SchemaEvent.ColumnTypeChanged e -> applyToColumn(schema,
                                                                  e.table(),
                                                                  e.column(),
                                                                  e.span(),
                                                                  col -> col.withType(e.newType()));
            case SchemaEvent.ColumnDefaultChanged e -> applyToColumn(schema,
                                                                     e.table(),
                                                                     e.column(),
                                                                     e.span(),
                                                                     col -> e.newDefault().isPresent()
                                                                           ? col.withDefault(e.newDefault().unwrap())
                                                                           : col.withoutDefault());
            case SchemaEvent.ColumnNullabilityChanged e -> applyToColumn(schema,
                                                                         e.table(),
                                                                         e.column(),
                                                                         e.span(),
                                                                         col -> col.withNullable(e.nullable()));
            case SchemaEvent.ConstraintAdded e -> applyToTable(schema,
                                                               e.table(),
                                                               e.span(),
                                                               t -> Result.success(t.withConstraint(e.constraint())));
            case SchemaEvent.ConstraintDropped e -> applyToTable(schema,
                                                                 e.table(),
                                                                 e.span(),
                                                                 t -> Result.success(t.withoutConstraint(e.constraintName())));
            case SchemaEvent.IndexCreated e -> {
                var tableName = e.index().table();
                var table = schema.table(tableName);
                yield table.isPresent()
                     ? Result.success(schema.withTableReplaced(tableName,
                                                               table.unwrap().withIndex(e.index())))
                     : Result.success(schema);
            }
            case SchemaEvent.IndexDropped e -> Result.success(schema);
            case SchemaEvent.SequenceCreated e -> Result.success(schema.withSequence(e.sequence()));
            case SchemaEvent.SequenceDropped e -> Result.success(schema.withoutSequence(e.sequenceName()));
            case SchemaEvent.TypeCreated e -> switch (e.type()){
                case PgType.EnumType et -> Result.success(schema.withEnumType(et));
                case PgType.CompositeType ct -> Result.success(schema.withCompositeType(ct));
                case PgType.DomainType dt -> Result.success(schema.withDomainType(dt));
                default -> Result.success(schema);
            };
            case SchemaEvent.TypeDropped e -> Result.success(schema);
            case SchemaEvent.EnumValueAdded e -> {
                var enumKey = e.typeName();
                var existing = schema.enumTypes().get(enumKey);
                if (existing == null) {yield SchemaErrors.typeNotFound(enumKey, e.span()).result();}
                var updated = e.before().isPresent()
                             ? existing.withValueBefore(e.value(),
                                                        e.before().unwrap())
                             : e.after().isPresent()
                             ? existing.withValueAfter(e.value(),
                                                       e.after().unwrap())
                             : existing.withValue(e.value());
                yield Result.success(schema.withEnumType(updated));
            }
            case SchemaEvent.SchemaCreated e -> Result.success(schema.withSchema(e.schemaName()));
            case SchemaEvent.SchemaDropped e -> Result.success(schema);
            case SchemaEvent.ExtensionCreated e -> Result.success(schema.withExtension(e.extensionName()));
            case SchemaEvent.CommentSet e -> {
                if ("TABLE".equalsIgnoreCase(e.targetType())) {
                    var table = schema.table(e.targetName());
                    yield table.isPresent() && e.comment().isPresent()
                         ? Result.success(schema.withTableReplaced(e.targetName(),
                                                                   table.unwrap().withComment(e.comment().unwrap())))
                         : Result.success(schema);
                }
                yield Result.success(schema);
            }
        };
    }

    private static Result<Table> renameColumn(Table t,
                                              String tableName,
                                              String oldName,
                                              String newName,
                                              SourceSpan span) {
        var col = t.column(oldName);
        return col.isPresent()
              ? Result.success(t.withColumnReplaced(oldName,
                                                    col.unwrap().renamed(newName)))
              : SchemaErrors.columnNotFound(tableName, oldName, span).result();
    }

    private static Result<Schema> applyToTable(Schema schema,
                                               String tableName,
                                               SourceSpan span,
                                               Function<Table, Result<Table>> fn) {
        var table = schema.table(tableName);
        if (table.isEmpty()) {return SchemaErrors.tableNotFound(tableName, span).result();}
        return fn.apply(table.unwrap()).map(t -> schema.withTableReplaced(tableName, t));
    }

    private static Result<Schema> applyToColumn(Schema schema,
                                                String tableName,
                                                String columnName,
                                                SourceSpan span,
                                                Function<Column, Column> fn) {
        return applyToTable(schema,
                            tableName,
                            span,
                            table -> {
                                var col = table.column(columnName);
                                if (col.isEmpty()) {return SchemaErrors.columnNotFound(tableName, columnName, span)
                                                                                      .result();}
                                return Result.success(table.withColumnReplaced(columnName,
                                                                               fn.apply(col.unwrap())));
                            });
    }
}
