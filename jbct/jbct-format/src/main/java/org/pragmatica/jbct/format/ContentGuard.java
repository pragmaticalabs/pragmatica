package org.pragmatica.jbct.format;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.jbct.parser.v6.Java25Lexer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.peg.token.TokenArray;

import static org.pragmatica.lang.Result.unitResult;


/// The formatter's content-preservation invariant (#3691): a formatted text is returned only when it carries the
/// same content as its input. Both texts are read with the parser's own lexer (never a regex over raw text, because
/// string literals and text blocks contain `//` and `/*`) and compared on two sequences:
///
/// 1. the comments, line and block, in order. The text is compared with trailing whitespace of every line removed; a
///    doc block comment (`/** */`) is also compared with the leading whitespace of every line removed, because the
///    formatter re-indents the continuation lines of a javadoc;
/// 2. the non-whitespace, non-comment tokens, in order, with `\r\n` read as `\n` inside a token (a text block).
///
/// The first difference of either sequence is a [FormattingError.ContentChanged] naming the file, the input line of
/// the first lost, added or changed item and its text. No formatter rule is allowed to change either sequence: a rule
/// that must (for example moving a comment together with the declaration it documents) is a named exception here,
/// stated and tested, never a loosened comparison.
public interface ContentGuard {
    /// The kind of lexed item the comparison cares about.
    enum Category {
        COMMENT,
        CODE,
        BLANK
    }

    /// One lexed item: its category, its text normalised for comparison and its offset in the text it was read from.
    record Item(Category category, String text, int offset) {
        static Item item(Category category, String text, int offset) {
            return new Item(category, text, offset);
        }
    }

    /// Return success when `output` carries the content of `input`; otherwise the typed refusal for `fileName`.
    static Result<Unit> verify(String fileName, String input, String output) {
        var before = Java25Lexer.lex(input);
        var after = Java25Lexer.lex(output);

        return firstDifference(fileName,
                               input,
                               items(before, Category.COMMENT),
                               items(after, Category.COMMENT),
                               "comment").orElse(() -> firstDifference(fileName,
                                                                       input,
                                                                       items(before, Category.CODE),
                                                                       items(after, Category.CODE),
                                                                       "token"))
                              .<Result<Unit>> fold(() -> unitResult(),
                                                   cause -> cause.result());
    }

    private static Option<FormattingError> firstDifference(String fileName,
                                                           String input,
                                                           List<Item> before,
                                                           List<Item> after,
                                                           String label) {
        return Option.from(IntStream.range(0,
                                           Math.max(before.size(),
                                                    after.size()))
                                    .filter(index -> differs(before, after, index))
                                    .boxed()
                                    .findFirst()).map(index -> refusal(fileName, input, before, after, index, label));
    }

    private static boolean differs(List<Item> before, List<Item> after, int index) {
        return index >= before.size() || index >= after.size() || !before.get(index)
                                                                         .text()
                                                                         .equals(after.get(index).text());
    }

    private static FormattingError refusal(String fileName,
                                           String input,
                                           List<Item> before,
                                           List<Item> after,
                                           int index,
                                           String label) {
        return index >= after.size() || index < before.size() && isLost(before, after, index)
               ? located(fileName, input, before.get(index), label + " lost: " + shown(before.get(index)))
               : index >= before.size() || isAdded(before, after, index)
                 ? addedAfter(fileName, input, before, index, label + " added: " + shown(after.get(index)))
                 : located(fileName,
                           input,
                           before.get(index),
                           label + " changed: " + shown(before.get(index)) + " became " + shown(after.get(index)));
    }

    /// The input item at `index` is lost when the output has no such item from `index` on; an item the output still
    /// has further on was moved, not lost.
    private static boolean isLost(List<Item> before, List<Item> after, int index) {
        return ! contains(after, index, before.get(index));
    }

    /// The output item at `index` is added when the input has no such item from `index` on.
    private static boolean isAdded(List<Item> before, List<Item> after, int index) {
        return ! contains(before, index, after.get(index));
    }

    private static boolean contains(List<Item> items, int from, Item wanted) {
        return items.stream()
                    .skip(from)
                    .anyMatch(item -> item.text()
                                          .equals(wanted.text()));
    }

    private static FormattingError located(String fileName, String input, Item item, String details) {
        return FormattingError.contentChanged(fileName, lineOf(input, item.offset()), details);
    }

    /// An item the input does not have has no input line of its own: the line of the input's last item of the kind.
    private static FormattingError addedAfter(String fileName,
                                              String input,
                                              List<Item> before,
                                              int index,
                                              String details) {
        return FormattingError.contentChanged(fileName,
                                              before.isEmpty()
                                              ? 1
                                              : lineOf(input,
                                                       before.get(Math.min(index, before.size() - 1)).offset()),
                                              details);
    }

    private static int lineOf(String text, int offset) {
        return 1 + (int) text.substring(0,
                                        Math.min(offset,
                                                 text.length()))
                             .chars()
                             .filter(character -> character == '\n')
                             .count();
    }

    private static String shown(Item item) {
        var text = item.text().replace("\n", "\\n");

        return "`" + (text.length() > 100
                      ? text.substring(0, 100) + "..."
                      : text) + "`";
    }

    private static List<Item> items(TokenArray tokens, Category category) {
        return IntStream.range(0,
                               tokens.count())
                        .mapToObj(index -> item(tokens, index))
                        .filter(item -> item.category() == category)
                        .collect(Collectors.toList());
    }

    private static Item item(TokenArray tokens, int index) {
        var text = tokens.textAt(index).toString();
        var offset = tokens.startAt(index);

        return switch (tokens.kindAt(index)) {
            case TokenArray.KIND_LINE_COMMENT, TokenArray.KIND_DOC_LINE_COMMENT -> Item.item(Category.COMMENT,
                                                                                             text.stripTrailing(),
                                                                                             offset);
            case TokenArray.KIND_BLOCK_COMMENT -> Item.item(Category.COMMENT, stripLines(text, false), offset);
            case TokenArray.KIND_DOC_BLOCK_COMMENT -> Item.item(Category.COMMENT, stripLines(text, true), offset);
            default -> text.isBlank()
                       ? Item.item(Category.BLANK, "", offset)
                       : Item.item(Category.CODE, text.replace("\r\n", "\n"), offset);
        };
    }

    private static String stripLines(String text, boolean both) {
        return text.lines()
                   .map(line -> both
                                ? line.strip()
                                : line.stripTrailing())
                   .collect(Collectors.joining("\n"));
    }
}
