// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.transform.CstExtractor;
import org.pragmatica.aether.pg.parser.transform.CstNavigator;
import org.pragmatica.aether.pg.parser.ast.common.Identifier;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.aether.pg.schema.model.Table;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// Pairs the bind parameters of one statement with the columns they are written against, read off the pg-parser
/// syntax tree (#2401). Comments and whitespace are trivia the tree does not carry, and every identifier goes through
/// [Identifier#normalized()]: unquoted names fold to lower case, quoted names keep theirs.
///
/// A parameter occurrence is paired when the tree places it against a column of a table the schema knows:
/// - the n-th value of every `VALUES` row of an `INSERT` that names its columns (equal counts);
/// - the value of a `SET col = ..` or `SET (a, b) = (.., ..)` item, in `UPDATE` and in `ON CONFLICT DO UPDATE`;
/// - an operand of `=`, `<>`, `<`, `IN (..)`, `LIKE`, `BETWEEN` or `IS DISTINCT FROM` whose other side is a column.
///
/// The column resolves through the statement's own tables: the target (by name and alias), `FROM`/`USING`
/// relations, `EXCLUDED` inside `ON CONFLICT`, and the relations of each enclosing `SELECT`. An occurrence under
/// `::type` or `CAST(.. AS type)` is cast; every other occurrence is unplaced.
@Contract
public final class SqlParamPairing {
    private SqlParamPairing() {}

    /// A parameter written against `column` of `table`. `cast`: the parameter sits under a cast.
    public record Pair(Table table, String column, String param, boolean cast) {}

    /// What the tree says about one statement: its pairs, the parameters with at least one unplaced occurrence, and
    /// the table an `INSERT`, `UPDATE` or `DELETE` writes (empty for a `SELECT`, or a table the schema lacks).
    public record Pairing(List<Pair> pairs, Set<String> unplaced, Option<Table> target) {
        public static final Pairing NONE = new Pairing(List.of(), Set.of(), Option.empty());
    }

    /// Pairs the parameters of `root`; `bindOrder` names the parameter bound to `$n` at index `n - 1`.
    public static Pairing pairing(CstNode root, List<String> bindOrder, Schema schema) {
        var walker = new Walker(bindOrder, schema);

        walker.visit(root, Scope.TOP);

        return walker.result();
    }

    /// Whether the outermost statement of `root` is an `INSERT` that names its columns.
    public static boolean insertNamesColumns(CstNode root) {
        return firstInsert(root).flatMap(SqlParamPairing::insertColumnList)
                          .isPresent();
    }

    private static Option<CstNavigator> firstInsert(CstNode node) {
        if (! (node instanceof CstNode.NonTerminal nt)) {
            return Option.empty();
        }

        if (INSERT.equals(nt.ruleName())) {
            return Option.present(CstNavigator.of(nt));
        }

        for (var child : nt.children()) {
            var found = firstInsert(child);

            if (found.isPresent()) {
                return found;
            }
        }

        return Option.empty();
    }

    /// The column list of an `INSERT`. With an alias (`INSERT INTO t AS a (c)`) the grammar hangs the list under the
    /// `Alias` node; an `INSERT` alias has no column-alias form, so the list there is still the statement's.
    private static Option<CstNavigator> insertColumnList(CstNavigator insert) {
        var direct = insert.child("ColumnList");

        if (direct.isPresent()) {
            return direct;
        }

        return insert.child("Alias")
                     .flatMap(alias -> alias.child("ColumnList"));
    }

    private static final String INSERT = "InsertStmt";
    private static final String UPDATE = "UpdateStmt";
    private static final String DELETE = "DeleteStmt";
    private static final String SELECT = "SelectStmt";
    private static final String EXCLUDED = "excluded";
    private static final Set<String> ALIAS_KEYWORDS = Set.of("as");
    private static final Set<String> DERIVED_RELATIONS = Set.of("SubqueryRef", "LateralRef", "FuncTableRef");

