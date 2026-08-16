package org.pragmatica.jbct.lint.cst.rules;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Regression coverage for the interface/record member-shape normalisation.
///
/// peglib 0.7.1 gave interfaces and records their own member productions, in which the member
/// wrapper and the declaration it holds **collapse into one node** — unlike a class, where
/// `ClassMember` (annotations + modifiers) sits over `Member` (the declaration alone). Every
/// test here pins behaviour that must NOT depend on which type kind a member lives in, and
/// each has a class counterpart so the asymmetry itself is the assertion.
///
/// The regression these guard against was invisible to the rule suites — it took a corpus
/// differential against the pre-bump binary to see it — so the cases are deliberately paired
/// and annotation-bearing: an annotation's own `(`/`)` is what defeats a declaration-shaped
/// heuristic once the wrapper's span includes it.
class MemberShapeRegressionTest {
    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    private List<Diagnostic> lint(String fileName, String source) {
        var sourceFile = SourceFile.sourceFile(Path.of(fileName), source);

        return linter.lint(sourceFile)
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }

    private List<Diagnostic> of(List<Diagnostic> diagnostics, String ruleId) {
        return diagnostics.stream()
                          .filter(d -> d.ruleId().equals(ruleId))
                          .toList();
    }

    // ===== memberDeclText falls back to wrapper text for a record CONSTRUCTOR =====
    // A record's canonical constructor is held by RecordMember, which also spans the
    // annotations. memberDeclText finds no METHOD_DECL child and returns the whole wrapper,
    // so the name regex captures the annotation's parentheses instead of the declaration's.

    @Test
    void awaitInAnnotatedRecordConstructor_namesTheConstructor_notTheAnnotation() {
        var diagnostics = of(lint("Money.java", """
                package org.example;
                public record Money(long amount) {
                    @Deprecated(since = "1.0")
                    public Money(long amount) {
                        this.amount = source().await();
                    }
                }
                """), "JBCT-PAT-03");

        assertEquals(1, diagnostics.size(), "expected exactly one await finding");
        assertTrue(diagnostics.getFirst()
                              .message()
                              .contains("'Money'"),
                   "message should name the constructor, was: " + diagnostics.getFirst().message());
    }

    @Test
    void awaitInAnnotatedClassConstructor_namesTheConstructor() {
        var diagnostics = of(lint("Money.java", """
                package org.example;
                public class Money {
                    @Deprecated(since = "1.0")
                    public Money(long amount) {
                        this.amount = source().await();
                    }
                }
                """), "JBCT-PAT-03");

        assertEquals(1, diagnostics.size(), "expected exactly one await finding");
        assertTrue(diagnostics.getFirst()
                              .message()
                              .contains("'Money'"),
                   "message should name the constructor, was: " + diagnostics.getFirst().message());
    }

    // ===== raw text(method) defeats the VO-01 builder exemption on an annotated record member =====

    @Test
    void valueObjectFactory_skipsBuilderRecord_whenWithMethodIsUnannotated() {
        var diagnostics = of(lint("Money.java", """
                package org.example;
                public record Money(long amount) {
                    public Money withAmount(long value) {
                        return new Money(value);
                    }
                }
                """), "JBCT-VO-01");

        assertEquals(List.of(), diagnostics, "builder record needs no factory");
    }

    @Test
    void valueObjectFactory_skipsBuilderRecord_whenWithMethodIsAnnotated() {
        var diagnostics = of(lint("Money.java", """
                package org.example;
                public record Money(long amount) {
                    @Deprecated(since = "1.0")
                    public Money withAmount(long value) {
                        return new Money(value);
                    }
                }
                """), "JBCT-VO-01");

        assertEquals(List.of(), diagnostics, "an annotation must not defeat the builder exemption");
    }

    // ===== diagnostic anchor for an annotated member differs by type kind =====

    @Test
    void alwaysSuccessResult_anchorsOnTheSignature_forAnnotatedClassMethod() {
        var diagnostics = of(lint("Foo.java", """
                package org.example;
                class Foo {
                    @Deprecated(since = "1.0")
                    static Result<Config> config(String name) {
                        return Result.success(new Config(name));
                    }
                }
                """), "JBCT-RET-05");

        assertEquals(1, diagnostics.size());
        assertEquals(4,
                     diagnostics.getFirst()
                                .line(),
                     "class member should anchor on the signature line");
    }

    @Test
    void alwaysSuccessResult_anchorsOnTheSignature_forAnnotatedInterfaceMethod() {
        var diagnostics = of(lint("Foo.java", """
                package org.example;
                interface Foo {
                    @Deprecated(since = "1.0")
                    static Result<Config> config(String name) {
                        return Result.success(new Config(name));
                    }
                }
                """), "JBCT-RET-05");

        assertEquals(1, diagnostics.size());
        assertEquals(4,
                     diagnostics.getFirst()
                                .line(),
                     "interface member should anchor on the signature line, like a class member");
    }

