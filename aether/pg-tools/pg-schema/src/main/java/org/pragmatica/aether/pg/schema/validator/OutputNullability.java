// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.schema.validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.transform.CstExtractor;
import org.pragmatica.aether.pg.parser.transform.CstNavigator;
import org.pragmatica.aether.pg.schema.model.Column;
import org.pragmatica.aether.pg.schema.model.Schema;
import org.pragmatica.aether.pg.schema.model.Table;
import org.pragmatica.lang.Option;


/// Whether each OUTPUT EXPRESSION of a query can be NULL (#2889), computed from the parse tree.
///
/// Nullability is a property of the expression, never of column metadata alone: a `NOT NULL` column is still NULL
/// on the nullable side of an outer join, in `max`/`min`/`sum` over an empty group, in a `CASE` without `ELSE`,
/// through `NULLIF`, from an empty scalar subquery, and in a set operation with a nullable branch.
///
/// An output is [Nullability.NonNull] only when it is PROVEN so: a `NOT NULL` column of the `FROM` root or of an
/// inner-joined table, `count(..)`, a non-`NULL` literal, or `COALESCE` whose last argument is itself proven. A
/// cast, a sign and parentheses keep the verdict of their operand. Everything else is [Nullability.MayBeNull], with
/// the reason that names why — an unproven form is reported, not guessed non-null.
///
/// Schema facts come from the supplied [Schema]; a relation the schema does not know (a derived table, a CTE, a
/// function) proves nothing.
public final class OutputNullability {
    private OutputNullability() {}

    /// The verdict for one output expression.
    public sealed interface Nullability {
        /// Proven never NULL.
        record NonNull() implements Nullability {}

        /// Not proven non-null; `reason` says which form makes it so.
        record MayBeNull(String reason) implements Nullability {}

        Nullability NON_NULL = new NonNull();

        static Nullability mayBeNull(String reason) {
            return new MayBeNull(reason);
        }

        default boolean isNonNull() {
            return this instanceof NonNull;
        }
    }

    /// One output column: its name when the query gives it one, and its verdict.
    public record Output(Option<String> name, Nullability nullability) {}

    private static final Set<String> AGGREGATES = Set.of("max",
                                                         "min",
                                                         "sum",
                                                         "avg",
                                                         "bool_and",
                                                         "bool_or",
                                                         "every",
                                                         "array_agg",
                                                         "string_agg",
                                                         "json_agg",
                                                         "jsonb_agg",
                                                         "stddev",
                                                         "variance");

    private static final Set<String> TRANSPARENT_RULES = Set.of("Expr",
                                                                "OrExpr",
                                                                "AndExpr",
                                                                "NotExpr",
                                                                "CompareExpr",
                                                                "IsExpr",
                                                                "AddExpr",
                                                                "MulExpr",
                                                                "ExponentExpr",
                                                                "ConcatExpr",
                                                                "ArrayExpr",
                                                                "PostfixExpr",
                                                                "PrimaryExpr",
                                                                "ExprOrDefault");

    private static final Set<String> SUBTOTAL_KEYWORDS = Set.of("RollupKW", "CubeKW", "GroupingSetsKW");

    /// A relation visible to the output expressions. `table` is empty for a derived table, a CTE, a function or a
    /// table the schema does not know; `nullableSide` names the outer join that null-extends it, if any.
    private record Relation(String name, Option<Table> table, Option<String> nullableSide) {
        Relation nullExtended(String join) {
            return new Relation(name,
                                table,
                                nullableSide.isPresent()
                                ? nullableSide
                                : Option.present(join));
        }
    }

    /// The relations of one statement or branch, and whether a subtotal row can null its grouped columns.
    private record Scope(List<Relation> relations, boolean subtotals) {}

    /// The nullability of every output of the query's single statement: the `RETURNING` list of an
    /// `INSERT`/`UPDATE`/`DELETE`, the target list of a `SELECT`, or - for a set operation - the first branch's names
    /// with a position nullable when any branch's is.
    ///
    /// Absent when the outputs cannot be listed: the text is not exactly one statement, a branch is a `VALUES` list,
    /// a `*` ranges over a relation whose columns are unknown, or the branches differ in width.
    public static Option<List<Output>> analyze(CstNavigator queryRoot, Schema schema) {
        return QueryValidator.singleStatementBody(queryRoot).flatMap(body -> analyzeBody(body, schema));
    }

