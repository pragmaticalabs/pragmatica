package org.pragmatica.jbct.format.flow;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import org.pragmatica.lang.Option;
import org.pragmatica.peg.token.TokenArray;


/// The comment cursor of the printer (#3698, #3691): the one place that writes a comment to the output.
///
/// **Invariant:** the comments of the source are printed in source order, each exactly once.
///
/// **Structure:** the printer never reads the text of a comment. A comment is printed by [#print], which first prints
/// every earlier comment that is still unprinted (through the `catchUp` layout), then hands the comment's [Slot] to the
/// caller's layout. [Slot#write] is the only code that reads the text of a comment token and passes it on to the [Sink],
/// and the only code that marks a comment printed. A layout cannot write a comment text itself, because it never holds
/// one.
///
/// **Runtime check:** [Slot#write] refuses to print comment N while a comment before N is unprinted: the first such
/// event is kept as a [Violation] naming both comments, and the printer's result carries it, so the format fails
/// instead of producing an out-of-order file. The text is still written, so the printer carries on to the end.
final class CommentCursor {
    /// Where the text of a comment goes: the printer's output, which counts its column and lines.
    interface Sink {
        void write(String text);
    }

    /// How the printer places the output before and after a comment text: it calls [Slot#write] exactly once.
    interface Layout {
        void place(Slot slot);
    }

    /// The first comment printed out of order: the input line of the one printed too early and a description naming it
    /// and the earlier comment that was still unprinted.
    record Violation(int line, String details) {}

    private final TokenArray tokens;
    private final String source;
    private final Sink sink;
    private final Layout catchUp;
    private final int[] comments;
    private final Set<Integer> printed = new HashSet<>();
    /// Position in `comments` of the first comment that is not printed yet.
    private int frontier;
    private Option<Violation> violation = Option.none();

    private CommentCursor(TokenArray tokens, String source, Sink sink, Layout catchUp) {
        this.tokens = tokens;
        this.source = source;
        this.sink = sink;
        this.catchUp = catchUp;
        this.comments = IntStream.range(0, tokens.count()).filter(t -> isComment(tokens, t)).toArray();
    }

    /// `catchUp` is the layout of a comment printed ahead of a later one that a printer rule asks for.
    static CommentCursor commentCursor(TokenArray tokens, String source, Sink sink, Layout catchUp) {
        return new CommentCursor(tokens, source, sink, catchUp);
    }

    /// The one operation that prints a comment: print every earlier unprinted comment, then comment `t` by `layout`.
    /// A comment that is printed already is not printed again.
    void print(int t, Layout layout) {
        if (printed.contains(t)) {
            return;
        }

        printEarlier(t);
        layout.place(new Slot(t));
    }

    private void printEarlier(int t) {
        for (int at = frontier; at < comments.length && comments[at] < t; at++) {
            print(comments[at], catchUp);
        }
    }

    boolean isComment(int t) {
        return isComment(tokens, t);
    }

    private static boolean isComment(TokenArray tokens, int t) {
        return tokens.isTrivia(t) && isCommentKind(tokens.kindAt(t));
    }

    private static boolean isCommentKind(int kind) {
        return kind == TokenArray.KIND_LINE_COMMENT || kind == TokenArray.KIND_BLOCK_COMMENT || kind == TokenArray.KIND_DOC_LINE_COMMENT || kind == TokenArray.KIND_DOC_BLOCK_COMMENT;
    }

    boolean isPrinted(int t) {
        return printed.contains(t);
    }

    /// `//` or `///`: the comment ends its line.
    boolean isLine(int t) {
        int kind = tokens.kindAt(t);

        return kind == TokenArray.KIND_LINE_COMMENT || kind == TokenArray.KIND_DOC_LINE_COMMENT;
    }

    /// A comment that spans more than one source line.
    boolean isMultiLine(int t) {
        return tokens.textAt(t)
                     .toString()
                     .indexOf('\n') >= 0;
    }

    /// Offset in the source of the first character of comment `t`.
    int startOf(int t) {
        return tokens.startAt(t);
    }

    Option<Violation> violation() {
        return violation;
    }

    /// The right to print one comment, valid inside [Layout#place]: the only way its text reaches the output.
    final class Slot {
        private final int t;

        private Slot(int t) {
            this.t = t;
        }

        /// Index of the comment's token.
        int index() {
            return t;
        }

        /// Print the comment: `prefix`, then its text with the trailing whitespace of every line removed.
        void write(String prefix) {
            checkOrder();
            sink.write(prefix + text());
            printed.add(t);
            while (frontier < comments.length && printed.contains(comments[frontier])) {
                frontier++;
            }
        }

        private String text() {
            return String.join("\n",
                               Arrays.stream(tokens.textAt(t).toString().split("\n", -1))
                                     .map(String::stripTrailing)
                                     .toList());
        }

        private void checkOrder() {
            if (frontier < comments.length && comments[frontier] != t && violation.isEmpty()) {
                violation = Option.some(new Violation(lineOf(t),
                                                      "comment `%s` (input line %d) would be printed before the earlier comment `%s` (input line %d)".formatted(first(t),
                                                                                                                                                                lineOf(t),
                                                                                                                                                                first(comments[frontier]),
                                                                                                                                                                lineOf(comments[frontier]))));
            }
        }
    }

    private String first(int t) {
        var text = tokens.textAt(t).toString().strip();
        int newline = text.indexOf('\n');

        return newline < 0
               ? text
               : text.substring(0, newline) + "...";
    }

    private int lineOf(int t) {
        return (int) source.substring(0,
                                      Math.min(tokens.startAt(t),
                                               source.length()))
                           .chars()
                           .filter(c -> c == '\n')
                           .count() + 1;
    }
}
