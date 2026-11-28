/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.testing;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/// The `PARK_SLEEP` pass of `FlakeScan` (#3386, #3850). The invariant: every `parkNanos` in test code waits to a DEADLINE or to a RE-CHECKED
/// STATE. `LockSupport.parkNanos` returns at once whenever the calling thread's interrupt flag is set (the call does not clear it), and early
/// on a spurious wakeup or a left-over unpark permit, so a park that stands alone, or one a loop merely repeats a counted number of times,
/// waits for nothing on a thread an earlier test left interrupted and then asserts state that has not happened yet (#3384). A test that waits
/// for a state waits for THAT state ([PollUntil]); a test that means a pause calls [Pause], which parks to a deadline and keeps the flag. A hit
/// is fixed, never listed.
///
/// What it SEES, in the file with comments, string literals, char literals and text blocks blanked: every `parkNanos(` call, qualified or
/// not (a Unicode letter before the name makes it another identifier). The NEAREST enclosing loop decides, a `while`, `for` or `do` whose body
/// is a braced block that contains the call or is the call's own statement, with no method, lambda, anonymous class or class boundary between
/// the loop and the call (a `case` arm whose body is a lambda is such a boundary). A park no loop encloses is a hit. A loop accepts its park
/// when it is one of:
///  - a DEADLINE loop: the loop condition, or a guard `if (...) return|break|throw|fail(...)` of the loop body (the exit at the top level of the guard's own block) that is reached before the
///    park, reads a clock (`nanoTime`, `currentTimeMillis`, `now`);
///  - a STATE loop: the loop condition, or such a guard, reads a name that is not a counter. A counter is a name declared in a `for`
///    initialiser, stepped in a `for` update, or incremented or decremented anywhere in the body. Upper-case names, type names, literals and
///    members after a `.` are not state. A guard counts only when it stands at the top level of the loop body (not inside another block)
///    and its block closes before the park, so a guard after the park, or one whose block holds the park, is not one.
///
/// A loop that is neither (`for (int i = 0; i < 5; i++)`, `while (true)` with no guard, a guard that reads only the counter) is a hit: it
/// repeats a pause a counted number of times, and under a set flag it spins through the count at once.
///
/// What it does NOT see, and review must: a negative observation. A loop that waits to show something does NOT happen and also exits on
/// the state it must not reach (`for (...) { if (stream.isWritable()) return false; park }`) looks like a wait for state and is accepted;
/// under a set flag it burns its whole count at once and passes vacuously. This is a NAMED LIMIT, NOT COVERAGE (CTO ruling 11-27): such a
/// wait needs real elapsed time and goes through [Pause], and the enforcer is the conversion of the sites, not this scan. A bound read
/// from a non-counter variable (`i < attempts`) reads as state. A park reached through a method reference (`LockSupport::parkNanos` handed
/// to a helper) or another wrapper, an expression lambda (no braces) inside a loop, which has no block to be a boundary and so counts as the
/// loop's own statement, and `parkUntil`/`park()`. `Thread.sleep` has its own pattern (`SLEEP`). Test-support code that ships in a main tree
/// is out of scope, like the rest of the scan; [Pause] is the one place that owns the call.
final class SingleParkSleep {
    private static final String ID = "\\p{L}\\p{N}_$";
    private static final Pattern NOISE = Pattern.compile("/\\*[\\s\\S]*?\\*/|//[^\\n]*|\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'");
    private static final Pattern TOKEN = Pattern.compile("[{}();]|(?<![" + ID + "])parkNanos(?=\\s*\\()");
    private static final Pattern LOOP_HEADER = Pattern.compile("(?:^|[^" + ID + "])(?:while|for)\\s*\\([\\s\\S]*\\)\\s*$");
    private static final Pattern LOOP_KEYWORD = Pattern.compile("(?<![" + ID + "])(while|for)\\s*\\(");
    private static final Pattern DO_HEADER = Pattern.compile("(?:^|[^" + ID + "])do\\s*$");
    private static final Pattern QUALIFIER = Pattern.compile("(?:[" + ID + "]+\\s*\\.\\s*)*$");
    /// A `case` or `default` label up to its first `->` or `:`; what follows it on the header is the arm's own body, if any.
    private static final Pattern CASE_LABEL = Pattern.compile("^\\s*(?:case(?![" + ID + "])[\\s\\S]*?|default(?![" + ID + "]))\\s*(?:->|:)");
    /// A header whose block is neither a loop nor a boundary: control flow.
    private static final Pattern CONTROL_HEADER = Pattern.compile("^\\s*(?:[" + ID + "]+\\s*:\\s*)?(?:(?:else\\s+)?if\\s*\\([\\s\\S]*\\)|else|try(?:\\s*\\([\\s\\S]*\\))?|finally|catch\\s*\\([\\s\\S]*\\)"
                                                                  + "|switch\\s*\\([\\s\\S]*\\)|synchronized\\s*\\([\\s\\S]*\\))?\\s*$");
    private static final Pattern IF_KEYWORD = Pattern.compile("^if\\s*\\(");
    private static final Pattern EXIT = Pattern.compile("(?<![" + ID + "])(?:return|break|throw)(?![" + ID + "])|(?<![" + ID + "])fail\\s*\\(");
    private static final Pattern CLOCK = Pattern.compile("(?<![" + ID + "])(?:nanoTime|currentTimeMillis|now)\\s*\\(");
    private static final Pattern NAME = Pattern.compile("(?<![" + ID + ".])[\\p{L}_$][" + ID + "]*");
    private static final Pattern DECLARED = Pattern.compile("(?<![" + ID + ".])([\\p{L}_$][" + ID + "]*)\\s*=(?!=)");
    private static final Pattern STEPPED = Pattern.compile("(?<![" + ID + ".])([\\p{L}_$][" + ID + "]*)\\s*(?:\\+\\+|--|[-+]=)|(?:\\+\\+|--)\\s*([\\p{L}_$][" + ID + "]*)");
    private static final Set<String> KEYWORDS = Set.of("true", "false", "null", "new", "this", "super", "instanceof", "var", "int", "long", "short", "byte", "char",
                                                       "boolean", "float", "double", "final");

