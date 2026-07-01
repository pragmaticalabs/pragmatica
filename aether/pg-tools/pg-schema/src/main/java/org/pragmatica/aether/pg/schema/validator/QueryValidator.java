// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.schema.validator;

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


public final class QueryValidator {
    private static final PgType UNKNOWN_TYPE = new PgType.BuiltinType("unknown", PgType.TypeCategory.STRING);

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
        return containsKeyword(cteDef, "InsertKW")
               || containsKeyword(cteDef, "DeleteKW")
               || containsKeyword(cteDef, "UpdateKW") && containsKeyword(cteDef, "SetKW");
    }

    private static boolean containsKeyword(CstNavigator nav, String keywordRule) {
        return !nav.findAll(keywordRule).isEmpty();
    }

    public ValidationResult validate(CstNode cst) {
        var nav = CstNavigator.wrap(cst);

        if (nav.isEmpty()) {
            return ValidationResult.empty();
        }

        return validateRoot(nav.unwrap());
    }

    private ValidationResult validateRoot(CstNavigator nav) {
        var errors = new ArrayList<ValidationError>();
        var cteScopeIndex = buildCteScopeIndex(nav);

        for (var select : nav.findAll("SelectCore")) {
            var preScope = cteScopeIndex.getOrDefault(select.span(), new Scope());

            validateSelect(select, preScope, errors);
        }

        var insertStmts = nav.findAll("InsertStmt");

        if (insertStmts.isEmpty() && !nav.findAll("InsertKW").isEmpty()) {
            validateInsert(nav, errors);
        } else {
            for (var insert : insertStmts) {
                validateInsert(insert, errors);
            }
        }

        var updateStmts = nav.findAll("UpdateStmt");

        if (updateStmts.isEmpty() && !nav.findAll("UpdateKW").isEmpty() && !nav.findAll("SetKW").isEmpty()) {
            validateUpdate(nav, errors);
        } else {
            for (var update : updateStmts) {
                validateUpdate(update, errors);
            }
        }

        var deleteStmts = nav.findAll("DeleteStmt");

        if (deleteStmts.isEmpty() && !nav.findAll("DeleteKW").isEmpty()) {
            validateDelete(nav, errors);
        } else {
            for (var delete : deleteStmts) {
                validateDelete(delete, errors);
            }
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

    private List<CstNavigator> ownedSelectCores(CstNavigator parent) {
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

    private Option<String> inferTargetColumnName(CstNavigator target) {
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

        validateColumnRefs(select, scope, errors);
    }

    private void validateInsert(CstNavigator insert, List<ValidationError> errors) {
        var qnames = insert.findAll("QualifiedName");

        if (qnames.isEmpty()) return;

        var tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();
        var table = resolveTable(tableName);

        if (table.isEmpty()) {
            errors.add(ValidationError.tableNotFound(tableName, insert.span()));

            return;
        }

        var columnLists = insert.findAll("ColumnList");

        if (!columnLists.isEmpty()) {
            var cols = CstExtractor.extractColumnList(columnLists.getFirst());

            for (var col : cols) {
                if (table.unwrap().column(col.normalized()).isEmpty()) {
                    errors.add(ValidationError.columnNotFound(col.normalized(), tableName, insert.span()));
                }
            }
        }
    }

    private void validateUpdate(CstNavigator update, List<ValidationError> errors) {
        var qnames = update.findAll("QualifiedName");

        if (qnames.isEmpty()) return;

        var tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();
        var table = resolveTable(tableName);

        if (table.isEmpty()) {
            errors.add(ValidationError.tableNotFound(tableName, update.span()));

            return;
        }

        var setItems = update.findAll("UpdateSetItem");

        for (var item : setItems) {
            var colIds = item.findAll("ColId");

            if (!colIds.isEmpty()) {
                var colName = CstExtractor.extractIdentifier(colIds.getFirst()).normalized();

                if (table.unwrap().column(colName).isEmpty()) {
                    errors.add(ValidationError.columnNotFound(colName, tableName, item.span()));
                }
            }
        }
    }

    private void validateDelete(CstNavigator delete, List<ValidationError> errors) {
        var qnames = delete.findAll("QualifiedName");

        if (qnames.isEmpty()) return;

        var tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();

        if (resolveTable(tableName).isEmpty()) {
            errors.add(ValidationError.tableNotFound(tableName, delete.span()));
        }
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
            var alias = extractAlias(ref, qnames.getFirst());
            var scopeName = alias.or(tableName);

            scope.registerFromCte(scopeName, tableName);

            return;
        }

        var table = resolveTable(tableName);

        if (table.isEmpty()) {
            errors.add(ValidationError.tableNotFound(tableName, ref.span()));

            return;
        }

        var alias = extractAlias(ref, qnames.getFirst());
        var scopeName = alias.or(tableName);

        scope.registerTable(scopeName, table.unwrap());
    }

    private Option<String> extractAlias(CstNavigator ref, CstNavigator tableQname) {
        var qnameColIds = tableQname.findAll("ColId").stream().map(CstNavigator::span).toList();
        var allColIds = ref.findAll("ColId");

        for (var colId : allColIds) {
            if (!qnameColIds.contains(colId.span())) {
                return Option.present(CstExtractor.extractIdentifier(colId).normalized());
            }
        }

        return Option.empty();
    }

    private void validateColumnRefs(CstNavigator nav, Scope scope, List<ValidationError> errors) {
        for (var qnav : nav.findAll("QualifiedName")) {
            var qname = CstExtractor.extractQualifiedName(qnav);

            if (qname.parts().size() >= 2) {
                var tableOrAlias = qname.parts().getFirst().normalized();
                var colName = qname.parts().getLast().normalized();

                if ("*".equals(colName)) continue;

                validateQualifiedRef(tableOrAlias, colName, qnav.span(), scope, errors);
            }
        }

        for (var primary : nav.findAll("PrimaryExpr")) {
            var colIds = primary.allChildren("ColId");
            var qnameContinuations = primary.allChildren("QualifiedName");

            if (!colIds.isEmpty() && !qnameContinuations.isEmpty()) {
                var tableOrAlias = CstExtractor.extractIdentifier(colIds.getFirst()).normalized();
                var contColIds = qnameContinuations.getFirst().findAll("ColId");

                if (!contColIds.isEmpty()) {
                    var colName = CstExtractor.extractIdentifier(contColIds.getFirst()).normalized();

                    if (!"*".equals(colName)) {
                        validateQualifiedRef(tableOrAlias, colName, primary.span(), scope, errors);
                    }
                }
            }
        }
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
    }
}
