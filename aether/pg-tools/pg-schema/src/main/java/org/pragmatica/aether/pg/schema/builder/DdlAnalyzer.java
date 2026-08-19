// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.schema.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.pg.parser.ast.common.DataTypeName;
import org.pragmatica.aether.pg.parser.transform.CstExtractor;
import org.pragmatica.aether.pg.parser.transform.CstNavigator;
import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.model.*;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;


public final class DdlAnalyzer {
    private DdlAnalyzer() {}

    public static Result<List<SchemaEvent>> analyze(CstNode cst) {
        var nav = CstNavigator.wrap(cst);

        if (nav.isEmpty()) {
            return Result.success(List.of());
        }

        return analyzeStatement(nav.unwrap());
    }

    private static Result<List<SchemaEvent>> analyzeStatement(CstNavigator nav) {
        var createTable = nav.findAll("CreateTableStmt");
        var alterTable = nav.findAll("AlterTableStmt");
        var dropTable = nav.findAll("DropTableStmt");
        var createIndex = nav.findAll("CreateIndexStmt");
        var dropIndex = nav.findAll("DropIndexStmt");
        var createSequence = nav.findAll("CreateSequenceStmt");
        var dropSequence = nav.findAll("DropSequenceStmt");
        var createType = nav.findAll("CreateTypeStmt");
        var alterType = nav.findAll("AlterTypeStmt");
        var dropType = nav.findAll("DropTypeStmt");
        var createSchema = nav.findAll("CreateSchemaStmt");
        var createExtension = nav.findAll("CreateExtensionStmt");
        var commentStmt = nav.findAll("CommentStatement");
        var events = new ArrayList<SchemaEvent>();

        for (var stmt : createTable) {
            analyzeCreateTable(stmt, events);
        }

        for (var stmt : alterTable) {
            analyzeAlterTable(stmt, events);
        }

        for (var stmt : dropTable) {
            analyzeDropTable(stmt, events);
        }

        for (var stmt : createIndex) {
            analyzeCreateIndex(stmt, nav, events);
        }

        for (var stmt : dropIndex) {
            analyzeDropIndex(stmt, events);
        }

        for (var stmt : createSequence) {
            analyzeCreateSequence(stmt, events);
        }

        for (var stmt : dropSequence) {
            analyzeDropSequence(stmt, events);
        }

        for (var stmt : createType) {
            analyzeCreateType(stmt, events);
        }

        for (var stmt : alterType) {
            analyzeAlterType(stmt, events);
        }

        for (var stmt : dropType) {
            analyzeDropType(stmt, events);
        }

        for (var stmt : createSchema) {
            analyzeCreateSchema(stmt, events);
        }

        for (var stmt : createExtension) {
            analyzeCreateExtension(stmt, events);
        }

        for (var stmt : commentStmt) {
            analyzeComment(stmt, events);
        }

        return Result.success(events);
    }

    private static void analyzeCreateTable(CstNavigator stmt, List<SchemaEvent> events) {
        var qname = stmt.child("QualifiedName").map(CstExtractor::extractQualifiedName);

        if (qname.isEmpty()) return;

        var name = qname.unwrap().name().normalized();
        var schema = qname.unwrap().schema().map(id -> id.normalized()).or("");
        var columns = new ArrayList<Column>();
        var constraints = new ArrayList<Constraint>();
        var tableElements = stmt.findAll("TableElement");

        // 0.7.x interposes the concrete element rule: `TableElement -> ColumnDef -> ColId DataType`,
        // where 0.6.0 inlined it as `TableElement -> ColId DataType`. Testing has("ColId") on the
        // TableElement therefore matched nothing and NO column was ever extracted — which is why
        // every downstream table/lint/validator assertion failed rather than just a few.
        for (var rawElem : tableElements) {
            var columnDef = rawElem.child("ColumnDef");

            if (columnDef.isPresent()) {
                extractColumn(columnDef.unwrap(), columns, constraints);
                continue;
            }

            var tblConstraint = rawElem.child("TableConstraint");

            if (tblConstraint.isPresent()) {
                extractTableConstraint(tblConstraint.unwrap(), constraints);
                continue;
            }

            var elem = rawElem;

            if (elem.has("ColId") && elem.has("DataType")) {
                extractColumn(elem, columns, constraints);
            } else if (elem.has("TableConstraintElem") || elem.has("ConstraintKW")) {
                extractTableConstraint(elem, constraints);
            }
        }

        Option<Table.PartitionBy> partitioning = Option.empty();
        var partClause = stmt.findAll("PartitionByClause");

        if (!partClause.isEmpty()) {
            partitioning = extractPartitioning(partClause.getFirst());
        }

        events.add(new SchemaEvent.TableCreated(stmt.span(), name, schema, columns, constraints, partitioning));
    }

