package org.pragmatica.aether.pg.schema.builder;

import org.pragmatica.aether.pg.parser.ast.common.DataTypeName;
import org.pragmatica.aether.pg.parser.transform.CstExtractor;
import org.pragmatica.aether.pg.parser.transform.CstNavigator;
import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.model.*;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;

import java.util.ArrayList;
import java.util.List;

/// Converts parsed CST nodes into SchemaEvents.
/// The bridge between pg-parser and pg-schema.
public final class DdlAnalyzer {
    private DdlAnalyzer() {}

    /// Analyze a top-level parsed statement and produce schema events.
    public static Result<List<SchemaEvent>> analyze(CstNode cst) {
        var nav = CstNavigator.wrap(cst);
        if ( nav.isEmpty()) {
        return Result.success(List.of());}
        return analyzeStatement(nav.unwrap());
    }

    private static Result<List<SchemaEvent>> analyzeStatement(CstNavigator nav) {
        // Top-level: Input -> Statement -> DdlStatement -> CreateStatement -> ...
        // Note: findAll searches recursively, so it finds nodes at any depth.
        // For CREATE INDEX, ConcurrentlyKW may be at CreateStatement level due to inlining.
        // We pass the broadest relevant navigator to each analyzer.
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
        for ( var stmt : createTable) { analyzeCreateTable(stmt, events);}
        for ( var stmt : alterTable) { analyzeAlterTable(stmt, events);}
        for ( var stmt : dropTable) { analyzeDropTable(stmt, events);}
        for ( var stmt : createIndex) { analyzeCreateIndex(stmt, nav, events);}
        for ( var stmt : dropIndex) { analyzeDropIndex(stmt, events);}
        for ( var stmt : createSequence) { analyzeCreateSequence(stmt, events);}
        for ( var stmt : dropSequence) { analyzeDropSequence(stmt, events);}
        for ( var stmt : createType) { analyzeCreateType(stmt, events);}
        for ( var stmt : alterType) { analyzeAlterType(stmt, events);}
        for ( var stmt : dropType) { analyzeDropType(stmt, events);}
        for ( var stmt : createSchema) { analyzeCreateSchema(stmt, events);}
        for ( var stmt : createExtension) { analyzeCreateExtension(stmt, events);}
        for ( var stmt : commentStmt) { analyzeComment(stmt, events);}
        return Result.success(events);
    }

    // === CREATE TABLE ===
    private static void analyzeCreateTable(CstNavigator stmt, List<SchemaEvent> events) {
        var qname = stmt.child("QualifiedName").map(CstExtractor::extractQualifiedName);
        if ( qname.isEmpty()) return;
        var name = qname.unwrap().name()
                               .normalized();
        var schema = qname.unwrap().schema()
                                 .map(id -> id.normalized())
                                 .or("");
        var columns = new ArrayList<Column>();
        var constraints = new ArrayList<Constraint>();
        var tableElements = stmt.findAll("TableElement");
        for ( var elem : tableElements) {
        // TableElement inlines ColumnDef's children (ColId, DataType, ColConstraint*)
        // or TableConstraint's children. Check for ColId+DataType pattern for columns.
        if ( elem.has("ColId") && elem.has("DataType")) {
        extractColumn(elem, columns, constraints);} else
        if ( elem.has("TableConstraintElem") || elem.has("PrimaryKW") || elem.has("ForeignKW") ||
        elem.has("UniqueKW") || elem.has("CheckKW") || elem.has("ExcludeKW")) {
        extractTableConstraint(elem, constraints);} else {
            // May be a table constraint wrapped in TableConstraint with ConstraintName
            var tblConstraint = elem.child("TableConstraint");
            if ( tblConstraint.isPresent()) {
            extractTableConstraint(tblConstraint.unwrap(), constraints);} else
            if ( elem.has("ConstraintKW")) {
            extractTableConstraint(elem, constraints);}
        }}
        Option<Table.PartitionBy> partitioning = Option.empty();
        var partClause = stmt.findAll("PartitionByClause");
        if ( !partClause.isEmpty()) {
        partitioning = extractPartitioning(partClause.getFirst());}
        events.add(new SchemaEvent.TableCreated(stmt.span(), name, schema, columns, constraints, partitioning));
    }

