package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


/// Offset-level text helpers for the Promise-callback rule (JBCT-ASYNC-01) and its collaborators [Declarations],
/// [PromiseCarrier] and [SharedWrites].
///
/// All of them work on the string- and comment-blanked view of a file ([MapperSafety#blankNonCode]), so a bracket or keyword in
/// a literal or a comment is never seen, and every offset still addresses the original source.
sealed interface SourceText permits SourceText.unused {
    record unused() implements SourceText {}

    /// A call chain's text with the statement prefix that precedes it removed, and whether that prefix was `return`.
    record Chain(String text, boolean isReturn) {
        static Chain chain(String text, boolean isReturn) {
            return new Chain(text, isReturn);
        }
    }

    String IDENT = "[A-Za-z_$][A-Za-z0-9_$]*";

    Pattern ELSE = Pattern.compile("^\\s*else\\b");
    Pattern HEADER = Pattern.compile("^\\s*(?:if|while|for|switch)\\s*(?=\\()");
    Pattern RETURN = Pattern.compile("^\\s*return\\b");

    /// Offset of the bracket closing the one at `open`, or `-1`.
    static int closeOf(String s, int open) {
        var depth = 0;

        for (var i = open; i < s.length(); i++) {
            var c = s.charAt(i);

            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;

                if (depth == 0) {
                    return i;
                }
            }
        }

        return - 1;
    }

    /// Offset of the `>` closing the `<` at `open`; `-1` when the span is not a type-argument list.
    static int closeAngle(String s, int open) {
        var depth = 0;

        for (var i = open; i < s.length(); i++) {
            var c = s.charAt(i);

            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;

                if (depth == 0) {
                    return i;
                }
            } else if (depth > 0 && isNotInTypeArguments(c)) {
                return - 1;
            }
        }

        return - 1;
    }

    private static boolean isNotInTypeArguments(char c) {
        return c == ';' || c == '{' || c == '}' || c == '(' || c == ')' || c == '=';
    }

    /// Offset of the `<` opening the type-argument list that ends at `close`; `close` itself when there is none.
    static int openAngle(String s, int close) {
        var depth = 0;

        for (var i = close; i >= 0; i--) {
            var c = s.charAt(i);

            if (c == '>') {
                depth++;
            } else if (c == '<') {
                depth--;

                if (depth == 0) {
                    return i;
                }
            } else if (c == ';' || c == '{' || c == '}') {
                return close;
            }
        }

        return close;
    }

    static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /// Offset of the `;` ending the statement that begins at `from`, skipping nested brackets; `-1` when none.
    static int statementEnd(String s, int from) {
        var depth = 0;

        for (var i = from; i < s.length(); i++) {
            var c = s.charAt(i);

            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    return - 1;
                }

                depth--;
            } else if (c == ';' && depth == 0) {
                return i;
            }
        }

        return - 1;
    }

    /// Start of the call chain whose last link ends at `dot`: scanned backward over balanced brackets to the first statement or
    /// argument boundary.
    static int chainStart(String s, int dot) {
        var depth = 0;

        for (var i = dot - 1; i >= 0; i--) {
            var c = s.charAt(i);

            if ((c == '{' || c == '}') && depth == 0) {
                return i + 1;
            }

            if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                if (depth == 0) {
                    return i + 1;
                }

                depth--;
            } else if (depth == 0 && isBoundary(s, i)) {
                return i + 1;
            }
        }

        return 0;
    }

    private static boolean isBoundary(String s, int i) {
        var c = s.charAt(i);

        return c == ';' || c == ',' || c == '=' && !isComparison(s, i) || c == '?' || c == ':' && !isDoubleColon(s, i)
               || c == '>' && i > 0 && s.charAt(i - 1) == '-';
    }

    private static boolean isComparison(String s, int i) {
        var next = i + 1 < s.length()
                   ? s.charAt(i + 1)
                   : ' ';
        var prev = i > 0
                   ? s.charAt(i - 1)
                   : ' ';

        return next == '=' || prev == '=' || prev == '!' || prev == '<' || prev == '>';
    }

    private static boolean isDoubleColon(String s, int i) {
        return i > 0 && s.charAt(i - 1) == ':' || i + 1 < s.length() && s.charAt(i + 1) == ':';
    }

    /// Strips leading `else` and `if (...)`/`while (...)`/`for (...)` headers, then a leading `return`, from `chain`.
    static Chain chain(String chain) {
        var text = chain;
        var next = stripControl(text);

        while (!next.equals(text)) {
            text = next;
            next = stripControl(text);
        }

        var ret = RETURN.matcher(text);

        return ret.find()
               ? Chain.chain(text.substring(ret.end()), true)
               : Chain.chain(text, false);
    }

    private static String stripControl(String text) {
        var otherwise = ELSE.matcher(text);

        if (otherwise.find()) {
            return text.substring(otherwise.end());
        }

        var header = HEADER.matcher(text);

        if (header.find()) {
            var close = closeOf(text, header.end());

            return close > 0
                   ? text.substring(close + 1)
                   : text;
        }

        return text;
    }

    /// `list` split at the commas that sit outside every bracket and type-argument list; blank pieces are dropped.
    static List<String> splitTopLevel(String list) {
        var pieces = new ArrayList<String>();
        var depth = 0;
        var from = 0;

        for (var i = 0; i < list.length(); i++) {
            var c = list.charAt(i);

            if (c == '(' || c == '[' || c == '{' || c == '<') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}' || c == '>' && !isArrow(list, i)) {
                depth--;
            } else if (c == ',' && depth == 0) {
                pieces.add(list.substring(from, i));
                from = i + 1;
            }
        }

        pieces.add(list.substring(from));
        return pieces.stream()
                     .filter(piece -> !piece.isBlank())
                     .toList();
    }

    private static boolean isArrow(String s, int i) {
        return i > 0 && s.charAt(i - 1) == '-';
    }

    /// The last identifier of each comma-separated declaration (`final Promise<X> p, int n` gives `p`, `n`).
    static List<String> declaredNames(String list) {
        var names = new ArrayList<String>();
        var ident = Pattern.compile(IDENT);

        for (var piece : splitTopLevel(list)) {
            var matcher = ident.matcher(piece);
            String last = null;

            while (matcher.find()) {
                last = matcher.group();
            }

            if (last != null) {
                names.add(last);
            }
        }

        return names;
    }
}
