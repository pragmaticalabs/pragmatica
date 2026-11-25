/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.testing;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/// Fails the build when a test source holds a nested class with test methods that nothing runs (a static one that is not `@Nested`:
/// surefire's `**/*$*` exclude drops it and no one reports it, #3688), and when a pom's surefire or failsafe `<excludes>` list would
/// replace that default exclude instead of keeping it. There is no allow-list: the tree has no such class, a new one is a build failure.
class NestedTestScanTest {
    private static final int MINIMUM_EXAMINED_FILES = 1_000;
    private static final List<String> TEST_ANNOTATIONS = List.of("Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate");
    private static final String NESTED_EXCLUDE = "**/*$*";

    @TempDir
    Path tmp;

    @Test
    void repository_holdsNoNestedTestClassThatNothingRuns() throws IOException {
        var scan = NestedTestScan.scan(repositoryRoot());

        assertThat(scan.examinedFiles()).as("positive controls: the scan reached the whole repository, including this test")
                                        .hasSizeGreaterThan(MINIMUM_EXAMINED_FILES)
                                        .contains("testing/src/test/java/org/pragmatica/testing/NestedTestScanTest.java");
        assertThat(scan.nestedTestClasses()).as("positive control: the scan sees the legal @Nested test classes of %d examined files", scan.examinedFiles().size()).isPositive();
        assertThat(scan.abstractBases()).as("positive control: the scan sees the legal abstract bases that a run class extends").isPositive();
        assertThat(scan.launched()).as("positive control: the scan sees the fixtures a launcher-importing file lists by Name.class").isPositive();
        assertThat(scan.violations().stream().map(NestedTestScan.Violation::message).toList())
            .as("nested classes with test methods that nothing runs (%d files scanned)", scan.examinedFiles().size())
            .isEmpty();
    }

    @Test
    void repository_everySurefireAndFailsafeExcludesList_keepsTheNestedExclude() throws Exception {
        var poms = poms(repositoryRoot());
        var checked = new ArrayList<String>();
        var replacing = new ArrayList<String>();

        for (var pom : poms) {
            collectExcludesLists(repositoryRoot(), pom, checked, replacing);
        }
        assertThat(poms).as("positive control: the scan reached the poms").hasSizeGreaterThan(50).contains("pom.xml");
        assertThat(checked).as("positive control: the root pom's surefire list is read").contains("pom.xml");
        assertThat(replacing).as("an <excludes> list that replaces the parent's drops `" + NESTED_EXCLUDE + "`: list it plus the nested exclude, or use combine.children=\"append\"")
                             .isEmpty();
    }

    @Test
    void excludesCheck_aListWithoutTheNestedExclude_isReplacing_andAppendOrContainsIsNot() throws Exception {
        var replacing = excludesVerdict(pom("<exclude>**/*IT.java</exclude>", ""));
        var appending = excludesVerdict(pom("<exclude>**/*IT.java</exclude>", " combine.children=\"append\""));
        var containing = excludesVerdict(pom("<exclude>**/*IT.java</exclude><exclude>" + NESTED_EXCLUDE + "</exclude>", ""));

        assertThat(replacing).isTrue();
        assertThat(appending).isFalse();
        assertThat(containing).isFalse();
    }

    @Test
    void violations_everyTestAnnotation_onAStaticNestedClass_isReported() throws IOException {
        for (var annotation : TEST_ANNOTATIONS) {
            var scan = NestedTestScan.scan(plant("A.java", String.join("\n",
                                                                   "class A {",
                                                                   "    static class Inner {",
                                                                   "        @" + annotation + " void t() {}",
                                                                   "    }",
                                                                   "}")));

            assertThat(scan.violations()).as("@" + annotation).extracting(NestedTestScan.Violation::name, NestedTestScan.Violation::line)
                                         .containsExactly(tuple("Inner", 2));
        }
    }