    private SingleParkSleep() {}

    private enum Kind {
        LOOP,
        CONTROL,
        BOUNDARY
    }

    /// An open block: its kind, the statement text before its `{` (a loop's header), and the offset of its `{`.
    private record Block(Kind kind, String header, int open) {
        static Block block(Kind kind, String header, int open) {
            return new Block(kind, header, open);
        }
    }

    /// The `parkNanos(` calls of `source` that no deadline or re-checked-state loop encloses, as hits for `file`; `lines` are the file's lines,
    /// for the hit text.
    ///
    /// `;` ends a statement only outside parentheses (the `;` of a `for (init; test; step)` header does not), and every block keeps its own
    /// parenthesis depth, so a lambda body inside a call's parentheses ends statements normally.
    static List<FlakeScan.Hit> hits(String file, String[] lines, String source) {
        var seen = blank(source);
        var hits = new ArrayList<FlakeScan.Hit>();
        var blocks = new ArrayDeque<Block>();
        var depths = new ArrayDeque<Integer>();
        var tokens = TOKEN.matcher(seen);
        var statementStart = 0;
        var depth = 0;

        while (tokens.find()) {
            var token = tokens.group();

            switch (token.charAt(0)) {
                case '{' -> {
                    var header = seen.substring(statementStart, tokens.start());

                    blocks.push(Block.block(kindOf(header), header, tokens.start()));
                    depths.push(depth);
                    depth = 0;
                }
                case '}' -> {
                    pop(blocks);
                    depth = depths.isEmpty() ? 0 : depths.pop();
                }
                case '(' -> depth++;
                case ')' -> depth = Math.max(0, depth - 1);
                case ';' -> {}
                default -> hitOf(file, lines, seen, statementStart, tokens.start(), blocks).ifPresent(hits::add);
            }
            if (isBoundary(token, depth)) {
                statementStart = tokens.end();
            }
        }
        return hits;
    }

    /// Whether `token` ends the statement being read: a brace always does, a `;` only outside parentheses.
    private static boolean isBoundary(String token, int depth) {
        return token.equals("{") || token.equals("}") || token.equals(";") && depth == 0;
    }

    private static Optional<FlakeScan.Hit> hitOf(String file,
                                                 String[] lines,
                                                 String seen,
                                                 int statementStart,
                                                 int call,
                                                 Deque<Block> blocks) {
        if (waitsToDeadlineOrState(seen, statementStart, call, blocks)) {
            return Optional.empty();
        }

        var line = 1 + (int) seen.substring(0, call).chars().filter(c -> c == '\n').count();

        return Optional.of(FlakeScan.Hit.hit(file, line, FlakeScan.Kind.PARK_SLEEP, lines[line - 1].strip()));
    }

