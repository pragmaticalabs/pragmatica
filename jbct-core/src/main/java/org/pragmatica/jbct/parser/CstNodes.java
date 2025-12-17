package org.pragmatica.jbct.parser;

import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Utility methods for working with CST nodes.
 */
public final class CstNodes {

    private CstNodes() {}

    /**
     * Get children of a node (empty list for terminals/tokens).
     */
    public static List<CstNode> children(CstNode node) {
        return switch (node) {
            case CstNode.NonTerminal nt -> nt.children();
            case CstNode.Terminal t -> List.of();
            case CstNode.Token tok -> List.of();
        };
    }

    /**
     * Get the text content of a node.
     */
    public static String text(CstNode node, String source) {
        return node.span().extract(source);
    }

    /**
     * Check if node matches a rule type.
     */
    public static boolean isRule(CstNode node, Class<? extends RuleId> ruleClass) {
        return ruleClass.isInstance(node.rule());
    }

    /**
     * Find all descendants matching a rule type.
     */
    public static List<CstNode> findAll(CstNode root, Class<? extends RuleId> ruleClass) {
        var results = new ArrayList<CstNode>();
        walk(root, node -> {
            if (isRule(node, ruleClass)) {
                results.add(node);
            }
        });
        return results;
    }

    /**
     * Find all descendants matching a predicate.
     */
    public static List<CstNode> findAll(CstNode root, Predicate<CstNode> predicate) {
        var results = new ArrayList<CstNode>();
        walk(root, node -> {
            if (predicate.test(node)) {
                results.add(node);
            }
        });
        return results;
    }

    /**
     * Find first descendant matching a rule type.
     */
    public static Option<CstNode> findFirst(CstNode root, Class<? extends RuleId> ruleClass) {
        return findFirst(root, node -> isRule(node, ruleClass));
    }

    /**
     * Find first descendant matching a predicate.
     */
    public static Option<CstNode> findFirst(CstNode root, Predicate<CstNode> predicate) {
        if (predicate.test(root)) {
            return Option.some(root);
        }
        for (var child : children(root)) {
            var found = findFirst(child, predicate);
            if (found.isPresent()) {
                return found;
            }
        }
        return Option.none();
    }

    /**
     * Find ancestor matching a rule type.
     */
    public static Option<CstNode> findAncestor(CstNode root, CstNode target, Class<? extends RuleId> ruleClass) {
        return findAncestorPath(root, target)
            .flatMap(path -> {
                for (int i = path.size() - 2; i >= 0; i--) {
                    if (isRule(path.get(i), ruleClass)) {
                        return Option.some(path.get(i));
                    }
                }
                return Option.none();
            });
    }

    /**
     * Get path from root to target node.
     */
    public static Option<List<CstNode>> findAncestorPath(CstNode root, CstNode target) {
        var path = new ArrayList<CstNode>();
        if (findPath(root, target, path)) {
            return Option.some(path);
        }
        return Option.none();
    }

    private static boolean findPath(CstNode current, CstNode target, List<CstNode> path) {
        path.add(current);
        if (current == target || current.span().equals(target.span())) {
            return true;
        }
        for (var child : children(current)) {
            if (findPath(child, target, path)) {
                return true;
            }
        }
        path.removeLast();
        return false;
    }

    /**
     * Walk the tree depth-first, calling visitor for each node.
     */
    public static void walk(CstNode root, Consumer<CstNode> visitor) {
        visitor.accept(root);
        for (var child : children(root)) {
            walk(child, visitor);
        }
    }

    /**
     * Stream all nodes in the tree depth-first.
     */
    public static Stream<CstNode> stream(CstNode root) {
        return Stream.concat(
            Stream.of(root),
            children(root).stream().flatMap(CstNodes::stream)
        );
    }

    /**
     * Get child by index.
     */
    public static Option<CstNode> child(CstNode node, int index) {
        var kids = children(node);
        if (index >= 0 && index < kids.size()) {
            return Option.some(kids.get(index));
        }
        return Option.none();
    }

    /**
     * Get first child matching a rule type.
     */
    public static Option<CstNode> childByRule(CstNode node, Class<? extends RuleId> ruleClass) {
        for (var child : children(node)) {
            if (isRule(child, ruleClass)) {
                return Option.some(child);
            }
        }
        return Option.none();
    }

    /**
     * Get all direct children matching a rule type.
     */
    public static List<CstNode> childrenByRule(CstNode node, Class<? extends RuleId> ruleClass) {
        var results = new ArrayList<CstNode>();
        for (var child : children(node)) {
            if (isRule(child, ruleClass)) {
                results.add(child);
            }
        }
        return results;
    }

    /**
     * Check if node contains a descendant matching rule type.
     */
    public static boolean contains(CstNode root, Class<? extends RuleId> ruleClass) {
        return findFirst(root, ruleClass).isPresent();
    }

    /**
     * Check if node is a terminal with specific text.
     */
    public static boolean isLiteral(CstNode node, String text) {
        return switch (node) {
            case CstNode.Terminal t -> text.equals(t.text());
            case CstNode.Token tok -> text.equals(tok.text());
            case CstNode.NonTerminal nt -> false;
        };
    }

    /**
     * Get terminal/token text if node is terminal.
     */
    public static Option<String> terminalText(CstNode node) {
        return switch (node) {
            case CstNode.Terminal t -> Option.some(t.text());
            case CstNode.Token tok -> Option.some(tok.text());
            case CstNode.NonTerminal nt -> Option.none();
        };
    }

    /**
     * Count descendants matching a rule type.
     */
    public static int count(CstNode root, Class<? extends RuleId> ruleClass) {
        return findAll(root, ruleClass).size();
    }

    /**
     * Get start line of a node.
     */
    public static int startLine(CstNode node) {
        return node.span().start().line();
    }

    /**
     * Get start column of a node.
     */
    public static int startColumn(CstNode node) {
        return node.span().start().column();
    }
}