    /// The relations a name can resolve against, innermost first. A relation the schema lacks (a CTE, an unknown
    /// table) is present with an empty table: a bare column may belong to it, so it blocks resolution.
    private record Scope(Map<String, Option<Table>> relations, Option<Scope> parent) {
        static final Scope TOP = new Scope(Map.of(), Option.empty());

        static Scope nested(Scope parent) {
            return new Scope(new HashMap<>(), Option.present(parent));
        }

        void register(String name, Option<Table> table) {
            relations.put(name, table);
        }

        Option<Table> qualified(String relation) {
            var table = relations.get(relation);

            if (table != null) {
                return table;
            }

            return parent.flatMap(outer -> outer.qualified(relation));
        }

        Option<Table> bare(String column) {
            var owners = new LinkedHashSet<Table>();
            var blocked = false;

            for (var table : relations.values()) {
                if (table.isEmpty()) {
                    blocked = true;
                } else if (table.unwrap().column(column).isPresent()) {
                    owners.add(table.unwrap());
                }
            }

            if (owners.size() == 1 && !blocked) {
                return Option.present(owners.getFirst());
            }

            if (owners.isEmpty() && !blocked) {
                return parent.flatMap(outer -> outer.bare(column));
            }

            return Option.empty();
        }
    }

    private record Hit(String param, int offset, boolean cast) {}

    private record Column(Table table, String name) {}

    private static final class Walker {
        private final List<String> bindOrder;
        private final Schema schema;
        private final List<Pair> pairs = new ArrayList<>();
        private final Set<Integer> claimed = new HashSet<>();
        private final List<Hit> occurrences = new ArrayList<>();
        private Option<Table> target = Option.empty();

        Walker(List<String> bindOrder, Schema schema) {
            this.bindOrder = bindOrder;
            this.schema = schema;
        }

        Pairing result() {
            var unplaced = new LinkedHashSet<String>();

            for (var hit : occurrences) {
                if (!claimed.contains(hit.offset())) {
                    unplaced.add(hit.param());
                }
            }

            return new Pairing(List.copyOf(pairs), Set.copyOf(unplaced), target);
        }

        void visit(CstNode node, Scope scope) {
            switch (node) {
                case CstNode.NonTerminal nt -> visitRule(CstNavigator.of(nt), scope);
                case CstNode.Token token when"ParamRef".equals(token.ruleName()) -> hitOf(token, false).onPresent(occurrences::add);
                default -> {}
            }
        }

        private void visitChildren(CstNavigator node, Scope scope) {
            for (var child : node.node().children()) {
                visit(child, scope);
            }
        }

        private void visitRule(CstNavigator node, Scope scope) {
            switch (node.rule()) {
                case INSERT -> visitInsert(node, scope);
                case UPDATE -> visitWrite(node, scope, "FromClause");
                case DELETE -> visitWrite(node, scope, "UsingClauseDelete");
                case SELECT -> visitSelect(node, scope);
                case "CompareExpr" -> {
                    pairComparison(node, scope);
                    visitChildren(node, scope);
                }
                case "IsExpr" -> {
                    pairDistinct(node, scope);
                    visitChildren(node, scope);
                }
                case "TypeCastExpr", "CastExpr" -> {
                    paramOf(node).filter(Hit::cast).onPresent(hit -> claimed.add(hit.offset()));
                    visitChildren(node, scope);
                }
                default -> visitChildren(node, scope);
            }
        }

        // --- statements ---
        private void visitInsert(CstNavigator insert, Scope outer) {
            var scope = Scope.nested(outer);
            var table = registerTarget(insert, scope);

            table.onPresent(known -> insertColumnList(insert).onPresent(list -> pairValues(insert, known, names(list))));
            for (var child : insert.node().children()) {
                if (child instanceof CstNode.NonTerminal nt && "InsertSource".equals(nt.ruleName())) {
                    // The source query never sees the target relation.
                    visit(child, outer);
                } else if (child instanceof CstNode.NonTerminal nt && "OnConflictClause".equals(nt.ruleName())) {
                    visitOnConflict(CstNavigator.of(nt), scope, table);
                } else {
                    visit(child, scope);
                }
            }
        }