    @Test
    void violations_aFullyQualifiedAnnotation_aNonStaticInnerWithoutNested_aDeepOne_andAnUnextendedAbstractBase_areReported() throws IOException {
        var source = String.join("\n",
                                 "class A {",
                                 "    static class Qualified {",
                                 "        @org.junit.jupiter.api.Test void t() {}",
                                 "    }",
                                 "    class Inner {",
                                 "        @Test void t() {}",
                                 "    }",
                                 "    @Nested class Holder {",
                                 "        static class Deep { @Test void t() {} }",
                                 "    }",
                                 "    abstract static class Orphan {",
                                 "        @Test void t() {}",
                                 "    }",
                                 "    abstract static class ExtendedByAHelper {",
                                 "        @Test void t() {}",
                                 "    }",
                                 "    static class Helper extends ExtendedByAHelper {}",
                                 "}");

        assertThat(NestedTestScan.scan(plant("A.java", source)).violations())
            .extracting(NestedTestScan.Violation::name, NestedTestScan.Violation::line)
            .containsExactly(tuple("Qualified", 2), tuple("Inner", 5), tuple("Deep", 9), tuple("Orphan", 11), tuple("ExtendedByAHelper", 14));
    }

    @Test
    void legal_aNestedInnerClass_withAnyOfTheAnnotationForms_isNotReported() throws IOException {
        var source = String.join("\n",
                                 "class A {",
                                 "    @Nested class Plain { @Test void t() {} }",
                                 "    @org.junit.jupiter.api.Nested class Qualified { @Test void t() {} }",
                                 "    @Nested @DisplayName(\"x { y\") @Tag({\"a\", \"b\"}) class Annotated { @ParameterizedTest void t() {} }",
                                 "    @DisplayName(\"first\") @Nested class AnnotatedFirst<T extends Comparable<T>> { @RepeatedTest(2) void t() {} }",
                                 "    @Nested class Outer { @Nested class Inner { @TestFactory void t() {} } }",
                                 "}");
        var scan = NestedTestScan.scan(plant("A.java", source));

        assertThat(scan.violations()).isEmpty();
        assertThat(scan.nestedTestClasses()).isEqualTo(5);
    }

    @Test
    void legal_anAbstractNestedBase_extendedByATopLevelClass_orByANestedClass_isNotReported() throws IOException {
        write(tmp.resolve("m/src/test/java/Base.java"), String.join("\n",
                                                                   "class Holder {",
                                                                   "    abstract static class SharedBase { @Test void t() {} }",
                                                                   "    abstract class Contract { @Test void c() {} }",
                                                                   "    @Nested class Entry extends Contract {}",
                                                                   "}"));
        write(tmp.resolve("m/src/test/java/Sub.java"), "class SubTest extends Holder.SharedBase<String> {}");

        var scan = NestedTestScan.scan(tmp);

        assertThat(scan.violations()).isEmpty();
        assertThat(scan.abstractBases()).isEqualTo(2);
    }

    @Test
    void legal_anAbstractNestedBase_withNoExtender_isReported_soTheExtenderIsWhatMakesItLegal() throws IOException {
        var scan = NestedTestScan.scan(plant("A.java", "class A { abstract static class SharedBase { @Test void t() {} } }"));

        assertThat(scan.violations()).extracting(NestedTestScan.Violation::name).containsExactly("SharedBase");
    }

    @Test
    void legal_helpersWithoutTests_andTopLevelTestClasses_areNotReported() throws IOException {
        var source = String.join("\n",
                                 "class A {",
                                 "    @Test void own() {}",
                                 "    static class Helper { void t() {} }",
                                 "    record Fixture(int x) { void t() {} }",
                                 "    enum Mode { ONE { void t() {} } }",
                                 "    void method() {",
                                 "        var anonymous = new Runnable() { @Test public void run() {} };",
                                 "        class Local { @Test void t() {} }",
                                 "        Runnable lambda = () -> { class InLambda { @Test void t() {} } };",
                                 "    }",
                                 "}",
                                 "class Second { @Test void t() {} }");

        assertThat(NestedTestScan.scan(plant("A.java", source)).violations()).isEmpty();
    }

    @Test
    void legal_testAnnotationsInCommentsStringsAndTextBlocks_areNotRead() throws IOException {
        var source = String.join("\n",
                                 "class A {",
                                 "    // static class InLine { @Test void t() {} }",
                                 "    /* static class InBlock { @Test void t() {} } */",
                                 "    /// static class InDoc { @Test void t() {} }",
                                 "    static final String TEXT = \"static class InString { @Test void t() {} }\";",
                                 "    static final char BRACE = '{';",
                                 "    static final String BLOCK = \"\"\"",
                                 "        static class InBlockText { @Test void t() {} }",
                                 "        \"\"\";",
                                 "    static class Helper {}",
                                 "}");

        assertThat(NestedTestScan.scan(plant("A.java", source)).violations()).isEmpty();
    }

