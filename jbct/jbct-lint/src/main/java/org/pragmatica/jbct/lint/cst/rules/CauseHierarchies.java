package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// Shared substrate of the JBCT-CAUSE pack (`jbct/docs/typed-error-lint-spec.md`): same-file cause
/// hierarchy detection, variant views, and the text-scan primitives the factory rules need.
///
/// Extracted from JBCT-SEAL-02 (retired by absorption into JBCT-CAUSE-01) and extended two ways:
/// interface collection runs to a same-file FIXPOINT (`E2 extends E1 extends Cause` is now
/// recognized where SEAL-02 stopped at direct `extends Cause`), and the `Cause.Terminal` /
/// `Cause.Wrapped` mixins mark a variant through their QUALIFIED spelling on the raw header —
/// deliberately not through [DeclSupport#implementedHeadNames], which strips qualifiers and would
/// reduce `Cause.Wrapped` to a collision-prone simple name.
///
/// Text scans run over [MapperSafety#blankNonCode]-masked source (strings and comments blanked,
/// offsets and newlines preserved), so positions computed here map directly to lines and columns
/// in the original.
final class CauseHierarchies {
    private CauseHierarchies() {}

    private static final String CAUSE = "Cause";

    /// The library that DEFINES the sanctioned ad-hoc tier is exempt from the pack: `Causes.cause`
    /// and friends are "the sanctioned anonymous form and library-side" by the spec's own words,
    /// and the census showed the pack convicting `Causes.java` itself without this gate.
    static boolean sanctionedLibraryPackage(String packageName) {
        return packageName.startsWith("org.pragmatica.lang");
    }
    private static final Pattern INTERFACE_NAME = Pattern.compile("\\binterface\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern EXTENDS_CLAUSE = Pattern.compile("\\bextends\\b([^{]*)");
    private static final Pattern QUALIFIED_MIXIN = Pattern.compile("\\bCause\\s*\\.\\s*(Terminal|Wrapped)\\b");
    private static final Pattern IDENTIFIER_TAIL = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*$");
    private static final Pattern FACTORY_CALL = Pattern.compile("\\b(forOneValue|forTwoValues|forThreeValues)\\s*\\(");

    /// Names that make a same-file `implements` clause a cause variant: `Cause` itself plus every
    /// same-file interface transitively extending one of them.
    static Set<String> causeInterfaceNames(Cursor root) {
        var names = new HashSet<String>();

        names.add(CAUSE);

        var grew = true;

        while (grew) {
            grew = false;
            for (var iface : findAllInterfaces(root)) {
                var header = headerOf(text(iface));
                var name = matchOf(INTERFACE_NAME, header);

                if (name.isEmpty() || names.contains(name)) {
                    continue;
                }

                var extendsPart = matchOf(EXTENDS_CLAUSE, header);

                if (!extendsPart.isEmpty() && mentionsAny(extendsPart, names)) {
                    names.add(name);
                    grew = true;
                }
            }
        }

        return names;
    }

    /// Whether `decl` implements a same-file cause interface, `Cause` directly, or a qualified
    /// mixin (`Cause.Terminal` / `Cause.Wrapped`).
    static boolean isCauseVariant(Cursor decl, Set<String> causeNames) {
        if (DeclSupport.implementedHeadNames(decl)
                       .stream()
                       .anyMatch(causeNames::contains)) {
            return true;
        }

        return QUALIFIED_MIXIN.matcher(headerOf(text(decl)))
                              .find();
    }

    static List<Cursor> causeRecords(Cursor root, Set<String> causeNames) {
        return findAllRecords(root).stream()
                      .filter(record -> isCauseVariant(record, causeNames))
                      .toList();
    }

    static List<Cursor> causeEnums(Cursor root, Set<String> causeNames) {
        return findAllEnums(root).stream()
                      .filter(anEnum -> isCauseVariant(anEnum, causeNames))
                      .toList();
    }

    /// Component names of a record declaration, in order.
    static List<String> recordComponentNames(Cursor record) {
        var names = new ArrayList<String>();

        childByRule(record, RuleKind.RECORD_DECL)
                .flatMap(recordDecl -> childByRule(recordDecl, RuleKind.RECORD_COMPONENTS))
                .onPresent(components -> {
                    for (var component : childrenByRule(components, RuleKind.RECORD_COMP)) {
                        var name = matchOf(IDENTIFIER_TAIL, text(component).trim());

                        if (!name.isEmpty()) {
                            names.add(name);
                        }
                    }
                });

        return names;
    }

    /// Top-level argument count of an enum constant's argument list (0 when it has none).
    static int enumConstantArgCount(Cursor enumConstant) {
        var masked = MapperSafety.blankNonCode(text(enumConstant));
        var open = masked.indexOf('(');

        if (open < 0) {
            return 0;
        }

        var args = topLevelArgs(masked, masked, open);

        return args.size() == 1 && args.getFirst().raw().isBlank() && text(enumConstant).charAt(open + 1) == ')'
               ? 0
               : args.size();
    }

    /// A `forXValues` call site: offset of the name, the rung (1/2/3), and the raw top-level
    /// argument texts. Declaration sites are excluded downstream by requiring the template
    /// argument to be a string literal — a parameter declaration (`String template`) never is.
    record FactoryCall(int offset, int valueArity, List<Arg> args) {}

    record Arg(int offset, String raw) {}

    static List<FactoryCall> factoryCalls(String source, String masked) {
        var calls = new ArrayList<FactoryCall>();
        var matcher = FACTORY_CALL.matcher(masked);

        while (matcher.find()) {
            var open = masked.indexOf('(', matcher.end(1));
            var args = topLevelArgs(source, masked, open);

            if (args.isEmpty()) {
                continue;
            }

            var arity = switch (matcher.group(1)) {
                case "forOneValue" -> 1;
                case "forTwoValues" -> 2;
                default -> 3;
            };

            calls.add(new FactoryCall(matcher.start(1), arity, args));
        }

        return calls;
    }

    /// Raw texts of the top-level (depth-1) arguments of the parenthesized list opening at `open`.
    /// Depth walks the MASKED text so parens and commas inside literals do not count; substrings
    /// come from the RAW text so literal content survives. Unbalanced input yields an empty list.
    static List<Arg> topLevelArgs(String source, String masked, int open) {
        if (open < 0 || open >= masked.length() || masked.charAt(open) != '(') {
            return List.of();
        }

        var args = new ArrayList<Arg>();
        var depth = 0;
        var argStart = open + 1;

        for (var i = open; i < masked.length(); i++) {
            var ch = masked.charAt(i);

            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth == 0) {
                    args.add(new Arg(argStart, source.substring(argStart, i).trim()));

                    return args;
                }
            } else if (ch == ',' && depth == 1) {
                args.add(new Arg(argStart, source.substring(argStart, i).trim()));
                argStart = i + 1;
            }
        }

        return List.of();
    }

