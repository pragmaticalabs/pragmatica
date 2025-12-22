package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.Set;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-RET-01: Business methods must use only four return kinds.
 *
 * T, Option<T>, Result<T>, or Promise<T>.
 */
public class CstReturnKindRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-RET-01";
    private static final String DOC_LINK = "https://github.com/siy/coding-technology/blob/main/series/part-2-four-return-types.md";

    private static final Set<String>FORBIDDEN_TYPES = Set.of(
    "Optional", "CompletableFuture", "Future", "CompletionStage");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public String description() {
        return "Business methods must use only four return kinds: T, Option<T>, Result<T>, Promise<T>";
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class)
                          .flatMap(pd -> findFirst(pd, RuleId.QualifiedName.class))
                          .map(qn -> text(qn, source))
                          .or("");
        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }
        return findAll(root, RuleId.MethodDecl.class)
               .stream()
               .filter(method -> !isPrivateMethod(method, root, source))
               .flatMap(method -> checkMethod(method, source, ctx));
    }

    private boolean isPrivateMethod(CstNode method, CstNode root, String source) {
        // Find the ClassMember ancestor which contains the Modifier
        return findAncestor(root, method, RuleId.ClassMember.class)
               .map(cm -> text(cm, source).contains("private "))
               .or(false);
    }

    private Stream<Diagnostic> checkMethod(CstNode method, String source, LintContext ctx) {
        // Get return type - first Type child of MethodDecl
        var returnType = childByRule(method, RuleId.Type.class);
        if (returnType.isEmpty()) {
            return Stream.empty();
        }
        var typeText = text(returnType.unwrap(),
                            source)
                       .trim();
        var methodName = childByRule(method, RuleId.Identifier.class)
                         .map(id -> text(id, source))
                         .or("(unknown)");
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

    private Diagnostic createVoidDiagnostic(CstNode method, String methodName, LintContext ctx) {
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

    private Diagnostic createForbiddenTypeDiagnostic(CstNode method,
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
}
