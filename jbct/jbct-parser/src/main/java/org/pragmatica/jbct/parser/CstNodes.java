package org.pragmatica.jbct.parser;

import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/// Utility methods for working with CST nodes.
public final class CstNodes {
    private CstNodes() {}

    /// Get children of a node (empty list for terminals/tokens).
    public static List<CstNode> children(CstNode node) {
        return switch (node) {
            case CstNode.NonTerminal nt -> nt.children();
            case CstNode.Terminal t -> List.of();
            case CstNode.Token tok -> List.of();
            case CstNode.Error err -> List.of();
        };
    }

    /// Get the text content of a node.
    public static String text(CstNode node, String source) {
        return node.span()
                   .extract(source);
    }

    /// Check if node matches a rule type.
    public static boolean isRule(CstNode node, Class<? extends RuleId> ruleClass) {
        return ruleClass.isInstance(node.rule());
    }

    /// Find all descendants matching a rule type.
    public static List<CstNode> findAll(CstNode root, Class<? extends RuleId> ruleClass) {
        return findAll(root, node -> isRule(node, ruleClass));
    }

    /// Find all descendants matching a predicate.
    public static List<CstNode> findAll(CstNode root, Predicate<CstNode> predicate) {
        return stream(root).filter(predicate)
                     .toList();
    }

    /// Find first descendant matching a rule type.
    public static Option<CstNode> findFirst(CstNode root, Class<? extends RuleId> ruleClass) {
        return findFirst(root, node -> isRule(node, ruleClass));
    }

    /// Find first descendant matching a predicate.
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

    /// Find ancestor matching a rule type.
    public static Option<CstNode> findAncestor(CstNode root, CstNode target, Class<? extends RuleId> ruleClass) {
        return findAncestorPath(root, target).flatMap(path -> findAncestorInPath(path, ruleClass));
    }

    private static Option<CstNode> findAncestorInPath(List<CstNode> path, Class<? extends RuleId> ruleClass) {
        for (int i = path.size() - 2; i >= 0; i--) {
            if (isRule(path.get(i), ruleClass)) {
                return Option.some(path.get(i));
            }
        }
        return Option.none();
    }

    /// Get path from root to target node.
    public static Option<List<CstNode>> findAncestorPath(CstNode root, CstNode target) {
        var path = new ArrayList<CstNode>();
        if (findPath(root, target, path)) {
            return Option.some(path);
        }
        return Option.none();
    }

