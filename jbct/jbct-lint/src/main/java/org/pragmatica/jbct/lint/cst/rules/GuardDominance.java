package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;


/// Text-level test for JBCT-TOT-02 (#3065): is a `getFirst` / `getLast` reached only on the NON-EMPTY side of an emptiness / size
/// check on the same receiver? Input is the string- and comment-masked body ([MapperSafety#maskForOps]).
///
/// Exempt forms (G = the whole guard condition, nothing else inside the parentheses):
///   - `G-nonEmpty && ... acc` with no `||`, `?`, `:`, `;`, `,` (at the chain's own level) or block between the guard and the accessor;
///   - `G-nonEmpty ? acc : d`, `G-empty ? d : acc`;
///   - `if (G-nonEmpty) stmt-containing-acc`;
///   - `if (G-empty) <return|throw>;` at statement level, then `acc` later in the same or an enclosing block.
///
/// Every form needs, in addition: the guard OPENS its operand (preceded by `(`, `,`, `?`, a ternary `:`, a plain `=`, a lambda
/// `->`, `return` / `yield`, or, for the `&&` chain only, by `&&` with no `||` earlier in the same expression), so
/// `flag == xs.isEmpty() ? ..` and `n - xs.size() > 0 && ..` are not guards; the receiver is not reassigned or mutated between
/// the guard and the accessor; and an early exit does not cross an old-style `case X:` / `default:` label.
///
/// G-nonEmpty: `!r.isEmpty()`, `r.size() > N`, `r.size() >= K` (K >= 1), `r.size() == K` (K >= 1), `r.size() != 0`.
/// G-empty: `r.isEmpty()` (a leading `!` is not part of the condition start, so it never qualifies), `r.size() == 0`, `r.size() < 1`, `r.size() <= 0`.
///
/// Anything else is undecidable here and does NOT exempt, so the rule keeps firing: a guard in another method, a compound
/// condition (`a || r.isEmpty()`, `!(a || r.isEmpty())`), an empty-side access (`if (r.isEmpty()) { r.getFirst(); }`), an
/// early exit that is conditional or in a closed block. A false positive is cheaper than a false negative.
sealed interface GuardDominance permits GuardDominance.unused {
    record unused() implements GuardDominance {}

    Pattern IF_OPEN = Pattern.compile("\\bif\\s*\\(\\s*$");
    Pattern EXIT_STATEMENT = Pattern.compile("^\\s*(?:return|throw)\\b");
    Pattern CLOSE_PAREN = Pattern.compile("^\\s*\\)");
    /// An old-style switch label (`case X:`, `default:`); arrow arms (`case X ->`) and `::` are not labels.
    Pattern SWITCH_LABEL = Pattern.compile("\\bcase\\b(?:(?!->)[^;{}])*?(?<!:):(?!:)|\\bdefault\\s*:(?!:)");

    /// Member names that read a list without changing it; any other member access on the receiver counts as a possible write.
    String READS = "isEmpty|size|get|getFirst|getLast|stream|parallelStream|contains|containsAll|indexOf|lastIndexOf|iterator|listIterator|forEach|toString|equals|hashCode|subList|toArray|peekFirst|peekLast|element|reversed|getClass";

    static boolean dominates(String masked, int accessorStart, String receiver) {
        var nonEmpty = guard(receiver,
                             "!\\s*R\\s*\\.\\s*isEmpty\\s*\\(\\s*\\)|R\\s*\\.\\s*size\\s*\\(\\s*\\)\\s*(?:>\\s*\\d+|>=\\s*[1-9]\\d*|==\\s*[1-9]\\d*|!=\\s*0)\\b").matcher(masked);
        var empty = guard(receiver,
                          "R\\s*\\.\\s*isEmpty\\s*\\(\\s*\\)|R\\s*\\.\\s*size\\s*\\(\\s*\\)\\s*(?:==\\s*0|<\\s*1|<=\\s*0)\\b").matcher(masked);

        while (nonEmpty.find() && nonEmpty.end() <= accessorStart) {
            if (nonEmptySideReaches(masked, nonEmpty.start(), nonEmpty.end(), accessorStart) && !written(masked,
                                                                                                         receiver,
                                                                                                         nonEmpty.end(),
                                                                                                         accessorStart)) {
                return true;
            }
        }

        while (empty.find() && empty.end() <= accessorStart) {
            if (emptySideReaches(masked, empty.start(), empty.end(), accessorStart) && !written(masked,
                                                                                                receiver,
                                                                                                empty.end(),
                                                                                                accessorStart)) {
                return true;
            }
        }

        return false;
    }

