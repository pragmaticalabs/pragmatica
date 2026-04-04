package org.pragmatica.aether.pg.schema.validator;

import org.pragmatica.aether.pg.parser.PostgresParser;
import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;
import org.pragmatica.aether.pg.parser.transform.CstExtractor;
import org.pragmatica.aether.pg.parser.transform.CstNavigator;
import org.pragmatica.aether.pg.schema.model.Column;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.aether.pg.schema.model.Table;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/// Validates SQL queries against a known schema.
/// Checks table existence, column references, and alias resolution.
public final class QueryValidator {
    private final Schema schema;

    private QueryValidator(Schema schema) {
        this.schema = schema;
    }

    public static QueryValidator queryValidator(Schema schema) {
        return new QueryValidator(schema);
    }

    public ValidationResult validate(CstNode cst) {
        var nav = CstNavigator.wrap(cst);
        if (nav.isEmpty()) {return ValidationResult.empty();}
        return validateRoot(nav.unwrap());
    }

    private ValidationResult validateRoot(CstNavigator nav) {
        var errors = new ArrayList<ValidationError>();
        for (var select : nav.findAll("SelectCore")) {validateSelect(select, new Scope(), errors);}
        var insertStmts = nav.findAll("InsertStmt");
        if (insertStmts.isEmpty() && !nav.findAll("InsertKW").isEmpty()) {validateInsert(nav, errors);} else {for (var insert : insertStmts) {validateInsert(insert,
                                                                                                                                                             errors);}}
        var updateStmts = nav.findAll("UpdateStmt");
        if (updateStmts.isEmpty() && !nav.findAll("UpdateKW").isEmpty() && !nav.findAll("SetKW").isEmpty()) {validateUpdate(nav,
                                                                                                                            errors);} else {for (var update : updateStmts) {validateUpdate(update,
                                                                                                                                                                                           errors);}}
        var deleteStmts = nav.findAll("DeleteStmt");
        if (deleteStmts.isEmpty() && !nav.findAll("DeleteKW").isEmpty()) {validateDelete(nav, errors);} else {for (var delete : deleteStmts) {validateDelete(delete,
                                                                                                                                                             errors);}}
        return new ValidationResult(errors);
    }

    private void validateSelect(CstNavigator select, Scope parentScope, List<ValidationError> errors) {
        var scope = new Scope(parentScope);
        var fromClauses = select.findAll("FromClause");
        for (var from : fromClauses) {resolveFromClause(from, scope, errors);}
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
            for (var col : cols) {if (table.unwrap().column(col.normalized())
                                                  .isEmpty()) {errors.add(ValidationError.columnNotFound(col.normalized(),
                                                                                                         tableName,
                                                                                                         insert.span()));}}
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
                if (table.unwrap().column(colName)
                                .isEmpty()) {errors.add(ValidationError.columnNotFound(colName, tableName, item.span()));}
            }
        }
    }

    private void validateDelete(CstNavigator delete, List<ValidationError> errors) {
        var qnames = delete.findAll("QualifiedName");
        if (qnames.isEmpty()) return;
        var tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();
        if (resolveTable(tableName).isEmpty()) {errors.add(ValidationError.tableNotFound(tableName, delete.span()));}
    }

    private void resolveFromClause(CstNavigator from, Scope scope, List<ValidationError> errors) {
        var baseRefs = from.findAll("BaseTableRef");
        if (!baseRefs.isEmpty()) {for (var ref : baseRefs) {resolveTableRef(ref, scope, errors);}}
        var tableRefs = from.findAll("TableRef");
        for (var ref : tableRefs) {
            var qnames = ref.allChildren("QualifiedName");
            if (!qnames.isEmpty() && !ref.has("SelectStmt")) {resolveTableRef(ref, scope, errors);}
        }
        var joinTableRefs = from.findAll("TableRefBase");
        for (var ref : joinTableRefs) {resolveTableRef(ref, scope, errors);}
        if (baseRefs.isEmpty() && tableRefs.isEmpty()) {resolveTableRef(from, scope, errors);}
    }

    private void resolveTableRef(CstNavigator ref, Scope scope, List<ValidationError> errors) {
        var qnames = ref.findAll("QualifiedName");
        if (qnames.isEmpty()) return;
        var tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();
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
        var qnameColIds = tableQname.findAll("ColId").stream()
                                            .map(CstNavigator::span)
                                            .toList();
        var allColIds = ref.findAll("ColId");
        for (var colId : allColIds) {if (!qnameColIds.contains(colId.span())) {return Option.present(CstExtractor.extractIdentifier(colId)
                                                                                                                                   .normalized());}}
        return Option.empty();
    }

    private void validateColumnRefs(CstNavigator nav, Scope scope, List<ValidationError> errors) {
        for (var qnav : nav.findAll("QualifiedName")) {
            var qname = CstExtractor.extractQualifiedName(qnav);
            if (qname.parts().size() >= 2) {
                var tableOrAlias = qname.parts().getFirst()
                                              .normalized();
                var colName = qname.parts().getLast()
                                         .normalized();
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
                    if (!"*".equals(colName)) {validateQualifiedRef(tableOrAlias, colName, primary.span(), scope, errors);}
                }
            }
        }
    }

    private void validateQualifiedRef(String tableOrAlias,
                                      String colName,
                                      SourceSpan span,
                                      Scope scope,
                                      List<ValidationError> errors) {
        var table = scope.getTable(tableOrAlias);
        if (table.isEmpty()) {errors.add(ValidationError.tableOrAliasNotFound(tableOrAlias, span));} else if (table.unwrap().column(colName)
                                                                                                                          .isEmpty()) {errors.add(ValidationError.columnNotFound(colName,
                                                                                                                                                                                 tableOrAlias,
                                                                                                                                                                                 span));}
    }

    private Option<Table> resolveTable(String name) {
        var table = schema.table(name);
        if (table.isPresent()) return table;
        if (!name.contains(".")) {return schema.table("public." + name);}
        return Option.empty();
    }

    static final class Scope {
        private final Map<String, Table> tables = new HashMap<>();

        private final Option<Scope> parent;

        Scope() {
            this.parent = Option.empty();
        }

        Scope(Scope parent) {
            this.parent = Option.present(parent);
        }

        void registerTable(String nameOrAlias, Table table) {
            tables.put(nameOrAlias, table);
        }

        Option<Table> getTable(String nameOrAlias) {
            var local = tables.get(nameOrAlias);
            if (local != null) return Option.present(local);
            return parent.flatMap(p -> p.getTable(nameOrAlias));
        }

        boolean hasColumn(String colName) {
            return tables.values().stream()
                                .anyMatch(t -> t.column(colName).isPresent()) || parent.isPresent() && parent.unwrap()
                                                                                                                    .hasColumn(colName);
        }

        boolean hasAnyTables() {
            return ! tables.isEmpty() || parent.isPresent() && parent.unwrap().hasAnyTables();
        }
    }
}
