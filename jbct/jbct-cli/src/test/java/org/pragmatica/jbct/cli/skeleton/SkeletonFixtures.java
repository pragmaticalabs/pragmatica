package org.pragmatica.jbct.cli.skeleton;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;


/// Fixture access for the skeleton tests. Fixtures live in `src/test/resources/skeleton`; a golden file is the
/// `--fields` output of the same name.
final class SkeletonFixtures {
    static final List<String> NAMES = List.of("Nested",
                                              "Records",
                                              "Enums",
                                              "Annotated",
                                              "Trivia",
                                              "TwoAnnotations",
                                              "Sealed",
                                              "Defaults",
                                              "Anonymous",
                                              "Generics",
                                              "Docs");

    private SkeletonFixtures() {}

    static Stream<String> names() {
        return NAMES.stream();
    }

    static String resource(String name) {
        try (var in = SkeletonFixtures.class.getResourceAsStream("/skeleton/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("fixture unreadable: " + name, e);
        }
    }

    static String source(String fixture) {
        return resource(fixture + ".java");
    }

    static List<Declaration> declarations(String source, boolean fields) {
        return Skeletons.skeleton(source, fields).fold(cause -> {
                                                           throw new AssertionError(cause.message());
                                                       },
                                                       Skeleton::declarations);
    }

    static String render(String source, boolean fields) {
        return SkeletonText.render(Skeleton.skeleton(declarations(source, fields)));
    }

    static Stream<Declaration> flatten(Declaration declaration) {
        return Stream.concat(Stream.of(declaration),
                             declaration.children().stream().flatMap(SkeletonFixtures::flatten));
    }

    /// The source text of a range, computed from the line structure of the source alone (rows split on `\n`, a
    /// trailing `\r` dropped, columns counted in characters), so a wrong coordinate cannot agree with itself.
    static String cut(String source, Range range) {
        var lines = source.split("\n", -1);
        var first = lines[range.startRow() - 1].stripTrailing();
        var last = lines[range.endRow() - 1];

        return range.startRow() == range.endRow()
               ? last.substring(range.startColumn() - 1, range.endColumn())
               : first.substring(range.startColumn() - 1)
                + "\n" + middle(lines, range) + last.substring(0, range.endColumn());
    }

    private static String middle(String[] lines, Range range) {
        return String.join("\n",
                           List.of(lines).subList(range.startRow(), range.endRow() - 1)) + (range.endRow() - range.startRow() > 1
                                                                                            ? "\n"
                                                                                            : "");
    }
}
