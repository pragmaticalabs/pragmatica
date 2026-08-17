package org.pragmatica.jbct.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.lang.Option;


/// Navigation utilities for the v6-based CST. Takes `Cursor` arguments and `RuleKind`
/// constants in place of the legacy `CstNode` / `Class<? extends RuleId>` API.
public final class CstNodes {
    private CstNodes() {}

    // ===== Children + text =====
    /// Direct children of the node. Empty for `Leaf` and `ErrorNode`.
    public static List<Cursor> children(Cursor node) {
        return switch (node) {
            case Cursor.Branch b -> b.children().toList();
            case Cursor.Leaf _, Cursor.ErrorNode _ -> List.of();
        };
    }

    /// Text content of the node — the source slice covered by its span.
    public static String text(Cursor node) {
        return node.cst()
                   .input()
                   .substring(node.spanStart(),
                              node.spanEnd());
    }

    /// The node's non-trivia tokens, concatenated — its text with comments and whitespace
    /// removed.
    ///
    /// A node's SPAN can reach past its last real token into trailing trivia, so [#text] on a
    /// bare annotation followed by a line comment returns `"Contract  // reason"` rather than
    /// `"Contract"`. Any comparison of a node's text against a NAME must go through this:
    /// matching on [#text] silently fails the moment someone writes a comment on that line,
    /// which reads as the annotation having no effect at all.
    public static String tokenText(Cursor node) {
        var tokens = node.cst()
                         .tokens();

        return IntStream.rangeClosed(node.firstTokenIdx(), node.lastTokenIdx())
                        .filter(t -> !tokens.isTrivia(t))
                        .mapToObj(tokens::textAt)
                        .collect(Collectors.joining());
    }

    // ===== Rule predicates =====
    public static boolean isRule(Cursor node, RuleKind kind) {
        return node.kindIs(kind);
    }

    // ===== Search (DFS) =====
    public static List<Cursor> findAll(Cursor root, RuleKind kind) {
        return findAll(root, n -> n.kindIs(kind));
    }

    public static List<Cursor> findAll(Cursor root, Predicate<Cursor> predicate) {
        return stream(root).filter(predicate)
                     .toList();
    }

    public static Option<Cursor> findFirst(Cursor root, RuleKind kind) {
        return findFirst(root, n -> n.kindIs(kind));
    }