    private static void extractColumn(CstNavigator elem, List<Column> columns, List<Constraint> constraints) {
        // `ColumnDef <- ColId DataType ColConstraint*`, so the name is the FIRST leaf. It cannot be
        // looked up as "ColId": under identifier fallback `name` arrives as an anonymous Terminal
        // and `public` as PublicKW, so a name-based lookup drops those columns entirely.
        var leading = CstExtractor.leadingIdentifier(elem);

        if (leading.isEmpty()) return;

        var colName = leading.unwrap().normalized();
        var dataTypes = elem.findAll("DataType");
        var pgType = !dataTypes.isEmpty()
                     ? resolveType(CstExtractor.extractDataType(dataTypes.getFirst()))
                     : new PgType.BuiltinType("text", PgType.TypeCategory.STRING);
        boolean nullable = true;
        Option<String> defaultExpr = Option.empty();
        Option<String> generatedExpr = Option.empty();
        Option<Column.IdentitySpec> identity = Option.empty();
        var colConstraints = elem.findAll("ColConstraint");

        // Dispatch on the constraint rules the grammar declares rather than on keyword presence:
        // under peglib 0.7.x kind unification a literal is named after whichever rule claims it, so
        // the NULL of `NOT NULL` arrives as `NullConstraint` and findAll("NullKW") sees nothing.
        // `GENERATED ALWAYS AS IDENTITY` parses as GeneratedClause wrapping an IdentitySpec, while
        // `GENERATED BY DEFAULT AS IDENTITY` parses as IdentityClause — so identity is detected by
        // IdentitySpec/IdentityClause, and GeneratedClause without one is the STORED form.
        for (var cc : colConstraints) {
            var isIdentity = !cc.findAll("IdentitySpec").isEmpty() || !cc.findAll("IdentityClause").isEmpty();

            if (isIdentity) {
                identity = Option.present(extractIdentitySpec(cc));
                nullable = false;
            } else if (!cc.findAll("GeneratedClause").isEmpty()) {
                generatedExpr = Option.present(extractExprText(cc));
                nullable = false;
            } else if (!cc.findAll("NotNullConstraint").isEmpty()) {
                nullable = false;
            } else if (!cc.findAll("NullConstraint").isEmpty()) {
                nullable = true;
            } else if (!cc.findAll("DefaultClause").isEmpty()) {
                defaultExpr = Option.present(extractExprText(cc));
            } else if (!cc.findAll("PrimaryKeyColConstraint").isEmpty()) {
                constraints.add(new Constraint.PrimaryKey(Option.empty(), List.of(colName)));
                nullable = false;
            } else if (!cc.findAll("UniqueColConstraint").isEmpty()) {
                constraints.add(new Constraint.Unique(Option.empty(), List.of(colName)));
            } else if (!cc.findAll("ReferencesClause").isEmpty()) {
                extractInlineReference(cc, colName, constraints);
            }
        }

        var col = Column.column(colName, pgType, nullable);

        if (defaultExpr.isPresent()) {
            col = col.withDefault(defaultExpr.unwrap());
        }

        if (generatedExpr.isPresent() || identity.isPresent()) {
            col = new Column(col.name(),
                             col.type(),
                             col.nullable(),
                             col.defaultExpr(),
                             generatedExpr,
                             identity,
                             col.comment());
        }

        columns.add(col);
    }

