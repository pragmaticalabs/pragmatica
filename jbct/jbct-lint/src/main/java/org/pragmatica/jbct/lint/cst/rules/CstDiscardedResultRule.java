package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-RET-07: Discarded Result/Promise/Option value.
///
/// Detects method call chains whose result is silently discarded (expression statement
/// ending with a known monadic terminal method), and static factory calls on Result,
/// Promise, or Option whose return value is ignored.
public class CstDiscardedResultRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-07";

    /// Chain terminal methods that always return a monadic wrapper.
    /// Discarding their result is always a bug.
    private static final Set<String> CHAIN_TERMINALS = Set.of(
            "map", "flatMap", "filter", "recover", "fold",
            "onSuccess", "onFailure", "onSuccessRun",
            "allOf", "anyOf", "async", "timeout", "delay", "race",
            "or", "orElse", "toResult", "mapToUnit", "onPresent",
            "mapError", "result", "onFailureRun");

    /// Pattern matching `.methodName(...)` at the end of an expression statement (before `;`)
    private static final Pattern CHAIN_TERMINAL_PATTERN = Pattern.compile(
            "\\.(" + String.join("|", CHAIN_TERMINALS) + ")\\s*\\([^)]*\\)\\s*;\\s*$");

    /// Known-type factory patterns: `Result.success(...)`, `Promise.failure(...)`, etc.
    private static final Pattern FACTORY_PATTERN = Pattern.compile(
            "^\\s*(Result|Promise|Option)\\s*\\.\\s*(success|failure|some|none|option|unitPromise|unitResult|unit)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class)
                .flatMap(pd -> findFirst(pd, RuleId.QualifiedName.class))
                .map(qn -> text(qn, source))
                .or("");
        if (!ctx.shouldLint(packageName)) {
            return Stream.empty();
        }
        return findAllStatements(root).stream()
                      .filter(stmt -> isExpressionStatement(stmt, source))
                      .filter(stmt -> isDiscardedResult(stmt, source))
                      .map(stmt -> createDiagnostic(root, stmt, source, ctx));
    }

    private boolean isExpressionStatement(CstNode stmt, String source) {
        var stmtText = text(stmt, source).trim();
        // Expression statements end with `;` and are not declarations, returns, or control flow
        return stmtText.endsWith(";")
               && !stmtText.startsWith("return ")
               && !stmtText.startsWith("var ")
               && !stmtText.startsWith("final ")
               && !stmtText.startsWith("if ")
               && !stmtText.startsWith("for ")
               && !stmtText.startsWith("while ")
               && !stmtText.startsWith("switch ")
               && !stmtText.startsWith("throw ")
               && !stmtText.startsWith("try ");
    }

    private boolean isDiscardedResult(CstNode stmt, String source) {
        var stmtText = text(stmt, source).trim();
        return isDiscardedChainTerminal(stmtText) || isDiscardedFactory(stmtText);
    }

    private boolean isDiscardedChainTerminal(String stmtText) {
        return CHAIN_TERMINAL_PATTERN.matcher(stmtText).find();
    }

    private boolean isDiscardedFactory(String stmtText) {
        return FACTORY_PATTERN.matcher(stmtText).find();
    }

    private Diagnostic createDiagnostic(CstNode root, CstNode stmt, String source, LintContext ctx) {
        var methodName = findAncestor(root, stmt, RuleId.Member.class)
                .flatMap(md -> childByRule(md, RuleId.Identifier.class))
                .map(id -> text(id, source))
                .or("(unknown)");
        var stmtText = text(stmt, source).trim();
        var discardedType = isDiscardedFactory(stmtText) ? "factory result" : "chain result";
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(stmt),
                                     startColumn(stmt),
                                     "Discarded " + discardedType + " in method '" + methodName
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
}