    @Test
    void scan_theViolationLine_isThatOfTheClassKeyword_afterLiteralsAndComments() throws IOException {
        var source = String.join("\n",
                                 "class A {",
                                 "    static final String S = \"a;{b\";",
                                 "    /* one",
                                 "       two */",
                                 "    @SuppressWarnings({\"a\", \"b\"})",
                                 "    static class Inner {",
                                 "        @Test void t() {}",
                                 "    }",
                                 "}");

        assertThat(NestedTestScan.scan(plant("A.java", source)).violations()).extracting(NestedTestScan.Violation::line).containsExactly(6);
    }

    @Test
    void scan_aTestInAnAnonymousBodyInsideACall_doesNotMarkTheEnclosingNestedClass() throws IOException {
        var source = String.join("\n",
                                 "class A {",
                                 "    static class Inner {",
                                 "        void run() { call(new Runnable() { @Test public void t() {} }); }",
                                 "    }",
                                 "}");

        assertThat(NestedTestScan.scan(plant("A.java", source)).violations()).isEmpty();
    }

    @Test
    void scan_srcItJava_isExamined_andBuildOutputAndMainSourcesAreNot() throws IOException {
        var source = "class A { static class Inner { @Test void t() {} } }";

        write(tmp.resolve("m/src/it/java/SomeIT.java"), source);
        write(tmp.resolve("m/src/main/java/Main.java"), source);
        write(tmp.resolve("m/target/generated/src/test/java/Gen.java"), source);

        var scan = NestedTestScan.scan(tmp);

        assertThat(scan.examinedFiles()).containsExactly("m/src/it/java/SomeIT.java");
        assertThat(scan.violations()).extracting(NestedTestScan.Violation::file).containsExactly("m/src/it/java/SomeIT.java");
    }

    @Test
    void scan_aRootWithNoTestSources_examinesNothing_soTheRepositoryControlCannotPassOnAnEmptyScope() throws IOException {
        write(tmp.resolve("m/src/main/java/Main.java"), "class Main {}");

        assertThat(NestedTestScan.scan(tmp).examinedFiles()).isEmpty();
    }

    private static boolean excludesVerdict(String pomText) throws Exception {
        var replacing = new ArrayList<String>();

        collectExcludesLists("pom.xml", parse(pomText), new ArrayList<>(), replacing);
        return !replacing.isEmpty();
    }

    private static String pom(String excludes, String attribute) {
        return "<project><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration><excludes" + attribute + ">" + excludes
               + "</excludes></configuration></plugin></plugins></build></project>";
    }

    private static void collectExcludesLists(Path root, String pom, List<String> checked, List<String> replacing) throws Exception {
        collectExcludesLists(pom, parse(Files.readString(root.resolve(pom))), checked, replacing);
    }