    private static void extractColumn(CstNavigator elem, List<Column> columns, List<Constraint> constraints) {
        // Use findAll for robust navigation — ColId and DataType may be nested
        var colIds = elem.findAll("ColId");
        if ( colIds.isEmpty()) return;
        var colName = CstExtractor.extractIdentifier(colIds.getFirst()).normalized();
        var dataTypes = elem.findAll("DataType");
        var pgType = !dataTypes.isEmpty()
                     ? resolveType(CstExtractor.extractDataType(dataTypes.getFirst()))
                     : new PgType.BuiltinType("text", PgType.TypeCategory.STRING);
        boolean nullable = true;
        Option<String> defaultExpr = Option.empty();
        // ColConstraint -> ConstraintName? ColConstraintElem
        // Use recursive search to find keywords inside the constraint hierarchy
        var colConstraints = elem.findAll("ColConstraint");
        for ( var cc : colConstraints) {
            boolean hasNot = !cc.findAll("NotKW").isEmpty();
            boolean hasNull = !cc.findAll("NullKW").isEmpty();
            boolean hasDefault = !cc.findAll("DefaultKW").isEmpty();
            boolean hasPrimary = !cc.findAll("PrimaryKW").isEmpty();
            boolean hasUnique = !cc.findAll("UniqueKW").isEmpty();
            boolean hasReferences = !cc.findAll("ReferencesKW").isEmpty();
            if ( hasNot && hasNull) {
            nullable = false;} else
            if ( hasNull && !hasNot) {
            nullable = true;} else if ( hasDefault) {
            defaultExpr = Option.present(extractExprText(cc));} else if ( hasPrimary) {
                constraints.add(new Constraint.PrimaryKey(Option.empty(), List.of(colName)));
                nullable = false;
            } else if ( hasUnique) {
            constraints.add(new Constraint.Unique(Option.empty(), List.of(colName)));} else if ( hasReferences) {
            extractInlineReference(cc, colName, constraints);}
        }
        var col = Column.column(colName, pgType, nullable);
        if ( defaultExpr.isPresent()) {
        col = col.withDefault(defaultExpr.unwrap());}
        columns.add(col);
    }

