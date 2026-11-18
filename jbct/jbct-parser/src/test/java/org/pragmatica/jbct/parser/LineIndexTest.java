package org.pragmatica.jbct.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Line terminators as the Java compiler counts them: LF, CRLF (one) and a lone CR.
class LineIndexTest {
    @Test
    void aLoneCarriageReturn_endsALine() {
        var index = new LineIndex("a\rb\rc");

        assertThat(index.lineAt(2)).isEqualTo(2);
        assertThat(index.lineAt(4)).isEqualTo(3);
        assertThat(index.columnAt(4)).isEqualTo(1);
        assertThat(index.lineCount()).isEqualTo(3);
    }

    @Test
    void carriageReturnLineFeed_isOneTerminator() {
        var index = new LineIndex("a\r\nb\r\nc");

        assertThat(index.lineCount()).isEqualTo(3);
        assertThat(index.lineAt(3)).isEqualTo(2);
        assertThat(index.columnAt(3)).isEqualTo(1);
    }

    @Test
    void lineFeed_stillEndsALine() {
        assertThat(new LineIndex("a\nb\n").lineCount()).isEqualTo(3);
    }
}
