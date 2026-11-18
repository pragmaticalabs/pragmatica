package org.pragmatica.jbct.cli.skeleton;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.cut;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.declarations;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.render;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.source;

/// What is listed and what is not, and where a range begins and ends when trivia surrounds it.
class SkeletonMembersTest {
    @Test
    void anonymousLocalClassesAndLambdas_areNotListed_theirHolderIs() {
        var names = names("Anonymous");

        assertThat(names).containsExactly("Anonymous", "build", "FIELD", "after");
        assertThat(names).doesNotContain("Local", "run", "hidden", "get");
    }

    @Test
    void enumConstantBodies_areNotListed() {
        assertThat(names("Enums")).contains("HIGH", "create").doesNotContain("toString");
    }

    @Test
    void aRange_endsAtTheLastRealToken_notAtTheCommentsAndBlankLinesAfterIt() {
        var source = source("Trivia");
        var all = declarations(source, true).stream().flatMap(SkeletonFixtures::flatten).toList();

        assertThat(cutOf(source, all, "second")).isEqualTo("void second() {\n\n    }");
        assertThat(cutOf(source, all, "third")).isEqualTo("void third() {}");
        assertThat(cutOf(source, all, "fourth")).isEqualTo("void fourth() {}");
        assertThat(cutOf(source, all, "first")).isEqualTo("private int first;");
        assertThat(cutOf(source, all, "Trivia")).endsWith("int fifth;\n\n}");
    }

    @Test
    void aRange_startsAtTheFirstAnnotation_evenWhenModifiersFollowOnOtherLines() {
        var source = source("TwoAnnotations");
        var all = declarations(source, false).stream().flatMap(SkeletonFixtures::flatten).toList();
        var twoLines = all.stream().filter(each -> each.name().equals("twoLines")).findFirst().orElseThrow();

        assertThat(twoLines.range().startRow()).isEqualTo(4);
        assertThat(twoLines.range().startColumn()).isEqualTo(5);
        assertThat(twoLines.annotations()).containsExactly("Deprecated", "SuppressWarnings");
        assertThat(cutOf(source, all, "twoLines")).startsWith("@Deprecated\n    @SuppressWarnings(\"unused\")\n    public static\n    void twoLines");
    }

    @Test
    void sealedInterface_listsItsPermittedRecordsAndEnumAsChildren() {
        var sealed = declarations(source("Sealed"), false).getFirst();

        assertThat(sealed.children().stream().map(Declaration::label)).containsExactly("Leaf", "Pair", "Nothing", "size(): int");
        assertThat(sealed.children().stream().map(Declaration::kind)).containsExactly(Kind.RECORD, Kind.RECORD, Kind.ENUM, Kind.METHOD);
    }

    @Test
    void interfaceMembers_listDefaultStaticAndPrivateMethods() {
        var labels = declarations(source("Defaults"), false).getFirst().children().stream().map(Declaration::label);

        assertThat(labels).containsExactly("get(): T", "twice(): List<T>", "of(A): Defaults<A>", "hidden(): String");
    }

    @Test
    void genericMethods_keepTheirParameterAndReturnTypesAsWritten() {
        var labels = declarations(source("Generics"), false).getFirst().children().stream().map(Declaration::label);

        assertThat(labels).contains("max(List<? extends T>, Map<K, V[]>): T", "rethrow(E): void", "listOf(X...): List<X>");
    }

    @Test
    void tabsCountOneColumn_andWindowsLineEndingsDoNotShiftRows() {
        assertThat(render("class A {\r\n\tvoid m() {}\r\n}\r\n", false)).isEqualTo("class A 1:1-3:1\n  method m(): void 2:2-2:12\n");
    }

    @Test
    void anEmptyFile_hasAnEmptySkeleton() {
        assertThat(render("", true)).isEmpty();
    }

    private static List<String> names(String fixture) {
        return declarations(source(fixture), true).stream().flatMap(SkeletonFixtures::flatten).map(Declaration::name).toList();
    }

    private static String cutOf(String source, List<Declaration> all, String name) {
        return cut(source, all.stream().filter(each -> each.name().equals(name)).findFirst().orElseThrow().range());
    }
}