    private static Column.IdentitySpec extractIdentitySpec(CstNavigator cc) {
        boolean byDefault = !cc.findAll("ByKW").isEmpty() && !cc.findAll("DefaultKW").isEmpty();

        return new Column.IdentitySpec(byDefault
                                       ? Column.IdentityKind.BY_DEFAULT
                                       : Column.IdentityKind.ALWAYS);
    }

    private static void extractInlineReference(CstNavigator refClause, String colName, List<Constraint> constraints) {
        var refQname = refClause.child("QualifiedName");

        if (refQname.isEmpty()) return;

        var refTableName = CstExtractor.extractQualifiedName(refQname.unwrap()).normalized();
        var refColumns = refClause.child("ColumnList")
                                  .map(CstExtractor::extractColumnList)
                                  .map(ids -> ids.stream()
                                                 .map(id -> id.normalized())
                                                 .toList())
                                  .or(List.of());
        var onUpdate = extractFkAction(refClause, "UpdateKW");
        var onDelete = extractFkAction(refClause, "DeleteKW");

        constraints.add(new Constraint.ForeignKey(Option.empty(),
                                                  List.of(colName),
                                                  refTableName,
                                                  refColumns,
                                                  onUpdate,
                                                  onDelete));
    }

    private static void extractTableConstraint(CstNavigator tblConstraint, List<Constraint> constraints) {
        // `ConstraintName <- ConstraintKW ^ ColId` — take the trailing leaf, not a "ColId" lookup.
        var constraintName = tblConstraint.child("ConstraintName")
                                          .flatMap(CstExtractor::identifierBeforeNested)
                                          .map(id -> id.normalized());
        var e = constraintBody(tblConstraint.child("TableConstraintElem").or(tblConstraint));

        // Dispatch on the constraint rule name returned by constraintBody rather than on keyword
        // presence, for the same reason as everywhere else: a keyword's kind depends on which rule
        // claimed its literal.
        if (e.rule().equals("PrimaryKeyTblConstraint") || e.has("PrimaryKW")) {
            var cols = extractConstraintColumns(e);

            constraints.add(new Constraint.PrimaryKey(constraintName, cols));
        } else if (e.rule().equals("UniqueTblConstraint") || e.has("UniqueKW")) {
            var cols = extractConstraintColumns(e);

            constraints.add(new Constraint.Unique(constraintName, cols));
        } else if (e.rule().equals("CheckTblConstraint") || e.has("CheckKW")) {
            constraints.add(new Constraint.Check(constraintName, extractExprText(e)));
        } else if (e.rule().equals("ForeignKeyTblConstraint") || e.has("ForeignKW")) {
            extractTableForeignKey(e, constraintName, constraints);
        } else if (e.rule().equals("ExcludeTblConstraint") || e.has("ExcludeKW")) {
            constraints.add(new Constraint.Exclusion(constraintName, "gist", extractExprText(e)));
        }
    }

    /// peglib 0.7.x materialises the specific constraint rule — `PrimaryKeyTblConstraint`,
    /// `ForeignKeyTblConstraint`, … — beneath `TableConstraintElem`, where 0.6.0 inlined its
    /// contents directly (`TableConstraintElem -> Token PrimaryKW`). Descending here keeps every
    /// direct-child lookup below working; without it `has("PrimaryKW")` matches the wrapper's name
    /// instead of the keyword and every table constraint is silently dropped.
    private static CstNavigator constraintBody(CstNavigator elem) {
        for (var rule : List.of("PrimaryKeyTblConstraint",
                                "UniqueTblConstraint",
                                "CheckTblConstraint",
                                "ForeignKeyTblConstraint",
                                "ExcludeTblConstraint")) {
            var body = elem.child(rule);

            if (body.isPresent()) {
                return body.unwrap();
            }
        }

        return elem;
    }

