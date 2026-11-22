// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IntersectionTypeTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnionTypeTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The [ReadAs] census (M-2r-1, ruling 7): the constructor is called exactly twice in the main sources of every aether module
/// (the `COPY` constant and the mint), and the type is held nowhere: not by a field, not by a record component, not by an array
/// and not as a type argument. The one exception is the compilation unit declaring `ReadAs` itself (CTO ruling s54): the mint's signature is
/// `Fn1<Result<T>, ReadAs>`, the shape the ruling fixed, and `COPY` is a constant, not a held value.
///
/// The census reads the SYNTAX TREE, not text. Three rounds of token rules each missed a spelling (an annotation between the
/// tokens, a qualified name, an array, a declaration with no modifier, an array inside a generic, an annotated type argument), so
/// the invariant is now one: every tree node that carries a type is visited, and the type is `ReadAs` when the node names it,
/// whatever its spelling. The nodes are the declared type of a field or record component, the element type of an array type or
/// an array creation, every type argument of a parameterized type, a generic method call or a constructor call (wildcard bounds
/// and annotations are looked through), and the class of a `new` expression or of a `::new` reference (the construction count).
///
/// Parse-only trees, not attributed ones, by choice: the census spans the main sources of every aether module, so attribution
/// would need the classpath and the generated sources of all of them, a build of its own. The price is that the name is matched,
/// simple or qualified, instead of resolved; the census therefore also pins that exactly one type named `ReadAs` is declared in
/// those sources, so a second one cannot be mistaken for it. A source that does not parse fails the test; a file is never skipped.
class ReadAsCensusTest {
    private static final Path AETHER_ROOT = Path.of("..");
    private static final String READ_AS = "ReadAs";
    private static final String EXEMPT_PACKAGE = "org.pragmatica.aether.stream";
    private static final Path CLAIMED_ROOT = Path.of("..");
    private static final Path OTHER_READ_AS_FIXTURE = Path.of("src/test/resources/read-as-census-fixture/ReadAs.java");

    private record Census(long parsed, long constructions, long declarations, List<String> offences) {}

    @Test
    void readAs_overTheMainSources_isConstructedTwiceAndHeldNowhere() throws IOException {
        List<Path> sources;

        try (Stream<Path> files = Files.walk(AETHER_ROOT)) {
            sources = files.filter(RingHeaderCensusTest::isAetherMainJava).toList();
        }

        var census = censusOfFiles(sources);

        assertThat(census.parsed()).as("main sources the census parsed == main sources under the claimed root, walked independently")
                                   .isEqualTo(mainSourcesUnderClaimedRoot());
        assertThat(census.constructions()).as("'new ReadAs(' in aether/*/src/main").isEqualTo(2L);
        assertThat(census.declarations()).as("types named ReadAs declared in aether/*/src/main").isEqualTo(1L);
        assertThat(census.offences()).isEmpty();
    }

