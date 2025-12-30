package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-ACR-01: Acronym naming convention.
 *
 * Detects all-caps acronyms in type and method names (e.g., HTTPClient, XMLParser)
 * and suggests proper camelCase (HttpClient, XmlParser).
 */
public class CstAcronymNamingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ACR-01";

    // Pattern to detect consecutive uppercase letters (3+ caps indicates an acronym like HTTP, XML, URL)
    // Excludes 2-letter sequences like LParen (Left Paren) which are just prefixes, not acronyms
    private static final Pattern ACRONYM_PATTERN = Pattern.compile("[A-Z]{3,}");

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        // Check class declarations
        var classDiagnostics = findAll(root, RuleId.ClassDecl.class)
                               .stream()
                               .flatMap(decl -> checkTypeName(decl, source, ctx));
        // Check interface declarations
        var interfaceDiagnostics = findAll(root, RuleId.InterfaceDecl.class)
                                   .stream()
                                   .flatMap(decl -> checkTypeName(decl, source, ctx));
        // Check enum declarations
        var enumDiagnostics = findAll(root, RuleId.EnumDecl.class)
                              .stream()
                              .flatMap(decl -> checkTypeName(decl, source, ctx));
        // Check record declarations
        var recordDiagnostics = findAll(root, RuleId.RecordDecl.class)
                                .stream()
                                .flatMap(decl -> checkTypeName(decl, source, ctx));
        // Check method declarations
        var methodDiagnostics = findAll(root, RuleId.MethodDecl.class)
                                .stream()
                                .flatMap(method -> checkMethodName(method, source, ctx));
        return Stream.of(classDiagnostics, interfaceDiagnostics, enumDiagnostics, recordDiagnostics, methodDiagnostics)
                     .flatMap(s -> s);
    }

    private Stream<Diagnostic> checkTypeName(CstNode decl, String source, LintContext ctx) {
        return childByRule(decl, RuleId.Identifier.class)
               .map(id -> text(id, source)
                          .trim())
               .filter(this::hasAcronymViolation)
               .map(name -> createDiagnostic(decl,
                                             "Type",
                                             name,
                                             suggestFix(name),
                                             ctx))
               .stream();
    }

    private Stream<Diagnostic> checkMethodName(CstNode method, String source, LintContext ctx) {
        return childByRule(method, RuleId.Identifier.class)
               .map(id -> text(id, source)
                          .trim())
               .filter(this::hasAcronymViolation)
               .map(name -> createDiagnostic(method,
                                             "Method",
                                             name,
                                             suggestFix(name),
                                             ctx))
               .stream();
    }

    private boolean hasAcronymViolation(String name) {
        return ACRONYM_PATTERN.matcher(name)
                              .find();
    }

    private String suggestFix(String name) {
        var result = new StringBuilder();
        var chars = name.toCharArray();
        for (int i = 0; i < chars.length; i++ ) {
            char c = chars[i];
            if (Character.isUpperCase(c)) {
                // Check if this is part of an acronym (2+ consecutive caps)
                int acronymEnd = i;
                while (acronymEnd < chars.length && Character.isUpperCase(chars[acronymEnd])) {
                    acronymEnd++ ;
                }
                int acronymLength = acronymEnd - i;
                if (acronymLength > 1) {
                    // Found acronym - convert to proper case
                    // If followed by lowercase, keep last char uppercase (e.g., HTTPClient → HttpClient)
                    if (acronymEnd < chars.length && Character.isLowerCase(chars[acronymEnd])) {
                        result.append(i == 0
                                      ? c
                                      : Character.toLowerCase(c));
                        for (int j = i + 1; j < acronymEnd - 1; j++ ) {
                            result.append(Character.toLowerCase(chars[j]));
                        }
                        if (acronymEnd > i + 1) {
                            result.append(chars[acronymEnd - 1]);
                        }
                        i = acronymEnd - 2;
                    }else {
                        // Acronym at end - lowercase all but first
                        result.append(i == 0
                                      ? c
                                      : Character.toLowerCase(c));
                        for (int j = i + 1; j < acronymEnd; j++ ) {
                            result.append(Character.toLowerCase(chars[j]));
                        }
                        i = acronymEnd - 1;
                    }
                }else {
                    result.append(c);
                }
            }else {
                result.append(c);
            }
        }
        return result.toString();
    }

    private Diagnostic createDiagnostic(CstNode node, String kind, String name, String suggested, LintContext ctx) {
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