        private void visitWrite(CstNavigator statement, Scope outer, String relationsClause) {
            var scope = Scope.nested(outer);
            var table = registerTarget(statement, scope);

            statement.child(relationsClause).onPresent(clause -> registerOwnedRelations(clause.node(), scope));
            table.onPresent(known -> pairSetItems(statement, known));
            visitChildren(statement, scope);
        }

        private void visitSelect(CstNavigator select, Scope outer) {
            var scope = Scope.nested(outer);

            for (var child : select.node().children()) {
                registerOwnedRelations(child, scope);
            }

            visitChildren(select, scope);
        }

        private void visitOnConflict(CstNavigator clause, Scope targetScope, Option<Table> table) {
            var actionScope = Scope.nested(targetScope);

            actionScope.register(EXCLUDED, table);
            for (var child : clause.node().children()) {
                if (child instanceof CstNode.NonTerminal nt && "ConflictAction".equals(nt.ruleName())) {
                    var action = CstNavigator.of(nt);

                    table.onPresent(known -> pairSetItems(action, known));
                    visit(child, actionScope);
                } else {
                    visit(child, targetScope);
                }
            }
        }

        /// Registers the statement's target under its written name and its alias. The first `INSERT`/`UPDATE`/`DELETE`
        /// reached is the outermost one, which is the statement's target.
        private Option<Table> registerTarget(CstNavigator statement, Scope scope) {
            var name = statement.child("QualifiedName")
                                .map(qname -> CstExtractor.extractQualifiedName(qname).normalized());

            if (name.isEmpty()) {
                return Option.empty();
            }

            var written = name.unwrap();
            var table = table(written);

            scope.register(written, table);
            statement.child("Alias")
                     .flatMap(SqlParamPairing::aliasName)
                     .onPresent(alias -> scope.register(alias, table));
            target = target.orElse(() -> table);

            return table;
        }

        /// The base tables `node` owns; the walk stops at a nested `SELECT`, whose relations are its own.
        private void registerOwnedRelations(CstNode node, Scope scope) {
            if (! (node instanceof CstNode.NonTerminal nt) || SELECT.equals(nt.ruleName())) {
                return;
            }

            if ("BaseTableRef".equals(nt.ruleName())) {
                registerRelation(CstNavigator.of(nt), scope);

                return;
            }

            if (DERIVED_RELATIONS.contains(nt.ruleName())) {
                // A subquery or function in FROM: its columns are unknown, so it blocks bare-name resolution.
                scope.register(CstNavigator.of(nt)
                                           .child("Alias")
                                           .flatMap(SqlParamPairing::aliasName)
                                           .or("(derived " + nt.span().start().offset() + ")"),
                               Option.empty());

                return;
            }

            for (var child : nt.children()) {
                registerOwnedRelations(child, scope);
            }
        }

        private void registerRelation(CstNavigator ref, Scope scope) {
            ref.child("QualifiedName")
               .map(qname -> CstExtractor.extractQualifiedName(qname).normalized())
               .onPresent(written -> {
                              var table = table(written);
                              var alias = ref.child("Alias")
                                             .flatMap(SqlParamPairing::aliasName);

                              scope.register(alias.or(written),
                                             table);
                          });
        }

        /// Looked up the way the query validator resolves a table: as written, then in `public` when unqualified.
        private Option<Table> table(String name) {
            var table = schema.table(name);

            if (table.isPresent() || name.contains(".")) {
                return table;
            }

            return schema.table("public." + name);
        }

        // --- pairs ---
        private void pairValues(CstNavigator insert, Table table, List<String> columns) {
            insert.child("InsertSource")
                  .flatMap(SqlParamPairing::valuesRows)
                  .onPresent(rows -> {
                                 for (var row : rows.allChildren("ExprOrDefaultList")) {
                                     pairList(table,
                                              columns,
                                              row.allChildren("ExprOrDefault"));
                                 }
                             });
        }