    // ===== EX-02 counts each orElseThrow call exactly once =====

    @Test
    void orElseThrow_reportsEachCallOnce_acrossChainPositions() {
        var diagnostics = of(lint("Foo.java", """
                package org.example;
                class Foo {
                    void run() {
                        list.stream().findFirst().orElseThrow();
                        var x = opt.orElseThrow();
                        var y = list.stream().findFirst().orElseThrow(IllegalStateException::new);
                        opt.map(this::wrap).orElseThrow();
                    }
                }
                """), "JBCT-EX-02");

        assertEquals(4,
                     diagnostics.size(),
                     "one finding per call, was: " + diagnostics.stream()
                                                                .map(d -> d.line() + ":" + d.column())
                                                                .toList());
    }

    // ===== typeBodyMembers on the TypeKind returned by findFirstInterface =====
    // UC-01 exempts multi-method interfaces via countAbstractMethods -> typeBodyMembers.
    // If typeBodyMembers cannot see the body from a TypeKind node, the exemption is dead
    // and the diagnostic fires on a legitimately non-lambda-able factory.

    @Test
    void nestedRecordFactory_skipsMultiMethodInterface() {
        var diagnostics = of(lint("UseCase.java", """
                package org.example;
                interface UseCase {
                    Promise<Response> execute(Request request);

                    Promise<Response> cancel(Request request);

                    static UseCase useCase(Dep dep) {
                        record impl(Dep dep) implements UseCase {
                            public Promise<Response> execute(Request request) {
                                return dep.process(request);
                            }
                        }
                        return new impl(dep);
                    }
                }
                """), "JBCT-UC-01");

        assertEquals(List.of(), diagnostics, "a two-method interface cannot be a lambda");
    }

    // ===== an annotation's own text is scanned as if it were method-body text =====

    @Test
    void fullyQualifiedName_ignoresQualifiedAnnotation_onClassMethod() {
        var diagnostics = of(lint("Foo.java", """
                package org.example;
                class Foo {
                    @java.lang.Deprecated
                    String run() {
                        return compute();
                    }
                }
                """), "JBCT-STY-03");

        assertEquals(List.of(), diagnostics, "an annotation is not a method body");
    }

    @Test
    void fullyQualifiedName_ignoresQualifiedAnnotation_onInterfaceMethod() {
        var diagnostics = of(lint("Foo.java", """
                package org.example;
                interface Foo {
                    @java.lang.Deprecated
                    default String run() {
                        return compute();
                    }
                }
                """), "JBCT-STY-03");

        assertEquals(List.of(), diagnostics, "an annotation is not a method body");
    }

    // Pinpoints H5: is the body a direct child of TypeKind (so typeBodyMembers can see it),
    // or one level deeper under InterfaceDecl (so every caller passing a TypeKind gets [])?
    @Test
    void typeBodyMembers_seesInterfaceMembers_fromTypeKind() {
        var source = """
                package org.example;
                interface UseCase {
                    Promise<Response> execute(Request request);

                    Promise<Response> cancel(Request request);
                }
                """;
        var root = new org.pragmatica.jbct.parser.Java25Parser().parse(source)
                                                                .onFailure(cause -> fail("Parse failed: " + cause.message()))
                                                                .unwrap();
        var typeKind = org.pragmatica.jbct.parser.CstNodes.findFirstInterface(root)
                                                          .or(root);
        var kindsUnderTypeKind = org.pragmatica.jbct.parser.CstNodes.children(typeKind)
                                                                    .stream()
                                                                    .map(c -> c.kind()
                                                                               .toString())
                                                                    .toList();
        var fromTypeKind = org.pragmatica.jbct.parser.CstNodes.typeBodyMembers(typeKind);

        assertEquals(2,
                     fromTypeKind.size(),
                     "typeBodyMembers(TypeKind) saw " + fromTypeKind.size()
                     + "; direct children of TypeKind are " + kindsUnderTypeKind);
    }

    @Test
    void nestedRecordFactory_flagsSingleMethodInterface() {
        var diagnostics = of(lint("UseCase.java", """
                package org.example;
                interface UseCase {
                    Promise<Response> execute(Request request);

                    static UseCase useCase(Dep dep) {
                        record impl(Dep dep) implements UseCase {
                            public Promise<Response> execute(Request request) {
                                return dep.process(request);
                            }
                        }
                        return new impl(dep);
                    }
                }
                """), "JBCT-UC-01");

        assertEquals(1, diagnostics.size(), "a one-method interface factory should return a lambda");
    }
}