    /// The nearest loop around the call decides: the call is the whole body of a braceless loop, or the innermost loop block before a boundary.
    private static boolean waitsToDeadlineOrState(String seen, int statementStart, int call, Deque<Block> blocks) {
        var prefix = QUALIFIER.matcher(seen.substring(statementStart, call)).replaceFirst("");

        if (isLoopHeader(prefix)) {
            return accepts(prefix, "", "");
        }
        for (var block : blocks) {
            if (block.kind() == Kind.LOOP) {
                return accepts(block.header(), bodyBefore(seen, block.open(), call), bodyAfter(seen, block.open()));
            }
            if (block.kind() == Kind.BOUNDARY) {
                return false;
            }
        }
        return false;
    }

    private static String bodyBefore(String seen, int open, int call) {
        return seen.substring(open + 1, call);
    }

    /// The whole body of the block opened at `open` and, for a `do` loop, the `while (...)` condition after it (empty text if it does not close).
    private static String bodyAfter(String seen, int open) {
        var close = closing(seen, open, '{', '}');

        return close < 0 ? seen.substring(open + 1) : seen.substring(open + 1, close) + "\u0000" + seen.substring(close + 1);
    }

    /// Whether a loop with this `header` accepts a park reached after `beforePark` (the body up to the call). `wholeBody` is the full body, a
    /// NUL, and the text after the loop's closing brace.
    private static boolean accepts(String header, String beforePark, String wholeBody) {
        var parts = conditionOf(header, wholeBody);
        var counters = counters(parts.init(), parts.step(), wholeBody.replace('\u0000', ' ') + " " + beforePark);

        return waitsOn(parts.test(), counters) || readsClock(parts) || guardedBeforePark(beforePark, counters);
    }

    /// A clock re-read in the `for` update (`left = deadline - System.nanoTime()`) makes the loop a deadline loop; one read once, in the
    /// initialiser, does not.
    private static boolean readsClock(Parts parts) {
        return CLOCK.matcher(parts.step()).find();
    }

    /// The pieces of a loop header: `for (init; test; step)`, or `while (test)` / `do ... while (test)` with an empty init and step.
    private record Parts(String init, String test, String step) {
        static Parts parts(String init, String test, String step) {
            return new Parts(init, test, step);
        }
    }

    private static Parts conditionOf(String header, String wholeBody) {
        if (DO_HEADER.matcher(header).find()) {
            return Parts.parts("", doCondition(wholeBody), "");
        }

        var keyword = LOOP_KEYWORD.matcher(header);

        if (!keyword.find()) {
            return Parts.parts("", "", "");
        }

        var open = keyword.end() - 1;
        var close = closing(header, open, '(', ')');
        var inside = header.substring(open + 1, close < 0 ? header.length() : close);

        return keyword.group(1).equals("for") ? forParts(inside) : Parts.parts("", inside, "");
    }

    private static String doCondition(String wholeBody) {
        var after = wholeBody.substring(wholeBody.indexOf('\u0000') + 1);
        var keyword = LOOP_KEYWORD.matcher(after);

        if (!keyword.find() || !after.substring(0, keyword.start()).isBlank()) {
            return "";
        }

        var open = keyword.end() - 1;
        var close = closing(after, open, '(', ')');

        return after.substring(open + 1, close < 0 ? after.length() : close);
    }

    /// A classic `for` header split at its two top-level semicolons; a `for (x : items)` has no test, it only walks data.
    private static Parts forParts(String inside) {
        var first = inside.indexOf(';');
        var second = first < 0 ? -1 : inside.indexOf(';', first + 1);

        return second < 0
               ? Parts.parts("", "", "")
               : Parts.parts(inside.substring(0, first), inside.substring(first + 1, second), inside.substring(second + 1));
    }

    /// Counter names: declared in the `for` initialiser, any name in its update, and any name incremented or decremented in the body.
    private static Set<String> counters(String init, String step, String body) {
        var counters = new TreeSet<String>();
        var declared = DECLARED.matcher(init);

        while (declared.find()) {
            counters.add(declared.group(1));
        }
        names(step).forEach(counters::add);

        var stepped = STEPPED.matcher(body);

        while (stepped.find()) {
            counters.add(stepped.group(1) != null ? stepped.group(1) : stepped.group(2));
        }
        return counters;
    }

    private static Set<String> names(String expression) {
        var names = new TreeSet<String>();
        var matcher = NAME.matcher(expression);

        while (matcher.find()) {
            var name = matcher.group();

            if (!KEYWORDS.contains(name) && !Character.isUpperCase(name.charAt(0))) {
                names.add(name);
            }
        }
        return names;
    }