    private static void extractTableForeignKey(CstNavigator elem, Option<String> name, List<Constraint> constraints) {
        var columnLists = elem.findAll("ColumnList");
        var fkColumns = columnLists.size() > 0
                        ? CstExtractor.extractColumnList(columnLists.getFirst())
                                      .stream()
                                      .map(id -> id.normalized())
                                      .toList()
                        : List.<String> of();
        var refQname = elem.child("QualifiedName");
        var refTableName = refQname.isPresent()
                           ? CstExtractor.extractQualifiedName(refQname.unwrap()).normalized()
                           : "";
        var refColumns = columnLists.size() > 1
                         ? CstExtractor.extractColumnList(columnLists.get(1))
                                       .stream()
                                       .map(id -> id.normalized())
                                       .toList()
                         : List.<String> of();
        var onUpdate = extractFkAction(elem, "UpdateKW");
        var onDelete = extractFkAction(elem, "DeleteKW");

        constraints.add(new Constraint.ForeignKey(name, fkColumns, refTableName, refColumns, onUpdate, onDelete));
    }

    private static void analyzeAlterTable(CstNavigator stmt, List<SchemaEvent> events) {
        var qname = stmt.child("QualifiedName").map(CstExtractor::extractQualifiedName);

        if (qname.isEmpty()) return;

        var tableName = qname.unwrap().normalized();
        var span = stmt.span();

        var renameAction = findFirst(stmt, "RenameAction");

        if (renameAction.isPresent()) {
            // `RenameAction <- RenameKW (ColumnKW ColId ToKW ColId / ToKW ColId)`: the names are the
            // leaves that are not the fixed keywords. They cannot be found as "ColId" — a column or
            // table called `name` arrives as an anonymous Terminal. See CstExtractor#leadingIdentifier.
            var names = CstExtractor.leafIdentifiers(renameAction.unwrap(), Set.of("rename", "column", "to"));

            if (names.size() >= 2) {
                events.add(new SchemaEvent.ColumnRenamed(span,
                                                         tableName,
                                                         names.get(0).normalized(),
                                                         names.get(1).normalized()));
            } else if (names.size() == 1) {
                events.add(new SchemaEvent.TableRenamed(span, tableName, names.getFirst().normalized()));
            }

            return;
        }

        var setSchema = stmt.child("SetSchemaAction");

        if (setSchema.isPresent()) return;

        if (stmt.has("AttachKW") || stmt.has("DetachKW")) return;

        var actions = stmt.findAll("AlterTableAction");

        if (actions.isEmpty()) {
            analyzeAlterTableAction(stmt, tableName, span, events);
        } else {
            for (var action : actions) {
                analyzeAlterTableAction(action, tableName, span, events);
            }
        }
    }

