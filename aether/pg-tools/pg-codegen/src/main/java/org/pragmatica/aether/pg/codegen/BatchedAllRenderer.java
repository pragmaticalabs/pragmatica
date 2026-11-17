// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.pragmatica.lang.Contract;


/// Renders a `<Monad>.all(...).map(<Type>::<type>)` row-mapper assembly that respects the core
/// combinator ceiling. The record is built through its type-named static factory ([Factory]), never through its
/// canonical constructor, so the invariants the factory checks run at the decode boundary; a fallible factory (one
/// returning `Result`) is joined with `flatMap`, so its failure becomes the mapper's failure.
///
/// Core's `all`/`MapperN`/`FnN`/`TupleN` ladders top out at arity 15 (`Result.all` →
/// `Result.Mapper15` → `Fn15` → `Tuple15`), so a flat `.all(a1..aN)` only compiles for N &lt;= 15.
///
///   - N &lt;= 15 → the UNCHANGED flat fast-path `Result.all(a1..aN).map(Type::type)`.
///   - N &gt; 15 → the components are batched into &lt;=15-wide parts, each materialized as a tuple with
///     `.id()` (`Result.Mapper15.id()` → `Result<Tuple15<...>>`); the parts are joined and the
///     cascade of `Tuple.map` calls (each returning a plain value, so the nesting does not stack
///     wrappers) rebinds every component for the factory call. All parts are constructed before the
///     outer join. The batching recurses at the parts level, so &gt;225 components (more than 15 parts)
///     does not reintroduce a cliff.
@Contract
public final class BatchedAllRenderer {
    /// Core `all`/`MapperN`/`FnN`/`TupleN` ceiling — `Result.all`/`Result.Mapper15`/`Fn15`/`Tuple15`
    /// all stop at arity 15, so a flat `.all(...)` compiles only up to this many arguments.
    public static final int MAX_FLAT_ARITY = 15;

    private BatchedAllRenderer() {}

    /// The type-named static factory a row record is built through: `Type.type(components...)`.
    ///
    /// @param typeName the record's name as written in generated code (`Row`, or `Outer.Row` for a nested record)
    /// @param fallible whether the factory returns `Result<Type>` (the record has an invariant) rather than `Type`
    public record Factory(String typeName, boolean fallible) {
        public static Factory factory(String typeName, boolean fallible) {
            return new Factory(typeName, fallible);
        }

        /// The factory's name: the record's simple name with a lowercase first letter.
        public String methodName() {
            var simple = typeName.substring(typeName.lastIndexOf('.') + 1);

            return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        }

        private String joinOperator() {
            return fallible
                   ? "flatMap"
                   : "map";
        }
    }

    /// Appends a complete `return <monad>.all(...).map(<Type>::<type>);` statement (`flatMap` for a fallible
    /// factory), batching when the argument count exceeds {@link #MAX_FLAT_ARITY}. `indent` is the leading
    /// whitespace of the `return` statement; argument lines are indented one level (four spaces) deeper.
    public static void appendReturn(StringBuilder sb,
                                    String monad,
                                    Factory factory,
                                    List<String> exprs,
                                    String indent) {
        if (exprs.size() <= MAX_FLAT_ARITY) {
            appendFlat(sb, monad, factory, exprs, indent);
        } else {
            appendBatched(sb, monad, factory, exprs, indent);
        }
    }

    private static void appendFlat(StringBuilder sb, String monad, Factory factory, List<String> exprs, String indent) {
        sb.append(indent).append("return ").append(monad).append(".all(\n");
        appendArgList(sb, exprs, indent + "    ");
        sb.append(indent)
          .append(").")
          .append(factory.joinOperator())
          .append('(')
          .append(factory.typeName())
          .append("::")
          .append(factory.methodName())
          .append(");\n");
    }

    private static void appendBatched(StringBuilder sb,
                                      String monad,
                                      Factory factory,
                                      List<String> exprs,
                                      String indent) {
        var roots = buildRoots(exprs);

        sb.append(indent)
          .append("// ")
          .append(exprs.size())
          .append(" components exceed the arity-")
          .append(MAX_FLAT_ARITY)
          .append(' ')
          .append(monad)
          .append(".all ceiling — batch into tuple parts, then cascade\n");
        var counter = new int[]{0};

        for (var root : roots) {
            appendPartDecls(sb, monad, root, indent, counter);
        }

        var topArgs = new ArrayList<String>();
        var topBinds = new ArrayList<String>();

        for (var root : roots) {
            topArgs.add(root.varName);
            topBinds.add(root.bindName);
        }

        sb.append(indent)
          .append("return ")
          .append(monad)
          .append(".all(")
          .append(String.join(", ", topArgs))
          .append(")\n");
        sb.append(indent)
          .append("        .")
          .append(factory.joinOperator())
          .append("((")
          .append(String.join(", ", topBinds))
          .append(") ->\n");
        var order = bfsGroups(roots);
        var stepIndent = indent + "             ";

        for (var group : order) {
            sb.append(stepIndent)
              .append(group.bindName)
              .append(".map((")
              .append(String.join(", ",
                                  boundNames(group)))
              .append(") ->\n");
        }

        var leafNames = new ArrayList<String>();

        for (var i = 0; i < exprs.size(); i++) {
            leafNames.add("c" + (i + 1));
        }

        sb.append(stepIndent)
          .append("    ")
          .append(factory.typeName())
          .append('.')
          .append(factory.methodName())
          .append('(')
          .append(String.join(", ", leafNames))
          .append(')')
          .append(")".repeat(order.size() + 1))
          .append(";\n");
    }

    /// Assigns var/bind names to a group and its sub-groups in post-order (children before parents, so
    /// a part declaration only references already-declared inner parts) and emits each declaration.
    private static void appendPartDecls(StringBuilder sb, String monad, Group group, String indent, int[] counter) {
        for (var child : group.children) {
            if (child instanceof Group inner) {
                appendPartDecls(sb, monad, inner, indent, counter);
            }
        }

        var k = ++counter[0];

        group.varName = "part" + k;
        group.bindName = "t" + k;
        sb.append(indent).append("var ").append(group.varName).append(" = ").append(monad).append(".all(\n");
        appendArgList(sb, memberExprs(group), indent + "    ");
        sb.append(indent).append(").id();\n");
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

    private static void appendArgList(StringBuilder sb, List<String> args, String argIndent) {
        for (var i = 0; i < args.size(); i++) {
            sb.append(argIndent).append(args.get(i));
            if (i < args.size() - 1) {
                sb.append(',');
            }

            sb.append('\n');
        }
    }

    /// Batches the leaf components into &lt;=15-wide groups, recursing until the top level holds at most
    /// {@link #MAX_FLAT_ARITY} nodes. For N &gt; 15 the first pass always yields &gt;=2 groups, so the
    /// returned roots are always groups (never bare leaves).
    private static List<Group> buildRoots(List<String> exprs) {
        var nodes = new ArrayList<Node>();

        for (var i = 0; i < exprs.size(); i++) {
            nodes.add(new Leaf(exprs.get(i), "c" + (i + 1)));
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

        return roots;
    }

    /// Breadth-first order over group nodes (roots first), guaranteeing a parent is unwrapped before
    /// its child so every tuple binding is in scope when the cascade reaches it.
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
