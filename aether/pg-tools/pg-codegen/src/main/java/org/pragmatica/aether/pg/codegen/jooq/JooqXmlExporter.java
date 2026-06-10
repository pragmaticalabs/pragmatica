// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.jooq;

import org.pragmatica.aether.pg.schema.model.*;
import org.pragmatica.aether.pg.schema.model.Constraint.*;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Unit.unit;


@SuppressWarnings("JBCT-EX-01")
public final class JooqXmlExporter {
    private static final String NS = "http://www.jooq.org/xsd/jooq-meta-3.21.0.xsd";

    private JooqXmlExporter() {}

    public static Result<String> toXml(Schema schema, JooqXmlConfig config) {
        try {
            var writer = new StringWriter();
            var xmlWriter = createWriter(writer, config.prettyPrint());

            emit(xmlWriter, schema, config);
            xmlWriter.close();

            return success(writer.toString());
        } catch (XMLStreamException e) {
            return new JooqXmlExportError.MarshalFailed(e.getMessage()).result();
        }
    }

    public static Result<Unit> writeXml(Schema schema, JooqXmlConfig config, Path target) {
        return toXml(schema, config).flatMap(xml -> writeToFile(xml, target));
    }

    private static Result<Unit> writeToFile(String xml, Path target) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, xml);

            return success(unit());
        } catch (IOException e) {
            return new JooqXmlExportError.IoError(e.getMessage()).result();
        }
    }

    private static XMLStreamWriter createWriter(StringWriter writer, boolean prettyPrint) throws XMLStreamException {
        var factory = XMLOutputFactory.newInstance();
        var xmlWriter = factory.createXMLStreamWriter(writer);

        return prettyPrint
               ? new IndentingXmlStreamWriter(xmlWriter)
               : xmlWriter;
    }

    private static void emit(XMLStreamWriter w, Schema schema, JooqXmlConfig config) throws XMLStreamException {
        w.writeStartDocument("UTF-8", "1.0");
        w.writeStartElement("information_schema");
        w.writeDefaultNamespace(NS);
        var tables = collectTables(schema, config);
        var defaultSchema = config.defaultSchemaName();

        emitCatalogs(w, config);
        emitSchemata(w, schema, config);
        emitTables(w, tables, config);
        emitColumns(w, tables, defaultSchema, config);
        emitConstraints(w, tables, config);
        emitSequences(w, schema, config);
        if (config.emitIndexes()) {
            emitIndexes(w, tables, config);
        }

        if (config.emitEnums()) {
            emitEnums(w, schema, defaultSchema);
        }

        emitDomains(w, schema, defaultSchema, config);
        w.writeEndElement();
        w.writeEndDocument();
    }

    private static void emitCatalogs(XMLStreamWriter w, JooqXmlConfig config) throws XMLStreamException {
        w.writeStartElement("catalogs");
        w.writeStartElement("catalog");
        writeElement(w, "catalog_name", config.catalogName());
        w.writeEndElement();
        w.writeEndElement();
    }

    private static void emitSchemata(XMLStreamWriter w, Schema schema, JooqXmlConfig config) throws XMLStreamException {
        var schemaNames = resolveSchemaNames(schema, config);

        w.writeStartElement("schemata");
        for (var name : schemaNames) {
            w.writeStartElement("schema");
            writeElement(w, "catalog_name", config.catalogName());
            writeElement(w, "schema_name", name);
            w.writeEndElement();
        }

        w.writeEndElement();
    }

    private static void emitTables(XMLStreamWriter w, List<TableWithSchema> tables, JooqXmlConfig config) throws XMLStreamException {
        w.writeStartElement("tables");
        for (var tw : tables) {
            w.writeStartElement("table");
            writeElement(w, "table_catalog", config.catalogName());
            writeElement(w, "table_schema", tw.schema());
            writeElement(w,
                         "table_name",
                         tw.table().name());
            writeElement(w, "table_type", "BASE TABLE");
            if (config.emitComments()) {
                tw.table().comment().onPresent(c -> writeElementUnchecked(w, "comment", c));
            }

            w.writeEndElement();
        }

        w.writeEndElement();
    }

    private static void emitColumns(XMLStreamWriter w,
                                    List<TableWithSchema> tables,
                                    String defaultSchema,
                                    JooqXmlConfig config) throws XMLStreamException {
        w.writeStartElement("columns");
        for (var tw : tables) {
            var columns = tw.table().columns();

            for (int i = 0; i < columns.size(); i++) {
                emitColumn(w, tw, columns.get(i), i + 1, defaultSchema, config);
            }
        }

        w.writeEndElement();
    }

    private static void emitColumn(XMLStreamWriter w,
                                   TableWithSchema tw,
                                   Column col,
                                   int ordinal,
                                   String defaultSchema,
                                   JooqXmlConfig config) throws XMLStreamException {
        var typeMapping = JooqTypeMapper.map(col.type(), defaultSchema);

        w.writeStartElement("column");
        writeElement(w, "table_catalog", config.catalogName());
        writeElement(w, "table_schema", tw.schema());
        writeElement(w,
                     "table_name",
                     tw.table().name());
        writeElement(w, "column_name", col.name());
        writeElement(w, "data_type", typeMapping.dataType());
        typeMapping.characterMaximumLength().onPresent(v -> writeElementUnchecked(w,
                                                                                  "character_maximum_length",
                                                                                  String.valueOf(v)));
        typeMapping.numericPrecision().onPresent(v -> writeElementUnchecked(w, "numeric_precision", String.valueOf(v)));
        typeMapping.numericScale().onPresent(v -> writeElementUnchecked(w, "numeric_scale", String.valueOf(v)));
        typeMapping.datetimePrecision().onPresent(v -> writeElementUnchecked(w, "datetime_precision", String.valueOf(v)));
        writeElement(w, "ordinal_position", String.valueOf(ordinal));
        col.defaultExpr().onPresent(d -> writeElementUnchecked(w, "column_default", d));
        writeElement(w,
                     "is_nullable",
                     col.nullable()
                     ? "YES"
                     : "NO");
        writeElement(w, "udt_schema", typeMapping.udtSchema());
        writeElement(w, "udt_name", typeMapping.udtName());
        col.identity().onPresent(id -> emitIdentity(w, id));
        col.generatedExpr().onPresent(expr -> emitGeneratedExpr(w, expr));
        if (config.emitComments()) {
            col.comment().onPresent(c -> writeElementUnchecked(w, "comment", c));
        }

        w.writeEndElement();
    }

    private static void emitConstraints(XMLStreamWriter w, List<TableWithSchema> tables, JooqXmlConfig config) throws XMLStreamException {
        var constraintCounter = new AtomicInteger(0);

        w.writeStartElement("table_constraints");
        for (var tw : tables) {
            for (var constraint : sortConstraints(tw.table().constraints(),
                                                  config)) {
                emitTableConstraint(w, tw, constraint, constraintCounter, config);
            }
        }

        w.writeEndElement();
        w.writeStartElement("key_column_usages");
        for (var tw : tables) {
            for (var constraint : sortConstraints(tw.table().constraints(),
                                                  config)) {
                emitKeyColumnUsages(w, tw, constraint, config);
            }
        }

        w.writeEndElement();
        w.writeStartElement("referential_constraints");
        for (var tw : tables) {
            for (var constraint : sortConstraints(tw.table().constraints(),
                                                  config)) {
                if (constraint instanceof ForeignKey fk) {
                    emitReferentialConstraint(w, tw, fk, config);
                }
            }
        }

        w.writeEndElement();
        if (config.emitCheckConstraints()) {
            w.writeStartElement("check_constraints");
            for (var tw : tables) {
                for (var constraint : sortConstraints(tw.table().constraints(),
                                                      config)) {
                    if (constraint instanceof Check check) {
                        emitCheckConstraint(w, tw, check, config);
                    }
                }
            }

            w.writeEndElement();
        }
    }

    private static List<Constraint> sortConstraints(List<Constraint> constraints, JooqXmlConfig config) {
        if (!config.sortElements()) return constraints;

        return constraints.stream()
                          .sorted(Comparator.comparing(c -> c.name()
                                                             .or("")))
                          .toList();
    }

    private static void emitTableConstraint(XMLStreamWriter w,
                                            TableWithSchema tw,
                                            Constraint constraint,
                                            AtomicInteger counter,
                                            JooqXmlConfig config) throws XMLStreamException {
        var type = constraintType(constraint);

        if (type.isEmpty()) return;

        var name = constraintName(constraint,
                                  tw.table().name(),
                                  counter);

        w.writeStartElement("table_constraint");
        writeElement(w, "constraint_catalog", config.catalogName());
        writeElement(w, "constraint_schema", tw.schema());
        writeElement(w, "constraint_name", name);
        writeElement(w, "table_catalog", config.catalogName());
        writeElement(w, "table_schema", tw.schema());
        writeElement(w,
                     "table_name",
                     tw.table().name());
        type.onPresent(t -> writeElementUnchecked(w, "constraint_type", t));
        writeElement(w, "enforced", "YES");
        w.writeEndElement();
    }

    private static void emitKeyColumnUsages(XMLStreamWriter w,
                                            TableWithSchema tw,
                                            Constraint constraint,
                                            JooqXmlConfig config) throws XMLStreamException {
        var columns = switch (constraint) {
            case PrimaryKey pk -> pk.columns();
            case ForeignKey fk -> fk.columns();
            case Unique u -> u.columns();
            default -> List.<String> of();
        };

        if (columns.isEmpty()) return;

        var constraintName = constraint.name().or(tw.table().name() + "_constraint");

        for (int i = 0; i < columns.size(); i++) {
            w.writeStartElement("key_column_usage");
            writeElement(w, "constraint_catalog", config.catalogName());
            writeElement(w, "constraint_schema", tw.schema());
            writeElement(w, "constraint_name", constraintName);
            writeElement(w, "table_catalog", config.catalogName());
            writeElement(w, "table_schema", tw.schema());
            writeElement(w,
                         "table_name",
                         tw.table().name());
            writeElement(w, "column_name", columns.get(i));
            writeElement(w, "ordinal_position", String.valueOf(i + 1));
            w.writeEndElement();
        }
    }

    private static void emitReferentialConstraint(XMLStreamWriter w,
                                                  TableWithSchema tw,
                                                  ForeignKey fk,
                                                  JooqXmlConfig config) throws XMLStreamException {
        var constraintName = fk.name().or(tw.table().name() + "_" + String.join("_", fk.columns()) + "_fkey");
        var refTableName = fk.refTable().contains(".")
                           ? fk.refTable().substring(fk.refTable().lastIndexOf('.') + 1)
                           : fk.refTable();
        var refSchema = fk.refTable().contains(".")
                        ? fk.refTable().substring(0,
                                                  fk.refTable().lastIndexOf('.'))
                        : tw.schema();
        var uniqueConstraintName = refTableName + "_pkey";

        w.writeStartElement("referential_constraint");
        writeElement(w, "constraint_catalog", config.catalogName());
        writeElement(w, "constraint_schema", tw.schema());
        writeElement(w, "constraint_name", constraintName);
        writeElement(w, "unique_constraint_catalog", config.catalogName());
        writeElement(w, "unique_constraint_schema", refSchema);
        writeElement(w, "unique_constraint_name", uniqueConstraintName);
        writeElement(w, "update_rule", fkActionToString(fk.onUpdate()));
        writeElement(w, "delete_rule", fkActionToString(fk.onDelete()));
        w.writeEndElement();
    }

    private static void emitCheckConstraint(XMLStreamWriter w, TableWithSchema tw, Check check, JooqXmlConfig config) throws XMLStreamException {
        var constraintName = check.name().or(tw.table().name() + "_check");

        w.writeStartElement("check_constraint");
        writeElement(w, "constraint_catalog", config.catalogName());
        writeElement(w, "constraint_schema", tw.schema());
        writeElement(w, "constraint_name", constraintName);
        writeElement(w, "check_clause", check.expression());
        w.writeEndElement();
    }

    private static void emitSequences(XMLStreamWriter w, Schema schema, JooqXmlConfig config) throws XMLStreamException {
        var sequences = schema.sequences().values().stream().filter(s -> isIncluded(s.schema(), config)).toList();

        if (config.sortElements()) {
            sequences = sequences.stream().sorted(Comparator.comparing(Sequence::name)).toList();
        }

        w.writeStartElement("sequences");
        for (var seq : sequences) {
            w.writeStartElement("sequence");
            writeElement(w, "sequence_catalog", config.catalogName());
            writeElement(w,
                         "sequence_schema",
                         seq.schema().isEmpty()
                         ? config.defaultSchemaName()
                         : seq.schema());
            writeElement(w, "sequence_name", seq.name());
            seq.dataType().onPresent(dt -> writeElementUnchecked(w, "data_type", dt));
            seq.startValue().onPresent(v -> writeElementUnchecked(w, "start_value", String.valueOf(v)));
            seq.increment().onPresent(v -> writeElementUnchecked(w, "increment", String.valueOf(v)));
            seq.minValue().onPresent(v -> writeElementUnchecked(w, "minimum_value", String.valueOf(v)));
            seq.maxValue().onPresent(v -> writeElementUnchecked(w, "maximum_value", String.valueOf(v)));
            writeElement(w,
                         "cycle_option",
                         seq.cycle()
                         ? "YES"
                         : "NO");
            seq.cache().onPresent(v -> writeElementUnchecked(w, "cache", String.valueOf(v)));
            w.writeEndElement();
        }

        w.writeEndElement();
    }

    private static void emitIndexes(XMLStreamWriter w, List<TableWithSchema> tables, JooqXmlConfig config) throws XMLStreamException {
        w.writeStartElement("indexes");
        for (var tw : tables) {
            var indexes = tw.table().indexes();

            if (config.sortElements()) {
                indexes = indexes.stream().sorted(Comparator.comparing(Index::name)).toList();
            }

            for (var idx : indexes) {
                w.writeStartElement("index");
                writeElement(w, "index_catalog", config.catalogName());
                writeElement(w, "index_schema", tw.schema());
                writeElement(w, "index_name", idx.name());
                writeElement(w, "table_catalog", config.catalogName());
                writeElement(w, "table_schema", tw.schema());
                writeElement(w,
                             "table_name",
                             tw.table().name());
                writeElement(w,
                             "is_unique",
                             idx.unique()
                             ? "YES"
                             : "NO");
                w.writeEndElement();
            }
        }

        w.writeEndElement();
        w.writeStartElement("index_column_usages");
        for (var tw : tables) {
            for (var idx : tw.table().indexes()) {
                for (int i = 0; i < idx.elements().size(); i++) {
                    w.writeStartElement("index_column_usage");
                    writeElement(w, "index_catalog", config.catalogName());
                    writeElement(w, "index_schema", tw.schema());
                    writeElement(w, "index_name", idx.name());
                    writeElement(w, "table_catalog", config.catalogName());
                    writeElement(w, "table_schema", tw.schema());
                    writeElement(w,
                                 "table_name",
                                 tw.table().name());
                    writeElement(w,
                                 "column_name",
                                 idx.elements().get(i).expression());
                    writeElement(w, "ordinal_position", String.valueOf(i + 1));
                    w.writeEndElement();
                }
            }
        }

        w.writeEndElement();
    }

    private static void emitEnums(XMLStreamWriter w, Schema schema, String defaultSchema) throws XMLStreamException {
        var enums = schema.enumTypes().values().stream().sorted(Comparator.comparing(PgType.EnumType::name)).toList();

        if (enums.isEmpty()) return;

        w.writeStartElement("enums");
        for (var e : enums) {
            var enumSchema = e.schema().isEmpty()
                             ? defaultSchema
                             : e.schema();

            for (var value : e.values()) {
                w.writeStartElement("enum");
                writeElement(w, "enum_schema", enumSchema);
                writeElement(w, "enum_name", e.name());
                writeElement(w, "enum_value", value);
                w.writeEndElement();
            }
        }

        w.writeEndElement();
    }

    private static void emitDomains(XMLStreamWriter w, Schema schema, String defaultSchema, JooqXmlConfig config) throws XMLStreamException {
        var domains = schema.domainTypes().values().stream().filter(d -> isIncluded(d.schema(), config)).sorted(Comparator.comparing(PgType.DomainType::name)).toList();

        if (domains.isEmpty()) return;

        w.writeStartElement("domains");
        for (var domain : domains) {
            var domainSchema = domain.schema().isEmpty()
                               ? defaultSchema
                               : domain.schema();
            var baseMapping = JooqTypeMapper.map(domain.baseType(), defaultSchema);

            w.writeStartElement("domain");
            writeElement(w, "domain_catalog", config.catalogName());
            writeElement(w, "domain_schema", domainSchema);
            writeElement(w, "domain_name", domain.name());
            writeElement(w, "data_type", baseMapping.dataType());
            baseMapping.characterMaximumLength().onPresent(v -> writeElementUnchecked(w,
                                                                                      "character_maximum_length",
                                                                                      String.valueOf(v)));
            baseMapping.numericPrecision().onPresent(v -> writeElementUnchecked(w,
                                                                                "numeric_precision",
                                                                                String.valueOf(v)));
            baseMapping.numericScale().onPresent(v -> writeElementUnchecked(w, "numeric_scale", String.valueOf(v)));
            domain.checkExpression().onPresent(expr -> writeElementUnchecked(w, "domain_default", expr));
            w.writeEndElement();
        }

        w.writeEndElement();
    }

    private static List<TableWithSchema> collectTables(Schema schema, JooqXmlConfig config) {
        var tables = schema.tables().values().stream().filter(t -> isIncluded(t.schema(), config)).map(t -> new TableWithSchema(t,
                                                                                                                                t.schema()
                                                                                                                                 .isEmpty()
                                                                                                                                ? config.defaultSchemaName()
                                                                                                                                : t.schema()));

        if (config.sortElements()) {
            tables = tables.sorted(Comparator.comparing(TableWithSchema::schema).thenComparing(tw -> tw.table()
                                                                                                       .name()));
        }

        return tables.toList();
    }

    private static List<String> resolveSchemaNames(Schema schema, JooqXmlConfig config) {
        var names = new TreeSet<String>();

        names.add(config.defaultSchemaName());
        schema.schemas().stream().filter(s -> isIncluded(s, config)).forEach(names::add);
        schema.tables().values().stream().map(Table::schema).filter(s -> !s.isEmpty()).filter(s -> isIncluded(s, config)).forEach(names::add);

        return List.copyOf(names);
    }

    private static boolean isIncluded(String schemaName, JooqXmlConfig config) {
        if (config.includedSchemas().isEmpty()) return true;

        if (schemaName.isEmpty()) return config.includedSchemas()
                                               .contains(config.defaultSchemaName());

        return config.includedSchemas()
                     .contains(schemaName);
    }

    private static String constraintName(Constraint constraint, String tableName, AtomicInteger counter) {
        return constraint.name()
                         .or(() -> tableName + "_constraint_" + counter.incrementAndGet());
    }

    private static void emitIdentity(XMLStreamWriter w, Column.IdentitySpec id) {
        writeElementUnchecked(w, "is_identity", "YES");
        writeElementUnchecked(w,
                              "identity_generation",
                              id.kind() == Column.IdentityKind.ALWAYS
                              ? "ALWAYS"
                              : "BY DEFAULT");
    }

    private static void emitGeneratedExpr(XMLStreamWriter w, String expr) {
        writeElementUnchecked(w, "is_generated", "ALWAYS");
        writeElementUnchecked(w, "generation_expression", expr);
    }

    private static Option<String> constraintType(Constraint constraint) {
        return switch (constraint) {
            case PrimaryKey _ -> Option.some("PRIMARY KEY");
            case ForeignKey _ -> Option.some("FOREIGN KEY");
            case Unique _ -> Option.some("UNIQUE");
            case Check _ -> Option.some("CHECK");
            case Exclusion _ -> Option.none();
        };
    }

    private static String fkActionToString(Constraint.FkAction action) {
        return switch (action) {
            case NO_ACTION -> "NO ACTION";
            case RESTRICT -> "RESTRICT";
            case CASCADE -> "CASCADE";
            case SET_NULL -> "SET NULL";
            case SET_DEFAULT -> "SET DEFAULT";
        };
    }

    private static void writeElement(XMLStreamWriter w, String name, String value) throws XMLStreamException {
        w.writeStartElement(name);
        w.writeCharacters(value);
        w.writeEndElement();
    }

    private static void writeElementUnchecked(XMLStreamWriter w, String name, String value) {
        try {
            writeElement(w, name, value);
        } catch (XMLStreamException e) {
            throw new RuntimeException(e);
        }
    }

    private record TableWithSchema(Table table, String schema) {}
}