    private static Option<List<Output>> analyzeBody(CstNavigator body, Schema schema) {
        var ctes = cteNames(body);
        var returning = body.child("ReturningClause");

        if (returning.isPresent()) {
            return outputs(returning.unwrap(), statementScope(body, ctes, schema), schema);
        }

        return branchOutputs(QueryValidator.ownedSelectCores(body), ctes, schema);
    }

    private static Option<List<Output>> branchOutputs(List<CstNavigator> cores, List<String> ctes, Schema schema) {
        var branches = new ArrayList<List<Output>>();

        for (var core : cores) {
            var branch = outputs(core, coreScope(core, ctes, schema), schema);

            if (branch.isEmpty()) return Option.empty();

            branches.add(branch.unwrap());
        }

        return branches.isEmpty()
               ? Option.empty()
               : merge(branches);
    }

    private static Option<List<Output>> merge(List<List<Output>> branches) {
        var first = branches.getFirst();
        var merged = new ArrayList<Output>();

        for (var branch : branches) {
            if (branch.size() != first.size()) return Option.empty();
        }

        for (int position = 0; position < first.size(); position++) {
            merged.add(new Output(first.get(position).name(),
                                  mergedVerdict(branches, position)));
        }

        return Option.present(merged);
    }

    private static Nullability mergedVerdict(List<List<Output>> branches, int position) {
        for (int branch = 0; branch < branches.size(); branch++) {
            var verdict = branches.get(branch).get(position).nullability();

            if (verdict instanceof Nullability.MayBeNull nullable && branches.size() > 1) {
                return Nullability.mayBeNull("set operation branch " + (branch + 1) + ": " + nullable.reason());
            }

            if (!verdict.isNonNull()) return verdict;
        }

        return Nullability.NON_NULL;
    }

    private static List<String> cteNames(CstNavigator body) {
        var names = new ArrayList<String>();

        body.child("WithClause")
            .onPresent(with -> with.findAll("CteDef")
                                   .forEach(def -> def.child("ColId")
                                                      .onPresent(id -> names.add(CstExtractor.extractIdentifier(id).normalized()))));

        return names;
    }

    // --- scopes ---
    private static Scope coreScope(CstNavigator core, List<String> ctes, Schema schema) {
        var relations = new ArrayList<Relation>();

        core.child("FromClause").onPresent(from -> relations.addAll(fromList(from, ctes, schema)));

        return new Scope(relations, hasSubtotals(core));
    }

    private static boolean hasSubtotals(CstNavigator core) {
        return core.child("GroupByClause")
                   .map(group -> SUBTOTAL_KEYWORDS.stream().anyMatch(keyword -> !group.findAll(keyword)
                                                                                      .isEmpty()))
                   .or(false);
    }

    /// The target of an `INSERT`/`UPDATE`/`DELETE` plus its `FROM`/`USING` relations, for its `RETURNING` list.
    private static Scope statementScope(CstNavigator stmt, List<String> ctes, Schema schema) {
        var relations = new ArrayList<Relation>();

        stmt.child("QualifiedName")
            .onPresent(target -> relations.add(baseRelation(target,
                                                            stmt.child("Alias"),
                                                            ctes,
                                                            schema)));
        stmt.child("FromClause").onPresent(from -> relations.addAll(fromList(from, ctes, schema)));
        stmt.child("UsingClauseDelete").onPresent(using -> relations.addAll(fromList(using, ctes, schema)));

        return new Scope(relations, false);
    }

    private static List<Relation> fromList(CstNavigator owner, List<String> ctes, Schema schema) {
        var relations = new ArrayList<Relation>();

        owner.findAll("FromList")
             .stream()
             .findFirst()
             .ifPresent(list -> list.allChildren("TableRef")
                                    .forEach(ref -> relations.addAll(tableRef(ref, ctes, schema))));

        return relations;
    }

    /// `TableRef <- TableRefBase JoinClause*` (and `JoinExpr`, which nests a `TableRef`): joins fold left, so a
    /// `RIGHT`/`FULL` join null-extends everything accumulated so far and a `LEFT`/`FULL` join the relation it adds.
    private static List<Relation> tableRef(CstNavigator ref, List<String> ctes, Schema schema) {
        var relations = new ArrayList<Relation>();

        ref.firstChild().onPresent(first -> relations.addAll(refBase(first, ctes, schema)));
        for (var join : ref.allChildren("JoinClause")) {
            var added = join.child("TableRefBase").map(base -> refBase(base, ctes, schema)).or(List.of());
            var type = joinType(join);

            if (type.equals("RIGHT JOIN") || type.equals("FULL JOIN")) {
                relations.replaceAll(relation -> relation.nullExtended(type));
            }

            relations.addAll(type.equals("LEFT JOIN") || type.equals("FULL JOIN")
                             ? added.stream().map(relation -> relation.nullExtended(type)).toList()
                             : added);
        }

        return relations;
    }

