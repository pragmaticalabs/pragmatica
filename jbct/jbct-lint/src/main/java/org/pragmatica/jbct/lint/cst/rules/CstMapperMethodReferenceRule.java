package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.CstNodes;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-TOT-02 (R-B): No partial method reference in a carrier mapper.
///
/// The #483 shape was `.map(Type::methodThatThrows)` — a method reference, not an inline lambda.
/// A method reference in mapper position (`map`/`flatMap`/`filter`/`replaceResult`/`fold`) flags when:
///   1. its name marks a throwing accessor (`*OrThrow` / the JDK `orElseThrow`, case-insensitive),
///      regardless of resolvability, or
///   2. its target method — resolved by the reference's receiver — is declared exactly once in this
///      compilation unit with a partial body (R-A's op set).
///
/// Resolution is receiver-scoped so a same-name method in an unrelated type never triggers a false
/// positive: `this::m` scans only the type enclosing the call site; `X::m` scans only a type named
/// `X` declared in this file. A receiver that is not `this` and names no type in the file (an
/// imported type or an instance variable) resolves to nothing — only the name heuristic applies.
/// Overloaded names are ambiguous (which overload does the reference bind?), so a name declared more
/// than once in the resolved type is excluded from body-scan resolution — only the name heuristic
/// applies. These scoping rules kill the cross-type collisions corpus validation surfaced (e.g. a
/// total `X::isActive` no longer flagged because an unrelated `Y.isActive()` is partial).
public class CstMapperMethodReferenceRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-TOT-02";

    /// `.<mapper>( Receiver::method )` — group 1 mapper, group 2 receiver, group 3 referenced method.
    private static final Pattern METHOD_REF_IN_MAPPER =
        Pattern.compile("\\.(map|flatMap|filter|replaceResult|fold)\\s*\\(\\s*([A-Za-z_$][\\w$]*)\\s*::\\s*([A-Za-z_$][\\w$]*)\\s*\\)");

    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");

    private static final Pattern TYPE_NAME_PATTERN =
        Pattern.compile("\\b(?:class|interface|record|enum)\\s+([A-Za-z_$][\\w$]*)");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        var typeIdxByName = typeIdxByName(root);
        var partialSingletons = partialSingletonsByType(root);

        return findAllMethods(root).stream()
                             .flatMap(method -> checkMethod(root, method, typeIdxByName, partialSingletons, ctx));
    }

    /// Declared name → CST index for every type in the file (used to resolve an `X::m` receiver).
    private Map<String, Integer> typeIdxByName(Cursor root) {
        var map = new HashMap<String, Integer>();

        for (var type : findAll(root, RuleKind.TYPE_KIND)) {
            typeName(type).onPresent(name -> map.put(name, type.idx()));
        }

        return map;
    }

    /// Type CST index → names that are declared exactly once directly in that type with a partial
    /// body. Restricting to singletons drops overloaded names (ambiguous target); keying by the
    /// declaring type keeps a partial `Y.isActive` from tainting a total `X.isActive`.
    private Map<Integer, Set<String>> partialSingletonsByType(Cursor root) {
        var result = new HashMap<Integer, Set<String>>();

        for (var type : findAll(root, RuleKind.TYPE_KIND)) {
            collectPartialSingletons(root, type, result);
        }

        return result;
    }

    private void collectPartialSingletons(Cursor root, Cursor type, Map<Integer, Set<String>> result) {
        var typeIdx = type.idx();
        var counts = new HashMap<String, Integer>();
        var partialNames = new HashSet<String>();

        for (var method : findAllMethods(type)) {
            if (!isDirectMethod(root, method, typeIdx)) {
                continue;
            }

            var name = extractMethodName(memberDeclText(method)).or("");

            if (name.isEmpty()) {
                continue;
            }

            counts.merge(name, 1, Integer::sum);

            if (MapperSafety.containsPartialOperation(bodyText(method))) {
                partialNames.add(name);
            }
        }

        addSingletons(typeIdx, counts, partialNames, result);
    }

    private void addSingletons(int typeIdx, Map<String, Integer> counts, Set<String> partialNames, Map<Integer, Set<String>> result) {
        var singletons = new HashSet<String>();

        for (var name : partialNames) {
            if (counts.getOrDefault(name, 0) == 1) {
                singletons.add(name);
            }
        }

        if (!singletons.isEmpty()) {
            result.put(typeIdx, singletons);
        }
    }

    /// A method belongs directly to `typeIdx` when that type is its nearest enclosing type (not a
    /// nested type declared inside it).
    private boolean isDirectMethod(Cursor root, Cursor method, int typeIdx) {
        return enclosingTypeIdx(root, method).map(idx -> idx == typeIdx)
                                             .or(false);
    }

    private String bodyText(Cursor method) {
        return methodBody(method).map(CstNodes::text)
                                 .or("");
    }

    private Stream<Diagnostic> checkMethod(Cursor root,
                                           Cursor method,
                                           Map<String, Integer> typeIdxByName,
                                           Map<Integer, Set<String>> partialSingletons,
                                           LintContext ctx) {
        var enclosingType = enclosingTypeIdx(root, method);
        var methodText = MapperSafety.blankNonCode(memberDeclText(method));
        var matcher = METHOD_REF_IN_MAPPER.matcher(methodText);
        var diagnostics = new ArrayList<Diagnostic>();

        while (matcher.find()) {
            var receiver = matcher.group(2);
            var refName = matcher.group(3);

            if (isPartialReference(receiver, refName, enclosingType, typeIdxByName, partialSingletons)) {
                var line = startLine(anchorOf(method)) + MapperSafety.newlinesBefore(methodText, matcher.start());

                diagnostics.add(createDiagnostic(method, receiver, refName, line, ctx));
            }
        }

        return diagnostics.stream();
    }

    /// A reference is partial when its name marks a throwing accessor, or when the type its receiver
    /// resolves to declares exactly one partial method of that name. `this` resolves to the
    /// enclosing type; any other receiver resolves to a same-named type in the file (or to nothing).
    private boolean isPartialReference(String receiver,
                                       String refName,
                                       Option<Integer> enclosingType,
                                       Map<String, Integer> typeIdxByName,
                                       Map<Integer, Set<String>> partialSingletons) {
        if (isThrowingName(refName)) {
            return true;
        }

        return targetType(receiver, enclosingType, typeIdxByName).map(typeIdx -> partialSingletons.getOrDefault(typeIdx, Set.of())
                                                                                                  .contains(refName))
                                                                 .or(false);
    }

    private Option<Integer> targetType(String receiver, Option<Integer> enclosingType, Map<String, Integer> typeIdxByName) {
        return "this".equals(receiver)
               ? enclosingType
               : Option.option(typeIdxByName.get(receiver));
    }

    /// `*OrThrow` (container convention) or the JDK `orElseThrow`, matched case-insensitively so
    /// `Optional::orElseThrow` is caught.
    private boolean isThrowingName(String refName) {
        var lower = refName.toLowerCase();

        return lower.endsWith("orthrow") || lower.endsWith("orelsethrow");
    }

    private Option<Integer> enclosingTypeIdx(Cursor root, Cursor node) {
        return findAncestor(root, node, RuleKind.TYPE_KIND).map(Cursor::idx);
    }

    private Diagnostic createDiagnostic(Cursor method, String receiver, String refName, int line, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     line,
                                     startColumn(anchorOf(method)),
                                     "Partial method reference '" + receiver + "::" + refName
                                    + "' in mapper - make the mapper total or lift to a typed Cause",
                                     "A method reference in map/flatMap/filter/replaceResult/fold whose target throws "
                                    + "(name ends with OrThrow, or its same-type body uses a partial accessor) can hang "
                                    + "a Promise when the throw is swallowed (#483). Return a Result/Promise instead.")
                         .withExample("""
            // Before: method ref to a throwing accessor
            promise.map(Wire::firstItemOrThrow);

            // After: total step returning a carrier
            promise.flatMap(wire -> wire.firstItem().async(EMPTY_ITEMS));
            """);
    }

    private Option<String> typeName(Cursor typeKind) {
        var matcher = TYPE_NAME_PATTERN.matcher(text(typeKind));

        return matcher.find()
               ? Option.some(matcher.group(1))
               : Option.none();
    }

    private static Option<String> extractMethodName(String memberText) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberText);

        return matcher.find()
               ? Option.some(matcher.group(1))
               : Option.none();
    }
}