    /// True when the receiver is reassigned (`xs = ..`, `xs += ..`) or accessed through anything but a read member between the
    /// guard and the accessor: the guarded state may no longer hold.
    private static boolean written(String code, String receiver, int from, int to) {
        var write = Pattern.compile("(?<![\\w$])" + Pattern.quote(receiver)
                                   + "\\s*(?:[-+*/%&|^]?=(?!=)|\\.\\s*(?!(?:" + READS
                                   + ")\\b)[A-Za-z_]\\w*)");

        return write.matcher(code.substring(from, to))
                    .find();
    }

    private static Pattern guard(String receiver, String shapes) {
        return Pattern.compile("(?<![\\w$.])(?:" + shapes.replace("R", Pattern.quote(receiver)) + ")");
    }

    private static boolean nonEmptySideReaches(String code, int guardStart, int guardEnd, int accessor) {
        return andChainReaches(code, guardStart, guardEnd, accessor) || ternaryReaches(code,
                                                                                       guardStart,
                                                                                       guardEnd,
                                                                                       accessor,
                                                                                       true) || ifThenReaches(code,
                                                                                                              guardStart,
                                                                                                              guardEnd,
                                                                                                              accessor);
    }

    private static boolean emptySideReaches(String code, int guardStart, int guardEnd, int accessor) {
        return ternaryReaches(code, guardStart, guardEnd, accessor, false) || earlyExitReaches(code,
                                                                                               guardStart,
                                                                                               guardEnd,
                                                                                               accessor);
    }

    private static boolean andChainReaches(String code, int guardStart, int guardEnd, int accessor) {
        var next = nextNonSpace(code, guardEnd);

        return code.startsWith("&&", next)
               && opensOperand(code, guardStart, true)
               && !escapesChain(code, next + 2, accessor);
    }

    /// True when anything between `from` and `to` can end the `&&` chain: `||`, a ternary, a statement or block boundary, or a
    /// closing parenthesis that was opened before the guard. `::` is a method reference, not a ternary.
    private static boolean escapesChain(String code, int from, int to) {
        var depth = 0;

        for (var i = from; i < to; i++) {
            var c = code.charAt(i);

            if (c == ':' && i + 1 < to && code.charAt(i + 1) == ':') {
                i++;
                continue;
            }

            if ("|?:;{}".indexOf(c) >= 0 || c == ',' && depth == 0) {
                return true;
            }

            depth += c == '('
                     ? 1
                     : c == ')'
                       ? -1
                       : 0;
            if (depth < 0) {
                return true;
            }
        }

        return false;
    }

    private static boolean ternaryReaches(String code, int guardStart, int guardEnd, int accessor, boolean thenSide) {
        var question = nextNonSpace(code, guardEnd);

        if (question >= code.length() || code.charAt(question) != '?' || !opensOperand(code, guardStart, false)) {
            return false;
        }

        var colon = matchingColon(code, question + 1);

        if (colon < 0) {
            return false;
        }

        return thenSide
               ? accessor > question && accessor < colon
               : accessor > colon && accessor < expressionEnd(code, colon + 1);
    }

    /// The guard is the whole operand: what precedes it opens an expression, so it is not the right side of a comparison or of
    /// arithmetic (`flag == xs.isEmpty()`, `n - xs.size() > 0`). `afterAnd` also accepts `&&` (the guard then continues a chain
    /// that has no `||` before it).
    private static boolean opensOperand(String code, int guardStart, boolean afterAnd) {
        var prev = previousNonSpace(code, guardStart);

        if (prev < 0) {
            return false;
        }

        var before = prev > 0
                     ? code.charAt(prev - 1)
                     : ' ';

        return switch (code.charAt(prev)) {
            case '(', ',', '?' -> true;
            case ':' -> before != ':';
            case '=' -> "=!<>+-*/%&|^".indexOf(before) < 0;
            case '>' -> before == '-';
            case '&' -> afterAnd && before == '&' && !disjunctionBefore(code, prev - 1);
            default -> code.substring(0, prev + 1).matches("(?s).*\\b(?:return|yield)");
        };
    }