    private static void analyzeAlterTableAction(CstNavigator rawAction,
                                                String tableName,
                                                SourceSpan span,
                                                List<SchemaEvent> events) {
        // Same 0.6.0 -> 0.7.x shape change as `constraintBody`: the concrete action rule
        // (`AddColumnAction`, `AlterColumnAction`, …) is now a real node under `AlterTableAction`
        // rather than inlined, so every `has("AddKW")` / `has("DropKW")` test below looks one level
        // too high and silently matches nothing.
        var action = rawAction.rule().equals("AlterTableAction")
                     ? rawAction.firstChild().or(rawAction)
                     : rawAction;

        if (action.has("AddKW") && (action.has("ColumnKW") || !action.findAll("DataType").isEmpty())) {
            var dataTypes = action.findAll("DataType");

            // No findAll("ColId") gate: the added column's name may be an anonymous Terminal, and
            // extractColumn resolves it positionally anyway.
            if (!dataTypes.isEmpty()) {
                var columns = new ArrayList<Column>();
                var constraints = new ArrayList<Constraint>();

                // Hand extractColumn the ColumnDef, not the action: the action's first leaf is
                // the ADD keyword, and extractColumn now reads the name positionally.
                extractColumn(action.child("ColumnDef").or(action), columns, constraints);
                for (var col : columns) events.add(new SchemaEvent.ColumnAdded(span, tableName, col));

                for (var c : constraints) events.add(new SchemaEvent.ConstraintAdded(span, tableName, c));
            }

            return;
        }

        if (action.has("AddKW") && (action.has("TableConstraint") || action.has("ConstraintKW") || !action.findAll("UniqueKW")
                                                                                                          .isEmpty() || !action.findAll("PrimaryKW")
                                                                                                                               .isEmpty() || !action.findAll("ForeignKW")
                                                                                                                                                    .isEmpty() || !action.findAll("CheckKW")
                                                                                                                                                                         .isEmpty())) {
            var tblConstraint = action.child("TableConstraint");
            var constraints = new ArrayList<Constraint>();

            extractTableConstraint(tblConstraint.or(action), constraints);
            for (var c : constraints) events.add(new SchemaEvent.ConstraintAdded(span, tableName, c));

            return;
        }

        if (action.has("DropKW") && (action.has("ColumnKW") || (!action.has("ConstraintKW") && !action.findAll("DropColumnAction")
                                                                                                      .isEmpty()))) {
            var colIds = action.findAll("ColId");

            if (!colIds.isEmpty()) {
                events.add(new SchemaEvent.ColumnDropped(span,
                                                         tableName,
                                                         CstExtractor.extractIdentifier(colIds.getFirst()).normalized()));
            }

            return;
        }

        if (action.has("DropKW") && action.has("ConstraintKW")) {
            var colIds = action.findAll("ColId");

            if (!colIds.isEmpty()) {
                events.add(new SchemaEvent.ConstraintDropped(span,
                                                             tableName,
                                                             CstExtractor.extractIdentifier(colIds.getFirst()).normalized()));
            }

            return;
        }

        if (action.has("AlterKW") || !action.findAll("AlterColumnCmd").isEmpty()) {
            // `AlterColumnAction <- AlterKW ColumnKW? ColId AlterColumnCmd` — take the name by
            // position. findAll("ColId") misses it whenever the column spells a keyword or an
            // inline literal (`name` arrives as an anonymous Terminal), and the command is then
            // silently never analysed.
            var colName = CstExtractor.identifierBeforeNested(action);

            if (colName.isPresent()) {
                analyzeAlterColumnCmd(action, tableName, colName.unwrap().normalized(), span, events);
            }
        }
    }

    private static Option<CstNavigator> findFirst(CstNavigator nav, String ruleName) {
        var found = nav.findAll(ruleName);

        return found.isEmpty()
               ? Option.empty()
               : Option.present(found.getFirst());
    }

    private static void analyzeAlterColumnCmd(CstNavigator cmd,
                                              String tableName,
                                              String colName,
                                              SourceSpan span,
                                              List<SchemaEvent> events) {
        // Dispatch on the command rules the grammar declares, not on loose keyword presence.
        // Under peglib 0.7.x kind unification a literal is named after whichever rule claims it, so
        // the NULL in `SET NOT NULL` arrives as `NullConstraint`, not `NullKW`, and every
        // findAll("NullKW") test silently returned nothing. The command nodes are unambiguous.
        if (!cmd.findAll("SetDataTypeCmd").isEmpty()) {
            var dataTypes = cmd.findAll("DataType");

            if (!dataTypes.isEmpty()) {
                var pgType = resolveType(CstExtractor.extractDataType(dataTypes.getFirst()));

                events.add(new SchemaEvent.ColumnTypeChanged(span, tableName, colName, pgType));
            }
        } else if (!cmd.findAll("SetNotNullCmd").isEmpty()) {
            events.add(new SchemaEvent.ColumnNullabilityChanged(span, tableName, colName, false));
        } else if (!cmd.findAll("DropNotNullCmd").isEmpty()) {
            events.add(new SchemaEvent.ColumnNullabilityChanged(span, tableName, colName, true));
        } else if (!cmd.findAll("SetDefaultCmd").isEmpty()) {
            events.add(new SchemaEvent.ColumnDefaultChanged(span,
                                                            tableName,
                                                            colName,
                                                            Option.present(extractExprText(cmd))));
        } else if (!cmd.findAll("DropDefaultCmd").isEmpty()) {
            events.add(new SchemaEvent.ColumnDefaultChanged(span, tableName, colName, Option.empty()));
        }
    }

