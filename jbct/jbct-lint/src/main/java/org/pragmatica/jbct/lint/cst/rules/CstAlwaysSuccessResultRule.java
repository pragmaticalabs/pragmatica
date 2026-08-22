package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-RET-05: a step that cannot fail, typed as if it can.
///
/// Flags a method returning `Result<T>` or `Promise<T>` whose body produces its value ONLY through
/// `success(...)` and has no way to fail. The Four Return Kinds make the return type a contract:
/// `Result<T>` asserts *fallible*, `Promise<T>` asserts *fallible and asynchronous*. A method that
/// is neither claims a contract its body does not have, and every caller pays — each must `flatMap`
/// a step with no failure branch, and the return type stops answering "can this fail?".
///
/// **Three conditions, all required.** (1) every value comes from a `success(...)` construction;
/// (2) no failure-producing construct — `failure(...)`, a `Cause` lifted with `.result()` /
/// `.promise()`, a `.filter(...)` or an `.ensure(...)`; and (3) no delegation to a call returning
/// `Result` / `Promise` that the body `flatMap`s, `.async()`-lifts, or joins with `all(...)`.
///
/// **Condition 3 is why a body-only scan is wrong**, and it is what this rule was missing. A body
/// like `delegate().flatMap(v -> Promise.success(f(v)))` names only success and produces no failure
/// of its own, yet is genuinely fallible: the delegate's failure propagates through it. Flagging it
/// would advise erasing a real failure channel. `.async()` matters for the same reason — it lifts a
/// `Result` into a `Promise`, carrying the failure with it — and is the shape that made this rule
/// blind to `Promise`-returning steps until it began matching them at all.
///
/// **Deliberate FN surface.** Fallibility is judged from THIS body only. A method delegating to a
/// same-file helper that is itself infallible is not flagged, because deciding that needs
/// whole-program reasoning the linter does not have. The shallow answer is the safe one: a false
/// negative costs a missed simplification, a false positive advises deleting a failure channel that
/// exists.
public class CstAlwaysSuccessResultRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-05";
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");

    /// Return types whose contract asserts fallibility. `Option<T>` is deliberately absent: it
    /// asserts optionality, not failure, and an always-present `Option` is a different smell.
    private static final Pattern FALLIBLE_RETURN = Pattern.compile("^(Result|Promise)\\s*<");

    /// Every `return` in the body, with whatever follows it — the anchor for condition 1.
    private static final Pattern RETURN_STATEMENT = Pattern.compile("\\breturn\\s+");

    /// A returned expression that IS a success construction: `Result.success(`, `Promise.success(`,
    /// or a statically-imported bare `success(`. Anchored at the start of the returned expression on
    /// purpose — "the body mentions success somewhere" is a different and much weaker claim, and
    /// getting that distinction wrong is what produced every false positive an audit of this rule
    /// found. `httpOps.fold(() -> Promise.success(unit()), ops -> sendWithRetry(...))` mentions
    /// success, but returns a DELEGATE whose other branch fails.
    private static final Pattern SUCCESS_HEAD = Pattern.compile("^(?:Result|Promise)\\s*\\.\\s*success\\s*\\(|^success\\s*\\(");

    /// Anything that introduces a failure in this body.
    private static final Pattern FAILURE =
        Pattern.compile("\\bfailure\\b|\\.result\\s*\\(\\s*\\)|\\.promise\\s*\\(\\s*\\)|\\.filter\\s*\\(|\\.ensure\\s*\\(");

    /// Condition 3: composition over a delegate's wrapper, which carries the delegate's failure.
    ///
    /// The aggregate forms are enumerated from `Promise`'s actual surface — `all`, `allOf`,
    /// `allOrCancel`, `allOfOrCancel`, `any` — rather than guessed. An earlier version matched only
    /// a bare `all(`, which silently missed `allOf(`: the dominant spelling in this codebase (33
    /// uses against 3) and the one that produced the one confirmed false positive an audit of these
    /// findings turned up — `AlertForwarder.forward`, whose `Promise.allOf(...)` joins fallible
    /// webhook calls and whose callers handle the failure it propagates.
    private static final Pattern DELEGATES =
        Pattern.compile("\\.flatMap\\s*\\(|\\.async\\s*\\(\\s*\\)|"
                       + "\\b(?:Result|Promise|Option)\\s*\\.\\s*(?:all|allOf|allOrCancel|allOfOrCancel|any)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return findAllMethods(root).stream()
                             .filter(this::returnsResult)
                             .filter(this::alwaysReturnsSuccess)
                             // Ordered last on purpose: this one walks to the enclosing member and
                             // scans its subtree for annotations, while the two above are a type
                             // check and a regex over text already in hand. Very few methods reach
                             // it, so the tree walk runs on candidates rather than on every
                             // Result/Promise-returning method in the file.
                             .filter(method -> !implementsAnInheritedContract(root, method))
                             .map(method -> createDiagnostic(method, ctx));
    }

    /// An `@Override` does not own its signature: the return type comes from the interface or
    /// superclass, so "return the value directly" is advice the author cannot take. A trivially
    /// succeeding `Promise<Unit> stop()` on an adapter is the contract being satisfied, not a step
    /// mis-typed as fallible — corpus-checking the Promise extension surfaced a cluster of exactly
    /// these (`stop`, `isHealthy`, `update` across adapter implementations) and they are pure noise.
    ///
    /// Matched with `contains` rather than an exact name comparison because trailing trivia after a
    /// bare annotation lands inside the annotation's own node span — the defect behind #600's
    /// silently-disabled `@Contract`.
    private boolean implementsAnInheritedContract(Cursor root, Cursor method) {
        return enclosingMember(root, method).map(member -> findAll(member, RuleKind.ANNOTATION).stream()
                                                                  .anyMatch(annotation -> text(annotation).contains("Override")))
                                            .or(false);
    }

    private boolean returnsResult(Cursor method) {
        return methodReturnType(method).map(type -> FALLIBLE_RETURN.matcher(text(type).trim())
                                                                    .find())
                                       .or(false);
    }

    /// Condition 1 as stated: EVERY return yields a success construction — not "a success appears
    /// somewhere in the body". Conditions 2 and 3 then rule out a body that can still fail or that
    /// carries a delegate's failure through a combinator.
    private boolean alwaysReturnsSuccess(Cursor method) {
        var methodText = memberDeclText(method);
        var returns = returnedExpressions(methodText);

        return !returns.isEmpty()
              && returns.stream().allMatch(expression -> SUCCESS_HEAD.matcher(expression).find())
              && !FAILURE.matcher(methodText).find()
              && !DELEGATES.matcher(methodText).find();
    }

    /// The expression each `return` yields, trimmed of leading whitespace. Text-level because a
    /// return's expression head is not a distinct CST node kind here; anchoring at the head is what
    /// makes it sound, not the fact that it is text.
    private List<String> returnedExpressions(String methodText) {
        var expressions = new ArrayList<String>();
        var matcher = RETURN_STATEMENT.matcher(methodText);

        while (matcher.find()) {
            expressions.add(methodText.substring(matcher.end()).stripLeading());
        }

        return expressions;
    }

    private Diagnostic createDiagnostic(Cursor method, LintContext ctx) {
        var methodName = extractMethodName(memberDeclText(method));

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(anchorOf(method)),
                                     startColumn(anchorOf(method)),
                                     "Method '" + methodName + "' always succeeds but is typed as fallible; return the value directly",
                                     "The return type is the contract: Result<T> asserts fallible, Promise<T> fallible and asynchronous. A body that only ever constructs success has neither, so every caller flatMaps a step that cannot fail. Return the plain value and let callers use map.")
                         .withExample("""
            // Before: Result that never fails
            public static Result<Config> config(String name) {
                return Result.success(new Config(name));
            }

            // After: return T directly
            public static Config config(String name) {
                return new Config(name);
            }
            """);
    }

    private static String extractMethodName(String memberText) {
        // v6: Identifier is a token, not a CST rule. Extract method name via regex.
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? matcher.group(1)
               : "(unknown)";
    }
}
