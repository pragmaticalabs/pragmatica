package org.pragmatica.jbct.cli.skeleton;

import org.junit.jupiter.api.Test;

import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.cut;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.declarations;
import static org.assertj.core.api.Assertions.assertThat;


/// Rows count a lone CR as a line terminator and CRLF as one, as the Java compiler does.
class SkeletonLineEndingTest {
    private static final String BODY = "package p;\n\n/// doc\nclass A {\n    int f;\n\n    void m() {}\n}\n";

    @Test
    void crOnly_aClassOnRowThree_reportsRowThree() {
        var all = declarations("package p;\r\rclass A {\r    void m() {}\r}\r", false);

        assertThat(all.getFirst().range().startRow()).isEqualTo(3);
        assertThat(all.getFirst().children().getFirst().range().startRow()).isEqualTo(4);
    }

    @Test
    void crOnly_everyRangeCutAtItsCoordinates_yieldsTheDeclaration() {
        var source = BODY.replace("\n", "\r");

        assertThat(declarations(source, true).stream().flatMap(SkeletonFixtures::flatten)
                                             .map(declaration -> cut(source, declaration.range()))
                                             .toList()).hasSize(3)
                                                       .allSatisfy(text -> assertThat(text).matches("(?s)(class A \\{.*\\}|int f;|void m\\(\\) \\{\\})"));
    }

    @Test
    void crOnly_docRowIsTheRowOfTheComment() {
        assertThat(declarations(BODY.replace("\n", "\r"), false).getFirst().docRow().or(0)).isEqualTo(3);
    }

    @Test
    void crlf_countsOneTerminatorPerLineBreak_likeLf() {
        var lf = declarations(BODY, true);
        var crlf = declarations(BODY.replace("\n", "\r\n"), true);

        assertThat(crlf).isEqualTo(lf);
    }
}
