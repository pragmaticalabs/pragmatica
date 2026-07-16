// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.jbct.slice.generator;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;


/// Batches a `<Monad>.all(...)` assembly so it stays within the core combinator ceiling.
///
/// Core's `all`/`MapperN`/`FnN`/`TupleN` ladders top out at arity 15 (`Promise.all` →
/// `Promise.Mapper15` → `Fn15` → `Tuple15`; likewise `Result`), so a flat `.all(a1..aN)` only
/// compiles for N &lt;= 15. When the assembled count exceeds {@link #MAX_FLAT_ARITY} the components are
/// grouped into &lt;=15-wide parts, each materialized as a tuple with `.id()`
/// (`Promise.Mapper15.id()` → `Promise<Tuple15<...>>`). Every part is constructed BEFORE the outer
/// join — for `Promise` this preserves concurrency (the batches launch together; nothing is
/// sequenced by a `flatMap` chain) — and the outer join's cascade of `Tuple.map` calls (each
/// returning a plain value, so the nesting does not stack wrappers) rebinds every component for the
/// factory. The grouping recurses at the parts level, so &gt;225 components does not reintroduce a
/// cliff.
///
/// Two emission shapes are supported: a statement shape (`var part1 = ...; return ...`) for method
/// bodies, and an expression shape (parts inlined as `Monad.all(...).id()` arguments) for fragments
/// that must remain a single expression.
final class BatchedAll {
    /// Core `all`/`MapperN`/`FnN`/`TupleN` ceiling — `Promise.all`/`Promise.Mapper15`/`Fn15`/`Tuple15`
    /// (and the `Result` equivalents) all stop at arity 15, so a flat `.all(...)` compiles only up to
    /// this many arguments.
    static final int MAX_FLAT_ARITY = 15;

    private BatchedAll() {}

    /// Emits the batched `Promise.all` assembly for a slice creation chain up to (but excluding) the
    /// innermost lambda body: the `var part = Promise.all(...).id();` declarations, the outer
    /// `Promise.all(parts).<chain>((...) ->` join and the nested `Tuple.map` cascade, ending with the
    /// innermost group opening a block lambda (`-> {`). The caller emits the unchanged creation body
    /// next, then passes the returned count to {@link #closeBatchedPromiseBody}.
    static int openBatchedPromiseBody(PrintWriter out,
                                      List<String> partExprs,
                                      List<String> leafNames,
                                      String outerChain) {
        var roots = plan(partExprs, leafNames);

        for (var root : roots) {
            emitPartDecl(out, "Promise", root);
        }

        var topArgs = new ArrayList<String>();
        var topBinds = new ArrayList<String>();

        for (var root : roots) {
            topArgs.add(root.varName);
            topBinds.add(root.bindName);
        }

        out.println("        return Promise.all(" + String.join(", ", topArgs) + ")");
        out.println("        ." + outerChain + "((" + String.join(", ", topBinds) + ") ->");
        var order = bfsGroups(roots);

        for (var i = 0; i < order.size(); i++) {
            var group = order.get(i);
            var opener = (i == order.size() - 1)
                         ? " -> {"
                         : " ->";

            out.println("            " + group.bindName + ".map((" + String.join(", ", boundNames(group)) + ")" + opener);
        }

        return order.size() + 1;
    }

    /// Closes the block lambda opened by {@link #openBatchedPromiseBody} and every `Tuple.map` / outer
    /// join parenthesis.
    static void closeBatchedPromiseBody(PrintWriter out, int openParens) {
        out.println("        }" + ")".repeat(openParens) + ";");
    }