    /// The second instrument of the reach check: a plain walk of the claimed root, sharing nothing with the census's own file filter.
    /// A reach that silently drops modules (a narrowed root, a filter that skips a directory) makes the two counts disagree.
    private static long mainSourcesUnderClaimedRoot() throws IOException {
        try (Stream<Path> files = Files.walk(CLAIMED_ROOT)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".java"))
                        .filter(ReadAsCensusTest::isUnderMainJava)
                        .count();
        }
    }

    private static boolean isUnderMainJava(Path path) {
        var names = new ArrayList<String>();

        path.normalize().forEach(name -> names.add(name.toString()));
        return !names.contains("target") && !names.contains(".m2-local") && Collections.indexOfSubList(names, List.of("src", "main", "java")) >= 0;
    }

    /// Positive controls: every spelling of a held ReadAs is an offence, however it is written.
    @Test
    void offences_firesOnEveryShapeOfAHeldReadAs() {
        var shapes = List.of("private ReadAs role;",
                             "private\n        final\n        ReadAs role;",
                             "ReadAs role;",
                             "private @Deprecated ReadAs role;",
                             "private final @Deprecated ReadAs role;",
                             "private org.pragmatica.aether.stream.ReadAs role;",
                             "private ReadAs[] roles;",
                             "private ReadAs[][] grid;",
                             "private ReadAs roles[];",
                             "void put(ReadAs... roles) {}",
                             "ReadAs[] mint() { return null; }",
                             "List<ReadAs> roles;",
                             "List<? extends ReadAs> roles;",
                             "List<? super ReadAs> roles;",
                             "Map<ReadAs, String> roles;",
                             "Function<String,\n        ReadAs> mint;",
                             "Map<String, List<\n  ? extends\n  ReadAs\n>> roles;",
                             "List<ReadAs[]> roles;",
                             "List<@Deprecated ReadAs> roles;",
                             "List<org.pragmatica.aether.stream.ReadAs> roles;",
                             "List<? extends @Deprecated org.pragmatica.aether.stream.ReadAs[]> roles;",
                             "<T extends Comparable<ReadAs>> void put(T role) {}",
                             "void put(Fn1<Result<T>, ReadAs> use) {}",
                             "List<ReadAs> mint() { return null; }",
                             "void run() { List<ReadAs> local = null; }",
                             "void run() { var all = (List<ReadAs>) other; }",
                             "void run() { var is = other instanceof ReadAs[]; }",
                             "void run() { Holder.<ReadAs>of(); }",
                             "void run() { var all = new ReadAs[3]; }",
                             "void run() { var all = new ReadAs[] {ReadAs.COPY}; }",
                             "void run() { var all = new ArrayList<ReadAs>(); }",
                             "static final ReadAs COPY = null;",
                             "record Held(String name, ReadAs role) {}",
                             "record Held(String name,\n        ReadAs role) {}",
                             "record Held<T>(T name, @Deprecated ReadAs role) {}");

        for (var shape : shapes) {
            assertThat(offences("X.java", inClass(shape))).as(shape).hasSize(1);
        }
    }

    /// Negative controls: a parameter, a return type, a local, a cast, a static call, a comment and a literal hold nothing, and
    /// neither does a type whose name only contains `ReadAs`.
    @Test
    void offences_ignoresUseWithoutHolding() {
        var shapes = List.of("Result<Long> read(long offset, ReadAs readAs) { return null; }",
                             "ReadAs mint() { return ReadAs.COPY; }",
                             "void run() { ReadAs local = ReadAs.COPY; }",
                             "void run() { var role = (ReadAs) other; }",
                             "void run() { var role = other instanceof ReadAs r; }",
                             "void run() { run((ReadAs r) -> 1); }",
                             "<T extends ReadAs> void put(T role) {}",
                             "// List<ReadAs> roles;",
                             "String text = \"List<? extends ReadAs> roles\";",
                             "/* private ReadAs role;\n       List<ReadAs> roles; */",
                             "String block = \"\"\"\n    List<ReadAs> roles;\n    \"\"\";",
                             "private MyReadAs role;",
                             "List<ReadAsView> roles;",
                             "private Other.ReadAsView[] roles;");

        for (var shape : shapes) {
            assertThat(offences("X.java", inClass(shape))).as(shape).isEmpty();
        }
    }

    /// The exemption is the one compilation unit that declares the `ReadAs` type in its package, resolved from the parse (package
    /// plus type name), never the file name: the same declarations are offences in any other unit, whatever the file is called.
    @Test
    void offences_exemptsExactlyTheUnitDeclaringReadAs() {
        var declarations = "public static final ReadAs COPY = new ReadAs(\"\", -1, 0L);\n"
                           + "<T> Promise<T> loan(Fn1<Result<T>, ReadAs> use) { return null; }";
        var declaring = "package " + EXEMPT_PACKAGE + ";\nfinal class ReadAs {\n" + declarations + "\n}\n";
        var otherPackage = "package org.example.other;\nfinal class ReadAs {\n" + declarations + "\n}\n";
        var otherType = "package " + EXEMPT_PACKAGE + ";\nfinal class Other {\n" + declarations + "\n}\n";

        assertThat(offences("ReadAs.java", declaring)).isEmpty();
        assertThat(offences("ReadAs.java", otherPackage)).hasSize(2);
        assertThat(offences("ReadAs.java", otherType)).hasSize(2);
        assertThat(offences("Other.java", inClass(declarations))).hasSize(2);
    }

    /// A second file named `ReadAs.java` in another package that holds a `ReadAs` is counted: the fixture is parsed by the same
    /// census function as the real sources, and a name match would have exempted it.
    @Test
    void census_ofASecondFileNamedReadAsJava_countsItsHeldReadAs() throws IOException {
        var census = censusOfFiles(List.of(OTHER_READ_AS_FIXTURE));

        assertThat(census.offences()).hasSize(1);
    }

    /// Constructions are counted by syntax: a qualified name, an annotation and a constructor reference are constructions, a
    /// comment and a string are not, and the exempt file is counted too.
    @Test
    void constructions_countsEveryShapeOfNewReadAs() throws IOException {
        var source = inClass("""
                             void run() {
                                 var a = new ReadAs(1); // new ReadAs(2)
                                 var b = "new ReadAs(3)";
                                 var c = new
                                     ReadAs(4);
                                 var d = new org.pragmatica.aether.stream.ReadAs(5);
                                 var e = new @Deprecated ReadAs(6);
                                 java.util.function.Supplier<ReadAs> f = ReadAs::new;
                                 var g = new ReadAsView(7);
                             }
                             """);

        assertThat(censusOfSource("X.java", source).constructions()).isEqualTo(5L);
        assertThat(censusOfSource("ReadAs.java", source).constructions()).isEqualTo(5L);
    }

    /// A source that does not parse is an error, never a silent skip: an unparsed file would hide any shape in it.
    @Test
    void census_ofASourceThatDoesNotParse_fails() {
        var broken = inClass("private ReadAs role");

        assertThatThrownBy(() -> censusOfSource("X.java", broken))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("parse");
    }

    private static String inClass(String members) {
        return "class X {\n" + members + "\n}\n";
    }

    private static List<String> offences(String fileName, String source) {
        try {
            return censusOfSource(fileName, source).offences();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Census censusOfSource(String fileName, String source) throws IOException {
        var object = new SimpleJavaFileObject(URI.create("string:///" + fileName), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        return census(List.of(object));
    }

    private static Census censusOfFiles(List<Path> files) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();

        try (var fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            var objects = new ArrayList<JavaFileObject>();

            fileManager.getJavaFileObjectsFromPaths(files).forEach(objects::add);

            return census(objects);
        }
    }

    private static Census census(List<? extends JavaFileObject> sources) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, sources);
        var units = task.parse();
        var scanner = new ReadAsScanner(Trees.instance(task).getSourcePositions());

        var parsed = 0L;

        for (var unit : units) {
            scanner.scan(unit, null);
            parsed++;
        }

        var errors = diagnostics.getDiagnostics()
                                .stream()
                                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                                .map(diagnostic -> diagnostic.getSource() + ":" + diagnostic.getLineNumber() + " " + diagnostic.getMessage(null))
                                .toList();

        assertThat(errors).as("every source must parse").isEmpty();
        return new Census(parsed, scanner.constructions, scanner.declarations, scanner.offences);
    }

    /// The node `type` is `ReadAs` itself, whatever its spelling: simple or qualified, annotated, or the bound of a wildcard.
    private static boolean denotesReadAs(Tree type) {
        return switch (type) {
            case IdentifierTree identifier -> identifier.getName().contentEquals(READ_AS);
            case MemberSelectTree select -> select.getIdentifier().contentEquals(READ_AS);
            case AnnotatedTypeTree annotated -> denotesReadAs(annotated.getUnderlyingType());
            case WildcardTree wildcard -> denotesReadAs(wildcard.getBound());
            case UnionTypeTree union -> union.getTypeAlternatives().stream().anyMatch(ReadAsCensusTest::denotesReadAs);
            case IntersectionTypeTree intersection -> intersection.getBounds().stream().anyMatch(ReadAsCensusTest::denotesReadAs);
            case null, default -> false;
        };
    }

    private static final class ReadAsScanner extends TreePathScanner<Void, Void> {
        private final SourcePositions positions;
        private final List<String> offences = new ArrayList<>();
        private CompilationUnitTree unit;
        private boolean exempt;
        private long constructions;
        private long declarations;

        ReadAsScanner(SourcePositions positions) {
            this.positions = positions;
        }

        @Override
        public Void visitCompilationUnit(CompilationUnitTree node, Void unused) {
            unit = node;
            exempt = declaresReadAsInExemptPackage(node);
            return super.visitCompilationUnit(node, unused);
        }

        private static boolean declaresReadAsInExemptPackage(CompilationUnitTree node) {
            return node.getPackageName() != null
                   && node.getPackageName().toString().equals(EXEMPT_PACKAGE)
                   && node.getTypeDecls().stream().anyMatch(type -> type instanceof ClassTree declared && declared.getSimpleName().contentEquals(READ_AS));
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            declarations += node.getSimpleName().contentEquals(READ_AS) ? 1 : 0;
            return super.visitClass(node, unused);
        }

        /// A variable whose parent is a class is a field, or a record component (the parser lists it among the members).
        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            if (getCurrentPath().getParentPath().getLeaf() instanceof ClassTree && denotesReadAs(node.getType())) {
                offend("field or record component", node);
            }

            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitArrayType(ArrayTypeTree node, Void unused) {
            if (denotesReadAs(node.getType())) {
                offend("array", node);
            }

            return super.visitArrayType(node, unused);
        }

        @Override
        public Void visitNewArray(NewArrayTree node, Void unused) {
            if (denotesReadAs(node.getType())) {
                offend("array creation", node);
            }

            return super.visitNewArray(node, unused);
        }

        @Override
        public Void visitParameterizedType(ParameterizedTypeTree node, Void unused) {
            node.getTypeArguments().stream().filter(ReadAsCensusTest::denotesReadAs).forEach(argument -> offend("type argument", argument));
            return super.visitParameterizedType(node, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            node.getTypeArguments().stream().filter(ReadAsCensusTest::denotesReadAs).forEach(argument -> offend("type argument", argument));
            return super.visitMethodInvocation(node, unused);
        }

        @Override
        public Void visitNewClass(NewClassTree node, Void unused) {
            constructions += denotesReadAs(node.getIdentifier()) ? 1 : 0;
            node.getTypeArguments().stream().filter(ReadAsCensusTest::denotesReadAs).forEach(argument -> offend("type argument", argument));
            return super.visitNewClass(node, unused);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void unused) {
            constructions += node.getMode() == MemberReferenceTree.ReferenceMode.NEW && denotesReadAs(node.getQualifierExpression()) ? 1 : 0;
            return super.visitMemberReference(node, unused);
        }

        private void offend(String kind, Tree node) {
            if (exempt) {
                return;
            }

            var file = unit.getSourceFile().getName();
            var line = unit.getLineMap().getLineNumber(positions.getStartPosition(unit, node));

            offences.add(file.substring(file.lastIndexOf('/') + 1) + ":" + line + " " + kind);
        }
    }
}