    private static void analyzeDropTable(CstNavigator stmt, List<SchemaEvent> events) {
        var qnames = stmt.findAll("QualifiedName");

        for (var qnav : qnames) {
            var qname = CstExtractor.extractQualifiedName(qnav);

            events.add(new SchemaEvent.TableDropped(stmt.span(), qname.normalized()));
        }
    }

    private static void analyzeCreateIndex(CstNavigator stmt, CstNavigator root, List<SchemaEvent> events) {
        // UNIQUE lexes as `UniqueColConstraint` here — that rule spells the same literal and
        // claimed the kind — so the keyword lookup alone reports every unique index as non-unique.
        boolean unique = !root.findAll("UniqueKW").isEmpty() || CstExtractor.hasLeafText(stmt, "unique");
        boolean concurrent = !root.findAll("ConcurrentlyKW").isEmpty();
        var colIds = stmt.findAll("ColId");
        var qnames = stmt.findAll("QualifiedName");
        String indexName = "";
        String tableName = "";

        if (!qnames.isEmpty()) {
            tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();
        }

        if (!colIds.isEmpty() && !qnames.isEmpty()) {
            var firstColId = colIds.getFirst();

            if (firstColId.span().start().offset() < qnames.getFirst().span().start().offset()) {
                indexName = CstExtractor.extractIdentifier(firstColId).normalized();
                if (qnames.size() > 1) {
                    tableName = CstExtractor.extractQualifiedName(qnames.get(1)).normalized();
                }
            }
        }

        var methodText = stmt.tokenText("IndexMethod").or("btree").toLowerCase();
        var method = switch (methodText) {
            case "hash" -> Index.IndexMethod.HASH;
            case "gin" -> Index.IndexMethod.GIN;
            case "gist" -> Index.IndexMethod.GIST;
            case "brin" -> Index.IndexMethod.BRIN;
            case "spgist" -> Index.IndexMethod.SPGIST;
            default -> Index.IndexMethod.BTREE;
        };
        var indexElems = stmt.findAll("IndexElem");
        var elements = indexElems.stream().map(DdlAnalyzer::toIndexElement).toList();
        var whereClause = stmt.child("WhereClause");
        Option<String> whereExpr = whereClause.isPresent()
                                   ? Option.present(extractExprText(whereClause.unwrap()))
                                   : Option.empty();
        var includeClause = stmt.child("IncludeClause");
        var includeCols = includeClause.isPresent()
                          ? includeClause.flatMap(ic -> ic.child("ColumnList"))
                                         .map(CstExtractor::extractColumnList)
                                         .map(ids -> ids.stream()
                                                        .map(id -> id.normalized())
                                                        .toList())
                                         .or(List.of())
                          : List.<String> of();
        var index = new Index(indexName, tableName, elements, method, unique, concurrent, whereExpr, includeCols);

        events.add(new SchemaEvent.IndexCreated(stmt.span(), index));
    }

    private static void analyzeDropIndex(CstNavigator stmt, List<SchemaEvent> events) {
        var qnames = stmt.findAll("QualifiedName");

        for (var qnav : qnames) {
            events.add(new SchemaEvent.IndexDropped(stmt.span(),
                                                    CstExtractor.extractQualifiedName(qnav).normalized()));
        }
    }

    private static void analyzeCreateSequence(CstNavigator stmt, List<SchemaEvent> events) {
        var qname = stmt.child("QualifiedName").map(CstExtractor::extractQualifiedName);

        if (qname.isEmpty()) return;

        var name = qname.unwrap().name().normalized();
        var schema = qname.unwrap().schema().map(id -> id.normalized()).or("");

        events.add(new SchemaEvent.SequenceCreated(stmt.span(), Sequence.sequence(name, schema)));
    }

