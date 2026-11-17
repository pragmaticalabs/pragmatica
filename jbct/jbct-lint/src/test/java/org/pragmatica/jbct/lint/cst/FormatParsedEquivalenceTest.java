package org.pragmatica.jbct.lint.cst;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.format.JbctFormatter;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The "parse once" of the run rests on this: formatting a tree that is already parsed gives the same verdict as
/// `isFormatted`, which parses by itself (#2525, memo3318 step 1). `formatParsed(parse(s)) == s` iff `isFormatted(s)`,
/// over this module's own sources (formatted and not), inline samples in both states, and one unparseable file.
class FormatParsedEquivalenceTest {
    private static final String FORMATTED = "class A {\n    int x() {\n        return 1;\n    }\n}\n";
    private static final String UNFORMATTED = "class   A{int x(){return   1;}}\n";
    private static final String BROKEN = "class {{{ not java";
    private static final Path MAIN = Path.of("src/main/java/org/pragmatica/jbct/lint/cst");

    private final JbctFormatter formatter = JbctFormatter.jbctFormatter();

    @Test
    void formattedSample_isFormattedBothWays() {
        assertTrue(parsedEquivalent(FORMATTED));
        assertEquals(true, formatter.isFormatted(source(FORMATTED)).or(false));
    }

    @Test
    void unformattedSample_isUnformattedBothWays() {
        assertTrue(parsedEquivalent(UNFORMATTED));
        assertEquals(false, formatter.isFormatted(source(UNFORMATTED)).or(true));
    }

    @Test
    void moduleSources_agreeWithIsFormatted() throws IOException {
        try (var files = Files.list(MAIN)) {
            var sources = files.filter(path -> path.toString().endsWith(".java")).toList();

            assertTrue(sources.size() > 5, "fixture directory not found from " + Path.of("").toAbsolutePath());
            for (var path : sources) {
                assertTrue(parsedEquivalent(Files.readString(path)), path.toString());
            }
        }
    }

    @Test
    void unparseableFile_failsBothWays() {
        assertTrue(new Java25Parser().parse(BROKEN).isFailure());
        assertTrue(formatter.isFormatted(source(BROKEN)).isFailure());
    }

    /// `formatParsed(parse(s)) == s` has the same truth value as `isFormatted(s)`.
    private boolean parsedEquivalent(String content) {
        var viaParsed = new Java25Parser().parse(content)
                                          .map(tree -> formatter.formatParsed(tree, source(content)).content().equals(content))
                                          .or(false);
        var viaCheck = formatter.isFormatted(source(content)).or(false);

        return viaParsed == viaCheck;
    }

    private static SourceFile source(String content) {
        return SourceFile.sourceFile(Path.of("Sample.java"), content);
    }
}
