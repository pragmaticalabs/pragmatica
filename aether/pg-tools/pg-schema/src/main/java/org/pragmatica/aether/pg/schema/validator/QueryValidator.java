// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.schema.validator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;
import org.pragmatica.aether.pg.parser.transform.CstExtractor;
import org.pragmatica.aether.pg.parser.transform.CstNavigator;
import org.pragmatica.aether.pg.schema.model.Column;
import org.pragmatica.aether.pg.schema.model.PgType;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.aether.pg.schema.model.Table;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


public final class QueryValidator {
    private static final PgType UNKNOWN_TYPE = new PgType.BuiltinType("unknown", PgType.TypeCategory.STRING);
    /// PostgreSQL exposes the row that failed to insert under this name inside `ON CONFLICT DO
    /// UPDATE`. It is registered as a real relation carrying the target's columns rather than
    /// whitelisted, so `EXCLUDED.nonexistent_col` stays an error.
    private static final String EXCLUDED_RELATION = "excluded";
    private static final Set<String> ALIAS_KEYWORDS = Set.of("as");

    private final Schema schema;

    private QueryValidator(Schema schema) {
        this.schema = schema;
    }

    public static QueryValidator queryValidator(Schema schema) {
        return new QueryValidator(schema);
    }

    /// Reports whether the query contains a data-modifying CTE — a `WITH` common table
    /// expression whose body is an `INSERT`, `UPDATE`, or `DELETE` statement (typically with
    /// `RETURNING`). These execute writes inside an otherwise read-shaped query and are not
    /// supported by the generated accessors, so callers reject them with a clear diagnostic
    /// rather than silently mis-validating the outer statement. Schema-independent: it inspects
    /// only the CST structure.
    public static boolean hasDataModifyingCte(CstNode cst) {
        var navOpt = CstNavigator.wrap(cst);

        if (navOpt.isEmpty()) {
            return false;
        }

        for (var cteDef : navOpt.unwrap().findAll("CteDef")) {
            if (isDataModifying(cteDef)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isDataModifying(CstNavigator cteDef) {
        return containsKeyword(cteDef, "InsertKW") || containsKeyword(cteDef, "DeleteKW") || containsKeyword(cteDef,
                                                                                                             "UpdateKW") && containsKeyword(cteDef,
                                                                                                                                            "SetKW");
    }

    private static boolean containsKeyword(CstNavigator nav, String keywordRule) {
        return ! nav.findAll(keywordRule)
                    .isEmpty();
    }

    /// Resolves the list of output column NAMES the query produces: the `RETURNING` list of an
    /// `INSERT`/`UPDATE`/`DELETE`, or the target list of a `SELECT`'s statement-level core.
    /// All-or-nothing: the result is present only when every target-list entry resolves to a column
    /// name — an explicit alias (`col AS name`), a qualified reference (`t.col`), or a bare column
    /// (`col`). It is absent when any target is `*` (a `StarExpr`) or a compound expression
    /// (operator, cast, function call, literal), when the text does not hold exactly one statement,
    /// or when a `SELECT` has multiple cores (a set operation such as `UNION`).
    /// Schema-independent: it inspects only the CST structure. Intended for callers that map a
    /// return-row record's fields to output columns and want to skip the check rather than warn
    /// spuriously when the output set cannot be determined precisely.
    ///
    /// The statement is reached by POSITION. The previous `findAll("SelectCore")` walked the WHOLE
    /// tree, so a single subquery anywhere made that subquery's projection "the query's output"
    /// and a `RETURNING` list was validated against it — three spurious warnings per row component
    /// on the reporting corpus — while zero or two subqueries skipped the check entirely (#646).
    public static Option<List<String>> selectOutputColumnNames(CstNavigator queryRoot) {
        var bodyOpt = singleStatementBody(queryRoot);

        if (bodyOpt.isEmpty()) return Option.empty();

        var body = bodyOpt.unwrap();
        var returning = body.child("ReturningClause");

        if (returning.isPresent()) {
            return outputColumnNames(returning.unwrap());
        }

        var cores = ownedSelectCores(body);

        if (cores.size() != 1) return Option.empty();

        return outputColumnNames(cores.getFirst());
    }

    /// The body of the query's single statement, reached by descending the fixed spine
    /// `_ROOT -> Input -> Statement -> DmlStatement -> <body>`. Absent when the text holds anything
    /// other than exactly one statement, so a script never has one statement's shape attributed to
    /// another.
    private static Option<CstNavigator> singleStatementBody(CstNavigator queryRoot) {
        var input = queryRoot.child("Input").or(queryRoot);
        var statements = input.allChildren("Statement");

        if (statements.size() != 1) return Option.empty();

        return statements.getFirst()
                         .child("DmlStatement")
                         .flatMap(CstNavigator::firstChild);
    }

    /// Names of the `TargetList` owned by `targetListOwner` — a `SelectCore` or a
    /// `ReturningClause`, which carry the same target-list shape.
    private static Option<List<String>> outputColumnNames(CstNavigator targetListOwner) {
        var targetListOpt = targetListOwner.child("TargetList");

        if (targetListOpt.isEmpty()) return Option.empty();

        var targets = targetListOpt.unwrap().findAll("TargetElem");

        if (targets.isEmpty()) return Option.empty();

        var names = new ArrayList<String>();

        for (var target : targets) {
            var nameOpt = outputColumnName(target);

            if (nameOpt.isEmpty()) return Option.empty();

            names.add(nameOpt.unwrap());
        }

        return Option.present(names);
    }

    /// Resolves a single target's output column name. Reuses `inferTargetColumnName` for the
    /// explicit-alias and qualified-reference cases; falls back to `bareColumnName` for a plain
    /// unqualified column; leaves `*` and compound expressions unresolved (absent) so the caller
    /// skips the whole check.
    private static Option<String> outputColumnName(CstNavigator target) {
        var aliasedOrQualified = inferTargetColumnName(target);

        if (aliasedOrQualified.isPresent()) return aliasedOrQualified;

        if (target.has("StarExpr")) return Option.empty();

        return bareColumnName(target);
    }

    /// Resolves a bare (unqualified, unaliased) column reference by descending the single-child
    /// expression precedence chain to its `ColId`. Any branching along the way — an operator, cast,
    /// function argument list, parentheses, or literal introduces a sibling node — stops the descent
    /// and yields absent, so only a plain column reference resolves.
    private static Option<String> bareColumnName(CstNavigator node) {
        var colId = node.child("ColId");

        if (colId.isPresent()) {
            return Option.present(CstExtractor.extractIdentifier(colId.unwrap()).normalized());
        }

        if (node.children().size() != 1) return Option.empty();

        return node.firstChild()
                   .flatMap(QueryValidator::bareColumnName);
    }

    public ValidationResult validate(CstNode cst) {
        var nav = CstNavigator.wrap(cst);

        if (nav.isEmpty()) {
            return ValidationResult.empty();
        }

        return validateRoot(nav.unwrap());
    }

    /// Each statement node is validated as itself. There is deliberately NO keyword-presence
    /// fallback: `InsertKW`/`UpdateKW`/`SetKW` are not statement identity, and
    /// `INSERT ... ON CONFLICT DO UPDATE SET` contains all three. The old fallback therefore ran the
    /// UPDATE rules over the whole INSERT — resolving its `SET` items against a target picked
    /// lexically and with no `EXCLUDED` in scope, which is how #649's four hard errors were emitted.
    private ValidationResult validateRoot(CstNavigator nav) {
        var errors = new ArrayList<ValidationError>();
        var cteScopeIndex = buildCteScopeIndex(nav);

        for (var select : nav.findAll("SelectCore")) {
            var preScope = cteScopeIndex.getOrDefault(select.span(), new Scope());

            validateSelect(select, preScope, errors);
        }

        for (var insert : nav.findAll("InsertStmt")) {
            validateInsert(insert, errors);
        }

        for (var update : nav.findAll("UpdateStmt")) {
            validateUpdate(update, errors);
        }

        for (var delete : nav.findAll("DeleteStmt")) {
            validateDelete(delete, errors);
        }

        return new ValidationResult(errors);
    }

    private Map<SourceSpan, Scope> buildCteScopeIndex(CstNavigator root) {
        var index = new HashMap<SourceSpan, Scope>();

        collectCteScopes(root, index);

        return index;
    }

    private void collectCteScopes(CstNavigator nav, Map<SourceSpan, Scope> index) {
        var withClauses = nav.allChildren("WithClause");

        if (!withClauses.isEmpty()) {
            var cteScope = new Scope();

            for (var wc : withClauses) {
                registerCtes(wc, cteScope);
            }

            for (var core : ownedSelectCores(nav)) {
                index.put(core.span(), cteScope);
            }

            var anyRecursive = withClauses.stream().anyMatch(w -> w.has("RecursiveKW"));

            if (anyRecursive) {
                for (var wc : withClauses) {
                    indexRecursiveCteBodies(wc, cteScope, index);
                }
            }
        }

        for (var child : nav.children()) {
            if (child instanceof CstNode.NonTerminal nt) {
                collectCteScopes(new CstNavigator(nt), index);
            }
        }
    }

    private void indexRecursiveCteBodies(CstNavigator withClause, Scope cteScope, Map<SourceSpan, Scope> index) {
        for (var cteDef : withClause.findAll("CteDef")) {
            for (var innerStmt : cteDef.findAll("SelectStmt")) {
                for (var core : ownedSelectCores(innerStmt)) {
                    index.putIfAbsent(core.span(), cteScope);
                }
            }
        }
    }

    private static List<CstNavigator> ownedSelectCores(CstNavigator parent) {
        var cores = new ArrayList<CstNavigator>();

        for (var child : parent.children()) {
            if (! (child instanceof CstNode.NonTerminal nt)) continue;

            var childNav = new CstNavigator(nt);

            if ("SelectCore".equals(nt.ruleName())) {
                cores.add(childNav);
            } else if ("SetOp".equals(nt.ruleName())) {
                for (var inner : childNav.allChildren("SelectCore")) {
                    cores.add(inner);
                }
            } else if ("SelectStmt".equals(nt.ruleName())) {
                cores.addAll(ownedSelectCores(childNav));
            }
        }

        return cores;
    }

    private void registerCtes(CstNavigator withClause, Scope scope) {
        for (var cteDef : withClause.findAll("CteDef")) {
            registerCteDef(cteDef, scope);
        }
    }

    private void registerCteDef(CstNavigator cteDef, Scope scope) {
        var colIds = cteDef.allChildren("ColId");

        if (colIds.isEmpty()) return;

        var cteName = CstExtractor.extractIdentifier(colIds.getFirst()).normalized();
        var explicitColumnList = cteDef.child("ColumnList");

        if (explicitColumnList.isPresent()) {
            var names = CstExtractor.extractColumnList(explicitColumnList.unwrap());
            var cols = new ArrayList<Column>();

            for (var n : names) {
                cols.add(Column.column(n.normalized(), UNKNOWN_TYPE, true));
            }

            scope.registerTable(cteName,
                                Table.table(cteName, "", cols, List.of()));

            return;
        }

        var inferredCols = inferCteColumns(cteDef);

        if (inferredCols.isPresent()) {
            scope.registerTable(cteName,
                                Table.table(cteName, "", inferredCols.unwrap(), List.of()));
        } else {
            scope.registerPermissive(cteName);
        }
    }

    private Option<List<Column>> inferCteColumns(CstNavigator cteDef) {
        var dmlStatements = cteDef.allChildren("DmlStatement");

        if (dmlStatements.isEmpty()) return Option.empty();

        var innerBody = dmlStatements.getFirst();
        var innerCores = ownedSelectCores(innerBody);

        if (innerCores.isEmpty()) return Option.empty();

        if (!innerBody.findAll("SetOp").isEmpty()) return Option.empty();

        var selectCore = innerCores.getFirst();
        var targetListOpt = selectCore.child("TargetList");

        if (targetListOpt.isEmpty()) return Option.empty();

        var columnNames = new ArrayList<String>();

        for (var target : targetListOpt.unwrap().allChildren("TargetElem")) {
            var colNameOpt = inferTargetColumnName(target);

            if (colNameOpt.isEmpty()) return Option.empty();

            columnNames.add(colNameOpt.unwrap());
        }

        if (columnNames.isEmpty()) return Option.empty();

        var cols = new ArrayList<Column>();

        for (var n : columnNames) {
            cols.add(Column.column(n, UNKNOWN_TYPE, true));
        }

        return Option.present(cols);
    }

    private static Option<String> inferTargetColumnName(CstNavigator target) {
        if (target.has("StarExpr")) return Option.empty();

        var colLabels = target.allChildren("ColLabel");

        if (!colLabels.isEmpty()) {
            return Option.present(CstExtractor.extractIdentifier(colLabels.getLast()).normalized());
        }

        var qnames = target.findAll("QualifiedName");

        if (qnames.size() == 1) {
            var qname = CstExtractor.extractQualifiedName(qnames.getFirst());

            if (!qname.parts().isEmpty()) {
                return Option.present(qname.parts().getLast().normalized());
            }
        }

        return Option.empty();
    }

    private void validateSelect(CstNavigator select, Scope parentScope, List<ValidationError> errors) {
        var scope = new Scope(parentScope);
        var fromClauses = select.findAll("FromClause");

        for (var from : fromClauses) {
            resolveFromClause(from, scope, errors);
        }

        validateColumnRefs(select, scope, BareRefPolicy.SKIP, errors);
    }

    private void validateInsert(CstNavigator insert, List<ValidationError> errors) {
        var tableNameOpt = targetTableName(insert);

        if (tableNameOpt.isEmpty()) return;

        var tableName = tableNameOpt.unwrap();
        var tableOpt = resolveTable(tableName);

        if (tableOpt.isEmpty()) {
            errors.add(ValidationError.tableNotFound(tableName, insert.span()));

            return;
        }

        var table = tableOpt.unwrap();
        var scope = targetScope(insert, tableName, table);

        validateColumnList(insert.child("ColumnList"), tableName, table, insert.span(), errors);
        validateOnConflict(insert, tableName, table, scope, errors);
        validateReturning(insert, scope, errors);
    }

    private void validateUpdate(CstNavigator update, List<ValidationError> errors) {
        var tableNameOpt = targetTableName(update);

        if (tableNameOpt.isEmpty()) return;

        var tableName = tableNameOpt.unwrap();
        var tableOpt = resolveTable(tableName);

        if (tableOpt.isEmpty()) {
            errors.add(ValidationError.tableNotFound(tableName, update.span()));

            return;
        }

        var table = tableOpt.unwrap();
        var scope = targetScope(update, tableName, table);

        resolveJoinedTables(update.child("FromClause"), scope, errors);
        validateSetItems(update, tableName, table, errors);
        validateColumnRefsIn(update.child("WhereClause"), scope, errors);
        validateReturning(update, scope, errors);
    }

    private void validateDelete(CstNavigator delete, List<ValidationError> errors) {
        var tableNameOpt = targetTableName(delete);

        if (tableNameOpt.isEmpty()) return;

        var tableName = tableNameOpt.unwrap();
        var tableOpt = resolveTable(tableName);

        if (tableOpt.isEmpty()) {
            errors.add(ValidationError.tableNotFound(tableName, delete.span()));

            return;
        }

        var scope = targetScope(delete, tableName, tableOpt.unwrap());

        resolveJoinedTables(delete.child("UsingClauseDelete"), scope, errors);
        validateColumnRefsIn(delete.child("WhereClause"), scope, errors);
        validateReturning(delete, scope, errors);
    }

    /// The relation a DML statement targets, taken from the statement's OWN structure — the
    /// `QualifiedName` that is a direct child of `InsertStmt`/`UpdateStmt`/`DeleteStmt`. The previous
    /// `findAll("QualifiedName").getFirst()` was lexical-first over the whole subtree: it happened to
    /// agree because the target precedes every other name, which is the select-by-position lesson
    /// waiting to be re-learned rather than a property anything guaranteed.
    private static Option<String> targetTableName(CstNavigator stmt) {
        return stmt.child("QualifiedName")
                   .map(qname -> CstExtractor.extractQualifiedName(qname).normalized());
    }

    /// The scope a DML statement's own clauses resolve against: the target relation under its
    /// written name, under its bare name when written schema-qualified, and under its alias.
    /// Parented by the statement's own `WITH` names so a `FROM`/`USING` reference to a CTE resolves
    /// instead of reporting a missing table.
    private Scope targetScope(CstNavigator stmt, String tableName, Table table) {
        var cteScope = new Scope();
        var withClause = stmt.child("WithClause");

        if (withClause.isPresent()) {
            registerCtes(withClause.unwrap(), cteScope);
        }

        var scope = new Scope(cteScope);

        scope.registerTable(tableName, table);
        scope.registerTable(tableName.substring(tableName.lastIndexOf('.') + 1),
                            table);
        var alias = aliasOf(stmt);

        if (alias.isPresent()) {
            scope.registerTable(alias.unwrap(), table);
        }

        return scope;
    }

    /// Validates the `ON CONFLICT` clause against the scope PostgreSQL gives it: the target relation
    /// — self-referencable by name, which is what `WHERE current_price.version < EXCLUDED.version`
    /// needs — plus `EXCLUDED`, the pseudo-relation carrying the row that failed to insert. The
    /// conflict target (an index predicate) sees the target only; `EXCLUDED` is legal solely inside
    /// `DO UPDATE`.
    private void validateOnConflict(CstNavigator insert,
                                    String tableName,
                                    Table table,
                                    Scope targetScope,
                                    List<ValidationError> errors) {
        var onConflictOpt = insert.child("OnConflictClause");

        if (onConflictOpt.isEmpty()) return;

        var onConflict = onConflictOpt.unwrap();

        validateColumnRefsIn(onConflict.child("ConflictTarget"), targetScope, errors);
        var actionOpt = onConflict.child("ConflictAction");

        if (actionOpt.isEmpty()) return;

        var action = actionOpt.unwrap();
        var scope = new Scope(targetScope);

        scope.registerTable(EXCLUDED_RELATION, table);
        validateSetItems(action, tableName, table, errors);
        validateColumnRefsIn(actionOpt, scope, errors);
    }

    private void validateSetItems(CstNavigator owner, String tableName, Table table, List<ValidationError> errors) {
        var setList = owner.child("UpdateSetList");

        if (setList.isEmpty()) return;

        for (var item : setList.unwrap().allChildren("UpdateSetItem")) {
            validateSetItem(item, tableName, table, errors);
        }
    }

    /// The assigned column of `UpdateSetItem <- ColId '=' ExprOrDefault` is its FIRST LEAF, taken by
    /// position. Reading it back as `findAll("ColId").getFirst()` was #649's build-blocker: under
    /// peglib 0.7.x identifier fallback `version` lexes as `Token VersionKW`, so the name-based
    /// lookup skipped the assignment target entirely and returned the first identifier of the
    /// RIGHT-hand side instead — reporting `EXCLUDED`, or a self-qualifier such as `reservations`,
    /// as a missing column of the target table.
    private void validateSetItem(CstNavigator item, String tableName, Table table, List<ValidationError> errors) {
        var columnList = item.child("ColumnList");

        if (columnList.isPresent()) {
            validateColumnList(columnList, tableName, table, item.span(), errors);

            return;
        }

        var target = CstExtractor.leadingIdentifier(item);

        if (target.isEmpty()) return;

        checkColumn(target.unwrap().normalized(),
                    tableName,
                    table,
                    item.span(),
                    errors);
    }

    private void validateColumnList(Option<CstNavigator> columnList,
                                    String tableName,
                                    Table table,
                                    SourceSpan span,
                                    List<ValidationError> errors) {
        if (columnList.isEmpty()) return;

        for (var col : CstExtractor.extractColumnList(columnList.unwrap())) {
            checkColumn(col.normalized(), tableName, table, span, errors);
        }
    }

    private void checkColumn(String colName,
                             String tableName,
                             Table table,
                             SourceSpan span,
                             List<ValidationError> errors) {
        if (table.column(colName).isPresent()) return;

        errors.add(ValidationError.columnNotFound(colName, tableName, span));
    }

    /// `RETURNING` projects columns of the statement's TARGET relation, so it resolves in the
    /// statement scope — never against a `SelectCore` discovered somewhere in the tree (#646).
    /// This also closes the silent-skip: a bogus `RETURNING` column used to be reported by nothing.
    private void validateReturning(CstNavigator stmt, Scope scope, List<ValidationError> errors) {
        validateColumnRefsIn(stmt.child("ReturningClause"), scope, errors);
    }

    private void resolveJoinedTables(Option<CstNavigator> clause, Scope scope, List<ValidationError> errors) {
        if (clause.isEmpty()) return;

        resolveFromClause(clause.unwrap(), scope, errors);
    }

    private void validateColumnRefsIn(Option<CstNavigator> clause, Scope scope, List<ValidationError> errors) {
        if (clause.isEmpty()) return;

        validateColumnRefs(clause.unwrap(), scope, BareRefPolicy.CHECK, errors);
    }

    private void resolveFromClause(CstNavigator from, Scope scope, List<ValidationError> errors) {
        var baseRefs = from.findAll("BaseTableRef");

        if (!baseRefs.isEmpty()) {
            for (var ref : baseRefs) {
                resolveTableRef(ref, scope, errors);
            }
        }

        var tableRefs = from.findAll("TableRef");

        for (var ref : tableRefs) {
            var qnames = ref.allChildren("QualifiedName");

            if (!qnames.isEmpty() && !ref.has("SelectStmt")) {
                resolveTableRef(ref, scope, errors);
            }
        }

        var joinTableRefs = from.findAll("TableRefBase");

        for (var ref : joinTableRefs) {
            resolveTableRef(ref, scope, errors);
        }

        if (baseRefs.isEmpty() && tableRefs.isEmpty()) {
            resolveTableRef(from, scope, errors);
        }
    }

    private void resolveTableRef(CstNavigator ref, Scope scope, List<ValidationError> errors) {
        var qnames = ref.findAll("QualifiedName");

        if (qnames.isEmpty()) return;

        var tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();

        if (scope.isKnownCte(tableName)) {
            var alias = extractAlias(ref);
            var scopeName = alias.or(tableName);

            scope.registerFromCte(scopeName, tableName);

            return;
        }

        var table = resolveTable(tableName);

        if (table.isEmpty()) {
            errors.add(ValidationError.tableNotFound(tableName, ref.span()));

            return;
        }

        var alias = extractAlias(ref);
        var scopeName = alias.or(tableName);

        scope.registerTable(scopeName, table.unwrap());
    }

    /// The alias is read off the `Alias` NODE rather than reconstructed by subtracting the table
    /// name's `ColId` spans from every `ColId` under the reference. The subtraction missed an alias
    /// spelling a keyword — it never arrives under the kind "ColId" — and then silently registered
    /// the table under its own name only, so every `alias.column` in the statement reported a
    /// missing alias.
    private Option<String> extractAlias(CstNavigator ref) {
        var aliases = ref.findAll("Alias");

        if (aliases.isEmpty()) return Option.empty();

        return aliasName(aliases.getFirst());
    }

    /// The statement-level alias of `UPDATE reservations r ...` / `DELETE FROM reservations r ...`.
    private static Option<String> aliasOf(CstNavigator stmt) {
        var alias = stmt.child("Alias");

        if (alias.isEmpty()) return Option.empty();

        return aliasName(alias.unwrap());
    }

    private static Option<String> aliasName(CstNavigator alias) {
        var names = CstExtractor.leafIdentifiers(alias, ALIAS_KEYWORDS);

        if (names.isEmpty()) return Option.empty();

        return Option.present(names.getFirst().normalized());
    }

    /// Whether a bare, unqualified name may be resolved against `scope`. Only a statement scope
    /// that owns every relation it can see says CHECK: a `SELECT` reaches names through joins,
    /// set operations and permissive CTEs that this validator does not model, so resolving bare
    /// names there would report absences it cannot actually establish.
    private enum BareRefPolicy {
        CHECK,
        SKIP
    }

    private void validateColumnRefs(CstNavigator nav, Scope scope, BareRefPolicy policy, List<ValidationError> errors) {
        var refs = new ArrayList<CstNavigator>();

        collectOwnedColRefs(nav.node(), refs);
        for (var ref : refs) {
            validateColRef(ref, scope, policy, errors);
        }
    }

    /// Column references OWNED by `node` — the walk stops at a nested `SelectStmt`, so a subquery's
    /// names are never resolved against the ENCLOSING statement's scope. Subqueries keep validating
    /// their own scopes: `validateRoot` reaches every `SelectCore` independently.
    ///
    /// Only `ColRef` counts as a column reference. The previous walk resolved every `QualifiedName`
    /// in the subtree, which also swept up the FROM clause's table names and function names — so a
    /// schema-qualified `public.users` or `pg_catalog.now()` was resolved as if `public` were a
    /// table alias.
    ///
    /// KNOWN GAP, deliberately not closed here: a column whose name is a RESERVED word reaches the
    /// CST as `PostfixExpr -> ColRef(alias) + PostfixOp('.' ColLabel)`, because `ColId` excludes
    /// reserved words. Its `ColRef` carries only the alias, so `u.end` is skipped rather than
    /// resolved. Closing it means resolving through `PostfixOp`, which also covers JSON and array
    /// operators — new false-positive surface neither #649 nor #646 asks for.
    private static void collectOwnedColRefs(CstNode node, List<CstNavigator> refs) {
        if (! (node instanceof CstNode.NonTerminal nt)) return;

        if ("SelectStmt".equals(nt.ruleName())) return;

        if ("ColRef".equals(nt.ruleName())) {
            refs.add(CstNavigator.of(nt));

            return;
        }

        for (var child : nt.children()) {
            collectOwnedColRefs(child, refs);
        }
    }

    private void validateColRef(CstNavigator colRef, Scope scope, BareRefPolicy policy, List<ValidationError> errors) {
        var qnameOpt = colRef.child("QualifiedName");

        if (qnameOpt.isEmpty()) return;

        var qnav = qnameOpt.unwrap();
        // `t.*` drops its star during extraction and would otherwise read as the bare name `t`.
        if (CstExtractor.hasLeafText(qnav, "*")) return;

        var parts = CstExtractor.extractQualifiedName(qnav).parts();

        if (parts.isEmpty()) return;

        var colName = parts.getLast().normalized();

        if (parts.size() >= 2) {
            validateQualifiedRef(parts.getFirst().normalized(),
                                 colName,
                                 qnav.span(),
                                 scope,
                                 errors);

            return;
        }

        if (policy == BareRefPolicy.SKIP || !scope.resolvesBareColumns() || scope.hasColumn(colName)) return;

        errors.add(ValidationError.columnNotResolved(colName, qnav.span()));
    }

    private void validateQualifiedRef(String tableOrAlias,
                                      String colName,
                                      SourceSpan span,
                                      Scope scope,
                                      List<ValidationError> errors) {
        if (scope.isPermissive(tableOrAlias)) return;

        var table = scope.getTable(tableOrAlias);

        if (table.isEmpty()) {
            errors.add(ValidationError.tableOrAliasNotFound(tableOrAlias, span));
        } else if (table.unwrap().column(colName).isEmpty()) {
            errors.add(ValidationError.columnNotFound(colName, tableOrAlias, span));
        }
    }

    private Option<Table> resolveTable(String name) {
        var table = schema.table(name);

        if (table.isPresent()) return table;

        if (!name.contains(".")) {
            return schema.table("public." + name);
        }

        return Option.empty();
    }

    static final class Scope {
        private final Map<String, Table> tables = new HashMap<>();
        private final Set<String> permissiveNames = new HashSet<>();
        private final Map<String, String> fromCteAliasToCteName = new HashMap<>();
        private final Option<Scope> parent;

        Scope() {
            this.parent = Option.empty();
        }

        Scope(Scope parent) {
            this.parent = Option.present(parent);
        }

        @Contract
        void registerTable(String nameOrAlias, Table table) {
            tables.put(nameOrAlias, table);
        }

        @Contract
        void registerPermissive(String cteName) {
            permissiveNames.add(cteName);
        }

        @Contract
        void registerFromCte(String aliasOrName, String cteName) {
            var resolved = lookupKnownCteTable(cteName);

            if (resolved.isPresent()) {
                tables.put(aliasOrName, resolved.unwrap());
            }

            if (isPermissiveCte(cteName)) {
                permissiveNames.add(aliasOrName);
            }
        }

        boolean isKnownCte(String name) {
            return tables.containsKey(name) || permissiveNames.contains(name) || parent.isPresent() && parent.unwrap()
                                                                                                             .isKnownCte(name);
        }

        boolean isPermissive(String nameOrAlias) {
            return permissiveNames.contains(nameOrAlias) || parent.isPresent() && parent.unwrap()
                                                                                        .isPermissive(nameOrAlias);
        }

        private boolean isPermissiveCte(String cteName) {
            return permissiveNames.contains(cteName) || parent.isPresent() && parent.unwrap()
                                                                                    .isPermissiveCte(cteName);
        }

        private Option<Table> lookupKnownCteTable(String cteName) {
            var local = tables.get(cteName);

            if (local != null) return Option.present(local);

            return parent.flatMap(p -> p.lookupKnownCteTable(cteName));
        }

        Option<Table> getTable(String nameOrAlias) {
            var local = tables.get(nameOrAlias);

            if (local != null) return Option.present(local);

            return parent.flatMap(p -> p.getTable(nameOrAlias));
        }

        boolean hasColumn(String colName) {
            return tables.values()
                         .stream()
                         .anyMatch(t -> t.column(colName)
                                         .isPresent()) || parent.isPresent() && parent.unwrap()
                                                                                      .hasColumn(colName);
        }

        boolean hasAnyTables() {
            return ! tables.isEmpty() || parent.isPresent() && parent.unwrap()
                                                                     .hasAnyTables();
        }

        /// A bare name can only be reported as absent when the scope knows every relation in it.
        /// A permissive CTE — one whose output columns could not be inferred — makes any name
        /// potentially valid, so the scope declines rather than guesses.
        boolean resolvesBareColumns() {
            return hasAnyTables() && !hasPermissiveNames();
        }

        private boolean hasPermissiveNames() {
            return ! permissiveNames.isEmpty() || parent.isPresent() && parent.unwrap()
                                                                              .hasPermissiveNames();
        }
    }
}