        private void pairSetItems(CstNavigator owner, Table table) {
            owner.child("UpdateSetList")
                 .onPresent(list -> {
                     for (var item : list.allChildren("UpdateSetItem")) {
                         pairSetItem(item, table);
                     }
                 });
        }

        private void pairSetItem(CstNavigator item, Table table) {
            var columnList = item.child("ColumnList");

            if (columnList.isPresent()) {
                item.child("ExprOrDefaultList")
                    .onPresent(values -> pairList(table,
                                                  names(columnList.unwrap()),
                                                  values.allChildren("ExprOrDefault")));

                return;
            }

            CstExtractor.leadingIdentifier(item)
                        .map(Identifier::normalized)
                        .onPresent(column -> item.child("ExprOrDefault")
                                                 .flatMap(this::paramOf)
                                                 .onPresent(hit -> addPair(table, column, hit)));
        }

        /// The n-th value with the n-th column; nothing when the counts differ, since Postgres refuses that statement.
        private void pairList(Table table, List<String> columns, List<CstNavigator> values) {
            if (columns.size() != values.size()) {
                return;
            }

            for (int i = 0; i < columns.size(); i++) {
                var column = columns.get(i);

                paramOf(values.get(i)).onPresent(hit -> addPair(table, column, hit));
            }
        }

        /// `column <op> operand` (either side for a binary operator), `column IN (..)`, `column LIKE ..` and
        /// `column BETWEEN .. AND ..`.
        private void pairComparison(CstNavigator compare, Scope scope) {
            var parts = compare.children();

            if (parts.isEmpty() || !(parts.getFirst() instanceof CstNode.NonTerminal first)) {
                return;
            }

            var column = columnOf(CstNavigator.of(first), scope);

            for (var part : parts.subList(1, parts.size())) {
                if (part instanceof CstNode.NonTerminal nt) {
                    var operands = operands(CstNavigator.of(nt));

                    column.onPresent(known -> operands.forEach(operand -> paramOf(operand).onPresent(hit -> addPair(known.table(),
                                                                                                                    known.name(),
                                                                                                                    hit))));
                    if ("IsExpr".equals(nt.ruleName())) {
                        columnOf(CstNavigator.of(nt), scope).onPresent(known -> paramOf(CstNavigator.of(first)).onPresent(hit -> addPair(known.table(),
                                                                                                                                         known.name(),
                                                                                                                                         hit)));
                    }
                }
            }
        }

        /// `column IS [NOT] DISTINCT FROM operand`, which the grammar parses as an `IsClause` of the column.
        private void pairDistinct(CstNavigator is, Scope scope) {
            var operand = is.child("IsClause").flatMap(clause -> clause.child("AddExpr"));

            if (operand.isEmpty()) {
                return;
            }

            is.child("AddExpr")
              .flatMap(left -> columnOf(left, scope))
              .onPresent(known -> paramOf(operand.unwrap()).onPresent(hit -> addPair(known.table(),
                                                                                     known.name(),
                                                                                     hit)));
        }

        private static List<CstNavigator> operands(CstNavigator part) {
            return switch (part.rule()) {
                case "IsExpr" -> List.of(part);
                case "InExpr" -> part.child("ExprList").map(list -> list.allChildren("Expr")).or(List.of());
                case "LikeExpr" -> part.firstChild().map(operand -> List.of(operand)).or(List.of());
                case "BetweenExpr" -> nonTerminals(part);
                default -> List.of();
            };
        }

        private static List<CstNavigator> nonTerminals(CstNavigator node) {
            var result = new ArrayList<CstNavigator>();

            for (var child : node.children()) {
                if (child instanceof CstNode.NonTerminal nt) {
                    result.add(CstNavigator.of(nt));
                }
            }

            return result;
        }

        private void addPair(Table table, String column, Hit hit) {
            pairs.add(new Pair(table, column, hit.param(), hit.cast()));
            claimed.add(hit.offset());
        }