    private static String joinType(CstNavigator join) {
        return join.child("JoinType")
                   .map(OutputNullability::outerJoinName)
                   .or("INNER JOIN");
    }

    private static String outerJoinName(CstNavigator type) {
        if (type.has("LeftKW")) return "LEFT JOIN";

        if (type.has("RightKW")) return "RIGHT JOIN";

        return type.has("FullKW")
               ? "FULL JOIN"
               : "INNER JOIN";
    }

    private static List<Relation> refBase(CstNavigator base, List<String> ctes, Schema schema) {
        var inner = base.firstChild();

        if (inner.isEmpty()) return List.of();

        var node = inner.unwrap();

        return switch (node.rule()) {
            case "BaseTableRef" -> node.child("QualifiedName").map(name -> List.of(baseRelation(name,
                                                                                                node.child("Alias"),
                                                                                                ctes,
                                                                                                schema))).or(List.of());
            case "JoinExpr" -> tableRef(node, ctes, schema);
            default -> List.of(derivedRelation(node));
        };
    }

    private static Relation baseRelation(CstNavigator qualifiedName,
                                         Option<CstNavigator> alias,
                                         List<String> ctes,
                                         Schema schema) {
        var written = CstExtractor.extractQualifiedName(qualifiedName).normalized();
        var bare = written.substring(written.lastIndexOf('.') + 1);
        var name = alias.flatMap(QueryValidator::aliasName).or(bare);
        var table = ctes.contains(written)
                    ? Option.<Table> empty()
                    : resolve(schema, written);

        return new Relation(name, table, Option.empty());
    }

    private static Option<Table> resolve(Schema schema, String name) {
        var table = schema.table(name);

        return table.isPresent() || name.contains(".")
               ? table
               : schema.table("public." + name);
    }

    private static Relation derivedRelation(CstNavigator node) {
        var name = node.child("Alias")
                       .flatMap(QueryValidator::aliasName)
                       .or(node.child("FuncTableRef")
                               .flatMap(func -> func.child("Alias"))
                               .flatMap(QueryValidator::aliasName)
                               .or(""));

        return new Relation(name, Option.empty(), Option.empty());
    }

    // --- output lists ---
    /// The outputs of a `SelectCore` or `ReturningClause`; absent when a target cannot be listed.
    private static Option<List<Output>> outputs(CstNavigator owner, Scope scope, Schema schema) {
        var list = owner.child("TargetList");

        if (list.isEmpty()) return Option.empty();

        var result = new ArrayList<Output>();

        for (var target : list.unwrap().allChildren("TargetElem")) {
            var produced = targetOutputs(target, scope);

            if (produced.isEmpty()) return Option.empty();

            result.addAll(produced.unwrap());
        }

        return result.isEmpty()
               ? Option.empty()
               : Option.present(result);
    }

    private static Option<List<Output>> targetOutputs(CstNavigator target, Scope scope) {
        var star = starQualifier(target);

        if (star.isPresent()) return expandStar(star.unwrap().qualifier(),
                                                scope);

        var verdict = target.child("Expr")
                            .map(expr -> evaluate(expr, scope))
                            .or(Nullability.mayBeNull("the output expression cannot be analysed"));

        return Option.present(List.of(new Output(QueryValidator.outputColumnName(target), verdict)));
    }

    /// A star output target: `qualifier` is empty for a bare `*` and the relation name for `q.*`.
    private record StarTarget(Option<String> qualifier) {}

    /// A [StarTarget] for a star, absent when the target is not a star. `q.*` parses as an expression whose
    /// `QualifiedName` ends in a `*` terminal.
    private static Option<StarTarget> starQualifier(CstNavigator target) {
        if (target.has("StarExpr")) {
            return Option.present(new StarTarget(target.child("StarExpr")
                                                       .flatMap(star -> star.child("QualifiedName"))
                                                       .flatMap(name -> lastQualifier(name))));
        }

        var names = target.findAll("QualifiedName");

        if (names.size() == 1 && endsInStar(names.getFirst())) {
            return Option.present(new StarTarget(lastQualifier(names.getFirst())));
        }

        return Option.empty();
    }