    private static void analyzeDropSequence(CstNavigator stmt, List<SchemaEvent> events) {
        var qnames = stmt.findAll("QualifiedName");

        for (var qnav : qnames) {
            events.add(new SchemaEvent.SequenceDropped(stmt.span(),
                                                       CstExtractor.extractQualifiedName(qnav).normalized()));
        }
    }

    private static void analyzeCreateType(CstNavigator stmt, List<SchemaEvent> events) {
        var qname = stmt.child("QualifiedName").map(CstExtractor::extractQualifiedName);

        if (qname.isEmpty()) return;

        var name = qname.unwrap().name().normalized();
        var schema = qname.unwrap().schema().map(id -> id.normalized()).or("");
        var enumLabels = stmt.findAll("EnumLabelList");

        if (!enumLabels.isEmpty()) {
            var stringLiterals = enumLabels.getFirst().findAll("StringLiteral");
            var values = stringLiterals.stream()
                                       .map(sl -> CstExtractor.stringLiteralText(sl)
                                                    .or(""))
                                       .filter(s -> !s.isEmpty())
                                       .toList();

            events.add(new SchemaEvent.TypeCreated(stmt.span(), new PgType.EnumType(name, schema, values)));

            return;
        }

        var compositeFields = stmt.findAll("CompositeField");

        if (!compositeFields.isEmpty()) {
            var fields = compositeFields.stream().map(DdlAnalyzer::toCompositeField).toList();

            events.add(new SchemaEvent.TypeCreated(stmt.span(), new PgType.CompositeType(name, schema, fields)));

            return;
        }

        events.add(new SchemaEvent.TypeCreated(stmt.span(), new PgType.CustomType(name, schema)));
    }

    private static void analyzeAlterType(CstNavigator stmt, List<SchemaEvent> events) {
        var qname = stmt.child("QualifiedName").map(CstExtractor::extractQualifiedName);

        if (qname.isEmpty()) return;

        var typeName = qname.unwrap().normalized();

        if (stmt.has("ValueKW")) {
            var stringLiterals = stmt.findAll("StringLiteral");

            if (!stringLiterals.isEmpty()) {
                var value = CstExtractor.stringLiteralText(stringLiterals.getFirst()).or("");
                Option<String> before = stmt.has("BeforeKW") && stringLiterals.size() > 1
                                        ? Option.present(CstExtractor.stringLiteralText(stringLiterals.get(1)).or(""))
                                        : Option.empty();
                Option<String> after = stmt.has("AfterKW") && stringLiterals.size() > 1
                                       ? Option.present(CstExtractor.stringLiteralText(stringLiterals.get(1)).or(""))
                                       : Option.empty();

                events.add(new SchemaEvent.EnumValueAdded(stmt.span(), typeName, value, before, after));
            }
        }
    }

    private static void analyzeDropType(CstNavigator stmt, List<SchemaEvent> events) {
        var qnames = stmt.findAll("QualifiedName");

        for (var qnav : qnames) {
            events.add(new SchemaEvent.TypeDropped(stmt.span(),
                                                   CstExtractor.extractQualifiedName(qnav).normalized()));
        }
    }

    private static void analyzeCreateSchema(CstNavigator stmt, List<SchemaEvent> events) {
        var colId = stmt.child("ColId");

        if (colId.isPresent()) {
            events.add(new SchemaEvent.SchemaCreated(stmt.span(),
                                                     CstExtractor.extractIdentifier(colId.unwrap()).normalized()));
        }
    }

    private static void analyzeCreateExtension(CstNavigator stmt, List<SchemaEvent> events) {
        var colId = stmt.child("ColId");

        if (colId.isPresent()) {
            events.add(new SchemaEvent.ExtensionCreated(stmt.span(),
                                                        CstExtractor.extractIdentifier(colId.unwrap()).normalized()));
        }
    }

