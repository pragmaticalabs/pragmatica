package org.pragmatica.jbct.lint.cst.rules;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-RET-07: Discarded Result/Promise/Option value.
///
/// Detects method call chains whose result is silently discarded (expression statement
/// ending with a known monadic terminal method), and static factory calls on Result,
/// Promise, or Option whose return value is ignored.
public class CstDiscardedResultRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-07";

    /// Chain terminal methods that always return a monadic wrapper.
    /// Discarding their result is always a bug on Result / Promise because errors get swallowed.
    /// Option-only side-effect terminals (`onPresent`, `onEmpty`, `onPresentRun`, `onEmptyRun`) are
    /// deliberately NOT listed here: Option has no error channel, so discarding the returned
    /// `Option<T>` after a fire-and-forget side effect loses no information.
    /// `timeout` is also not listed: it schedules a cancellation side effect and returns `this`,
    /// so fire-and-forget `promise.timeout(duration);` is the idiomatic way to arm the timer.
    private static final Set<String> CHAIN_TERMINALS = Set.of("map",
                                                              "flatMap",
                                                              "filter",
                                                              "recover",
                                                              "fold",
                                                              "onSuccess",
                                                              "onFailure",
                                                              "onSuccessRun",
                                                              "allOf",
                                                              "anyOf",
                                                              "async",
                                                              "delay",
                                                              "race",
                                                              "or",
                                                              "orElse",
                                                              "toResult",
                                                              "mapToUnit",
                                                              "mapError",
                                                              "result",
                                                              "onFailureRun");

    /// Pattern matching `.methodName(...)` at the end of an expression statement (before `;`)
    private static final Pattern CHAIN_TERMINAL_PATTERN = Pattern.compile("\\.(" + String.join("|", CHAIN_TERMINALS)
                                                                         + ")\\s*\\([^)]*\\)\\s*;\\s*$");

    /// Known-type factory patterns: `Result.success(...)`, `Promise.failure(...)`, etc.
    private static final Pattern FACTORY_PATTERN = Pattern.compile("^\\s*(Result|Promise|Option)\\s*\\.\\s*(success|failure|some|none|option|unitPromise|unitResult|unit)\\s*\\(");

    /// v6: Identifier is a token, not a CST rule. Extract method name from member text.
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return findAllStatements(root).stream()
                                .filter(this::isExpressionStatement)
                                .filter(this::isDiscardedResult)
                                .map(stmt -> createDiagnostic(root, stmt, ctx));
    }

    private boolean isExpressionStatement(Cursor stmt) {
        // Skip local variable declarations at the CST level when the parser exposes them as such.
        if (hasChildOfRule(stmt, RuleKind.LOCAL_VAR)) {
            return false;
        }

        var stmtText = text(stmt).trim();

        if (!stmtText.endsWith(";")) {
            return false;
        }

        if (stmtText.startsWith("return ") || stmtText.startsWith("if ") || stmtText.startsWith("for ") || stmtText.startsWith("while ") || stmtText.startsWith("switch ") || stmtText.startsWith("throw ") || stmtText.startsWith("try ")) {
            return false;
        }
        // Fallback for the ordered-choice parser layout that does not expose LocalVar as a direct
        // child of BlockStmt. If the statement has a top-level `=` (outside parens / brackets /
        // braces and distinct from ==/!=/<=/>= comparators) the chain terminal's value is being
        // assigned, so the result is NOT discarded.
        return ! hasTopLevelAssignment(stripStringLiterals(stmtText));
    }

    /// Scans `stmt` for an assignment operator at top level (depth zero of parens/brackets/braces).
    /// Distinguishes `=` from `==`, `!=`, `<=`, `>=`, and chained `=` in the middle of `+=`, `-=`,
    /// etc. Any of the compound forms (+=, -=, *=, /=, %=, &=, |=, ^=) also count as assignments.
    private static boolean hasTopLevelAssignment(String stmt) {
        int depth = 0;

        for (int i = 0; i < stmt.length(); i++) {
            char c = stmt.charAt(i);

            if (c == '(' || c == '[' || c == '{') {
                depth++;
                continue;
            }

            if (c == ')' || c == ']' || c == '}') {
                depth--;
                continue;
            }

            if (c != '=' || depth != 0) {
                continue;
            }

            char prev = i > 0
                        ? stmt.charAt(i - 1)
                        : '\0';
            char next = i + 1 < stmt.length()
                        ? stmt.charAt(i + 1)
                        : '\0';

            if (next == '=') {
                i++;
                continue;
            }

            if (prev == '!' || prev == '<' || prev == '>' || prev == '=') {
                continue;
            }

            return true;
        }

        return false;
    }

    private boolean isDiscardedResult(Cursor stmt) {
        var stmtText = text(stmt).trim();
        var stripped = stripStringLiterals(stmtText);

        return isDiscardedChainTerminal(stripped) || isDiscardedFactory(stripped);
    }

    private boolean isDiscardedChainTerminal(String stmtText) {
        return CHAIN_TERMINAL_PATTERN.matcher(stmtText).find();
    }

    /// A factory call is discarded only when it is the whole statement. Specifically, after the
    /// closing `)` that matches the factory's opening `(`, nothing but whitespace and the terminal
    /// `;` may appear. This excludes chained forms like `Option.option(x).onPresent(y);` — the
    /// chain itself is handled by the chain-terminal check.
    private boolean isDiscardedFactory(String stmtText) {
        var matcher = FACTORY_PATTERN.matcher(stmtText);

        if (!matcher.find()) {
            return false;
        }

        int openParen = matcher.end() - 1;
        int closeParen = findMatchingClose(stmtText, openParen);

        if (closeParen < 0) {
            return false;
        }

        for (int i = closeParen + 1; i < stmtText.length(); i++) {
            char c = stmtText.charAt(i);

            if (c == ';') {
                return true;
            }

            if (!Character.isWhitespace(c)) {
                return false;
            }
        }

        return false;
    }

    /// Returns the index of the `)` that matches the `(` at `openIdx`, or -1 if unbalanced.
    private static int findMatchingClose(String s, int openIdx) {
        int depth = 0;

        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }

    /// Replaces the content of every string literal with an empty placeholder of the same delimiter
    /// so regex-based pattern matching never matches inside a literal — otherwise
    /// `sb.append(".map((")` would falsely trip the chain-terminal regex on the literal. Preserves
    /// statement length roughly so downstream diagnostics stay aligned.
    ///
    /// Shares [MapperSafety#STRING_LITERAL] rather than compiling a private copy: the duplicate
    /// carried its own copy of the #540 stack-overflow bug, and one definition cannot drift.
    private static String stripStringLiterals(String stmtText) {
        return MapperSafety.STRING_LITERAL.matcher(stmtText).replaceAll(match -> {
            var s = match.group();

            return s.startsWith("\"\"\"")
                   ? "\"\"\"\"\"\""
                   : "\"\"";
        });
    }

    private Diagnostic createDiagnostic(Cursor root, Cursor stmt, LintContext ctx) {
        var methodName = findAncestor(root, stmt, RuleKind.MEMBER).map(md -> extractMethodName(text(md)))
                                     .or("(unknown)");
        var stmtText = text(stmt).trim();
        var discardedType = isDiscardedFactory(stripStringLiterals(stmtText))
                            ? "factory result"
                            : "chain result";

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(stmt),
                                     startColumn(stmt),
                                     "Discarded " + discardedType
                                    + " in method '" + methodName
                                    + "' — Result/Promise/Option value silently ignored",
                                     "The return value of a Result, Promise, or Option operation is discarded. "
                                    + "This silently ignores the outcome. Assign, return, or chain the result.")
                         .withExample("""
            // Before: result discarded — error silently swallowed
            repository.save(entity).map(this::enrichResult);

            // After: return or assign the result
            return repository.save(entity).map(this::enrichResult);

            // Before: factory result discarded
            Result.success(value);

            // After: return or assign
            return Result.success(value);
            """);
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