    /// Format-conversion count of a template literal's content: `%%` and `%n` excluded.
    static int conversionCount(String literalContent) {
        var conversion = Pattern.compile("%(?:(%)|(n)|(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z])")
                                .matcher(literalContent);
        var count = 0;

        while (conversion.find()) {
            if (conversion.group(1) == null && conversion.group(2) == null) {
                count++;
            }
        }

        return count;
    }

    /// Content of a leading string literal (`"…"` or text block), or empty when `raw` does not
    /// start with one — the guard that separates call sites from declaration sites.
    static String leadingStringLiteral(String raw) {
        if (raw.startsWith("\"\"\"")) {
            var end = raw.indexOf("\"\"\"", 3);

            return end > 0 ? raw.substring(3, end) : "";
        }

        if (raw.startsWith("\"")) {
            var end = raw.indexOf('"', 1);

            return end > 0 ? raw.substring(1, end) : "";
        }

        return "";
    }

    static int lineAt(String source, int offset) {
        var line = 1;

        for (var i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }

        return line;
    }

    static int columnAt(String source, int offset) {
        var column = 1;

        for (var i = offset - 1; i >= 0 && source.charAt(i) != '\n'; i--) {
            column++;
        }

        return column;
    }

    private static String headerOf(String declText) {
        var brace = declText.indexOf('{');

        return brace >= 0 ? declText.substring(0, brace) : declText;
    }

    private static String matchOf(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);

        return matcher.find() ? matcher.group(1) : "";
    }

    private static boolean mentionsAny(String clause, Set<String> names) {
        for (var name : names) {
            if (Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(clause).find()) {
                return true;
            }
        }

        return false;
    }
}
