package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.CstNodes;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-TOT-02 (R-B): No partial method reference in a carrier mapper.
///
/// The #483 shape was `.map(Type::methodThatThrows)` — a method reference, not an inline lambda.
/// A method reference in mapper position (`map`/`flatMap`/`filter`/`replaceResult`/`fold`) flags when:
///   1. its target method is declared in the SAME compilation unit and that method's body contains
///      a partial operation (R-A's op set), or
///   2. its name matches `*OrThrow` — the by-convention marker for a partial accessor — regardless
///      of whether the target is resolvable in this file.
///
/// Resolution is by method name within the file (syntax-only cannot see the owning type); an
/// unresolved target that is not `*OrThrow` is left to the human, not flagged.
public class CstMapperMethodReferenceRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-TOT-02";

    /// `.<mapper>( Target::method )` — group 1 mapper, group 2 receiver, group 3 referenced method.
    private static final Pattern METHOD_REF_IN_MAPPER =
        Pattern.compile("\\.(map|flatMap|filter|replaceResult|fold)\\s*\\(\\s*([A-Za-z_$][\\w$]*)\\s*::\\s*([A-Za-z_$][\\w$]*)\\s*\\)");

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

        var partialMethods = collectPartialMethodNames(root);

        return findAllMethods(root).stream()
                             .flatMap(method -> checkMethod(method, partialMethods, ctx));
    }

    /// Names of methods in this compilation unit whose body contains a partial operation.
    private Set<String> collectPartialMethodNames(Cursor root) {
        var names = new HashSet<String>();

        for (var method : findAllMethods(root)) {
            var body = methodBody(method).map(CstNodes::text)
                                         .or("");

            if (MapperSafety.containsPartialOperation(body)) {
                extractMethodName(text(method)).onPresent(names::add);
            }
        }

        return names;
    }

    private Stream<Diagnostic> checkMethod(Cursor method, Set<String> partialMethods, LintContext ctx) {
        var methodText = MapperSafety.blankNonCode(text(method));
        var matcher = METHOD_REF_IN_MAPPER.matcher(methodText);
        var diagnostics = new ArrayList<Diagnostic>();

        while (matcher.find()) {
            var refName = matcher.group(3);

            if (isPartialReference(refName, partialMethods)) {
                var line = startLine(method) + MapperSafety.newlinesBefore(methodText, matcher.start());

                diagnostics.add(createDiagnostic(method, matcher.group(2), refName, line, ctx));
            }
        }

        return diagnostics.stream();
    }

    /// A method reference is partial when its name marks a throwing accessor — `*OrThrow` or the
    /// JDK `orElseThrow`, both matched case-insensitively so `Optional::orElseThrow` is caught — or
    /// a same-file method of that name has a partial body. Known false positive: name-based
    /// resolution cannot see overloads, so an unrelated same-file method with a matching name and a
    /// partial body flags the reference; `@SuppressWarnings("JBCT-TOT-02")` is the escape hatch.
    private boolean isPartialReference(String refName, Set<String> partialMethods) {
        var lower = refName.toLowerCase();

        return lower.endsWith("orthrow") || lower.endsWith("orelsethrow") || partialMethods.contains(refName);
    }

    private Diagnostic createDiagnostic(Cursor method, String receiver, String refName, int line, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     line,
                                     startColumn(method),
                                     "Partial method reference '" + receiver + "::" + refName
                                    + "' in mapper - make the mapper total or lift to a typed Cause",
                                     "A method reference in map/flatMap/filter/replaceResult/fold whose target throws "
                                    + "(name ends with OrThrow, or its same-file body uses a partial accessor) can hang "
                                    + "a Promise when the throw is swallowed (#483). Return a Result/Promise instead.")
                         .withExample("""
            // Before: method ref to a throwing accessor
            promise.map(Wire::firstItemOrThrow);

            // After: total step returning a carrier
            promise.flatMap(wire -> wire.firstItem().async(EMPTY_ITEMS));
            """);
    }

    private static Option<String> extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? Option.some(matcher.group(1))
               : Option.none();
    }
}
