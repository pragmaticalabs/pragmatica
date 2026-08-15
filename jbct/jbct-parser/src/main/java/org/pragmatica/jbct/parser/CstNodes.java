package org.pragmatica.jbct.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
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

    /// Find the nearest ancestor of `target` matching ANY of `kinds`. Nearest wins, so the
    /// order of `kinds` does not matter — this is a search for one node, not a preference.
    public static Option<Cursor> findAncestorAny(Cursor root, Cursor target, RuleKind... kinds) {
        return findAncestorPath(root, target).flatMap(path -> findAncestorInPathAny(path, kinds));
    }

    private static Option<Cursor> findAncestorInPath(List<Cursor> path, RuleKind kind) {
        for (int i = path.size() - 2; i >= 0; i--) {
            if (path.get(i).kindIs(kind)) {
                return Option.some(path.get(i));
            }
        }

        return Option.none();
    }

    private static Option<Cursor> findAncestorInPathAny(List<Cursor> path, RuleKind... kinds) {
        for (int i = path.size() - 2; i >= 0; i--) {
            if (path.get(i).kindIsAny(kinds)) {
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
    /// Classes and enums spell a member as `ClassMember -> Member -> MethodDecl`. Interfaces
    /// and records hold the `MethodDecl` DIRECTLY under `InterfaceMember` / `RecordMember`,
    /// with no `Member` level at all. Rules ask structural questions — "the method members of
    /// this type", "the member enclosing this statement" — whose answers must not depend on
    /// which spelling the enclosing type happens to use, so the shapes are reconciled here
    /// rather than at each call site.
    ///
    /// Nodes that hold a member declaration directly; `MEMBER` is the class/enum spelling.
    private static final RuleKind[] METHOD_MEMBER_KINDS = {RuleKind.MEMBER,
                                                           RuleKind.INTERFACE_MEMBER,
                                                           RuleKind.RECORD_MEMBER};

    /// Member wrappers carrying the annotations and modifiers that `MEMBER` excludes. For
    /// interfaces and records this is the SAME node that holds the declaration.
    private static final RuleKind[] MEMBER_WRAPPER_KINDS = {RuleKind.CLASS_MEMBER,
                                                            RuleKind.INTERFACE_MEMBER,
                                                            RuleKind.RECORD_MEMBER};

    /// Field declarations, across every type kind that can hold one.
    private static final RuleKind[] FIELD_DECL_KINDS = {RuleKind.FIELD_DECL,
                                                        RuleKind.INTERFACE_FIELD_DECL,
                                                        RuleKind.RECORD_STATIC_FIELD};

    /// Every node that sits at member level, wrapper or declaration. Callers walking a path
    /// through the member layers — a class member nests a `Member`, an interface member does
    /// not — use this to recognise the layers without caring how many there are.
    private static final RuleKind[] MEMBER_LEVEL_KINDS = {RuleKind.MEMBER,
                                                          RuleKind.CLASS_MEMBER,
                                                          RuleKind.INTERFACE_MEMBER,
                                                          RuleKind.RECORD_MEMBER};

    /// Bodies that hold members, across every type kind that has one.
    private static final RuleKind[] TYPE_BODY_KINDS = {RuleKind.CLASS_BODY,
                                                       RuleKind.INTERFACE_BODY,
                                                       RuleKind.RECORD_BODY,
                                                       RuleKind.ENUM_BODY};

    /// True for any member-level node — a member wrapper or the declaration it holds.
    public static boolean isMemberLevel(Cursor node) {
        return node.kindIsAny(MEMBER_LEVEL_KINDS);
    }

    /// True for a node that holds a member declaration directly, whatever the declaration is.
    public static boolean isMemberDecl(Cursor node) {
        return node.kindIsAny(METHOD_MEMBER_KINDS);
    }

    /// True for a member wrapper — the node carrying a member's annotations and modifiers.
    public static boolean isMemberWrapper(Cursor node) {
        return node.kindIsAny(MEMBER_WRAPPER_KINDS);
    }

    /// True for a field declaration in any type kind.
    public static boolean isFieldDecl(Cursor node) {
        return node.kindIsAny(FIELD_DECL_KINDS);
    }

    /// True for a member node that represents a method declaration, in any type kind.
    public static boolean isMethodMember(Cursor node) {
        return isMemberDecl(node) && hasChildOfRule(node, RuleKind.METHOD_DECL);
    }

    /// The member wrapper enclosing `node` — the node carrying its annotations and modifiers.
    ///
    /// Matches `node` ITSELF when it is already a wrapper. In a class the wrapper and the
    /// declaration are two nodes (`ClassMember` over `Member`), so an ancestor search finds
    /// the wrapper; in an interface or record they are ONE node, and an ancestor-only search
    /// would walk straight past it and report "no modifiers".
    public static Option<Cursor> enclosingMember(Cursor root, Cursor node) {
        return selfOrAncestor(root, node, MEMBER_WRAPPER_KINDS);
    }

    /// The declaration-holding member enclosing `node`, i.e. the node a method accessor such
    /// as [#methodBody] can be applied to. Matches `node` itself when it already is one.
    public static Option<Cursor> enclosingMethodMember(Cursor root, Cursor node) {
        return selfOrAncestor(root, node, METHOD_MEMBER_KINDS);
    }

    private static Option<Cursor> selfOrAncestor(Cursor root, Cursor node, RuleKind... kinds) {
        return node.kindIsAny(kinds)
               ? Option.some(node)
               : findAncestorAny(root, node, kinds);
    }

    /// The member wrappers declared directly by a type's body, whichever body spelling the
    /// type kind uses.
    ///
    /// Accepts either the `TypeKind` or the `*Decl` beneath it: a nested type is a bare
    /// `TypeKind` whose child is the `ClassDecl`/`InterfaceDecl` that actually holds the
    /// body, so looking only at direct children silently returns nothing for every caller
    /// that passes a `TypeKind` — which is what `findFirstInterface` and friends return.
    public static List<Cursor> typeBodyMembers(Cursor typeDecl) {
        return typeBody(typeDecl).map(body -> children(body).stream()
                                                            .filter(CstNodes::isMemberWrapper)
                                                            .toList())
                                 .or(List.of());
    }

    private static Option<Cursor> typeBody(Cursor typeDecl) {
        for (var child : children(typeDecl)) {
            if (child.kindIsAny(TYPE_BODY_KINDS)) {
                return Option.some(child);
            }
        }

        for (var child : children(typeDecl)) {
            for (var grandChild : children(child)) {
                if (grandChild.kindIsAny(TYPE_BODY_KINDS)) {
                    return Option.some(grandChild);
                }
            }
        }

        return Option.none();
    }

    /// Text of a member's DECLARATION, excluding the annotations and modifiers that an
    /// `InterfaceMember` / `RecordMember` node also spans. Byte-identical to `text(member)`
    /// for a class member, whose `Member` node covers exactly the declaration — so callers
    /// that regex this text read the same string whatever type kind the member lives in.
    /// Most of them extract a method name, and without this an annotation's own parentheses
    /// (`@SuppressWarnings("...")`) precede the real signature and can capture the match.
    ///
    /// The declaration is the member's first non-`Annotation` child, which holds for a
    /// constructor, a field and a nested type as well as a method — keying on `MethodDecl`
    /// alone left every other member shape reading its annotations as if they were the
    /// declaration.
    public static String memberDeclText(Cursor member) {
        return memberDecl(member).map(CstNodes::text)
                                 .or(() -> text(member));
    }

    /// The declaration a member holds — its first child that is not an annotation. Modifiers
    /// are tokens rather than child nodes, so they fall outside this span too, exactly as
    /// they do for a class's `Member` node.
    public static Option<Cursor> memberDecl(Cursor member) {
        return children(member).stream()
                               .filter(child -> !child.kindIs(RuleKind.ANNOTATION))
                               .findFirst()
                               .map(Option::some)
                               .orElseGet(Option::none);
    }

    public static List<Cursor> findAllMethods(Cursor root) {
        return findAll(root, CstNodes::isMethodMember);
    }

    /// Return the `Type` (return-type) node for a method member. Walks
    /// member → METHOD_DECL → TYPE.
    public static Option<Cursor> methodReturnType(Cursor methodMember) {
        return childByRule(methodMember, RuleKind.METHOD_DECL).flatMap(md -> childByRule(md, RuleKind.TYPE));
    }

    /// Return the `Params` node for a method member, if any.
    /// Walks member → METHOD_DECL → PARAMS.
    public static Option<Cursor> methodParams(Cursor methodMember) {
        return childByRule(methodMember, RuleKind.METHOD_DECL).flatMap(md -> childByRule(md, RuleKind.PARAMS));
    }

    /// Return the individual declared parameters of a `Params` node, in source order.
    ///
    /// The grammar nests these rather than listing them flat: `Params` holds an optional
    /// `ReceiverParam` followed by `OrdinaryParams`, and `OrdinaryParams` holds
    /// `PlainParam*` then a single `LastParam` (which is also where varargs live). Callers
    /// that want "the parameters of this method" want that sequence flattened, so this
    /// walks the nesting for them.
    ///
    /// A `ReceiverParam` (the explicit `this` receiver, JLS 8.4.1) is deliberately
    /// EXCLUDED: it declares no variable, so it is not a parameter for the purposes of
    /// naming, reassignment or nullability analysis.
    public static List<Cursor> parameterNodes(Cursor params) {
        var results = new ArrayList<Cursor>();

        for (var ordinary : childrenByRule(params, RuleKind.ORDINARY_PARAMS)) {
            results.addAll(childrenByRule(ordinary, RuleKind.PLAIN_PARAM));
            results.addAll(childrenByRule(ordinary, RuleKind.LAST_PARAM));
        }

        return results;
    }

    /// Return the `Block` body of a method member, if any.
    /// Walks member → METHOD_DECL → BLOCK.
    public static Option<Cursor> methodBody(Cursor methodMember) {
        return childByRule(methodMember, RuleKind.METHOD_DECL).flatMap(md -> childByRule(md, RuleKind.BLOCK));
    }

    /// Return the `Throws` clause of a method member, if any.
    /// Walks member → METHOD_DECL → THROWS.
    public static Option<Cursor> methodThrows(Cursor methodMember) {
        return childByRule(methodMember, RuleKind.METHOD_DECL).flatMap(md -> childByRule(md, RuleKind.THROWS));
    }

    public static Option<Cursor> findFirstMethod(Cursor root) {
        return findFirst(root, CstNodes::isMethodMember);
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
