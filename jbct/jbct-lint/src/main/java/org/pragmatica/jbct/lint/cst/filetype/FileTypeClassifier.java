package org.pragmatica.jbct.lint.cst.filetype;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.CstNodes;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// File-type classification engine (issue #453).
///
/// Classifies a compilation unit into one [FileType] from its own syntax alone — no cross-file
/// type resolution. Classification is anchored on the file's *principal* top-level type (the first
/// `public` top-level type, or the first top-level type when none is public); a `@Test`-family
/// method anywhere in the file wins outright as [FileType#TEST_CLASS]. The verdict routes each
/// structural rule (JBCT-UC-02, JBCT-ORD-01, JBCT-INJ-01, JBCT-VAL-01) to the file kind it governs
/// and is exposed for reuse by the method-shape classifier (#448) and future score categories.
///
/// Precedence over the principal type (most specific first): error type (`extends`/`implements
/// Cause`) → utility interface (`sealed` + `record unused()`) → use case (an interface with an
/// `execute` entry AND nested Request/Response records or a static factory returning its own type)
/// → value object (a record with a static `Result<T>` factory that is not a service/adapter or
/// entry point) → step interface (single-abstract-method interface). A type matching none is
/// [FileType#UNCLASSIFIED].
///
/// **CST model.** A top-level type is a `TypeDecl` wrapping a `TypeKind`; a *nested* type is a bare
/// `TypeKind` (no `TypeDecl` wrapper). Member scoping is therefore expressed against the nearest
/// enclosing `TypeKind`: [#principalType] returns the principal's `TypeKind`, and the member views
/// ([#directMethods], [#directNestedTypes]) count only members whose nearest enclosing type is that
/// `TypeKind`, so a nested type's own members are not attributed to the enclosing type.
///
/// **Limits (syntax only).** `execute` is treated as the reserved Zone-1 entry name, so an unrelated
/// interface that merely declares an `execute` method reads as a use case (accepted FP). A value
/// object whose sole factory returns `Option<T>` rather than `Result<T>` is not recognised
/// (accepted FN — the book's canonical value-object factory returns `Result<T>`). An error type is
/// recognised by a DIRECT super named `Cause` or a single same-file hop to a `Cause`-extending
/// interface; generic-argument sections are stripped first, so `Comparable<Cause>` does not read as
/// an error type (fixes the earlier FP), while a `Cause`-extending chain longer than one same-file
/// hop, or `Cause` reached only under an import alias, is an accepted FN. Annotations — including
/// array-valued ones whose arguments contain `{` — are stripped before the header scan, so a
/// `@SuppressWarnings({...})` preceding the type no longer truncates the header and hides `sealed`.
public final class FileTypeClassifier {
    private FileTypeClassifier() {}

