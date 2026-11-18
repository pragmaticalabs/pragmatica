package org.pragmatica.jbct.cli.skeleton;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/// The text rendering of a skeleton: one line per declaration, two spaces of indentation per nesting level,
///
/// `<kind> <name><signature> <startRow>:<startCol>-<endRow>:<endCol>[ doc@<row>][ @Annotation ...]`
///
/// Rows and columns are 1-based and the end is inclusive (the position of the closing brace or semicolon).
public final class SkeletonText {
    private static final String INDENT = "  ";

    private SkeletonText() {}

    /// The lines of `skeleton`, each ended by a newline; empty for a file that declares nothing.
    public static String render(Skeleton skeleton) {
        return lines(skeleton.declarations(), 0).collect(Collectors.joining());
    }

    private static Stream<String> lines(List<Declaration> declarations, int depth) {
        return declarations.stream()
                           .flatMap(declaration -> Stream.concat(Stream.of(line(declaration, depth)),
                                                                 lines(declaration.children(),
                                                                       depth + 1)));
    }

    private static String line(Declaration declaration, int depth) {
        return INDENT.repeat(depth) + declaration.kind()
                                                 .label() + label(declaration)
             + " " + declaration.range()
                                .render() + doc(declaration) + annotations(declaration)
             + "\n";
    }

    private static String label(Declaration declaration) {
        return declaration.label()
                          .isEmpty()
               ? ""
               : " " + declaration.label();
    }

    private static String doc(Declaration declaration) {
        return declaration.docRow()
                          .map(row -> " doc@" + row)
                          .or("");
    }

    private static String annotations(Declaration declaration) {
        return declaration.annotations()
                          .stream()
                          .map(annotation -> " " + annotation)
                          .collect(Collectors.joining());
    }
}