    private static void collectExcludesLists(String pom, Element project, List<String> checked, List<String> replacing) {
        var plugins = project.getElementsByTagName("plugin");

        for (var i = 0; i < plugins.getLength(); i++) {
            var plugin = (Element) plugins.item(i);
            var artifact = child(plugin, "artifactId");

            if (artifact != null && (artifact.getTextContent().strip().equals("maven-surefire-plugin") || artifact.getTextContent().strip().equals("maven-failsafe-plugin"))) {
                var excludes = child(child(plugin, "configuration"), "excludes");

                if (excludes != null) {
                    checked.add(pom);
                    if (!excludes.getAttribute("combine.children").equals("append") && !excludes.getTextContent().contains(NESTED_EXCLUDE)) {
                        replacing.add(pom);
                    }
                }
            }
        }
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private static Element parse(String text) throws IOException, SAXException, ParserConfigurationException {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(text))).getDocumentElement();
    }

    private static List<String> poms(Path root) throws IOException {
        var found = new ArrayList<String>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && FlakeScan.SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                       ? FileVisitResult.SKIP_SUBTREE
                       : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("pom.xml")) {
                    found.add(root.relativize(file).toString().replace('\\', '/'));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    private static final String LAUNCHER = "import org.junit.platform.launcher.core.LauncherFactory;";

    @Test
    void run_aNestedClassInAStaticHolderNothingRuns_isReported_withTheEnclosingTypeNamed() throws IOException {
        var scan = NestedTestScan.scan(plant("A.java", "class A { static class Holder { @Nested class Inner { @Test void t() {} } } }"));

        assertThat(scan.violations()).extracting(NestedTestScan.Violation::name, NestedTestScan.Violation::reason)
                                     .containsExactly(tuple("Inner", "@Nested but enclosing Holder is not run"));
    }

    @Test
    void run_aTwoLevelNestedChain_isLegal_andAStaticLinkInTheMiddleIsReported() throws IOException {
        var chain = NestedTestScan.scan(plant("A.java", "class A { @Nested class B { @Nested class C { @Test void t() {} } } }"));
        var broken = NestedTestScan.scan(plant("Z.java", "class Z { @Nested class B { static class M { @Nested class C { @Test void t() {} } } } }"));

        assertThat(chain.violations()).isEmpty();
        assertThat(chain.nestedTestClasses()).isEqualTo(1);
        assertThat(broken.violations()).extracting(NestedTestScan.Violation::name, NestedTestScan.Violation::reason)
                                       .containsExactly(tuple("C", "@Nested but enclosing M is not run"));
    }

    @Test
    void run_aLauncherImportAndTheClassMentionInCode_makeAStaticFixtureLegal_andEitherAloneDoesNot() throws IOException {
        var fixture = "static class S { @Test void t() {} }";
        var both = NestedTestScan.scan(plant("A.java", LAUNCHER + "\nclass A { " + fixture + " void run() { launch(S.class); } }"));
        var testkit = NestedTestScan.scan(plant("A.java", "import org.junit.platform.testkit.engine.EngineTestKit;\nclass A { " + fixture + " Object o = S . class; }"));
        var importOnly = NestedTestScan.scan(plant("A.java", LAUNCHER + "\nclass A { " + fixture + " }"));
        var mentionOnly = NestedTestScan.scan(plant("A.java", "class A { " + fixture + " void run() { launch(S.class); } }"));

        assertThat(both.violations()).isEmpty();
        assertThat(both.launched()).isEqualTo(1);
        assertThat(testkit.violations()).isEmpty();
        assertThat(importOnly.violations()).extracting(NestedTestScan.Violation::name).containsExactly("S");
        assertThat(mentionOnly.violations()).extracting(NestedTestScan.Violation::name).containsExactly("S");
    }

    @Test
    void run_theClassMentionOnlyInACommentOrAString_doesNotLaunch() throws IOException {
        var source = String.join("\n",
                                 LAUNCHER,
                                 "class A {",
                                 "    static class S { @Test void t() {} }",
                                 "    // S.class is listed here",
                                 "    String s = \"S.class\";",
                                 "    String block = \"\"\"",
                                 "        S.class",
                                 "        \"\"\";",
                                 "}");

        assertThat(NestedTestScan.scan(plant("A.java", source)).violations()).extracting(NestedTestScan.Violation::name).containsExactly("S");
    }

    @Test
    void run_aNestedInnerOfALaunchedHolder_isLegalByTheEnclosingClause() throws IOException {
        var source = String.join("\n",
                                 LAUNCHER,
                                 "class A {",
                                 "    static class Holder { @Nested class Inner { @Test void t() {} } }",
                                 "    Class<?>[] fixtures = {Holder.class};",
                                 "}");
        var scan = NestedTestScan.scan(plant("A.java", source));

        assertThat(scan.violations()).isEmpty();
        assertThat(scan.launched()).isEqualTo(1);
        assertThat(scan.nestedTestClasses()).isEqualTo(1);
    }

    @Test
    void run_anAbstractBaseWhoseOnlySubclassIsNotRun_isReported() throws IOException {
        var scan = NestedTestScan.scan(plant("A.java", "class A { abstract static class Base { @Test void t() {} } static class Sub extends Base {} }"));

        assertThat(scan.violations()).extracting(NestedTestScan.Violation::name, NestedTestScan.Violation::reason)
                                     .containsExactly(tuple("Base", "abstract base that no run class extends"));
    }

    private static Path repositoryRoot() {
        var dir = Path.of("").toAbsolutePath();

        while (dir != null && !Files.isDirectory(dir.resolve("testing/src/main/java"))) {
            dir = dir.getParent();
        }
        return dir == null ? Path.of("").toAbsolutePath() : dir;
    }

    private Path plant(String name, String content) throws IOException {
        write(tmp.resolve("m/src/test/java").resolve(name), content);
        return tmp;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