    /// True when a `||` precedes `end` in the same expression (outside parentheses that close before `end`).
    private static boolean disjunctionBefore(String code, int end) {
        var depth = 0;

        for (var i = end - 1; i >= 0; i--) {
            var c = code.charAt(i);

            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    return false;
                }

                depth--;
            } else if (depth == 0 && ",;{}?".indexOf(c) >= 0) {
                return false;
            } else if (depth == 0 && c == '|' && i > 0 && code.charAt(i - 1) == '|') {
                return true;
            }
        }

        return false;
    }

    private static int matchingColon(String code, int from) {
        var depth = 0;
        var nested = 0;

        for (var i = from; i < code.length(); i++) {
            var c = code.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ';' || c == '{' || c == '}' || depth < 0) {
                return -1;
            } else if (depth == 0 && c == '?') {
                nested++;
            } else if (depth == 0 && c == ':' && i + 1 < code.length() && code.charAt(i + 1) == ':') {
                i++;
            } else if (depth == 0 && c == ':') {
                if (nested == 0) {
                    return i;
                }

                nested--;
            }

            if (depth < 0) {
                return -1;
            }
        }

        return -1;
    }

    private static int expressionEnd(String code, int from) {
        var depth = 0;

        for (var i = from; i < code.length(); i++) {
            var c = code.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }

            if (depth < 0 || depth == 0 && (c == ';' || c == ',') || c == '}') {
                return i;
            }
        }

        return code.length();
    }

    private static boolean ifThenReaches(String code, int guardStart, int guardEnd, int accessor) {
        var statementStart = ifConditionEnd(code, guardStart, guardEnd);

        return statementStart >= 0 && accessor < statementEnd(code, statementStart);
    }

    /// Index just after the `)` of `if (<guard>)` when the guard is the entire condition, or `-1`.
    private static int ifConditionEnd(String code, int guardStart, int guardEnd) {
        var close = CLOSE_PAREN.matcher(code.substring(guardEnd));

        return IF_OPEN.matcher(code.substring(0, guardStart)).find() && close.find()
               ? guardEnd + close.end()
               : -1;
    }

    private static boolean earlyExitReaches(String code, int guardStart, int guardEnd, int accessor) {
        var statementStart = ifConditionEnd(code, guardStart, guardEnd);

        if (statementStart < 0 || !atStatementStart(code, guardStart)) {
            return false;
        }

        var statementEnd = statementEnd(code, statementStart);

        return accessor >= statementEnd
               && exits(code.substring(statementStart, statementEnd))
               && !leavesBlock(code, statementEnd, accessor)
               && !SWITCH_LABEL.matcher(code)
                               .region(statementEnd, accessor)
                               .find();
    }

    /// The `if` owning the guard begins a statement (not `else if`, not nested in an expression).
    private static boolean atStatementStart(String code, int guardStart) {
        var ifStart = IF_OPEN.matcher(code.substring(0, guardStart));

        if (!ifStart.find()) {
            return false;
        }

        var prev = previousNonSpace(code, ifStart.start());

        return prev < 0 || ";{}".indexOf(code.charAt(prev)) >= 0;
    }

    /// The statement always leaves: it starts with `return` / `throw`, or it is a block whose LAST statement does.
    private static boolean exits(String statement) {
        var trimmed = statement.strip();

        if (!trimmed.startsWith("{")) {
            return EXIT_STATEMENT.matcher(trimmed).find();
        }

        var inner = trimmed.substring(1,
                                      Math.max(1, trimmed.length() - 1)).strip();
        var body = inner.endsWith(";")
                   ? inner.substring(0, inner.length() - 1)
                   : inner;
        var lastBoundary = Math.max(body.lastIndexOf(';'),
                                    Math.max(body.lastIndexOf('{'), body.lastIndexOf('}')));

        return inner.endsWith(";") && EXIT_STATEMENT.matcher(body.substring(lastBoundary + 1)).find();
    }

    /// True when a block that is open at `from` closes before `to`: the early exit then guards only that inner block.
    private static boolean leavesBlock(String code, int from, int to) {
        var depth = 0;

        for (var i = from; i < to; i++) {
            depth += code.charAt(i) == '{'
                     ? 1
                     : code.charAt(i) == '}'
                       ? -1
                       : 0;
            if (depth < 0) {
                return true;
            }
        }

        return false;
    }

    /// End (exclusive) of the statement starting at `from`: a braced block, or up to and including the next `;`.
    private static int statementEnd(String code, int from) {
        var start = nextNonSpace(code, from);

        if (start >= code.length()) {
            return code.length();
        }

        if (code.charAt(start) != '{') {
            var semicolon = code.indexOf(';', start);

            return semicolon < 0
                   ? code.length()
                   : semicolon + 1;
        }

        var depth = 0;

        for (var i = start; i < code.length(); i++) {
            depth += code.charAt(i) == '{'
                     ? 1
                     : code.charAt(i) == '}'
                       ? -1
                       : 0;
            if (depth == 0) {
                return i + 1;
            }
        }

        return code.length();
    }

    private static int nextNonSpace(String code, int from) {
        var i = from;

        while (i < code.length() && Character.isWhitespace(code.charAt(i))) {
            i++;
        }

        return i;
    }

    private static int previousNonSpace(String code, int before) {
        var i = before - 1;

        while (i >= 0 && Character.isWhitespace(code.charAt(i))) {
            i--;
        }

        return i;
    }
}
