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

    /// Validate a parsed SQL statement against the schema.
    public ValidationResult validate(CstNode cst) {
        var nav = CstNavigator.wrap(cst);
        if ( nav.isEmpty()) {
        return ValidationResult.empty();}
        return validateRoot(nav.unwrap());
    }

    private ValidationResult validateRoot(CstNavigator nav) {
        var errors = new ArrayList<ValidationError>();
        // Find DML by keywords — rule names may be inlined in CST
        for ( var select : nav.findAll("SelectCore")) {
        validateSelect(select, new Scope(), errors);}
        // INSERT: find by InsertKW + IntoKW context
        var insertStmts = nav.findAll("InsertStmt");
        if ( insertStmts.isEmpty() && !nav.findAll("InsertKW").isEmpty()) {
        validateInsert(nav, errors);} else
        {
        for ( var insert : insertStmts) { validateInsert(insert, errors);}}
        // UPDATE: find by UpdateKW + SetKW context
        var updateStmts = nav.findAll("UpdateStmt");
        if ( updateStmts.isEmpty() && !nav.findAll("UpdateKW").isEmpty() && !nav.findAll("SetKW").isEmpty()) {
        validateUpdate(nav, errors);} else
        {
        for ( var update : updateStmts) { validateUpdate(update, errors);}}
        // DELETE: find by DeleteKW + FromKW context
        var deleteStmts = nav.findAll("DeleteStmt");
        if ( deleteStmts.isEmpty() && !nav.findAll("DeleteKW").isEmpty()) {
        validateDelete(nav, errors);} else
        {
        for ( var delete : deleteStmts) { validateDelete(delete, errors);}}
        return new ValidationResult(errors);
    }

    // === SELECT validation ===
    private void validateSelect(CstNavigator select, Scope parentScope, List<ValidationError> errors) {
        var scope = new Scope(parentScope);
        // Resolve FROM clause — register tables and aliases
        var fromClauses = select.findAll("FromClause");
        for ( var from : fromClauses) {
        resolveFromClause(from, scope, errors);}
        // Validate all column references in the entire SELECT (target list, WHERE, GROUP BY, etc.)
        validateColumnRefs(select, scope, errors);
    }

    // === INSERT validation ===
    private void validateInsert(CstNavigator insert, List<ValidationError> errors) {
        var qnames = insert.findAll("QualifiedName");
        if ( qnames.isEmpty()) return;
        var tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();
        var table = resolveTable(tableName);
        if ( table.isEmpty()) {
            errors.add(ValidationError.tableNotFound(tableName, insert.span()));
            return;
        }
        // Validate column list if specified
        var columnLists = insert.findAll("ColumnList");
        if ( !columnLists.isEmpty()) {
            var cols = CstExtractor.extractColumnList(columnLists.getFirst());
            for ( var col : cols) {
            if ( table.unwrap().column(col.normalized())
                             .isEmpty()) {
            errors.add(ValidationError.columnNotFound(col.normalized(), tableName, insert.span()));}}
        }
    }

    // === UPDATE validation ===
    private void validateUpdate(CstNavigator update, List<ValidationError> errors) {
        var qnames = update.findAll("QualifiedName");
        if ( qnames.isEmpty()) return;
        var tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();
        var table = resolveTable(tableName);
        if ( table.isEmpty()) {
            errors.add(ValidationError.tableNotFound(tableName, update.span()));
            return;
        }
        // Validate SET column names
        var setItems = update.findAll("UpdateSetItem");
        for ( var item : setItems) {
            var colIds = item.findAll("ColId");
            if ( !colIds.isEmpty()) {
                var colName = CstExtractor.extractIdentifier(colIds.getFirst()).normalized();
                if ( table.unwrap().column(colName)
                                 .isEmpty()) {
                errors.add(ValidationError.columnNotFound(colName, tableName, item.span()));}
            }
        }
    }

    // === DELETE validation ===
    private void validateDelete(CstNavigator delete, List<ValidationError> errors) {
        var qnames = delete.findAll("QualifiedName");
        if ( qnames.isEmpty()) return;
        var tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();
        if ( resolveTable(tableName).isEmpty()) {
        errors.add(ValidationError.tableNotFound(tableName, delete.span()));}
    }

    // === FROM clause resolution ===
    private void resolveFromClause(CstNavigator from, Scope scope, List<ValidationError> errors) {
        // Register all table references from FROM clause including JOINs.
        // Tables can be in BaseTableRef, TableRef, or JoinClause nodes.
        // Use a broad approach: find ALL QualifiedName+alias patterns in the FROM subtree.
        var baseRefs = from.findAll("BaseTableRef");
        if ( !baseRefs.isEmpty()) {
        for ( var ref : baseRefs) { resolveTableRef(ref, scope, errors);}}
        // Also check TableRef nodes (may contain tables not wrapped in BaseTableRef)
        var tableRefs = from.findAll("TableRef");
        for ( var ref : tableRefs) {
            // Only process if it has a direct QualifiedName (not a subquery)
            var qnames = ref.allChildren("QualifiedName");
            if ( !qnames.isEmpty() && !ref.has("SelectStmt")) {
            resolveTableRef(ref, scope, errors);}
        }
        // Register JOIN tables — they appear in TableRefBase inside JoinClause
        var joinTableRefs = from.findAll("TableRefBase");
        for ( var ref : joinTableRefs) {
        resolveTableRef(ref, scope, errors);}
        // Fallback for simple FROM without structural wrappers
        if ( baseRefs.isEmpty() && tableRefs.isEmpty()) {
        resolveTableRef(from, scope, errors);}
    }

    private void resolveTableRef(CstNavigator ref, Scope scope, List<ValidationError> errors) {
        var qnames = ref.findAll("QualifiedName");
        if ( qnames.isEmpty()) return;
        var tableName = CstExtractor.extractQualifiedName(qnames.getFirst()).normalized();
        var table = resolveTable(tableName);
        if ( table.isEmpty()) {
            errors.add(ValidationError.tableNotFound(tableName, ref.span()));
            return;
        }
        // Register in scope with alias if present
        var alias = extractAlias(ref, qnames.getFirst());
        var scopeName = alias.or(tableName);
        scope.registerTable(scopeName, table.unwrap());
    }

    private Option<String> extractAlias(CstNavigator ref, CstNavigator tableQname) {
        // Look for ColId nodes that are NOT part of the table's QualifiedName
        var qnameColIds = tableQname.findAll("ColId").stream()
                                            .map(CstNavigator::span)
                                            .toList();
        var allColIds = ref.findAll("ColId");
        for ( var colId : allColIds) {
        if ( !qnameColIds.contains(colId.span())) {
        return Option.present(CstExtractor.extractIdentifier(colId).normalized());}}
        return Option.empty();
    }

    // === Column reference validation ===
    private void validateColumnRefs(CstNavigator nav, Scope scope, List<ValidationError> errors) {
        // Qualified column references like u.name may appear as:
        // 1. QualifiedName with 2+ parts (interpreted parser sometimes)
        // 2. PostfixExpr: ColRef(u) + PostfixOp(. name) (generated parser / expression context)
        // Handle both by looking for QualifiedName with 2+ parts AND PostfixOp with dot access.
        // Check QualifiedName with 2+ parts
        for ( var qnav : nav.findAll("QualifiedName")) {
            var qname = CstExtractor.extractQualifiedName(qnav);
            if ( qname.parts().size() >= 2) {
                var tableOrAlias = qname.parts().getFirst()
                                              .normalized();
                var colName = qname.parts().getLast()
                                         .normalized();
                if ( "*".equals(colName)) continue;
                validateQualifiedRef(tableOrAlias, colName, qnav.span(), scope, errors);
            }
        }
        // Check PrimaryExpr for dot-access: ColId(u) + QualifiedName(. col) as siblings
        // The generated parser splits table.column into sibling ColId + QualifiedName
        for ( var primary : nav.findAll("PrimaryExpr")) {
            var colIds = primary.allChildren("ColId");
            var qnameContinuations = primary.allChildren("QualifiedName");
            if ( !colIds.isEmpty() && !qnameContinuations.isEmpty()) {
                var tableOrAlias = CstExtractor.extractIdentifier(colIds.getFirst()).normalized();
                // The QualifiedName continuation contains '.' + ColId(column)
                var contColIds = qnameContinuations.getFirst().findAll("ColId");
                if ( !contColIds.isEmpty()) {
                    var colName = CstExtractor.extractIdentifier(contColIds.getFirst()).normalized();
                    if ( !"*".equals(colName)) {
                    validateQualifiedRef(tableOrAlias, colName, primary.span(), scope, errors);}
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
        if ( table.isEmpty()) {
        errors.add(ValidationError.tableOrAliasNotFound(tableOrAlias, span));} else
        if ( table.unwrap().column(colName)
                         .isEmpty()) {
        errors.add(ValidationError.columnNotFound(colName, tableOrAlias, span));}
    }

    // === Table resolution ===
    private Option<Table> resolveTable(String name) {
        // Try exact match first
        var table = schema.table(name);
        if ( table.isPresent()) return table;
        // Try with default "public" schema
        if ( !name.contains(".")) {
        return schema.table("public." + name);}
        return Option.empty();
    }

    // === Scope for alias tracking ===
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
            if ( local != null) return Option.present(local);
            return parent.flatMap(p -> p.getTable(nameOrAlias));
        }

        boolean hasColumn(String colName) {
            return tables.values().stream()
                                .anyMatch(t -> t.column(colName).isPresent()) ||
            parent.isPresent() && parent.unwrap().hasColumn(colName);
        }

        boolean hasAnyTables() {
            return ! tables.isEmpty() || parent.isPresent() && parent.unwrap().hasAnyTables();
        }
    }
}
