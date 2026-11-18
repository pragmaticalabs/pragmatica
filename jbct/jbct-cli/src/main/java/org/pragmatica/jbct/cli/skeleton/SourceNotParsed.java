package org.pragmatica.jbct.cli.skeleton;

import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.parser.LineIndex;
import org.pragmatica.lang.Cause;


/// A source that does not parse, with the position of the first diagnostic (1-based row and column). No skeleton is
/// produced for such a source: a partial one would read as a complete one.
public record SourceNotParsed(int row, int column, String detail) implements Cause {
    /// A plain factory for the failure at a known position.
    static SourceNotParsed sourceNotParsed(int row, int column, String detail) {
        return new SourceNotParsed(row, column, detail);
    }

    static Cause sourceNotParsed(Cause cause, String source) {
        return switch (cause) {
            case Java25Parser.ParseError error when!error.diagnostics().isEmpty() -> positioned(error, source);
            default -> cause;
        };
    }

    private static Cause positioned(Java25Parser.ParseError error, String source) {
        var first = error.diagnostics().getFirst();
        var lines = new LineIndex(source);

        return sourceNotParsed(lines.lineAt(first.offset()),
                               lines.columnAt(first.offset()),
                               first.message());
    }

    @Override
    public String message() {
        return row + ":" + column + ": " + detail;
    }
}
