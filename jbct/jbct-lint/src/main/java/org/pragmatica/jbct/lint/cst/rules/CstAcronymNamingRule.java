package org.pragmatica.jbct.lint.cst.rules;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Cursor;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-ACR-01: Acronym naming convention.
///
/// Detects all-caps acronyms in type and method names (e.g., HTTPClient, XMLParser)
/// and suggests proper camelCase (HttpClient, XmlParser).
public class CstAcronymNamingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ACR-01";
    // Pattern to detect consecutive uppercase letters (3+ caps indicates an acronym like HTTP, XML, URL)
    // Excludes 2-letter sequences like LParen (Left Paren) which are just prefixes, not acronyms
    private static final Pattern ACRONYM_PATTERN = Pattern.compile("[A-Z]{3,}");

    // v6: Identifier is a token, not a CST rule. Extract type names from `class/interface/enum/record <Name>`.
    private static final Pattern TYPE_NAME_PATTERN = Pattern.compile("\\b(?:class|interface|enum|record|@interface)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    // Method name: first identifier followed by `(` (skips return-type tokens via the boundary).
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile("\\b([a-zA-Z_$][a-zA-Z0-9_$]*)\\s*\\(");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        // Check class declarations
        var classDiagnostics = findAllClasses(root).stream().flatMap(decl -> checkTypeName(decl, ctx));
        // Check interface declarations
        var interfaceDiagnostics = findAllInterfaces(root).stream().flatMap(decl -> checkTypeName(decl, ctx));
        // Check enum declarations
        var enumDiagnostics = findAllEnums(root).stream().flatMap(decl -> checkTypeName(decl, ctx));
        // Check record declarations
        var recordDiagnostics = findAllRecords(root).stream().flatMap(decl -> checkTypeName(decl, ctx));
        // Check method declarations
        var methodDiagnostics = findAllMethods(root).stream().flatMap(method -> checkMethodName(method, ctx));

        return Stream.of(classDiagnostics, interfaceDiagnostics, enumDiagnostics, recordDiagnostics, methodDiagnostics).flatMap(s -> s);
    }

    private Stream<Diagnostic> checkTypeName(Cursor decl, LintContext ctx) {
        var matcher = TYPE_NAME_PATTERN.matcher(text(decl));

        if (!matcher.find()) {
            return Stream.empty();
        }

        var name = matcher.group(1);

        if (!hasAcronymViolation(name)) {
            return Stream.empty();
        }

        return Stream.of(createDiagnostic(decl, "Type", name, suggestFix(name), ctx));
    }

    private Stream<Diagnostic> checkMethodName(Cursor method, LintContext ctx) {
        var matcher = METHOD_NAME_PATTERN.matcher(memberDeclText(method));

        if (!matcher.find()) {
            return Stream.empty();
        }

        var name = matcher.group(1);

        if (!hasAcronymViolation(name)) {
            return Stream.empty();
        }

        return Stream.of(createDiagnostic(method, "Method", name, suggestFix(name), ctx));
    }

    private boolean hasAcronymViolation(String name) {
        return ACRONYM_PATTERN.matcher(name).find();
    }

    private String suggestFix(String name) {
        var result = new StringBuilder();
        var chars = name.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];

            if (Character.isUpperCase(c)) {
                // Check if this is part of an acronym (2+ consecutive caps)
                int acronymEnd = i;

                while (acronymEnd < chars.length && Character.isUpperCase(chars[acronymEnd])) {
                    acronymEnd++;
                }

                int acronymLength = acronymEnd - i;

                if (acronymLength > 1) {
                    // Found acronym - convert to proper case
                    // If followed by lowercase, keep last char uppercase (e.g., HTTPClient → HttpClient)
                    if (acronymEnd < chars.length && Character.isLowerCase(chars[acronymEnd])) {
                        result.append(i == 0
                                      ? c
                                      : Character.toLowerCase(c));
                        for (int j = i + 1; j < acronymEnd - 1; j++) {
                            result.append(Character.toLowerCase(chars[j]));
                        }

                        if (acronymEnd > i + 1) {
                            result.append(chars[acronymEnd - 1]);
                        }

                        i = acronymEnd - 2;
                    } else {
                        // Acronym at end - lowercase all but first
                        result.append(i == 0
                                      ? c
                                      : Character.toLowerCase(c));
                        for (int j = i + 1; j < acronymEnd; j++) {
                            result.append(Character.toLowerCase(chars[j]));
                        }

                        i = acronymEnd - 1;
                    }
                } else {
                    result.append(c);
                }
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    private Diagnostic createDiagnostic(Cursor node, String kind, String name, String suggested, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     kind + " '" + name + "' uses all-caps acronym; prefer '" + suggested + "'",
                                     "Acronyms in identifiers should use PascalCase for readability. "
                                    + "Example: HTTPClient → HttpClient, XMLParser → XmlParser, URLEncoder → UrlEncoder.")
                         .withExample("""
            // Before
            public class HTTPClient { }
            public interface XMLParser { }

            // After
            public class HttpClient { }
            public interface XmlParser { }
            """);
    }
}