    private static final Pattern HEADER_NAME = Pattern.compile("\\b(?:record|class|interface|enum)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern METHOD_NAME = Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    private static final Pattern EXTENDS_CLAUSE = Pattern.compile("\\bextends\\b([\\s\\S]*?)(?=\\bimplements\\b|\\bpermits\\b|$)");
    private static final Pattern IMPLEMENTS_CLAUSE = Pattern.compile("\\bimplements\\b([\\s\\S]*?)(?=\\bpermits\\b|$)");
    private static final String CAUSE = "Cause";
    private static final Pattern SEALED = Pattern.compile("\\bsealed\\b");
    private static final Pattern PUBLIC = Pattern.compile("\\bpublic\\b");
    private static final Pattern UNUSED_RECORD = Pattern.compile("\\brecord\\s+unused\\s*\\(");

    private static final Set<String> TEST_ANNOTATIONS = Set.of("Test",
                                                               "ParameterizedTest",
                                                               "RepeatedTest",
                                                               "TestFactory",
                                                               "TestTemplate");

    /// Carrier types that wrap a factory's own type in a value-object / use-case factory.
    private static final Set<String> WRAPPERS = Set.of("Result", "Option", "Promise");

    /// JDK interfaces a value object may legitimately implement without becoming a service/adapter.
    private static final Set<String> VALUE_ISH_JDK = Set.of("Comparable", "Serializable");

    /// The [FileType] of the compilation unit rooted at `root`.
    public static FileType classify(Cursor root) {
        if (hasTestAnnotation(root)) {
            return FileType.TEST_CLASS;
        }

        return principalDecl(root).map(typeDecl -> classifyDecl(root, typeDecl))
                                  .or(FileType.UNCLASSIFIED);
    }

    /// The principal type node (a `TypeKind`) the verdict is about, for rules that anchor a
    /// diagnostic on it and navigate its members. The principal is the first `public` top-level
    /// type, or the first top-level type when none is public.
    public static Option<Cursor> principalType(Cursor root) {
        return principalDecl(root).flatMap(typeDecl -> childByRule(typeDecl, RuleKind.TYPE_KIND));
    }

    /// Whether `node`'s nearest enclosing type is `typeKind` (a direct member/nested type of it) —
    /// the scoping primitive the structural rules build member views on.
    public static boolean directlyEncloses(Cursor root, Cursor typeKind, Cursor node) {
        return findAncestor(root, node, RuleKind.TYPE_KIND).map(ancestor -> ancestor.idx() == typeKind.idx())
                            .or(false);
    }

    /// Method members declared directly in `typeKind` (methods of nested types are excluded).
    public static List<Cursor> directMethods(Cursor root, Cursor typeKind) {
        return findAllMethods(typeKind).stream()
                      .filter(method -> directlyEncloses(root, typeKind, method))
                      .toList();
    }

    /// Type declarations nested directly in `typeKind` (its own declaration excluded; deeper nesting
    /// excluded). Nested types are bare `TypeKind` nodes.
    public static List<Cursor> directNestedTypes(Cursor root, Cursor typeKind) {
        return findAll(typeKind, RuleKind.TYPE_KIND).stream()
                      .filter(nested -> nested.idx() != typeKind.idx() && directlyEncloses(root, typeKind, nested))
                      .toList();
    }

    /// Whether a TYPE node (a `TypeKind`) is declared inside a METHOD BODY — a local class or
    /// record — rather than in a type's member list. Callers pass type nodes: a method member is its
    /// own nearest method member and would trivially read as local.
    ///
    /// [#directlyEncloses] answers "which type encloses this node", NOT "is this node in that type's
    /// member list": a method-local declaration has no `TypeKind` between it and the enclosing type,
    /// so it passes that check and shows up in the enclosing type's member views. Member-ordering
    /// and member-scanning rules must exclude it explicitly — the slice idiom declares its
    /// implementation record inside the static factory, and ranking that record as a member made
    /// JBCT-ORD-01 unsatisfiable for every slice (#645).
    public static boolean isLocalDeclaration(Cursor root, Cursor node) {
        return enclosingMethodMember(root, node).filter(CstNodes::isMethodMember)
                            .isPresent();
    }

    /// Whether a method member carries the `static` modifier.
    public static boolean isStatic(Cursor root, Cursor method) {
        return modifiersText(root, method).contains("static ");
    }

    /// Whether a member declaration carries the `private` modifier.
    public static boolean isPrivate(Cursor root, Cursor member) {
        return modifiersText(root, member).contains("private ");
    }

    /// Whether a method member is an abstract interface method — no `static`/`default` modifier and
    /// no body.
    public static boolean isAbstractMethod(Cursor root, Cursor method) {
        var modifiers = modifiersText(root, method);

        return ! modifiers.contains("static ") && !modifiers.contains("default ") && !methodBody(method).isPresent();
    }

    /// Whether the type node (a `TypeKind`) is an interface.
    public static boolean isInterface(Cursor typeKind) {
        return hasChildOfRule(typeKind, RuleKind.INTERFACE_DECL);
    }

    /// Whether the type node (a `TypeKind`) is a record.
    public static boolean isRecord(Cursor typeKind) {
        return hasChildOfRule(typeKind, RuleKind.RECORD_DECL);
    }

    /// Simple name of a method member (the identifier immediately preceding its parameter list).
    public static String methodName(Cursor method) {
        var matcher = METHOD_NAME.matcher(memberDeclText(method));

        return matcher.find()
               ? matcher.group(1)
               : "";
    }

    /// Declared simple name of a record/class/interface/enum type node, or `""` when absent.
    public static String declaredName(Cursor typeKind) {
        var matcher = HEADER_NAME.matcher(text(typeKind));

        return matcher.find()
               ? matcher.group(1)
               : "";
    }

    private static FileType classifyDecl(Cursor root, Cursor typeDecl) {
        var header = headerOf(typeDecl);

        if (isErrorType(root, header)) {
            return FileType.ERROR_TYPE;
        }

        return childByRule(typeDecl, RuleKind.TYPE_KIND).map(typeKind -> classifyKind(root, typeKind, header))
                            .or(FileType.UNCLASSIFIED);
    }

    /// Classify an arbitrary type node (a `TypeKind`), for callers that need the role of a
    /// specific — possibly nested — type rather than the file's principal. Uses the type node's own
    /// header, which (for a nested type) carries the `extends`/`implements` clause and name but not
    /// wrapper-level modifiers; the `sealed`-dependent [FileType#UTILITY_INTERFACE] verdict may
    /// therefore be under-detected for nested types. USE_CASE / STEP_INTERFACE / ERROR_TYPE /
    /// VALUE_OBJECT do not depend on modifiers and are reported the same as for the principal.
    public static FileType classifyType(Cursor root, Cursor typeKind) {
        var header = headerOf(typeKind);

        if (isErrorType(root, header)) {
            return FileType.ERROR_TYPE;
        }

        return classifyKind(root, typeKind, header);
    }

    private static FileType classifyKind(Cursor root, Cursor typeKind, String header) {
        if (isInterface(typeKind)) {
            return classifyInterface(root, typeKind, header);
        }

        if (isRecord(typeKind) && hasResultSelfFactory(root, typeKind) && !isServiceOrEntryPoint(root, typeKind)) {
            return FileType.VALUE_OBJECT;
        }

        return FileType.UNCLASSIFIED;
    }

    private static FileType classifyInterface(Cursor root, Cursor typeKind, String header) {
        if (isSealed(header) && hasUnusedRecord(typeKind)) {
            return FileType.UTILITY_INTERFACE;
        }

        if (isUseCaseInterface(root, typeKind)) {
            return FileType.USE_CASE;
        }

        if (isStepInterface(root, typeKind)) {
            return FileType.STEP_INTERFACE;
        }

        return FileType.UNCLASSIFIED;
    }

    /// A use-case interface exposes an `execute` Zone-1 entry AND carries corroborating structure —
    /// nested Request/Response records, or a static factory that produces the interface's own type.
    /// An execute-only interface (an SPI, command runner, or connector) lacks that corroboration and
    /// is not read as a use case.
    private static boolean isUseCaseInterface(Cursor root, Cursor typeKind) {
        return hasExecuteEntry(root, typeKind)
               && (hasNestedRequestOrResponse(root, typeKind) || hasStaticSelfFactory(root, typeKind));
    }

    /// Whether a declaration header identifies an error type: a DIRECT super named `Cause`, or a
    /// direct super that is an interface declared in this file which itself directly extends `Cause`
    /// (a single same-file hop). Generic argument sections are stripped first, so `Comparable<Cause>`
    /// is not mistaken for extending `Cause`.
    private static boolean isErrorType(Cursor root, String header) {
        var supers = directSuperNames(header);

        if (supers.contains(CAUSE)) {
            return true;
        }

        return supers.stream()
                     .anyMatch(name -> sameFileInterfaceExtendsCause(root, name));
    }

    private static boolean sameFileInterfaceExtendsCause(Cursor root, String name) {
        return findAll(root, RuleKind.TYPE_KIND).stream()
                      .filter(FileTypeClassifier::isInterface)
                      .filter(typeKind -> declaredName(typeKind).equals(name))
                      .anyMatch(typeKind -> directSuperNames(headerOf(typeKind)).contains(CAUSE));
    }

    /// Simple head names of a declaration's `extends` and `implements` clauses, with generic
    /// argument sections removed and package qualifiers stripped.
    private static List<String> directSuperNames(String header) {
        var stripped = stripGenerics(header);
        var names = new ArrayList<String>();

        addClauseNames(names, EXTENDS_CLAUSE.matcher(stripped));
        addClauseNames(names, IMPLEMENTS_CLAUSE.matcher(stripped));

        return names;
    }

    private static void addClauseNames(List<String> names, Matcher matcher) {
        if (!matcher.find()) {
            return;
        }

        for (var entry : matcher.group(1)
                                .split(",")) {
            var name = entry.trim();
            var dot = name.lastIndexOf('.');

            if (dot >= 0) {
                name = name.substring(dot + 1)
                           .trim();
            }

            if (!name.isEmpty()) {
                names.add(name);
            }
        }
    }

    /// Removes every `<...>` generic-argument section (nesting-aware) so super-type matching sees
    /// only the raw head names.
    private static String stripGenerics(String header) {
        var builder = new StringBuilder();
        var depth = 0;

        for (var i = 0; i < header.length(); i++) {
            var c = header.charAt(i);

            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                builder.append(c);
            }
        }

        return builder.toString();
    }

