package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/// JBCT-STATIC-01: Prefer static imports for Pragmatica factory methods.
///
/// Detects qualified calls like Result.success(), Option.some(), Causes.cause()
/// that should use static imports for cleaner, more readable code.
public class CstStaticImportRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-STATIC-01";

    // Factory patterns: TypeName.methodName(
    private static final List<FactoryPattern> FACTORY_PATTERNS = List.of(new FactoryPattern("Result",
                                                                                            Set.of("success", "failure")),
                                                                         new FactoryPattern("Option",
                                                                                            Set.of("some",
                                                                                                   "none",
                                                                                                   "option")),
                                                                         new FactoryPattern("Causes", Set.of("cause")),
                                                                         new FactoryPattern("Promise",
                                                                                            Set.of("promise",
                                                                                                   "resolved",
                                                                                                   "failed")));

    // Regex to find qualified factory calls
    private static final Pattern QUALIFIED_CALL = Pattern.compile("\\b(Result|Option|Causes|Promise)\\.([a-z]+)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }
        // Collect static imports already in the file
        var staticImports = collectStaticImports(root);
        // Find qualified factory calls
        return findAllMethods(root).stream()
                      .flatMap(method -> findQualifiedCalls(method, staticImports, ctx));
    }

    private Set<String> collectStaticImports(Cursor root) {
        var imports = new HashSet<String>();
        findAll(root, RuleKind.IMPORT_DECL)
        .forEach(imp -> {
                     var importText = text(imp);
                     if (importText.contains("static")) {
                         // Extract the imported members
        for (var pattern : FACTORY_PATTERNS) {
                             for (var method : pattern.methods()) {
                                 // Check for specific method import or wildcard
        if (importText.contains("." + method + ";") ||
        importText.contains("." + pattern.typeName() + ".*;") ||
        importText.contains("." + pattern.typeName() + "." + method)) {
                                     imports.add(pattern.typeName() + "." + method);
                                 }
                             }
                         }
                     }
                 });
        return imports;
    }

    private Stream<Diagnostic> findQualifiedCalls(Cursor method,
                                                  Set<String> staticImports,
                                                  LintContext ctx) {
        var methodText = text(method);
        var matcher = QUALIFIED_CALL.matcher(methodText);
        return Stream.iterate(matcher.find(),
                              found -> found,
                              found -> matcher.find())
                     .map(_ -> new String[]{matcher.group(1), matcher.group(2)})
                     .filter(parts -> isFactoryMethod(parts[0], parts[1]))
                     .filter(parts -> !staticImports.contains(parts[0] + "." + parts[1]))
                     .map(parts -> createDiagnostic(method, parts[0], parts[1], ctx))
                     .limit(3);
    }

    private boolean isFactoryMethod(String typeName, String methodName) {
        return FACTORY_PATTERNS.stream()
                               .anyMatch(p -> p.typeName()
                                               .equals(typeName) &&
        p.methods()
         .contains(methodName));
    }

    private Diagnostic createDiagnostic(Cursor node,
                                        String typeName,
                                        String methodName,
                                        LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Use static import for " + typeName + "." + methodName + "()",
                                     "Add 'import static org.pragmatica.lang." + typeName + "." + methodName + ";' "
                                     + "and use '" + methodName + "(...)' directly for cleaner code.");
    }

    private record FactoryPattern(String typeName, Set<String> methods) {}
}
