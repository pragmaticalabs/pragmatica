package org.pragmatica.jbct.score;

import java.nio.file.Path;

import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The density denominator counts non-blank lines ([SourceFile#nonBlankLines]); the scan itself is performed by the run
/// (`RunScore`), whose unanalysed-file pins are `RunActionTableTest`'s S4 and S5 rows.
class SourceScanTest {
    @Test
    void nonBlankLines_blankAndWhitespaceOnlyLines_areNotCounted() {
        var source = SourceFile.sourceFile(Path.of("Sample.java"), "a\n\n   \n\tb\n");

        assertThat(source.nonBlankLines()).isEqualTo(2);
    }

    @Test
    void nonBlankLines_commentsAndBraces_areCounted() {
        var source = SourceFile.sourceFile(Path.of("Sample.java"), "// comment\n{\n}\n");

        assertThat(source.nonBlankLines()).isEqualTo(3);
    }

    @Test
    void nonBlankLines_missingTrailingNewline_doesNotChangeTheCount() {
        assertThat(SourceFile.sourceFile(Path.of("Sample.java"), "a\nb\n").nonBlankLines())
                  .isEqualTo(SourceFile.sourceFile(Path.of("Sample.java"), "a\nb").nonBlankLines());
    }
}