        // --- operands ---
        /// The column an expression is, when it is nothing but a column reference.
        private static Option<Column> columnOf(CstNavigator expression, Scope scope) {
            var current = expression;

            while (!"ColRef".equals(current.rule())) {
                var single = onlyRule(current);

                if (single.isEmpty()) {
                    return Option.empty();
                }

                current = single.unwrap();
            }

            return current.child("QualifiedName")
                          .flatMap(qname -> resolve(CstExtractor.extractQualifiedName(qname).parts(),
                                                    scope));
        }

        private static Option<Column> resolve(List<Identifier> parts, Scope scope) {
            if (parts.isEmpty()) {
                return Option.empty();
            }

            var names = parts.stream().map(Identifier::normalized).toList();
            var column = names.getLast();
            var table = names.size() == 1
                        ? scope.bare(column)
                        : scope.qualified(String.join(".",
                                                      names.subList(0, names.size() - 1)));

            return table.map(known -> new Column(known, column));
        }

        /// The parameter an expression is, when it is nothing but a parameter, possibly parenthesised or cast.
        private Option<Hit> paramOf(CstNavigator expression) {
            var current = expression;
            var cast = false;

            while (true) {
                var parts = current.children();

                if (parts.size() == 1 && parts.getFirst() instanceof CstNode.Token token && "ParamRef".equals(token.ruleName())) {
                    return hitOf(token, cast);
                }

                var next = onlyRule(current);

                if (next.isEmpty() && "TypeCastExpr".equals(current.rule()) && current.has("DataType")) {
                    next = current.child("PostfixExpr");
                    cast = true;
                } else if (next.isEmpty() && "CastExpr".equals(current.rule())) {
                    next = current.child("Expr");
                    cast = true;
                } else if (next.isEmpty() && parenthesised(current)) {
                    next = current.child("Expr");
                }

                if (next.isEmpty()) {
                    return Option.empty();
                }

                current = next.unwrap();
            }
        }

        /// `'(' Expr ')'`.
        private static boolean parenthesised(CstNavigator node) {
            var parts = node.children();

            return parts.size() == 3
                   && parts.getFirst() instanceof CstNode.Terminal open
                   && "(".equals(open.text())
                   && parts.get(1) instanceof CstNode.NonTerminal inner
                   && "Expr".equals(inner.ruleName())
                   && parts.getLast() instanceof CstNode.Terminal close
                   && ")".equals(close.text());
        }

        /// The single child of a node that has nothing else: a pure wrapper rule.
        private static Option<CstNavigator> onlyRule(CstNavigator node) {
            var parts = node.children();

            if (parts.size() == 1 && parts.getFirst() instanceof CstNode.NonTerminal nt) {
                return Option.present(CstNavigator.of(nt));
            }

            return Option.empty();
        }

        /// The bound parameter a `$n` token names, by the generated bind order.
        private Option<Hit> hitOf(CstNode.Token token, boolean cast) {
            return positionName(token.text()).map(name -> new Hit(name,
                                                                  token.span().start().offset(),
                                                                  cast));
        }

        private Option<String> positionName(String placeholder) {
            var index = Integer.parseInt(placeholder.substring(1)) - 1;

            if (index < 0 || index >= bindOrder.size()) {
                return Option.empty();
            }

            return Option.present(bindOrder.get(index));
        }
    }

    /// The `VALUES` rows of an insert source, which the grammar parses as a `SELECT` whose core is `VALUES`.
    private static Option<CstNavigator> valuesRows(CstNavigator source) {
        return source.child(SELECT)
                     .flatMap(select -> select.child("SelectCore"))
                     .flatMap(core -> core.child("ValuesClause"))
                     .flatMap(values -> values.child("ValueRowList"));
    }

    private static List<String> names(CstNavigator columnList) {
        return CstExtractor.extractColumnList(columnList)
                           .stream()
                           .map(Identifier::normalized)
                           .toList();
    }

    private static Option<String> aliasName(CstNavigator alias) {
        var names = CstExtractor.leafIdentifiers(alias, ALIAS_KEYWORDS);

        if (names.isEmpty()) {
            return Option.empty();
        }

        return Option.present(names.getFirst().normalized());
    }
}