    private static Option<Cursor> principalDecl(Cursor root) {
        var topLevel = topLevelTypes(root);

        for (var typeDecl : topLevel) {
            if (isPublic(typeDecl)) {
                return Option.some(typeDecl);
            }
        }

        return topLevel.isEmpty()
               ? Option.none()
               : Option.some(topLevel.getFirst());
    }

    private static List<Cursor> topLevelTypes(Cursor root) {
        return findAll(root, RuleKind.TYPE_DECL).stream()
                      .filter(typeDecl -> !findAncestor(root, typeDecl, RuleKind.TYPE_DECL)
                                                        .isPresent())
                      .toList();
    }

    private static boolean hasTestAnnotation(Cursor root) {
        return findAll(root, RuleKind.ANNOTATION).stream()
                      .map(CstNodes::annotationSimpleName)
                      .anyMatch(TEST_ANNOTATIONS::contains);
    }

    private static boolean hasExecuteEntry(Cursor root, Cursor typeKind) {
        return directMethods(root, typeKind).stream()
                                            .anyMatch(method -> methodName(method).equals("execute"));
    }

    private static boolean isStepInterface(Cursor root, Cursor typeKind) {
        var abstractMethods = directMethods(root, typeKind).stream()
                                                           .filter(method -> isAbstractMethod(root, method))
                                                           .count();

        return abstractMethods == 1 && directNestedTypes(root, typeKind).isEmpty();
    }