    /// Renders the batched assembly as a single expression (no `var` declarations): parts are inlined
    /// as nested `Monad.all(...).id()` arguments, the outer join uses `flatMap` (the factory returns a
    /// carrier), and the cascade rebinds every component for `<typeName>.<factoryMethod>(...)`.
    /// `suffix` (e.g. `.async()`) is appended verbatim.
    static String renderConfigExpression(String monad,
                                         List<String> memberExprs,
                                         List<String> leafNames,
                                         String typeName,
                                         String factoryMethod,
                                         String suffix) {
        var roots = plan(memberExprs, leafNames);
        var topArgs = new ArrayList<String>();
        var topBinds = new ArrayList<String>();

        for (var root : roots) {
            topArgs.add(inlineExpr(monad, root));
            topBinds.add(root.bindName);
        }

        var sb = new StringBuilder();

        sb.append(monad).append(".all(").append(String.join(", ", topArgs)).append(')');
        sb.append(".flatMap((").append(String.join(", ", topBinds)).append(") -> ");
        var order = bfsGroups(roots);

        for (var group : order) {
            sb.append(group.bindName).append(".map((").append(String.join(", ", boundNames(group))).append(") -> ");
        }

        sb.append(typeName)
          .append('.')
          .append(factoryMethod)
          .append('(')
          .append(String.join(", ", leafNames))
          .append(')');
        sb.append(")".repeat(order.size() + 1));
        sb.append(suffix);

        return sb.toString();
    }

    private static void emitPartDecl(PrintWriter out, String monad, Group group) {
        for (var child : group.children) {
            if (child instanceof Group inner) {
                emitPartDecl(out, monad, inner);
            }
        }

        out.println("        var " + group.varName + " = " + monad + ".all(");
        var members = memberExprs(group);

        for (var i = 0; i < members.size(); i++) {
            var comma = (i < members.size() - 1)
                        ? ","
                        : "";

            out.println("            " + members.get(i) + comma);
        }

        out.println("        ).id();");
    }

    private static String inlineExpr(String monad, Group group) {
        var members = new ArrayList<String>();

        for (var child : group.children) {
            members.add(child instanceof Leaf leaf
                        ? leaf.expr()
                        : inlineExpr(monad, (Group) child));
        }

        return monad + ".all(" + String.join(", ", members) + ").id()";
    }

    private static List<String> memberExprs(Group group) {
        var members = new ArrayList<String>();

        for (var child : group.children) {
            members.add(child instanceof Leaf leaf
                        ? leaf.expr()
                        : ((Group) child).varName);
        }

        return members;
    }

    private static List<String> boundNames(Group group) {
        var names = new ArrayList<String>();

        for (var child : group.children) {
            names.add(child instanceof Leaf leaf
                      ? leaf.name()
                      : ((Group) child).bindName);
        }

        return names;
    }

    /// Builds the batching tree over the leaf components and assigns each group a part/bind name in
    /// post-order (children before parents, so a declaration references only already-declared inner
    /// parts). For N &gt; 15 the first pass always yields &gt;=2 groups, so the roots are always groups.
    private static List<Group> plan(List<String> exprs, List<String> leafNames) {
        var nodes = new ArrayList<Node>();

        for (var i = 0; i < exprs.size(); i++) {
            nodes.add(new Leaf(exprs.get(i), leafNames.get(i)));
        }

        while (nodes.size() > MAX_FLAT_ARITY) {
            var grouped = new ArrayList<Node>();

            for (var i = 0; i < nodes.size(); i += MAX_FLAT_ARITY) {
                grouped.add(new Group(new ArrayList<>(nodes.subList(i,
                                                                    Math.min(i + MAX_FLAT_ARITY, nodes.size())))));
            }

            nodes = grouped;
        }

        var roots = new ArrayList<Group>();

        for (var node : nodes) {
            roots.add((Group) node);
        }

        var counter = new int[]{0};

        for (var root : roots) {
            assignNames(root, counter);
        }

        return roots;
    }

    private static void assignNames(Group group, int[] counter) {
        for (var child : group.children) {
            if (child instanceof Group inner) {
                assignNames(inner, counter);
            }
        }

        var k = ++counter[0];

        group.varName = "part" + k;
        group.bindName = "t" + k;
    }

    private static List<Group> bfsGroups(List<Group> roots) {
        var order = new ArrayList<Group>();
        Queue<Group> queue = new ArrayDeque<>(roots);

        while (!queue.isEmpty()) {
            var group = queue.poll();

            order.add(group);
            for (var child : group.children) {
                if (child instanceof Group inner) {
                    queue.add(inner);
                }
            }
        }

        return order;
    }

    private sealed interface Node permits Leaf, Group {}

    private record Leaf(String expr, String name) implements Node {}

    private static final class Group implements Node {
        private final List<Node> children;
        private String varName;
        private String bindName;

        private Group(List<Node> children) {
            this.children = children;
        }
    }
}