    private static void analyzeComment(CstNavigator stmt, List<SchemaEvent> events) {
        var target = stmt.child("CommentTarget");

        if (target.isEmpty()) return;

        var t = target.unwrap();
        String targetType = "";
        String targetName = "";

        if (t.has("TableKW")) {
            targetType = "TABLE";
            var qn = t.child("QualifiedName");

            if (qn.isPresent()) targetName = CstExtractor.extractQualifiedName(qn.unwrap()).normalized();
        } else if (t.has("ColumnKW")) {
            targetType = "COLUMN";
            var qn = t.child("QualifiedName");

            if (qn.isPresent()) targetName = CstExtractor.extractQualifiedName(qn.unwrap()).normalized();
        }

        if (!targetType.isEmpty()) {
            var stringLit = stmt.findAll("StringLiteral");
            Option<String> comment = !stringLit.isEmpty()
                                     ? Option.present(CstExtractor.stringLiteralText(stringLit.getFirst()).or(""))
                                     : Option.empty();

            events.add(new SchemaEvent.CommentSet(stmt.span(), targetType, targetName, comment));
        }
    }

    private static Index.IndexElement toIndexElement(CstNavigator e) {
        var elemColId = e.child("ColId");
        var elemText = elemColId.isPresent()
                       ? CstExtractor.extractIdentifier(elemColId.unwrap()).normalized()
                       : extractExprText(e);

        return new Index.IndexElement(elemText, Option.empty(), Option.empty());
    }

    private static PgType.CompositeField toCompositeField(CstNavigator f) {
        var fieldName = f.child("ColId").map(CstExtractor::extractIdentifier).map(id -> id.normalized()).or("?");
        var fieldType = f.child("DataType")
                         .map(CstExtractor::extractDataType)
                         .map(DdlAnalyzer::resolveType)
                         .or(new PgType.BuiltinType("text", PgType.TypeCategory.STRING));

        return new PgType.CompositeField(fieldName, fieldType);
    }

    private static PgType resolveType(DataTypeName dt) {
        return BuiltinTypes.resolve(dt.baseName(), dt.modifiers(), dt.arrayDimensions());
    }

    private static List<String> extractConstraintColumns(CstNavigator constraint) {
        var colList = constraint.child("ColumnList");

        if (colList.isEmpty()) return List.of();

        return CstExtractor.extractColumnList(colList.unwrap())
                           .stream()
                           .map(id -> id.normalized())
                           .toList();
    }

    private static Constraint.FkAction extractFkAction(CstNavigator nav, String actionKeyword) {
        var actions = nav.findAll("FkAction");

        for (var action : actions) {
            if (action.has(actionKeyword)) {
                var actionType = action.child("FkActionType");

                if (actionType.isPresent()) {
                    if (actionType.unwrap().has("CascadeKW")) return Constraint.FkAction.CASCADE;

                    if (actionType.unwrap().has("RestrictKW")) return Constraint.FkAction.RESTRICT;

                    if (actionType.unwrap().has("SetKW") && actionType.unwrap().has("NullKW")) return Constraint.FkAction.SET_NULL;

                    if (actionType.unwrap().has("SetKW") && actionType.unwrap().has("DefaultKW")) return Constraint.FkAction.SET_DEFAULT;
                }
            }
        }

        return Constraint.FkAction.NO_ACTION;
    }

    private static String extractExprText(CstNavigator nav) {
        return "(expr)";
    }

    private static Option<Table.PartitionBy> extractPartitioning(CstNavigator partClause) {
        var strategy = partClause.child("PartitionStrategy");

        if (strategy.isEmpty()) return Option.empty();

        var strategyType = partitionStrategyOf(strategy.unwrap());
        var keys = partClause.findAll("ColId")
                             .stream()
                             .map(CstExtractor::extractIdentifier)
                             .map(id -> id.normalized())
                             .toList();

        return Option.present(new Table.PartitionBy(strategyType, keys));
    }

    private static Table.PartitionStrategy partitionStrategyOf(CstNavigator strategy) {
        if (strategy.has("ListKW")) {
            return Table.PartitionStrategy.LIST;
        }

        if (strategy.has("HashKW")) {
            return Table.PartitionStrategy.HASH;
        }

        return Table.PartitionStrategy.RANGE;
    }
}
