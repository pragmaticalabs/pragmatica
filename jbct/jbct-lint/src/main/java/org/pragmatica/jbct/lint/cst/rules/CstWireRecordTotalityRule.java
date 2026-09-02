package org.pragmatica.jbct.lint.cst.rules;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-TOT-03 (R-C): Jackson wire-record accessor dereferences a possibly-null component.
///
/// A Jackson-bound record (any `@Json*` / `@JacksonXml*` annotation) has no compile-time guarantee
/// that a reference-typed component is populated — an absent XML/JSON field leaves it null. An
/// accessor/helper method inside the record that dereferences such a component (calls a method on
/// it, `.stream()`s it, or indexes it) without a null guard on that component NPEs at runtime —
/// the mechanism behind the #483 wire-DTO incident. Guard the component or model it as `Option`.
public class CstWireRecordTotalityRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-TOT-03";

    private static final Pattern JACKSON_MARKER = Pattern.compile("@(Json[A-Za-z]*|JacksonXml[A-Za-z]*)");

    private static final Pattern COMPONENT_SPLIT = Pattern.compile("^(.*\\S)\\s+([A-Za-z_$][\\w$]*)$");

    private static final Pattern ANNOTATION_STRIP = Pattern.compile("@[A-Za-z_$][\\w$.]*\\s*(\\([^)]*\\))?");

    private static final Set<String> PRIMITIVES = Set.of("int", "long", "double", "float", "boolean", "char", "byte", "short", "void");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        return findAllRecords(root).stream()
                             .filter(this::isJacksonRecord)
                             .flatMap(record -> checkRecord(record, ctx));
    }

    private boolean isJacksonRecord(Cursor record) {
        return JACKSON_MARKER.matcher(text(record))
                             .find();
    }

    private Stream<Diagnostic> checkRecord(Cursor record, LintContext ctx) {
        var refComponents = referenceComponents(record);

        if (refComponents.isEmpty()) {
            return Stream.empty();
        }

        return findAllMethods(record).stream()
                             .flatMap(method -> checkMethod(method, refComponents, ctx));
    }

    private Stream<Diagnostic> checkMethod(Cursor method, Set<String> refComponents, LintContext ctx) {
        return methodBody(method).map(body -> checkBody(body, refComponents, ctx))
                                 .or(Stream.empty());
    }

    private Stream<Diagnostic> checkBody(Cursor body, Set<String> refComponents, LintContext ctx) {
        var bodyText = MapperSafety.blankNonCode(text(body));

        return refComponents.stream()
                            .flatMap(component -> unguardedDereference(bodyText, component).map(offset -> report(body, component, offset, ctx))
                                                                                           .stream())
                            .limit(1);
    }

    /// Offset of the first dereference of `component` that is not protected by a null guard on the
    /// same component anywhere in the method body, if any.
    private Option<Integer> unguardedDereference(String bodyText, String component) {
        var deref = derefPattern(component).matcher(bodyText);

        if (!deref.find() || isGuarded(bodyText, component)) {
            return Option.none();
        }

        return Option.some(deref.start());
    }

    private Pattern derefPattern(String component) {
        return Pattern.compile("\\b" + Pattern.quote(component) + "\\b\\s*(\\(\\s*\\))?\\s*[.\\[]");
    }

    /// A component is guarded when a null check names it as a whole word — `\b` boundaries keep
    /// `cAbc != null` from counting as a guard for component `c`. Comments are already blanked
    /// upstream so a guard mentioned only in a comment does not count.
    private boolean isGuarded(String bodyText, String component) {
        var word = "\\b" + Pattern.quote(component) + "\\b";
        var guard = Pattern.compile(word + "\\s*[=!]=\\s*null"
                                    + "|null\\s*[=!]=\\s*" + word
                                    + "|requireNonNull\\s*\\(\\s*" + word
                                    + "|ofNullable\\s*\\(\\s*" + word
                                    + "|Option\\.option\\s*\\(\\s*" + word);

        return guard.matcher(bodyText)
                    .find();
    }

    private Diagnostic report(Cursor body, String component, int offset, LintContext ctx) {
        var line = startLine(body) + MapperSafety.newlinesBefore(MapperSafety.blankNonCode(text(body)), offset);

        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     line,
                                     startColumn(body),
                                     "Wire-record accessor dereferences possibly-null component '" + component
                                    + "' without a guard",
                                     "A Jackson-bound record component may be null when the field is absent from the "
                                    + "payload. Guard it (component == null / Option.option(component)) or model it as "
                                    + "Option before dereferencing.");
    }

    private Set<String> referenceComponents(Cursor record) {
        var components = childByRule(record, RuleKind.RECORD_DECL).flatMap(rd -> childByRule(rd, RuleKind.RECORD_COMPONENTS))
                                    .map(rc -> childrenByRule(rc, RuleKind.RECORD_COMP))
                                    .or(List.of());
        var names = new LinkedHashSet<String>();

        for (var component : components) {
            parseComponent(text(component)).onPresent(names::add);
        }

        return names;
    }

    /// Name of a reference-typed record component, or `none` for a primitive component.
    private Option<String> parseComponent(String componentText) {
        var stripped = ANNOTATION_STRIP.matcher(componentText)
                                       .replaceAll("")
                                       .trim();
        var matcher = COMPONENT_SPLIT.matcher(stripped);

        if (!matcher.matches() || isPrimitive(matcher.group(1))) {
            return Option.none();
        }

        return Option.some(matcher.group(2));
    }

    private boolean isPrimitive(String type) {
        var trimmed = type.trim();

        if (trimmed.contains("[")) {
            return false;
        }

        var base = trimmed.contains("<")
                   ? trimmed.substring(0, trimmed.indexOf('<'))
                   : trimmed;

        return PRIMITIVES.contains(base.trim());
    }
}