    private static boolean hasNestedRequestOrResponse(Cursor root, Cursor typeKind) {
        return directNestedTypes(root, typeKind).stream()
                                                .map(FileTypeClassifier::declaredName)
                                                .anyMatch(name -> name.endsWith("Request") || name.endsWith("Response"));
    }

    /// Whether the type has a direct static factory that produces its own type — the interface type
    /// directly (use-case factory) or wrapped as `Result`/`Option`/`Promise` (value-object factory).
    private static boolean hasStaticSelfFactory(Cursor root, Cursor typeKind) {
        var ownName = declaredName(typeKind);

        return directMethods(root, typeKind).stream()
                                            .filter(method -> isStatic(root, method))
                                            .anyMatch(method -> producesOwnType(method, ownName));
    }

    /// Whether the record has a direct static factory returning `Result<Own>` — the value object's
    /// parse-don't-validate factory. `Option`/`Promise`-wrapped factories are deliberately excluded
    /// (documented FN: the book's canonical value-object factory returns `Result<T>`).
    private static boolean hasResultSelfFactory(Cursor root, Cursor typeKind) {
        var ownName = declaredName(typeKind);

        return directMethods(root, typeKind).stream()
                                            .filter(method -> isStatic(root, method))
                                            .anyMatch(method -> returnsWrapperOf(method, "Result", ownName));
    }

    /// Whether a method's return type produces `ownTypeName` — either that type directly, or wrapped
    /// as `Result`/`Option`/`Promise` of it.
    public static boolean producesOwnType(Cursor method, String ownTypeName) {
        var returnType = methodReturnType(method).map(type -> text(type).trim())
                                                 .or("");
        var outer = simpleTypeName(returnType);

        if (outer.equals(ownTypeName)) {
            return true;
        }

        return WRAPPERS.contains(outer) && innerSimpleName(returnType).equals(ownTypeName);
    }

    private static boolean returnsWrapperOf(Cursor method, String wrapper, String ownTypeName) {
        var returnType = methodReturnType(method).map(type -> text(type).trim())
                                                 .or("");

        return simpleTypeName(returnType).equals(wrapper) && innerSimpleName(returnType).equals(ownTypeName);
    }

    /// A record is not a value object when it is really a service/adapter or an entry point — it
    /// declares a `main` method, or it implements an interface that is not declared in this file and
    /// is not one of the JDK value-ish interfaces (`Comparable`/`Serializable`), i.e. an external SPI.
    private static boolean isServiceOrEntryPoint(Cursor root, Cursor typeKind) {
        return hasMainMethod(root, typeKind) || implementsExternalInterface(root, typeKind);
    }