    private static boolean endsInStar(CstNavigator qualifiedName) {
        return qualifiedName.node()
                            .children()
                            .stream()
                            .anyMatch(child -> child instanceof CstNode.Terminal term && term.text()
                                                                                             .equals("*"));
    }

    private static Option<String> lastQualifier(CstNavigator qualifiedName) {
        var parts = CstExtractor.extractQualifiedName(qualifiedName).parts();

        return Option.from(parts.stream().reduce((_, tail) -> tail)).map(last -> last.normalized());
    }

    private static Option<List<Output>> expandStar(Option<String> qualifier, Scope scope) {
        var outputs = new ArrayList<Output>();
        var matched = false;

        for (var relation : scope.relations()) {
            if (qualifier.isPresent() && !qualifier.unwrap().equals(relation.name())) continue;

            matched = true;
            if (relation.table().isEmpty()) return Option.empty();

            for (var column : relation.table().unwrap().columns()) {
                outputs.add(new Output(Option.present(column.name()),
                                       columnVerdict(relation, column, scope)));
            }
        }

        return matched
               ? Option.present(outputs)
               : Option.empty();
    }

    // --- expressions ---
    private static Nullability evaluate(CstNavigator node, Scope scope) {
        return switch (node.rule()) {
            case "ColRef" -> columnRef(node, scope);
            case "Literal" -> literal(node);
            case "TypedLiteral" -> Nullability.NON_NULL;
            case "CoalesceExpr" -> coalesce(node, scope);
            case "CastExpr" -> operand(node.child("Expr"), scope);
            case "TypeCastExpr" -> operand(node.firstChild(), scope);
            case "UnaryExpr" -> signed(node, scope);
            case "FuncCall" -> functionCall(node);
            case "CaseExpr" -> Nullability.mayBeNull(node.has("ElseClause")
                                                     ? "a CASE result is not proven non-null"
                                                     : "CASE without ELSE yields NULL when no WHEN matches");
            case "NullIfExpr" -> Nullability.mayBeNull("NULLIF yields NULL when its arguments are equal");
            case "SubqueryExpr" -> Nullability.mayBeNull("a scalar subquery yields NULL when it returns no row");
            default -> transparentOrUnproven(node, scope);
        };
    }

    private static Nullability operand(Option<CstNavigator> operand, Scope scope) {
        return operand.map(node -> evaluate(node, scope))
                      .or(Nullability.mayBeNull("the operand cannot be analysed"));
    }

    private static Nullability signed(CstNavigator node, Scope scope) {
        return node.children()
                   .size() == 2
               ? operand(node.child("UnaryExpr"), scope)
               : transparentOrUnproven(node, scope);
    }

    /// A rule that adds nothing to its single operand keeps the operand's verdict; `( expr )` is the same.
    private static Nullability transparentOrUnproven(CstNavigator node, Scope scope) {
        var children = node.children();

        if (TRANSPARENT_RULES.contains(node.rule()) && children.size() == 1) {
            return operand(node.firstChild(), scope);
        }

        if (node.rule().equals("PrimaryExpr") && isParenthesised(node)) {
            return operand(node.child("Expr"), scope);
        }

        return transparentChain(node, scope);
    }

    private static Nullability transparentChain(CstNavigator node, Scope scope) {
        var onlyChild = node.children().size() == 1
                        ? node.firstChild()
                        : Option.<CstNavigator> empty();

        return onlyChild.isPresent() && !node.rule()
                                             .equals("TargetElem")
               ? evaluate(onlyChild.unwrap(), scope)
               : Nullability.mayBeNull(unprovenForm(node));
    }

    private static boolean isParenthesised(CstNavigator node) {
        return node.has("Expr")
               && node.allChildren("Expr")
                      .size() == 1
               && node.children()
                      .size() == 3;
    }

    private static String unprovenForm(CstNavigator node) {
        return switch (node.rule()) {
            case "AddExpr", "MulExpr", "ExponentExpr" -> "an arithmetic expression is not proven non-null";
            case "ConcatExpr" -> "a || expression is not proven non-null";
            case "CompareExpr", "IsExpr", "AndExpr", "OrExpr", "NotExpr" -> "a boolean expression is not proven non-null";
            case "PostfixExpr", "ArrayExpr" -> "a JSON, array or field access is not proven non-null";
            case "ExistsExpr" -> "an EXISTS expression is not proven non-null";
            default -> "the " + node.rule() + " expression is not proven non-null";
        };
    }