    public static Option<Cursor> findFirst(Cursor root, Predicate<Cursor> predicate) {
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

    /// Find the nearest ancestor of `target` (relative to `root`) whose kind matches.
    public static Option<Cursor> findAncestor(Cursor root, Cursor target, RuleKind kind) {
        return findAncestorPath(root, target).flatMap(path -> findAncestorInPath(path, kind));
    }

    private static Option<Cursor> findAncestorInPath(List<Cursor> path, RuleKind kind) {
        for (int i = path.size() - 2; i >= 0; i--) {
            if (path.get(i).kindIs(kind)) {
                return Option.some(path.get(i));
            }
        }

        return Option.none();
    }

    /// Path from `root` to `target` (inclusive). Returns `none` if `target` isn't a
    /// descendant. Equality uses cursor span (which is unique within one CstArray).
    public static Option<List<Cursor>> findAncestorPath(Cursor root, Cursor target) {
        var path = new ArrayList<Cursor>();

        if (findPath(root, target, path)) {
            return Option.some(path);
        }

        return Option.none();
    }

    private static boolean findPath(Cursor current, Cursor target, List<Cursor> path) {
        path.add(current);
        if (current.idx() == target.idx() && current.cst() == target.cst()) {
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

    // ===== Traversal =====
    public static void walk(Cursor root, Consumer<Cursor> visitor) {
        visitor.accept(root);
        for (var child : children(root)) {
            walk(child, visitor);
        }
    }

    public static Stream<Cursor> stream(Cursor root) {
        return Stream.concat(Stream.of(root),
                             children(root).stream().flatMap(CstNodes::stream));
    }

    // ===== Children by index/rule =====
    public static Option<Cursor> child(Cursor node, int index) {
        var kids = children(node);

        if (index >= 0 && index < kids.size()) {
            return Option.some(kids.get(index));
        }

        return Option.none();
    }

    public static Option<Cursor> childByRule(Cursor node, RuleKind kind) {
        for (var child : children(node)) {
            if (child.kindIs(kind)) {
                return Option.some(child);
            }
        }

        return Option.none();
    }

    public static List<Cursor> childrenByRule(Cursor node, RuleKind kind) {
        var results = new ArrayList<Cursor>();

        for (var child : children(node)) {
            if (child.kindIs(kind)) {
                results.add(child);
            }
        }

        return results;
    }

    public static boolean contains(Cursor root, RuleKind kind) {
        return findFirst(root, kind).isPresent();
    }

    public static boolean hasChildOfRule(Cursor node, RuleKind kind) {
        for (var child : children(node)) {
            if (child.kindIs(kind)) {
                return true;
            }
        }

        return false;
    }

    public static int count(Cursor root, RuleKind kind) {
        return findAll(root, kind).size();
    }

    // ===== Terminals =====
    /// True iff the node is a Leaf whose source text exactly equals `text`.
    public static boolean isLiteral(Cursor node, String text) {
        return node instanceof Cursor.Leaf leaf && text.equals(leaf.text().toString());
    }

    /// Returns the leaf's text, or `none` for non-leaf nodes.
    public static Option<String> terminalText(Cursor node) {
        return switch (node) {
            case Cursor.Leaf leaf -> Option.some(leaf.text().toString());
            case Cursor.Branch _, Cursor.ErrorNode _ -> Option.none();
        };
    }

    // ===== Spans =====
    public static int startLine(Cursor node) {
        return node.startLine();
    }

    public static int startColumn(Cursor node) {
        return node.startColumn();
    }

    // ===== Package =====
    /// Extract the package name from a CompilationUnit root, or `""` if no `package` decl.
    public static String packageName(Cursor root) {
        return findFirst(root, RuleKind.PACKAGE_DECL).flatMap(pd -> findFirst(pd, RuleKind.QUALIFIED_NAME))
                        .map(CstNodes::text)
                        .or("");
    }

    // ===== Member-shape detection =====
    /// True for a `Member` node that represents a method declaration. Under v6, the
    /// Member node wraps a single `MethodDecl` child (or `ConstructorDecl`, `FieldDecl`,
    /// `TypeKind`). A method member is one whose first child is `MethodDecl`.
    public static boolean isMethodMember(Cursor node) {
        if (!node.kindIs(RuleKind.MEMBER)) {
            return false;
        }

        return hasChildOfRule(node, RuleKind.METHOD_DECL);
    }

    public static List<Cursor> findAllMethods(Cursor root) {
        return findAll(root, RuleKind.MEMBER).stream()
                      .filter(CstNodes::isMethodMember)
                      .toList();
    }

    /// Return the `Type` (return-type) node for a method member. Walks
    /// MEMBER → METHOD_DECL → TYPE.
    public static Option<Cursor> methodReturnType(Cursor methodMember) {
        return childByRule(methodMember, RuleKind.METHOD_DECL).flatMap(md -> childByRule(md, RuleKind.TYPE));
    }

    /// Return the `Params` node for a method member, if any.
    /// Walks MEMBER → METHOD_DECL → PARAMS.
    public static Option<Cursor> methodParams(Cursor methodMember) {
        return childByRule(methodMember, RuleKind.METHOD_DECL).flatMap(md -> childByRule(md, RuleKind.PARAMS));
    }

    /// Return the `Block` body of a method member, if any.
    /// Walks MEMBER → METHOD_DECL → BLOCK.
    public static Option<Cursor> methodBody(Cursor methodMember) {
        return childByRule(methodMember, RuleKind.METHOD_DECL).flatMap(md -> childByRule(md, RuleKind.BLOCK));
    }

    /// Return the `Throws` clause of a method member, if any.
    /// Walks MEMBER → METHOD_DECL → THROWS.
    public static Option<Cursor> methodThrows(Cursor methodMember) {
        return childByRule(methodMember, RuleKind.METHOD_DECL).flatMap(md -> childByRule(md, RuleKind.THROWS));
    }

    public static Option<Cursor> findFirstMethod(Cursor root) {
        return findFirst(root, node -> node.kindIs(RuleKind.MEMBER) && isMethodMember(node));
    }

    public static boolean containsMethod(Cursor root) {
        return findFirstMethod(root).isPresent();
    }

    public static int countMethods(Cursor root) {
        return findAllMethods(root).size();
    }

    // ===== Class / interface / record / enum =====
    /// All class TypeKind nodes. Distinguishes ClassDecl from InterfaceDecl/EnumDecl/etc. via
    /// the TypeKind's first child kind (ClassDecl in this case).
    public static List<Cursor> findAllClasses(Cursor root) {
        return findAll(root, RuleKind.TYPE_KIND).stream()
                      .filter(tk -> hasChildOfRule(tk, RuleKind.CLASS_DECL))
                      .toList();
    }

    public static Option<Cursor> findFirstClass(Cursor root) {
        return findFirst(root, n -> n.kindIs(RuleKind.TYPE_KIND) && hasChildOfRule(n, RuleKind.CLASS_DECL));
    }

    public static boolean containsClass(Cursor root) {
        return findFirstClass(root).isPresent();
    }

    public static List<Cursor> findAllInterfaces(Cursor root) {
        return findAll(root, RuleKind.TYPE_KIND).stream()
                      .filter(tk -> hasChildOfRule(tk, RuleKind.INTERFACE_DECL))
                      .toList();
    }

    public static Option<Cursor> findFirstInterface(Cursor root) {
        return findFirst(root, n -> n.kindIs(RuleKind.TYPE_KIND) && hasChildOfRule(n, RuleKind.INTERFACE_DECL));
    }

    public static boolean containsInterface(Cursor root) {
        return findFirstInterface(root).isPresent();
    }

    public static List<Cursor> findAllRecords(Cursor root) {
        return findAll(root, RuleKind.TYPE_KIND).stream()
                      .filter(tk -> hasChildOfRule(tk, RuleKind.RECORD_DECL))
                      .toList();
    }

    public static Option<Cursor> findFirstRecord(Cursor root) {
        return findFirst(root, n -> n.kindIs(RuleKind.TYPE_KIND) && hasChildOfRule(n, RuleKind.RECORD_DECL));
    }

    public static List<Cursor> findAllEnums(Cursor root) {
        return findAll(root, RuleKind.TYPE_KIND).stream()
                      .filter(tk -> hasChildOfRule(tk, RuleKind.ENUM_DECL))
                      .toList();
    }

    // ===== Statements / lambdas =====
    public static List<Cursor> findAllStatements(Cursor root) {
        return findAll(root, RuleKind.BLOCK_STMT);
    }

    /// True for a `Primary` node that represents a lambda expression. Under v6 the lambda
    /// shape is `Primary > Lambda > [LambdaParams, (Block | Expr)]`, so we check whether
    /// the Primary has a `Lambda` child (or, defensively, a `LambdaParams` direct child).
    public static boolean isLambdaPrimary(Cursor node) {
        if (!node.kindIs(RuleKind.PRIMARY)) {
            return false;
        }

        return hasChildOfRule(node, RuleKind.LAMBDA) || hasChildOfRule(node, RuleKind.LAMBDA_PARAMS);
    }

    /// Returns LAMBDA nodes (not Primary wrappers) so that direct-child lookups on lambda
    /// internals (`LAMBDA_PARAMS`, `BLOCK`/`EXPR` body) work.
    public static List<Cursor> findAllLambdas(Cursor root) {
        return findAll(root, RuleKind.LAMBDA);
    }
}