    /// Whether `expression` reads a clock or any name that is not a counter. A bare name compared with a counter is that counter's bound
    /// (`i < frames`), not state, and is not read.
    private static boolean waitsOn(String expression, Set<String> counters) {
        return CLOCK.matcher(expression).find() || names(withoutBounds(expression, counters)).stream().anyMatch(name -> !counters.contains(name));
    }

    private static String withoutBounds(String expression, Set<String> counters) {
        if (counters.isEmpty()) {
            return expression;
        }

        var counter = "(?:" + String.join("|", counters.stream().map(Pattern::quote).toList()) + ")";
        var bare = "[\\p{L}_$][" + ID + "]*(?![" + ID + "])(?!\\s*[.(\\[])";
        var compare = "\\s*(?:<=|>=|<|>|!=|==)\\s*";

        return expression.replaceAll("(?<![" + ID + ".])" + counter + "(?![" + ID + "])" + compare + bare, " ")
                         .replaceAll("(?<![" + ID + ".])" + bare + compare + counter + "(?![" + ID + "])", " ");
    }

    /// Whether the top level of the loop body, before the park, holds `if (cond) exit` with a `cond` that waits on a clock or state, whose block
    /// closes before the park.
    private static boolean guardedBeforePark(String beforePark, Set<String> counters) {
        var depth = 0;

        for (var at = 0; at < beforePark.length(); at++) {
            var c = beforePark.charAt(at);

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0 && startsIf(beforePark, at) && guardAt(beforePark, at, counters)) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsIf(String text, int at) {
        return (at == 0 || !isIdentifierChar(text.charAt(at - 1))) && IF_KEYWORD.matcher(text.substring(at)).find();
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /// The `if` at `at` is a guard: its condition waits on a clock or state, and its body, braced and closed before the end of `text` or a
    /// single statement, exits the loop or the method.
    private static boolean guardAt(String text, int at, Set<String> counters) {
        var open = text.indexOf('(', at);
        var close = closing(text, open, '(', ')');

        if (close < 0 || !waitsOn(text.substring(open + 1, close), counters)) {
            return false;
        }

        var bodyStart = skipBlank(text, close + 1);

        if (bodyStart >= text.length()) {
            return false;
        }
        if (text.charAt(bodyStart) == '{') {
            var bodyEnd = closing(text, bodyStart, '{', '}');

            return bodyEnd >= 0 && EXIT.matcher(topLevel(text.substring(bodyStart + 1, bodyEnd))).find();
        }

        var statementEnd = text.indexOf(';', bodyStart);

        return statementEnd >= 0 && EXIT.matcher(text.substring(bodyStart, statementEnd)).lookingAt();
    }

    /// `text` without the content of its nested blocks: an exit inside one is conditional on something the guard does not read.
    private static String topLevel(String text) {
        var kept = new StringBuilder();
        var depth = 0;

        for (var c : text.toCharArray()) {
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                kept.append(c);
            }
        }
        return kept.toString();
    }

    private static int skipBlank(String text, int from) {
        var at = from;

        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at;
    }

    /// The index of the bracket that closes the one at `open`, or -1.
    private static int closing(String text, int open, char opener, char closer) {
        var depth = 0;

        for (var at = open; at < text.length(); at++) {
            var c = text.charAt(at);

            if (c == opener) {
                depth++;
            } else if (c == closer && --depth == 0) {
                return at;
            }
        }
        return -1;
    }

    private static Kind kindOf(String header) {
        if (isLoopHeader(header)) {
            return Kind.LOOP;
        }

        var label = CASE_LABEL.matcher(header);

        if (label.find()) {
            var arm = header.substring(label.end());

            return arm.isBlank() ? Kind.CONTROL : kindOf(arm);
        }
        return CONTROL_HEADER.matcher(header).matches() ? Kind.CONTROL : Kind.BOUNDARY;
    }

    private static boolean isLoopHeader(String header) {
        return LOOP_HEADER.matcher(header).find() || DO_HEADER.matcher(header).find();
    }

    private static void pop(Deque<Block> blocks) {
        if (!blocks.isEmpty()) {
            blocks.pop();
        }
    }

    /// `text` with comments, string literals, char literals and text blocks replaced by spaces, line breaks kept, so offsets and lines hold.
    private static String blank(String text) {
        return NOISE.matcher(text).replaceAll(noise -> Matcher.quoteReplacement(noise.group().replaceAll("[^\\n]", " ")));
    }
}
