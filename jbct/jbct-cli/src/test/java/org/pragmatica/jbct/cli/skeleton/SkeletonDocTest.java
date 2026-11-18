package org.pragmatica.jbct.cli.skeleton;


import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.declarations;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.source;

/// `doc@`: the first row of the doc comment directly above a declaration, outside its range.
class SkeletonDocTest {
    @Test
    void docRow_isTheFirstRowOfTheCommentBlock_ofBothCommentStyles() {
        var docs = docRows("Docs");

        assertThat(docs).containsEntry("Docs", 3)
                        .containsEntry("one", 7)
                        .containsEntry("three", 10)
                        .containsEntry("block", 15)
                        .containsEntry("annotated", 21)
                        .containsEntry("R", 27);
    }

    @Test
    void aPlainComment_isNotADocComment() {
        assertThat(docRows("Docs")).doesNotContainKey("plain");
    }

    @Test
    void aDocComment_belongsToTheDeclarationBelowIt_notToTheMembersWithoutOne() {
        var nested = docRows("Nested");

        assertThat(nested).containsEntry("Nested", 5).containsEntry("Middle", 11).containsEntry("Inner", 15).doesNotContainKey("pick");
    }

    @Test
    void docRow_isAlwaysAboveTheRange() {
        var all = declarations(source("Docs"), true).stream().flatMap(SkeletonFixtures::flatten).toList();

        assertThat(all.stream().filter(each -> each.docRow().isPresent())).allSatisfy(each -> assertThat(each.docRow().or(0)).isLessThan(each.range().startRow()));
    }

    private static java.util.Map<String, Integer> docRows(String fixture) {
        var result = new java.util.LinkedHashMap<String, Integer>();

        declarations(source(fixture), true).stream()
                                           .flatMap(SkeletonFixtures::flatten)
                                           .forEach(each -> each.docRow().onPresent(row -> result.put(each.name(), row)));
        return result;
    }
}
