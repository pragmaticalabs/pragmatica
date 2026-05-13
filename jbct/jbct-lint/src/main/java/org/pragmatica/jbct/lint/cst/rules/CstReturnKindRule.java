package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-RET-01: Business methods must use only four return kinds.
///
/// T, Option<T>, Result<T>, or Promise<T>.
public class CstReturnKindRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-01";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/series/part-2-four-return-types.md";
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");

    private static final Set<String> FORBIDDEN_TYPES = Set.of("Optional",
                                                              "CompletableFuture",
                                                              "Future",
                                                              "CompletionStage");

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
                      .filter(method -> !isPrivateMethod(method, root))
                      .flatMap(method -> checkMethod(method, ctx));
    }

    private boolean isPrivateMethod(Cursor method, Cursor root) {
        // Find the ClassMember ancestor which contains the Modifier
        return findAncestor(root, method, RuleKind.CLASS_MEMBER).map(cm -> text(cm).contains("private "))
                           .or(false);
    }

    private Stream<Diagnostic> checkMethod(Cursor method, LintContext ctx) {
        // Get return type - Member → MethodDecl → Type
        return methodReturnType(method).map(type -> checkReturnType(method, type, ctx))
                          .or(Stream.empty());
    }

    private Stream<Diagnostic> checkReturnType(Cursor method, Cursor type, LintContext ctx) {
        var typeText = text(type).trim();
        var methodName = extractMethodName(text(method));
        // Check for void
        if (typeText.equals("void")) {
            return Stream.of(createVoidDiagnostic(method, methodName, ctx));
        }
        // Check for forbidden types
        for (var forbidden : FORBIDDEN_TYPES) {
            if (typeText.startsWith(forbidden + "<") || typeText.equals(forbidden)) {
                return Stream.of(createForbiddenTypeDiagnostic(method, methodName, typeText, ctx));
            }
        }
        return Stream.empty();
    }

    private Diagnostic createVoidDiagnostic(Cursor method, String methodName, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Method '" + methodName
                                     + "' returns void; JBCT requires Result<Unit> or Promise<Unit>",
                                     "In JBCT, void methods should return Result<Unit> (sync) or Promise<Unit> (async).")
                         .withExample("""
            // Before (void)
            public void saveUser(User user) { ... }

            // After (Result<Unit>)
            public Result<Unit> saveUser(User user) { ... }
            """)
                         .withDocLink(DOC_LINK);
    }

    private Diagnostic createForbiddenTypeDiagnostic(Cursor method,
                                                     String methodName,
                                                     String typeName,
                                                     LintContext ctx) {
        var replacement = suggestReplacement(typeName);
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(method),
                                     startColumn(method),
                                     "Method '" + methodName + "' returns " + typeName + "; use " + replacement
                                     + " instead",
                                     "JBCT uses its own monadic types for consistency.")
                         .withExample("""
            // Before
            public %s process() { ... }

            // After
            public %s process() { ... }
            """.formatted(typeName, replacement))
                         .withDocLink(DOC_LINK);
    }

    private String suggestReplacement(String typeName) {
        if (typeName.startsWith("Optional")) {
            return typeName.replace("Optional", "Option");
        }
        if (typeName.startsWith("CompletableFuture") || typeName.startsWith("Future") || typeName.startsWith("CompletionStage")) {
            return "Promise<...>";
        }
        return "Result<...> or Promise<...>";
    }

    private static String extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);
        return matcher.find() ? matcher.group(1) : "(unknown)";
    }
}