    private static void extractInlineReference(CstNavigator refClause, String colName, List<Constraint> constraints) {
        var refQname = refClause.child("QualifiedName");
        if ( refQname.isEmpty()) return;
        var refTableName = CstExtractor.extractQualifiedName(refQname.unwrap()).normalized();
        var refColumns = refClause.child("ColumnList").map(CstExtractor::extractColumnList)
                                        .map(ids -> ids.stream().map(id -> id.normalized())
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
        var constraintName = tblConstraint.child("ConstraintName").flatMap(cn -> cn.child("ColId"))
                                                .map(CstExtractor::extractIdentifier)
                                                .map(id -> id.normalized());
        // TableConstraintElem may be a direct child, or its content may be inlined
        var e = tblConstraint.child("TableConstraintElem").or(tblConstraint);
        if ( e.has("PrimaryKW")) {
            var cols = extractConstraintColumns(e);
            constraints.add(new Constraint.PrimaryKey(constraintName, cols));
        } else


        if ( e.has("UniqueKW")) {
            var cols = extractConstraintColumns(e);
            constraints.add(new Constraint.Unique(constraintName, cols));
        } else if ( e.has("CheckKW")) {
        constraints.add(new Constraint.Check(constraintName, extractExprText(e)));} else if ( e.has("ForeignKW")) {
        extractTableForeignKey(e, constraintName, constraints);} else if ( e.has("ExcludeKW")) {
        constraints.add(new Constraint.Exclusion(constraintName, "gist", extractExprText(e)));}
    }

    private static void extractTableForeignKey(CstNavigator elem, Option<String> name, List<Constraint> constraints) {
        var columnLists = elem.findAll("ColumnList");
        var fkColumns = columnLists.size() > 0
                        ? CstExtractor.extractColumnList(columnLists.getFirst()).stream()
                                                        .map(id -> id.normalized())
                                                        .toList()
                        : List.<String>of();
        var refQname = elem.child("QualifiedName");
        var refTableName = refQname.isPresent()
                           ? CstExtractor.extractQualifiedName(refQname.unwrap()).normalized()
                           : "";
        var refColumns = columnLists.size() > 1
                         ? CstExtractor.extractColumnList(columnLists.get(1)).stream()
                                                         .map(id -> id.normalized())
                                                         .toList()
                         : List.<String>of();
        var onUpdate = extractFkAction(elem, "UpdateKW");
        var onDelete = extractFkAction(elem, "DeleteKW");
        constraints.add(new Constraint.ForeignKey(name, fkColumns, refTableName, refColumns, onUpdate, onDelete));
    }

    // === ALTER TABLE ===
    private static void analyzeAlterTable(CstNavigator stmt, List<SchemaEvent> events) {
        var qname = stmt.child("QualifiedName").map(CstExtractor::extractQualifiedName);
        if ( qname.isEmpty()) return;
        var tableName = qname.unwrap().normalized();
        var span = stmt.span();
        // Rename — detect by RenameKW keyword
        if ( !stmt.findAll("RenameKW").isEmpty()) {
            // All ColIds in the rename part (after the table name QualifiedName)
            var allColIds = stmt.findAll("ColId");
            // First ColId(s) are in the table QualifiedName, skip them
            var tableQname = stmt.child("QualifiedName").map(CstExtractor::extractQualifiedName);
            int tablePartCount = tableQname.isPresent()
                                 ? tableQname.unwrap().parts()
                                                    .size()
                                 : 0;
            var renameColIds = allColIds.subList(Math.min(tablePartCount, allColIds.size()),
                                                 allColIds.size());
            if ( !stmt.findAll("ColumnKW").isEmpty() && renameColIds.size() >= 2) {
                // RENAME COLUMN old TO new
                var oldName = CstExtractor.extractIdentifier(renameColIds.get(0)).normalized();
                var newName = CstExtractor.extractIdentifier(renameColIds.get(1)).normalized();
                events.add(new SchemaEvent.ColumnRenamed(span, tableName, oldName, newName));
            } else


            if ( !renameColIds.isEmpty()) {
                // RENAME TO new_name
                var newName = CstExtractor.extractIdentifier(renameColIds.getLast()).normalized();
                events.add(new SchemaEvent.TableRenamed(span, tableName, newName));
            }
            return;
        }
        // Set schema
        var setSchema = stmt.child("SetSchemaAction");
        if ( setSchema.isPresent()) return;
        // TODO: handle
        // Attach/Detach partition
        if ( stmt.has("AttachKW") || stmt.has("DetachKW")) return;
        // TODO: handle
        // ALTER TABLE actions — may be in AlterTableAction or AlterTableActions
        var actions = stmt.findAll("AlterTableAction");
        if ( actions.isEmpty()) {
        // Try the whole statement as a single action
        analyzeAlterTableAction(stmt, tableName, span, events);} else
        {
        for ( var action : actions) {
        analyzeAlterTableAction(action, tableName, span, events);}}
    }

    private static void analyzeAlterTableAction(CstNavigator action,
                                                String tableName,
                                                SourceSpan span,
                                                List<SchemaEvent> events) {
        // Detect action type by keywords at the action level
        // CST inlines sub-rule contents, so we look at the action node directly
        if ( action.has("AddKW") && (action.has("ColumnKW") || !action.findAll("DataType").isEmpty())) {
            // ADD COLUMN — ColumnDef is a sibling of AddKW, not inside AddColumnAction
            var colIds = action.findAll("ColId");
            var dataTypes = action.findAll("DataType");
            if ( !colIds.isEmpty() && !dataTypes.isEmpty()) {
                var columns = new ArrayList<Column>();
                var constraints = new ArrayList<Constraint>();
                extractColumn(action, columns, constraints);
                for ( var col : columns) events.add(new SchemaEvent.ColumnAdded(span, tableName, col));
                for ( var c : constraints) events.add(new SchemaEvent.ConstraintAdded(span, tableName, c));
            }
            return;
        }
        if ( action.has("AddKW") && (action.has("TableConstraint") || action.has("ConstraintKW") ||
        !action.findAll("UniqueKW").isEmpty() || !action.findAll("PrimaryKW").isEmpty() ||
        !action.findAll("ForeignKW").isEmpty() || !action.findAll("CheckKW").isEmpty())) {
            // ADD CONSTRAINT — TableConstraint may be a direct child
            var tblConstraint = action.child("TableConstraint");
            var constraints = new ArrayList<Constraint>();
            extractTableConstraint(tblConstraint.or(action), constraints);
            for ( var c : constraints) events.add(new SchemaEvent.ConstraintAdded(span, tableName, c));
            return;
        }
        if ( action.has("DropKW") && (action.has("ColumnKW") || (!action.has("ConstraintKW") && !action.findAll("DropColumnAction").isEmpty()))) {
            // DROP COLUMN
            var colIds = action.findAll("ColId");
            if ( !colIds.isEmpty()) {
            events.add(new SchemaEvent.ColumnDropped(span,
                                                     tableName,
                                                     CstExtractor.extractIdentifier(colIds.getFirst()).normalized()));}
            return;
        }
        if ( action.has("DropKW") && action.has("ConstraintKW")) {
            // DROP CONSTRAINT
            var colIds = action.findAll("ColId");
            if ( !colIds.isEmpty()) {
            events.add(new SchemaEvent.ConstraintDropped(span,
                                                         tableName,
                                                         CstExtractor.extractIdentifier(colIds.getFirst()).normalized()));}
            return;
        }
        if ( action.has("AlterKW") || !action.findAll("AlterColumnCmd").isEmpty()) {
            // ALTER COLUMN
            var colIds = action.findAll("ColId");
            if ( !colIds.isEmpty()) {
                var colName = CstExtractor.extractIdentifier(colIds.getFirst()).normalized();
                analyzeAlterColumnCmd(action, tableName, colName, span, events);
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
        // Detect command type by keywords — sub-rules are inlined in CST
        boolean hasSet = !cmd.findAll("SetKW").isEmpty();
        boolean hasDrop = !cmd.findAll("DropKW").isEmpty();
        boolean hasNot = !cmd.findAll("NotKW").isEmpty();
        boolean hasNull = !cmd.findAll("NullKW").isEmpty();
        boolean hasDefault = !cmd.findAll("DefaultKW").isEmpty();
        boolean hasType = !cmd.findAll("TypeKW").isEmpty();
        if ( hasType || !cmd.findAll("DataType").isEmpty() && hasSet) {
            // SET DATA TYPE / TYPE
            var dataTypes = cmd.findAll("DataType");
            if ( !dataTypes.isEmpty()) {
                var pgType = resolveType(CstExtractor.extractDataType(dataTypes.getFirst()));
                events.add(new SchemaEvent.ColumnTypeChanged(span, tableName, colName, pgType));
            }
        } else if ( hasSet && hasNot && hasNull) {
        // SET NOT NULL
        events.add(new SchemaEvent.ColumnNullabilityChanged(span, tableName, colName, false));} else if ( hasDrop && hasNot && hasNull) {
        // DROP NOT NULL
        events.add(new SchemaEvent.ColumnNullabilityChanged(span, tableName, colName, true));} else if ( hasSet && hasDefault) {
        // SET DEFAULT
        events.add(new SchemaEvent.ColumnDefaultChanged(span, tableName, colName, Option.present(extractExprText(cmd))));} else if ( hasDrop && hasDefault) {
        // DROP DEFAULT
        events.add(new SchemaEvent.ColumnDefaultChanged(span, tableName, colName, Option.empty()));}
    }

    // === DROP TABLE ===
    private static void analyzeDropTable(CstNavigator stmt, List<SchemaEvent> events) {
        var qnames = stmt.findAll("QualifiedName");
        for ( var qnav : qnames) {
            var qname = CstExtractor.extractQualifiedName(qnav);
            events.add(new SchemaEvent.TableDropped(stmt.span(), qname.normalized()));
        }
    }

    // === CREATE/DROP INDEX ===
    private static void analyzeCreateIndex(CstNavigator stmt, CstNavigator root, List<SchemaEvent> events) {
        // Keywords may be at stmt level or parent level due to CST inlining
        boolean unique = !root.findAll("UniqueKW").isEmpty();
        boolean concurrent = !root.findAll("ConcurrentlyKW").isEmpty();
        // Index name (optional — unnamed indexes)
        var colIds = stmt.findAll("ColId");
        var qnames = stmt.findAll("QualifiedName");
        // The grammar: CREATE INDEX name ON table (...) or CREATE INDEX ON table (...)
        // The table is in a QualifiedName, the index name is a ColId before ON
        String indexName = "";
        String tableName = "";
        if ( !qnames.isEmpty()) {
        tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();}
        // First ColId that appears before the table name is the index name
        if ( !colIds.isEmpty() && !qnames.isEmpty()) {
            var firstColId = colIds.getFirst();
            if ( firstColId.span().start()
                                .offset() < qnames.getFirst().span()
                                                           .start()
                                                           .offset()) {
                indexName = CstExtractor.extractIdentifier(firstColId).normalized();
                if ( qnames.size() > 1) {
                tableName = CstExtractor.extractQualifiedName(qnames.get(1)).normalized();}
            }
        }
        // Index method
        var methodText = stmt.tokenText("IndexMethod").or("btree")
                                       .toLowerCase();
        var method = switch (methodText) {case "hash" -> Index.IndexMethod.HASH;case "gin" -> Index.IndexMethod.GIN;case "gist" -> Index.IndexMethod.GIST;case "brin" -> Index.IndexMethod.BRIN;case "spgist" -> Index.IndexMethod.SPGIST;default -> Index.IndexMethod.BTREE;};
        // Index elements
        var indexElems = stmt.findAll("IndexElem");
        var elements = indexElems.stream().map(DdlAnalyzer::toIndexElement)
                                        .toList();
        // WHERE clause for partial index
        var whereClause = stmt.child("WhereClause");
        Option<String> whereExpr = whereClause.isPresent()
                                   ? Option.present(extractExprText(whereClause.unwrap()))
                                   : Option.empty();
        // INCLUDE columns
        var includeClause = stmt.child("IncludeClause");
        var includeCols = includeClause.isPresent()
                          ? includeClause.flatMap(ic -> ic.child("ColumnList")).map(CstExtractor::extractColumnList)
                                                 .map(ids -> ids.stream().map(id -> id.normalized())
                                                                       .toList())
                                                 .or(List.of())
                          : List.<String>of();
        var index = new Index(indexName, tableName, elements, method, unique, concurrent, whereExpr, includeCols);
        events.add(new SchemaEvent.IndexCreated(stmt.span(), index));
    }

    private static void analyzeDropIndex(CstNavigator stmt, List<SchemaEvent> events) {
        var qnames = stmt.findAll("QualifiedName");
        for ( var qnav : qnames) {
        events.add(new SchemaEvent.IndexDropped(stmt.span(),
                                                CstExtractor.extractQualifiedName(qnav).normalized()));}
    }

    // === CREATE/DROP SEQUENCE ===
    private static void analyzeCreateSequence(CstNavigator stmt, List<SchemaEvent> events) {
        var qname = stmt.child("QualifiedName").map(CstExtractor::extractQualifiedName);
        if ( qname.isEmpty()) return;
        var name = qname.unwrap().name()
                               .normalized();
        var schema = qname.unwrap().schema()
                                 .map(id -> id.normalized())
                                 .or("");
        events.add(new SchemaEvent.SequenceCreated(stmt.span(), Sequence.sequence(name, schema)));
    }

    private static void analyzeDropSequence(CstNavigator stmt, List<SchemaEvent> events) {
        var qnames = stmt.findAll("QualifiedName");
        for ( var qnav : qnames) {
        events.add(new SchemaEvent.SequenceDropped(stmt.span(),
                                                   CstExtractor.extractQualifiedName(qnav).normalized()));}
    }

    // === CREATE/ALTER/DROP TYPE ===
    private static void analyzeCreateType(CstNavigator stmt, List<SchemaEvent> events) {
        var qname = stmt.child("QualifiedName").map(CstExtractor::extractQualifiedName);
        if ( qname.isEmpty()) return;
        var name = qname.unwrap().name()
                               .normalized();
        var schema = qname.unwrap().schema()
                                 .map(id -> id.normalized())
                                 .or("");
        // ENUM type
        var enumLabels = stmt.findAll("EnumLabelList");
        if ( !enumLabels.isEmpty()) {
            var stringLiterals = enumLabels.getFirst().findAll("StringLiteral");
            var values = stringLiterals.stream().map(sl -> sl.firstTokenText().or(""))
                                              .filter(s -> !s.isEmpty())
                                              .toList();
            events.add(new SchemaEvent.TypeCreated(stmt.span(), new PgType.EnumType(name, schema, values)));
            return;
        }
        // Composite type
        var compositeFields = stmt.findAll("CompositeField");
        if ( !compositeFields.isEmpty()) {
            var fields = compositeFields.stream().map(DdlAnalyzer::toCompositeField)
                                               .toList();
            events.add(new SchemaEvent.TypeCreated(stmt.span(), new PgType.CompositeType(name, schema, fields)));
            return;
        }
        // Domain type or empty shell — simplified
        events.add(new SchemaEvent.TypeCreated(stmt.span(), new PgType.CustomType(name, schema)));
    }

    private static void analyzeAlterType(CstNavigator stmt, List<SchemaEvent> events) {
        var qname = stmt.child("QualifiedName").map(CstExtractor::extractQualifiedName);
        if ( qname.isEmpty()) return;
        var typeName = qname.unwrap().normalized();
        // ADD VALUE
        if ( stmt.has("ValueKW")) {
            var stringLiterals = stmt.findAll("StringLiteral");
            if ( !stringLiterals.isEmpty()) {
                var value = stringLiterals.getFirst().firstTokenText()
                                                   .or("");
                Option<String> before = stmt.has("BeforeKW") && stringLiterals.size() > 1
                                        ? Option.present(stringLiterals.get(1).firstTokenText()
                                                                           .or(""))
                                        : Option.empty();
                Option<String> after = stmt.has("AfterKW") && stringLiterals.size() > 1
                                       ? Option.present(stringLiterals.get(1).firstTokenText()
                                                                          .or(""))
                                       : Option.empty();
                events.add(new SchemaEvent.EnumValueAdded(stmt.span(), typeName, value, before, after));
            }
        }
    }

    private static void analyzeDropType(CstNavigator stmt, List<SchemaEvent> events) {
        var qnames = stmt.findAll("QualifiedName");
        for ( var qnav : qnames) {
        events.add(new SchemaEvent.TypeDropped(stmt.span(),
                                               CstExtractor.extractQualifiedName(qnav).normalized()));}
    }

    // === CREATE SCHEMA ===
    private static void analyzeCreateSchema(CstNavigator stmt, List<SchemaEvent> events) {
        var colId = stmt.child("ColId");
        if ( colId.isPresent()) {
        events.add(new SchemaEvent.SchemaCreated(stmt.span(),
                                                 CstExtractor.extractIdentifier(colId.unwrap()).normalized()));}
    }

    // === CREATE EXTENSION ===
    private static void analyzeCreateExtension(CstNavigator stmt, List<SchemaEvent> events) {
        var colId = stmt.child("ColId");
        if ( colId.isPresent()) {
        events.add(new SchemaEvent.ExtensionCreated(stmt.span(),
                                                    CstExtractor.extractIdentifier(colId.unwrap()).normalized()));}
    }

    // === COMMENT ON ===
    private static void analyzeComment(CstNavigator stmt, List<SchemaEvent> events) {
        var target = stmt.child("CommentTarget");
        if ( target.isEmpty()) return;
        var t = target.unwrap();
        String targetType = "";
        String targetName = "";
        if ( t.has("TableKW")) {
            targetType = "TABLE";
            var qn = t.child("QualifiedName");
            if ( qn.isPresent()) targetName = CstExtractor.extractQualifiedName(qn.unwrap()).normalized();
        } else if ( t.has("ColumnKW")) {
            targetType = "COLUMN";
            var qn = t.child("QualifiedName");
            if ( qn.isPresent()) targetName = CstExtractor.extractQualifiedName(qn.unwrap()).normalized();
        }
        if ( !targetType.isEmpty()) {
            var stringLit = stmt.findAll("StringLiteral");
            Option<String> comment = !stringLit.isEmpty()
                                     ? Option.present(stringLit.getFirst().firstTokenText()
                                                                        .or(""))
                                     : Option.empty();
            events.add(new SchemaEvent.CommentSet(stmt.span(), targetType, targetName, comment));
        }
    }

    // === Extracted named methods ===
    private static Index.IndexElement toIndexElement(CstNavigator e) {
        var elemColId = e.child("ColId");
        var elemText = elemColId.isPresent()
                       ? CstExtractor.extractIdentifier(elemColId.unwrap()).normalized()
                       : extractExprText(e);
        return new Index.IndexElement(elemText, Option.empty(), Option.empty());
    }

    private static PgType.CompositeField toCompositeField(CstNavigator f) {
        var fieldName = f.child("ColId").map(CstExtractor::extractIdentifier)
                               .map(id -> id.normalized())
                               .or("?");
        var fieldType = f.child("DataType").map(CstExtractor::extractDataType)
                               .map(DdlAnalyzer::resolveType)
                               .or(new PgType.BuiltinType("text", PgType.TypeCategory.STRING));
        return new PgType.CompositeField(fieldName, fieldType);
    }

    // === Helpers ===
    private static PgType resolveType(DataTypeName dt) {
        return BuiltinTypes.resolve(dt.baseName(), dt.modifiers(), dt.arrayDimensions());
    }

    private static List<String> extractConstraintColumns(CstNavigator constraint) {
        var colList = constraint.child("ColumnList");
        if ( colList.isEmpty()) return List.of();
        return CstExtractor.extractColumnList(colList.unwrap()).stream()
                                             .map(id -> id.normalized())
                                             .toList();
    }

    private static Constraint.FkAction extractFkAction(CstNavigator nav, String actionKeyword) {
        var actions = nav.findAll("FkAction");
        for ( var action : actions) {
        if ( action.has(actionKeyword)) {
            var actionType = action.child("FkActionType");
            if ( actionType.isPresent()) {
                if ( actionType.unwrap().has("CascadeKW")) return Constraint.FkAction.CASCADE;
                if ( actionType.unwrap().has("RestrictKW")) return Constraint.FkAction.RESTRICT;
                if ( actionType.unwrap().has("SetKW") && actionType.unwrap().has("NullKW")) return Constraint.FkAction.SET_NULL;
                if ( actionType.unwrap().has("SetKW") && actionType.unwrap().has("DefaultKW")) return Constraint.FkAction.SET_DEFAULT;
            }
        }}
        return Constraint.FkAction.NO_ACTION;
    }

    private static String extractExprText(CstNavigator nav) {
        // Simple approach: return the raw text of the expression area
        // In a real implementation, we'd reconstruct from the CST
        return "(expr)";
    }

    // placeholder — proper implementation needs source text access
    private static Option<Table.PartitionBy> extractPartitioning(CstNavigator partClause) {
        var strategy = partClause.child("PartitionStrategy");
        if ( strategy.isEmpty()) return Option.empty();
        var strategyType = strategy.unwrap().has("ListKW")
                           ? Table.PartitionStrategy.LIST
                           : strategy.unwrap().has("HashKW")
                           ? Table.PartitionStrategy.HASH
                           : Table.PartitionStrategy.RANGE;
        var keys = partClause.findAll("ColId").stream()
                                     .map(CstExtractor::extractIdentifier)
                                     .map(id -> id.normalized())
                                     .toList();
        return Option.present(new Table.PartitionBy(strategyType, keys));
    }
}
