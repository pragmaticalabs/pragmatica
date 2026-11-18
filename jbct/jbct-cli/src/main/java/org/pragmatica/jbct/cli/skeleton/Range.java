package org.pragmatica.jbct.cli.skeleton;

/// A source range in 1-based rows and columns, the END INCLUSIVE: `endRow:endColumn` is the position of the
/// last character of the declaration (its closing brace or semicolon), not the position after it. Columns count
/// characters of the line, a tab counts one.
public record Range(int startRow, int startColumn, int endRow, int endColumn) {
    public static Range range(int startRow, int startColumn, int endRow, int endColumn) {
        return new Range(startRow, startColumn, endRow, endColumn);
    }

    /// `startRow:startCol-endRow:endCol`.
    public String render() {
        return startRow + ":" + startColumn + "-" + endRow + ":" + endColumn;
    }
}
