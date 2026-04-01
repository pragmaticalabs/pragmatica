package org.pragmatica.aether.pg.parser.transform;

import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/// Utility for navigating CST nodes produced by the PostgreSQL parser.
public record CstNavigator( CstNode.NonTerminal node) {
    public String rule() {
        return node.ruleName();
    }

    public SourceSpan span() {
        return node.span();
    }

    public List<CstNode> children() {
        return node.children().stream()
                            .filter(CstNavigator::isNonEmpty)
                            .toList();
    }

    public Option<CstNavigator> child(String ruleName) {
        return node.children().stream()
                            .filter(c -> hasRule(c, ruleName) && isNonEmpty(c))
                            .findFirst()
                            .map(CstNavigator::ofNode)
                            .map(Option::present)
                            .orElse(Option.empty());
    }

    public List<CstNavigator> allChildren(String ruleName) {
        return node.children().stream()
                            .filter(c -> hasRule(c, ruleName) && isNonEmpty(c))
                            .map(CstNavigator::ofNode)
                            .toList();
    }

    public boolean has(String ruleName) {
        return node.children().stream()
                            .anyMatch(c -> hasRule(c, ruleName) && isNonEmpty(c));
    }

    public Option<String> tokenText(String ruleName) {
        return node.children().stream()
                            .filter(c -> hasRule(c, ruleName))
                            .flatMap(c -> switch (c) {case CstNode.Token tok -> Stream.of(tok.text());case CstNode.NonTerminal nt -> nt.children().stream()
                                                                                                                                                .flatMap(cc -> cc instanceof CstNode.Token t
                                                                                                                                                              ? Stream.of(t.text())
                                                                                                                                                              : Stream.empty());default -> Stream.empty();})
                            .findFirst()
                            .map(Option::present)
                            .orElse(Option.empty());
    }

    public Option<String> firstTokenText() {
        return node.children().stream()
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
        return node.children().stream()
                            .filter(c -> c instanceof CstNode.NonTerminal && isNonEmpty(c))
                            .findFirst()
                            .map(CstNavigator::ofNode)
                            .map(Option::present)
                            .orElse(Option.empty());
    }

    public Option<CstNavigator> path(String... ruleNames) {
        var current = Option.present(this);
        for ( var name : ruleNames) {
        current = current.flatMap(nav -> nav.child(name));}
        return current;
    }

    public String text(String source) {
        var start = node.span().start()
                             .offset();
        var end = node.span().end()
                           .offset();
        return source.substring(start, end);
    }

    public static CstNavigator of(CstNode.NonTerminal node) {
        return new CstNavigator(node);
    }

    public static Option<CstNavigator> wrap(CstNode node) {
        return switch (node) {case CstNode.NonTerminal nt when isNonEmpty(nt) -> Option.present(new CstNavigator(nt));default -> Option.empty();};
    }

    private static CstNavigator ofNode(CstNode node) {
        return switch (node) {case CstNode.NonTerminal nt -> new CstNavigator(nt);case CstNode.Token tok -> new CstNavigator(new CstNode.NonTerminal(tok.span(),
                                                                                                                                                     tok.ruleName(),
                                                                                                                                                     List.of(tok)));case CstNode.Terminal term -> new CstNavigator(new CstNode.NonTerminal(term.span(),
                                                                                                                                                                                                                                           term.text(),
                                                                                                                                                                                                                                           List.of(term)));case CstNode.Error err -> new CstNavigator(new CstNode.NonTerminal(err.span(),
                                                                                                                                                                                                                                                                                                                              "error",
                                                                                                                                                                                                                                                                                                                              List.of()));};
    }

    private static boolean hasRule(CstNode node, String ruleName) {
        return node.ruleName().equals(ruleName);
    }

    private static boolean isNonEmpty(CstNode node) {
        return switch (node) {case CstNode.NonTerminal nt -> !nt.children().isEmpty();case CstNode.Token tok -> !tok.text().isEmpty();case CstNode.Terminal term -> !term.text().isEmpty();case CstNode.Error _ -> true;};
    }

    @SuppressWarnings("JBCT-RET-01")
    private static<T> void collectListRecursive(CstNavigator nav,
                                                String itemRule,
                                                Function<CstNavigator, T> mapper,
                                                List<T> result) {
        for ( var child : nav.node.children()) {
        if ( child instanceof CstNode.NonTerminal nt && isNonEmpty(nt)) {
            var childNav = new CstNavigator(nt);
            if ( nt.ruleName().equals(itemRule)) {
            result.add(mapper.apply(childNav));} else
            if ( nt.ruleName().equals(nav.rule())) {
            collectListRecursive(childNav, itemRule, mapper, result);}
        }}
    }

    @SuppressWarnings("JBCT-RET-01")
    private static void findAllRecursive(CstNode node, String ruleName, List<CstNavigator> result) {
        switch ( node) {
            case CstNode.NonTerminal nt -> {
                if ( nt.ruleName().equals(ruleName) && isNonEmpty(nt)) {
                result.add(new CstNavigator(nt));}
                for ( var child : nt.children()) { findAllRecursive(child, ruleName, result);}
            }
            case CstNode.Token tok -> {
                if ( tok.ruleName().equals(ruleName) && isNonEmpty(tok)) {
                result.add(ofNode(tok));}
            }
            default -> {}
        }
    }
}
