// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;
import org.pragmatica.lang.Option;


public record CstNavigator(CstNode.NonTerminal node) {
    public String rule() {
        return node.ruleName();
    }

    public SourceSpan span() {
        return node.span();
    }

    public List<CstNode> children() {
        return node.children()
                   .stream()
                   .filter(CstNavigator::isNonEmpty)
                   .toList();
    }

    public Option<CstNavigator> child(String ruleName) {
        return node.children()
                   .stream()
                   .filter(c -> hasRule(c, ruleName) && isNonEmpty(c))
                   .findFirst()
                   .map(CstNavigator::ofNode)
                   .map(Option::present)
                   .orElse(Option.empty());
    }

    public List<CstNavigator> allChildren(String ruleName) {
        return node.children()
                   .stream()
                   .filter(c -> hasRule(c, ruleName) && isNonEmpty(c))
                   .map(CstNavigator::ofNode)
                   .toList();
    }

    public boolean has(String ruleName) {
        return node.children()
                   .stream()
                   .anyMatch(c -> hasRule(c, ruleName) && isNonEmpty(c));
    }

    /// Text of the direct child named `ruleName`, descending one level to its leaf.
    ///
    /// Terminals count as leaves alongside Tokens: peglib 0.7.x emits a named lexer kind as a
    /// `Token` but an INLINE literal as an anonymous `Terminal`, so a rule whose body is a bare
    /// literal — `NumericType <- 'integer'i / ...` — bottoms out in a Terminal. Matching only
    /// Tokens made every such lookup return empty, which surfaced as data types extracting as
    /// "unknown".
    public Option<String> tokenText(String ruleName) {
        return node.children()
                   .stream()
                   .filter(c -> hasRule(c, ruleName))
                   .flatMap(c -> switch (c) {
            case CstNode.Token tok -> Stream.of(tok.text());
            case CstNode.Terminal term -> Stream.of(term.text());
            case CstNode.NonTerminal nt -> nt.children().stream().flatMap(CstNavigator::leafText);
            default -> Stream.<String> empty();
        })
                   .findFirst()
                   .map(Option::present)
                   .orElse(Option.empty());
    }

    private static Stream<String> leafText(CstNode node) {
        return switch (node) {
            case CstNode.Token tok -> Stream.of(tok.text());
            case CstNode.Terminal term -> Stream.of(term.text());
            default -> Stream.empty();
        };
    }

    public Option<String> firstTokenText() {
        return node.children()
                   .stream()
                   .flatMap(c -> c instanceof CstNode.Token tok
                                 ? Stream.of(tok.text())
                                 : Stream.empty())
                   .findFirst()
                   .map(Option::present)
                   .orElse(Option.empty());
    }

    public <T> List<T> collectList(String itemRule, Function<CstNavigator, T> mapper) {
        var result = new ArrayList<T>();

        collectListRecursive(this, itemRule, mapper, result);

        return result;
    }

    public List<CstNavigator> findAll(String ruleName) {
        var result = new ArrayList<CstNavigator>();

        findAllRecursive(node, ruleName, result);

        return result;
    }

    public Option<CstNavigator> firstChild() {
        return node.children()
                   .stream()
                   .filter(c -> c instanceof CstNode.NonTerminal && isNonEmpty(c))
                   .findFirst()
                   .map(CstNavigator::ofNode)
                   .map(Option::present)
                   .orElse(Option.empty());
    }

    public Option<CstNavigator> path(String... ruleNames) {
        var current = Option.present(this);

        for (var name : ruleNames) {
            current = current.flatMap(nav -> nav.child(name));
        }

        return current;
    }

    public String text(String source) {
        var start = node.span().start().offset();
        var end = node.span().end().offset();

        return source.substring(start, end);
    }

    public static CstNavigator of(CstNode.NonTerminal node) {
        return new CstNavigator(node);
    }

    public static Option<CstNavigator> wrap(CstNode node) {
        return switch (node) {
            case CstNode.NonTerminal nt when isNonEmpty(nt) -> Option.present(new CstNavigator(nt));
            default -> Option.empty();
        };
    }

    private static CstNavigator ofNode(CstNode node) {
        return switch (node) {
            case CstNode.NonTerminal nt -> new CstNavigator(nt);
            case CstNode.Token tok -> new CstNavigator(new CstNode.NonTerminal(tok.span(), tok.ruleName(), List.of(tok)));
            case CstNode.Terminal term -> new CstNavigator(new CstNode.NonTerminal(term.span(),
                                                                                   term.text(),
                                                                                   List.of(term)));
            case CstNode.Error err -> new CstNavigator(new CstNode.NonTerminal(err.span(), "error", List.of()));
        };
    }

    private static boolean hasRule(CstNode node, String ruleName) {
        return node.ruleName()
                   .equals(ruleName);
    }

    private static boolean isNonEmpty(CstNode node) {
        return switch (node) {
            case CstNode.NonTerminal nt -> !nt.children().isEmpty();
            case CstNode.Token tok -> !tok.text().isEmpty();
            case CstNode.Terminal term -> !term.text().isEmpty();
            case CstNode.Error _ -> true;
        };
    }

    @SuppressWarnings("JBCT-RET-01")
    private static <T> void collectListRecursive(CstNavigator nav,
                                                 String itemRule,
                                                 Function<CstNavigator, T> mapper,
                                                 List<T> result) {
        for (var child : nav.node.children()) {
            if (child instanceof CstNode.NonTerminal nt && isNonEmpty(nt)) {
                var childNav = new CstNavigator(nt);

                if (nt.ruleName().equals(itemRule)) {
                    result.add(mapper.apply(childNav));
                } else if (nt.ruleName().equals(nav.rule())) {
                    collectListRecursive(childNav, itemRule, mapper, result);
                }
            }
        }
    }

    @SuppressWarnings("JBCT-RET-01")
    private static void findAllRecursive(CstNode node, String ruleName, List<CstNavigator> result) {
        switch (node) {
            case CstNode.NonTerminal nt -> {
                if (nt.ruleName().equals(ruleName) && isNonEmpty(nt)) {
                    result.add(new CstNavigator(nt));
                }

                for (var child : nt.children()) {
                    findAllRecursive(child, ruleName, result);
                }
            }
            case CstNode.Token tok -> {
                if (tok.ruleName().equals(ruleName) && isNonEmpty(tok)) {
                    result.add(ofNode(tok));
                }
            }
            default -> {}
        }
    }
}
