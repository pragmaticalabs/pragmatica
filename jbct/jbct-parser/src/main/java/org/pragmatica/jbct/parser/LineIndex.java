package org.pragmatica.jbct.parser;

import java.util.Arrays;


/// Maps source character offsets to 1-based (line, column) coordinates. Built once per
/// parse from the input string; queried by Cursor's `startLine`/`startColumn` helpers and
/// any consumer that needs line/column for diagnostics.
public final class LineIndex {
    private final int[] lineStarts;

    public LineIndex(String source) {
        var starts = new int[source.length() / 40 + 1];

        starts[0] = 0;
        int count = 1;

        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                if (count >= starts.length) {
                    starts = Arrays.copyOf(starts, starts.length * 2);
                }

                starts[count++] = i + 1;
            }
        }

        this.lineStarts = Arrays.copyOf(starts, count);
    }

    /// 1-based line number for the character at `offset`. Offsets at or past EOF clamp to
    /// the final line.
    public int lineAt(int offset) {
        int pos = Arrays.binarySearch(lineStarts, offset);

        if (pos >= 0) {
            return pos + 1;
        }
        // binarySearch returns -(insertion point) - 1; the line is one before the insertion point
        return -pos - 1;
    }

    /// 1-based column number for the character at `offset`.
    public int columnAt(int offset) {
        int line = lineAt(offset);

        return offset - lineStarts[line - 1] + 1;
    }

    /// Total line count, useful for diagnostic-coordinates validation.
    public int lineCount() {
        return lineStarts.length;
    }
}