    private static boolean findPath(CstNode current, CstNode target, List<CstNode> path) {
        path.add(current);
        if (current == target || current.span()
                                        .equals(target.span())) {
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

    /// Walk the tree depth-first, calling visitor for each node.
    public static void walk(CstNode root, Consumer<CstNode> visitor) {
        visitor.accept(root);
        for (var child : children(root)) {
            walk(child, visitor);
        }
    }

    /// Stream all nodes in the tree depth-first.
    public static Stream<CstNode> stream(CstNode root) {
        return Stream.concat(Stream.of(root),
                             children(root).stream()
                                     .flatMap(CstNodes::stream));
    }

    /// Get child by index.
    public static Option<CstNode> child(CstNode node, int index) {
        var kids = children(node);
        if (index >= 0 && index < kids.size()) {
            return Option.some(kids.get(index));
        }
        return Option.none();
    }

    /// Get first child matching a rule type.
    public static Option<CstNode> childByRule(CstNode node, Class<? extends RuleId> ruleClass) {
        for (var child : children(node)) {
            if (isRule(child, ruleClass)) {
                return Option.some(child);
            }
        }
        return Option.none();
    }

    /// Get all direct children matching a rule type.
    public static List<CstNode> childrenByRule(CstNode node, Class<? extends RuleId> ruleClass) {
        var results = new ArrayList<CstNode>();
        for (var child : children(node)) {
            if (isRule(child, ruleClass)) {
                results.add(child);
            }
        }
        return results;
    }

    /// Check if node contains a descendant matching rule type.
    public static boolean contains(CstNode root, Class<? extends RuleId> ruleClass) {
        return findFirst(root, ruleClass).isPresent();
    }

    /// Check if node is a terminal with specific text.
    public static boolean isLiteral(CstNode node, String text) {
        return switch (node) {
            case CstNode.Terminal t -> text.equals(t.text());
            case CstNode.Token tok -> text.equals(tok.text());
            case CstNode.NonTerminal nt -> false;
            case CstNode.Error err -> false;
        };
    }

    /// Get terminal/token text if node is terminal.
    public static Option<String> terminalText(CstNode node) {
        return switch (node) {
            case CstNode.Terminal t -> Option.some(t.text());
            case CstNode.Token tok -> Option.some(tok.text());
            case CstNode.NonTerminal nt -> Option.none();
            case CstNode.Error err -> Option.none();
        };
    }

    /// Count descendants matching a rule type.
    public static int count(CstNode root, Class<? extends RuleId> ruleClass) {
        return findAll(root, ruleClass).size();
    }

    /// Get start line of a node.
    public static int startLine(CstNode node) {
        return node.span()
                   .start()
                   .line();
    }

    /// Get start column of a node.
    public static int startColumn(CstNode node) {
        return node.span()
                   .start()
                   .column();
    }

    /// Extract package name from a compilation unit root node.
    public static String packageName(CstNode root, String source) {
        return findFirst(root, RuleId.PackageDecl.class).flatMap(pd -> findFirst(pd, RuleId.QualifiedName.class))
                        .map(qn -> text(qn, source))
                        .or("");
    }

    // --- Ordered-choice wrappers (java-peglib 0.2.1+) ---
    // TypeKind wraps ClassDecl/InterfaceDecl/EnumDecl/RecordDecl/AnnotationDecl
    // Member wraps ConstructorDecl/TypeKind/MethodDecl/FieldDecl

    /// Check if node has a direct child matching a rule type.
    public static boolean hasChildOfRule(CstNode node, Class<? extends RuleId> ruleClass) {
        return children(node).stream()
                             .anyMatch(child -> isRule(child, ruleClass));
    }

    /// Check if a Member node represents a method declaration.
    /// Methods have: [TypeParams?] Type Identifier '(' ...
    public static boolean isMethodMember(CstNode node) {
        if (!isRule(node, RuleId.Member.class)) {
            return false;
        }

        var kids = children(node);

        return kids.size() >= 4
               && hasChildOfRule(node, RuleId.Type.class)
               && hasChildOfRule(node, RuleId.Identifier.class)
               && kids.stream().anyMatch(k -> isLiteral(k, "("));
    }

    /// Find all method-like Member nodes.
    public static List<CstNode> findAllMethods(CstNode root) {
        return findAll(root, RuleId.Member.class).stream()
                      .filter(CstNodes::isMethodMember)
                      .toList();
    }

    /// Find first method-like Member node.
    public static Option<CstNode> findFirstMethod(CstNode root) {
        return findFirst(root, node -> isRule(node, RuleId.Member.class) && isMethodMember(node));
    }

    /// Check if node contains a method Member descendant.
    public static boolean containsMethod(CstNode root) {
        return findFirstMethod(root).isPresent();
    }

    /// Count method Member descendants.
    public static int countMethods(CstNode root) {
        return findAllMethods(root).size();
    }

    /// Find all class TypeKind nodes (containing ClassKW).
    public static List<CstNode> findAllClasses(CstNode root) {
        return findAll(root, RuleId.TypeKind.class).stream()
                      .filter(tk -> hasChildOfRule(tk, RuleId.ClassKW.class))
                      .toList();
    }

    /// Find first class TypeKind node.
    public static Option<CstNode> findFirstClass(CstNode root) {
        return findFirst(root, node -> isRule(node, RuleId.TypeKind.class) && hasChildOfRule(node, RuleId.ClassKW.class));
    }

    /// Check if node contains a class TypeKind descendant.
    public static boolean containsClass(CstNode root) {
        return findFirstClass(root).isPresent();
    }

    /// Find all interface TypeKind nodes (containing InterfaceKW).
    public static List<CstNode> findAllInterfaces(CstNode root) {
        return findAll(root, RuleId.TypeKind.class).stream()
                      .filter(tk -> hasChildOfRule(tk, RuleId.InterfaceKW.class))
                      .toList();
    }

    /// Find first interface TypeKind node.
    public static Option<CstNode> findFirstInterface(CstNode root) {
        return findFirst(root, node -> isRule(node, RuleId.TypeKind.class) && hasChildOfRule(node, RuleId.InterfaceKW.class));
    }

    /// Check if node contains an interface TypeKind descendant.
    public static boolean containsInterface(CstNode root) {
        return findFirstInterface(root).isPresent();
    }

    /// Find all record TypeKind nodes (containing RecordKW).
    public static List<CstNode> findAllRecords(CstNode root) {
        return findAll(root, RuleId.TypeKind.class).stream()
                      .filter(tk -> hasChildOfRule(tk, RuleId.RecordKW.class))
                      .toList();
    }

    /// Find first record TypeKind node.
    public static Option<CstNode> findFirstRecord(CstNode root) {
        return findFirst(root, node -> isRule(node, RuleId.TypeKind.class) && hasChildOfRule(node, RuleId.RecordKW.class));
    }

    /// Find all enum TypeKind nodes (containing EnumKW).
    public static List<CstNode> findAllEnums(CstNode root) {
        return findAll(root, RuleId.TypeKind.class).stream()
                      .filter(tk -> hasChildOfRule(tk, RuleId.EnumKW.class))
                      .toList();
    }

    // --- BlockStmt wraps Stmt (ordered choice: LocalVar / LocalTypeDecl / Stmt) ---

    /// Find all statement-like BlockStmt nodes (Stmt alternatives are now wrapped in BlockStmt).
    public static List<CstNode> findAllStatements(CstNode root) {
        return findAll(root, RuleId.BlockStmt.class);
    }

    // --- Primary wraps Lambda (ordered choice) ---

    /// Check if a Primary node represents a lambda expression.
    /// Lambdas have a LambdaParams child.
    public static boolean isLambdaPrimary(CstNode node) {
        if (!isRule(node, RuleId.Primary.class)) {
            return false;
        }

        return hasChildOfRule(node, RuleId.LambdaParams.class);
    }

    /// Find all lambda-like Primary nodes.
    public static List<CstNode> findAllLambdas(CstNode root) {
        return findAll(root, RuleId.Primary.class).stream()
                      .filter(CstNodes::isLambdaPrimary)
                      .toList();
    }
}