    private static boolean hasMainMethod(Cursor root, Cursor typeKind) {
        return directMethods(root, typeKind).stream()
                                            .anyMatch(method -> methodName(method).equals("main"));
    }

    private static boolean implementsExternalInterface(Cursor root, Cursor typeKind) {
        return directSuperNames(headerOf(typeKind)).stream()
                                                   .anyMatch(name -> !VALUE_ISH_JDK.contains(name)
                                                                     && !isDeclaredInFile(root, name));
    }

    private static boolean isDeclaredInFile(Cursor root, String name) {
        return findAll(root, RuleKind.TYPE_KIND).stream()
                      .anyMatch(typeKind -> declaredName(typeKind).equals(name));
    }

    /// Simple head name of a type text — the outermost type name, before any generic arguments and
    /// package qualifier (`Result<Money>` -> `Result`, `com.x.Email` -> `Email`).
    private static String simpleTypeName(String typeText) {
        var head = typeText.split("<", 2)[0].trim();
        var dot = head.lastIndexOf('.');

        return dot >= 0
               ? head.substring(dot + 1)
               : head;
    }

    /// Simple name of the first type argument of a generic type text (`Result<Money>` -> `Money`),
    /// or `""` when there is none.
    private static String innerSimpleName(String typeText) {
        var open = typeText.indexOf('<');

        if (open < 0) {
            return "";
        }

        var depth = 0;

        for (var i = open; i < typeText.length(); i++) {
            var c = typeText.charAt(i);

            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;

                if (depth == 0) {
                    return simpleTypeName(typeText.substring(open + 1, i));
                }
            }
        }

        return "";
    }

    /// Text of the member wrapper carrying a method's modifiers (`static`, `default`, `public`).
    /// The `MEMBER` node text begins at the return type, so modifiers live on the enclosing
    /// wrapper; falls back to the method text when no wrapper is found.
    private static String modifiersText(Cursor root, Cursor method) {
        return enclosingMember(root, method).map(wrapper -> text(wrapper))
                            .or(text(method));
    }

    private static boolean isPublic(Cursor typeDecl) {
        return PUBLIC.matcher(headerOf(typeDecl))
                     .find();
    }

    private static boolean isSealed(String header) {
        return SEALED.matcher(header)
                     .find();
    }

    private static boolean hasUnusedRecord(Cursor typeKind) {
        return UNUSED_RECORD.matcher(text(typeKind))
                            .find();
    }

    /// Declaration header up to (excluding) the body brace — carries modifiers, name, and the
    /// `extends`/`implements` clause, without body members. Annotations (and their argument lists,
    /// which may contain `{`) are stripped first, so a `@SuppressWarnings({...})` before the type
    /// does not truncate the header at the annotation's brace and hide the `sealed` modifier.
    private static String headerOf(Cursor typeDecl) {
        var declText = stripAnnotations(text(typeDecl));
        var brace = declText.indexOf('{');

        return brace >= 0
               ? declText.substring(0, brace)
               : declText;
    }

    /// Removes annotations — `@Name` and a following balanced `(...)` argument list — from a
    /// declaration's text, replacing each with a space. Parenthesis balancing consumes array-valued
    /// arguments (`@SuppressWarnings({"a","b"})`) whole, so their `{` never reaches the header scan.
    /// Public so header-parsing helpers in other packages share the one fix.
    public static String stripAnnotations(String text) {
        var builder = new StringBuilder();
        var i = 0;

        while (i < text.length()) {
            if (text.charAt(i) != '@') {
                builder.append(text.charAt(i));
                i++;

                continue;
            }

            i = skipAnnotation(text, i);
            builder.append(' ');
        }

        return builder.toString();
    }

    private static int skipAnnotation(String text, int at) {
        var i = at + 1;

        while (i < text.length() && (Character.isJavaIdentifierPart(text.charAt(i)) || text.charAt(i) == '.')) {
            i++;
        }

        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }

        return i < text.length() && text.charAt(i) == '('
               ? skipBalancedParens(text, i)
               : i;
    }

    private static int skipBalancedParens(String text, int open) {
        var depth = 0;

        for (var i = open; i < text.length(); i++) {
            if (text.charAt(i) == '(') {
                depth++;
            } else if (text.charAt(i) == ')' && --depth == 0) {
                return i + 1;
            }
        }

        return text.length();
    }
}
