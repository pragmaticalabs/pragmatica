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
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/// The `PARK_SLEEP` pass of `FlakeScan` (#3386): a `parkNanos` call that no loop encloses is a single-call sleep. `LockSupport.parkNanos`
/// returns at once whenever the calling thread's interrupt flag is set (the call does not clear it), and early on a spurious wakeup or a
/// left-over unpark permit, so a test that sleeps with ONE call waits for nothing on a thread an earlier test left interrupted and then
/// asserts state that has not happened yet (#3384). A test that waits for a state waits for THAT state ([PollUntil]); a test that means a
/// pause calls [Pause], which parks to a deadline and keeps the flag. A hit is fixed, never listed.
///
/// What it SEES, in the file with comments, string literals, char literals and text blocks blanked: every `parkNanos(` call, qualified or
/// not. The call is accepted when a loop encloses it: a `while`, `for` or `do` whose body is a braced block that contains the call, or whose
/// body is the call's own statement, with no method, lambda, anonymous class or class boundary between the loop and the call. Everything
/// else is a hit, including a call in a lambda handed to a looping helper (hand the helper [Pause] instead).
///
/// What it does NOT see, and review must: whether the enclosing loop re-checks anything (a `for` that paces, or a loop whose condition
/// cannot change, still passes; such a loop returns at once under a set flag and so spins instead of waiting), a park reached through a
/// method reference (`LockSupport::parkNanos` handed to a helper) or through another wrapper, and `parkUntil`/`park()`. `Thread.sleep`
/// has its own pattern (`SLEEP`). Test-support code that ships in a main tree is out of scope, like the rest of the scan; [Pause] is
/// the one place that owns the call.
final class SingleParkSleep {
    private static final Pattern NOISE = Pattern.compile("/\\*[\\s\\S]*?\\*/|//[^\\n]*|\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'");
    private static final Pattern TOKEN = Pattern.compile("[{}();]|\\bparkNanos(?=\\s*\\()");
    private static final Pattern LOOP_HEADER = Pattern.compile("(?:^|[^\\w$])(?:while|for)\\s*\\([\\s\\S]*\\)\\s*$");
    private static final Pattern DO_HEADER = Pattern.compile("(?:^|[^\\w$])do\\s*$");
    private static final Pattern QUALIFIER = Pattern.compile("(?:[\\w$]+\\s*\\.\\s*)*$");
    /// A header whose block is neither a loop nor a boundary: control flow, and a `case` arm (its `->` is not a lambda).
    private static final Pattern CONTROL_HEADER = Pattern.compile("^\\s*(?:[\\w$]+\\s*:\\s*)?(?:(?:else\\s+)?if\\s*\\([\\s\\S]*\\)|else|try(?:\\s*\\([\\s\\S]*\\))?|finally|catch\\s*\\([\\s\\S]*\\)"
                                                                  + "|switch\\s*\\([\\s\\S]*\\)|synchronized\\s*\\([\\s\\S]*\\))?\\s*$"
                                                                  + "|^\\s*(?:case\\b|default\\b)[\\s\\S]*$");

    private SingleParkSleep() {}

    private enum Block {
        LOOP,
        CONTROL,
        BOUNDARY
    }

    /// The `parkNanos(` calls of `source` that no loop encloses, as hits for `file`; `lines` are the file's lines, for the hit text.
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
                    blocks.push(blockOf(seen.substring(statementStart, tokens.start())));
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
        if (insideLoop(seen.substring(statementStart, call), blocks)) {
            return Optional.empty();
        }

        var line = 1 + (int) seen.substring(0, call).chars().filter(c -> c == '\n').count();

        return Optional.of(FlakeScan.Hit.hit(file, line, FlakeScan.Kind.PARK_SLEEP, lines[line - 1].strip()));
    }

    /// A loop encloses the call when it is the innermost block before a boundary, or when the call is the whole body of a braceless loop.
    private static boolean insideLoop(String beforeCallInStatement, Deque<Block> blocks) {
        var prefix = QUALIFIER.matcher(beforeCallInStatement).replaceFirst("");

        if (isLoopHeader(prefix)) {
            return true;
        }
        for (var block : blocks) {
            if (block == Block.LOOP) {
                return true;
            }
            if (block == Block.BOUNDARY) {
                return false;
            }
        }
        return false;
    }

    private static Block blockOf(String header) {
        if (isLoopHeader(header)) {
            return Block.LOOP;
        }
        return CONTROL_HEADER.matcher(header).matches() ? Block.CONTROL : Block.BOUNDARY;
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