    private static Nullability literal(CstNavigator literal) {
        return literal.firstLeafText()
                      .filter(text -> text.equalsIgnoreCase("null"))
                      .isPresent()
               ? Nullability.mayBeNull("the NULL literal")
               : Nullability.NON_NULL;
    }

    /// `COALESCE` is non-null only when its LAST argument is proven so.
    private static Nullability coalesce(CstNavigator node, Scope scope) {
        var last = node.child("ExprList")
                       .flatMap(list -> Option.from(list.allChildren("Expr").stream().reduce((_, tail) -> tail)));

        if (last.isEmpty()) return Nullability.mayBeNull("COALESCE without a last argument");

        return switch (evaluate(last.unwrap(), scope)) {
            case Nullability.NonNull _ -> Nullability.NON_NULL;
            case Nullability.MayBeNull nullable -> Nullability.mayBeNull("COALESCE whose last argument may be NULL (" + nullable.reason() + ")");
        };
    }

    private static Nullability functionCall(CstNavigator call) {
        var name = call.child("FuncName")
                       .flatMap(func -> func.child("QualifiedName"))
                       .flatMap(qualified -> lastQualifier(qualified))
                       .map(String::toLowerCase)
                       .or("");

        if (name.equals("count")) return Nullability.NON_NULL;

        return Nullability.mayBeNull(AGGREGATES.contains(name)
                                     ? name + "() yields NULL over an empty group, or when every input is NULL"
                                     : "the result of " + name + "() is not proven non-null");
    }

    // --- columns ---
    private static Nullability columnRef(CstNavigator ref, Scope scope) {
        var parts = ref.child("QualifiedName")
                       .map(name -> CstExtractor.extractQualifiedName(name).parts())
                       .or(List.of());

        if (parts.isEmpty()) return Nullability.mayBeNull("the column reference cannot be analysed");

        var column = parts.getLast().normalized();
        var qualifier = parts.size() >= 2
                        ? Option.present(parts.get(parts.size() - 2).normalized())
                        : Option.<String> empty();

        return qualifier.isPresent()
               ? qualifiedColumn(qualifier.unwrap(), column, scope)
               : bareColumn(column, scope);
    }

    private static Nullability qualifiedColumn(String qualifier, String column, Scope scope) {
        var relation = scope.relations().stream().filter(candidate -> candidate.name()
                                                                               .equals(qualifier)).findFirst();

        return relation.map(found -> relationColumn(found, column, scope))
                       .orElse(Nullability.mayBeNull("column '" + qualifier + "." + column + "' cannot be resolved"));
    }

    private static Nullability bareColumn(String column, Scope scope) {
        if (scope.relations().stream().anyMatch(relation -> relation.table()
                                                                    .isEmpty())) {
            return Nullability.mayBeNull("column '" + column + "' may come from a derived table, CTE or function");
        }

        var verdicts = scope.relations()
                            .stream()
                            .filter(relation -> relation.table()
                                                        .unwrap()
                                                        .column(column)
                                                        .isPresent())
                            .map(relation -> relationColumn(relation, column, scope))
                            .toList();

        return verdicts.stream()
                       .filter(verdict -> !verdict.isNonNull())
                       .findFirst()
                       .orElse(verdicts.isEmpty()
                               ? Nullability.mayBeNull("column '" + column + "' cannot be resolved")
                               : Nullability.NON_NULL);
    }

    private static Nullability relationColumn(Relation relation, String column, Scope scope) {
        if (relation.table().isEmpty()) {
            return Nullability.mayBeNull("column '" + relation.name()
                                        + "." + column
                                        + "' comes from a derived table, CTE or function");
        }

        return relation.table()
                       .unwrap()
                       .column(column)
                       .map(found -> columnVerdict(relation, found, scope))
                       .or(Nullability.mayBeNull("column '" + relation.name() + "." + column + "' cannot be resolved"));
    }

    private static Nullability columnVerdict(Relation relation, Column column, Scope scope) {
        var label = relation.name() + "." + column.name();

        if (relation.nullableSide().isPresent()) {
            return Nullability.mayBeNull("column '" + label
                                        + "' is on the nullable side of a " + relation.nullableSide()
                                                                                      .unwrap());
        }

        if (scope.subtotals()) {
            return Nullability.mayBeNull("column '" + label
                                        + "' is NULL in the subtotal rows of ROLLUP, CUBE or GROUPING SETS");
        }

        return column.nullable()
               ? Nullability.mayBeNull("column '" + label + "' is nullable")
               : Nullability.NON_NULL;
    }
}
