package org.pragmatica.jbct.lint.cst.rules;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;
import org.pragmatica.lang.Option;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * JBCT-UTIL-01: Use Pragmatica parsing utilities instead of JDK parsing methods.
 *
 * Detects direct use of Integer.parseInt(), Long.parseLong(), etc. and suggests
 * using Number.parseInt(), Number.parseLong() which return Result&lt;T&gt; for
 * composable error handling.
 */
public class CstParsingUtilitiesRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-UTIL-01";

    // Map of JDK parsing patterns to Pragmatica alternatives
    private static final List<ParsingPattern> PATTERNS = List.of(// Number parsing
    new ParsingPattern("Integer\\.parseInt", "Number.parseInt", "org.pragmatica.lang.utils.Number"),
    new ParsingPattern("Integer\\.parseUnsignedInt", "Number.parseInt", "org.pragmatica.lang.utils.Number"),
    new ParsingPattern("Long\\.parseLong", "Number.parseLong", "org.pragmatica.lang.utils.Number"),
    new ParsingPattern("Long\\.parseUnsignedLong", "Number.parseLong", "org.pragmatica.lang.utils.Number"),
    new ParsingPattern("Short\\.parseShort", "Number.parseShort", "org.pragmatica.lang.utils.Number"),
    new ParsingPattern("Byte\\.parseByte", "Number.parseByte", "org.pragmatica.lang.utils.Number"),
    new ParsingPattern("Float\\.parseFloat", "Number.parseFloat", "org.pragmatica.lang.utils.Number"),
    new ParsingPattern("Double\\.parseDouble", "Number.parseDouble", "org.pragmatica.lang.utils.Number"),
    new ParsingPattern("new BigInteger\\s*\\(", "Number.parseBigInteger", "org.pragmatica.lang.utils.Number"),
    new ParsingPattern("new BigDecimal\\s*\\(", "Number.parseBigDecimal", "org.pragmatica.lang.utils.Number"),
    // Text/Pattern parsing
    new ParsingPattern("Enum\\.valueOf", "Text.parseEnum", "org.pragmatica.lang.utils.Text"),
    new ParsingPattern("Pattern\\.compile", "Text.compilePattern", "org.pragmatica.lang.utils.Text"),
    new ParsingPattern("Base64\\.getDecoder\\(\\)\\.decode", "Text.decodeBase64", "org.pragmatica.lang.utils.Text"),
    new ParsingPattern("Base64\\.getUrlDecoder\\(\\)\\.decode", "Text.decodeBase64URL", "org.pragmatica.lang.utils.Text"),
    new ParsingPattern("Base64\\.getMimeDecoder\\(\\)\\.decode",
                       "Text.decodeBase64MIME",
                       "org.pragmatica.lang.utils.Text"),
    // DateTime parsing
    new ParsingPattern("LocalDate\\.parse", "DateTime.parseLocalDate", "org.pragmatica.lang.utils.DateTime"),
    new ParsingPattern("LocalTime\\.parse", "DateTime.parseLocalTime", "org.pragmatica.lang.utils.DateTime"),
    new ParsingPattern("LocalDateTime\\.parse", "DateTime.parseLocalDateTime", "org.pragmatica.lang.utils.DateTime"),
    new ParsingPattern("ZonedDateTime\\.parse", "DateTime.parseZonedDateTime", "org.pragmatica.lang.utils.DateTime"),
    new ParsingPattern("OffsetDateTime\\.parse", "DateTime.parseOffsetDateTime", "org.pragmatica.lang.utils.DateTime"),
    new ParsingPattern("OffsetTime\\.parse", "DateTime.parseOffsetTime", "org.pragmatica.lang.utils.DateTime"),
    new ParsingPattern("Instant\\.parse", "DateTime.parseInstant", "org.pragmatica.lang.utils.DateTime"),
    new ParsingPattern("Duration\\.parse", "DateTime.parseDuration", "org.pragmatica.lang.utils.DateTime"),
    new ParsingPattern("Period\\.parse", "DateTime.parsePeriod", "org.pragmatica.lang.utils.DateTime"),
    // Network/Identifier parsing
    new ParsingPattern("new URL\\s*\\(", "Network.parseURL", "org.pragmatica.lang.utils.Network"),
    new ParsingPattern("URI\\.create", "Network.parseURI", "org.pragmatica.lang.utils.Network"),
    new ParsingPattern("new URI\\s*\\(", "Network.parseURI", "org.pragmatica.lang.utils.Network"),
    new ParsingPattern("UUID\\.fromString", "Network.parseUUID", "org.pragmatica.lang.utils.Network"),
    new ParsingPattern("InetAddress\\.getByName", "Network.parseInetAddress", "org.pragmatica.lang.utils.Network"),
    // I18n parsing
    new ParsingPattern("Currency\\.getInstance", "I18n.parseCurrency", "org.pragmatica.lang.utils.I18n"),
    new ParsingPattern("Locale\\.forLanguageTag", "I18n.parseLocale", "org.pragmatica.lang.utils.I18n"),
    new ParsingPattern("Charset\\.forName", "I18n.parseCharset", "org.pragmatica.lang.utils.I18n"),
    new ParsingPattern("ZoneId\\.of\\s*\\(", "I18n.parseZoneId", "org.pragmatica.lang.utils.I18n"),
    new ParsingPattern("ZoneOffset\\.of\\s*\\(", "I18n.parseZoneOffset", "org.pragmatica.lang.utils.I18n"));

    // Combined regex for all patterns
    private static final Pattern COMBINED_PATTERN;
    private static final Map<String, ParsingPattern> PATTERN_MAP;

    static {
        var patternStrings = PATTERNS.stream()
                                     .map(p -> "(" + p.jdkPattern() + ")")
                                     .toList();
        COMBINED_PATTERN = Pattern.compile(String.join("|", patternStrings));
        var mapBuilder = new HashMap<String, ParsingPattern>();
        for (var p : PATTERNS) {
            mapBuilder.put(p.jdkPattern(), p);
        }
        PATTERN_MAP = Map.copyOf(mapBuilder);
    }

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(CstNode root, String source, LintContext ctx) {
        var packageName = findFirst(root, RuleId.PackageDecl.class).flatMap(pd -> findFirst(pd,
                                                                                            RuleId.QualifiedName.class))
                                   .map(qn -> text(qn, source))
                                   .or("");
        if (!ctx.isBusinessPackage(packageName)) {
            return Stream.empty();
        }
        return findAll(root, RuleId.MethodDecl.class).stream()
                      .flatMap(method -> findJdkParsing(method, source, ctx));
    }

    private Stream<Diagnostic> findJdkParsing(CstNode method, String source, LintContext ctx) {
        var methodText = text(method, source);
        var matcher = COMBINED_PATTERN.matcher(methodText);
        return Stream.iterate(matcher.find(),
                              found -> found,
                              found -> matcher.find())
                     .map(_ -> matcher.group())
                     .map(this::findMatchingPattern)
                     .filter(Option::isPresent)
                     .map(Option::unwrap)
                     .map(pattern -> createDiagnostic(method, pattern, ctx))
                     .limit(3);
    }

    private Option<ParsingPattern> findMatchingPattern(String match) {
        for (var entry : PATTERN_MAP.entrySet()) {
            if (Pattern.matches(entry.getKey(), match)) {
                return Option.some(entry.getValue());
            }
        }
        // Direct lookup for exact matches
        for (var pattern : PATTERNS) {
            if (match.contains(pattern.jdkPattern()
                                      .replace("\\s*", "")
                                      .replace("\\(",
                                               "("))) {
                return Option.some(pattern);
            }
        }
        return PATTERNS.stream()
                       .filter(p -> match.matches(".*" + p.jdkPattern() + ".*"))
                       .findFirst()
                       .map(Option::some)
                       .orElse(Option.none());
    }

    private Diagnostic createDiagnostic(CstNode node, ParsingPattern pattern, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(node),
                                     startColumn(node),
                                     "Use " + pattern.pragmaticaMethod() + "() instead of JDK parsing",
                                     "Pragmatica parsing utilities return Result<T> for composable error handling. "
                                     + "Import from " + pattern.importPath() + ".");
    }

    private record ParsingPattern(String jdkPattern, String pragmaticaMethod, String importPath) {}
}
