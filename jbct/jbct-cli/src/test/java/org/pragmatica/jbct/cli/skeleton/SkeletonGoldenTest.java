package org.pragmatica.jbct.cli.skeleton;

import java.util.stream.Collectors;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.render;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.resource;
import static org.pragmatica.jbct.cli.skeleton.SkeletonFixtures.source;

/// The whole output of every fixture against its golden file, with and without `--fields`.
class SkeletonGoldenTest {
    @ParameterizedTest
    @MethodSource("org.pragmatica.jbct.cli.skeleton.SkeletonFixtures#names")
    void skeleton_withFields_matchesTheGoldenFile(String fixture) {
        assertThat(render(source(fixture), true)).isEqualTo(resource(fixture + ".skeleton.txt"));
    }

    @ParameterizedTest
    @MethodSource("org.pragmatica.jbct.cli.skeleton.SkeletonFixtures#names")
    void skeleton_withoutFields_isTheGoldenFileMinusFieldsAndEnumConstants(String fixture) {
        var expected = resource(fixture + ".skeleton.txt").lines()
                                                          .filter(SkeletonGoldenTest::isNotFieldLike)
                                                          .map(line -> line + "\n")
                                                          .collect(Collectors.joining());

        assertThat(render(source(fixture), false)).isEqualTo(expected);
    }

    private static boolean isNotFieldLike(String line) {
        var text = line.stripLeading();

        return !text.startsWith("field ") && !text.startsWith("enum-constant ");
    }
}
