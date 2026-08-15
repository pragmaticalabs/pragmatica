package org.pragmatica.jbct.lint.cst;

import java.util.*;
import java.util.regex.Pattern;

import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.LineIndex;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// Extracts @SuppressWarnings, @Contract, @TerminalOperation, and @NullReturn annotations
/// and determines which rules are suppressed at which locations.
///
/// Supports both standard suppressions and JBCT rule IDs:
/// - @SuppressWarnings("JBCT-RET-01") - single rule
/// - @SuppressWarnings({"JBCT-RET-01", "JBCT-RET-02"}) - multiple rules
/// - @SuppressWarnings("all") - suppresses all JBCT rules
/// - @Contract - suppresses all JBCT rules (method signature dictated by external API contract)
/// - @TerminalOperation - suppresses JBCT-PAT-03 (intentional blocking)
/// - @NullReturn - suppresses JBCT-RET-03 (intentional null return)
public final class SuppressionExtractor {
    private static final Pattern JBCT_RULE_PATTERN = Pattern.compile("JBCT-[A-Z]+-\\d+");

    private SuppressionExtractor() {}

    /// A suppression scope with the suppressed rules and line range.
    public record Suppression(Set<String> ruleIds, int startLine, int endLine) {
        public static Suppression suppression(Set<String> ruleIds, int startLine, int endLine) {
            return new Suppression(Set.copyOf(ruleIds), startLine, endLine);
        }

        public boolean suppressesAll() {
            return ruleIds.contains("all");
        }

        public boolean suppressesRule(String ruleId) {
            return suppressesAll() || ruleIds.contains(ruleId);
        }

        public boolean coversLine(int line) {
            return line >= startLine && line <= endLine;
        }

        public boolean suppresses(String ruleId, int line) {
            return coversLine(line) && suppressesRule(ruleId);
        }
    }

    private static final Set<String> CONTRACT_NAMES = Set.of("Contract", "org.pragmatica.lang.Contract");
    private static final Set<String> CONTRACT_SUPPRESSED_RULES = Set.of("all");

    private static final Set<String> TERMINAL_OP_NAMES = Set.of("TerminalOperation",
                                                                "org.pragmatica.lang.TerminalOperation");

    private static final Set<String> TERMINAL_OP_SUPPRESSED = Set.of("JBCT-PAT-03");
    private static final Set<String> NULL_RETURN_NAMES = Set.of("NullReturn", "org.pragmatica.lang.NullReturn");
    private static final Set<String> NULL_RETURN_SUPPRESSED = Set.of("JBCT-RET-03");

    /// Extract all suppressions from a CST.
    public static List<Suppression> extractSuppressions(Cursor root, String source) {
        var suppressions = new ArrayList<Suppression>();
        var lines = new LineIndex(source);
        var src = source;
        var annotations = findAll(root, RuleKind.ANNOTATION);

        for (var annotation : annotations) {
            var name = findFirst(annotation, RuleKind.QUALIFIED_NAME).map(qn -> text(qn).trim()).or("");

            if (CONTRACT_NAMES.contains(name)) {
                addScope(root, annotation, lines, src, CONTRACT_SUPPRESSED_RULES, suppressions);
                continue;
            }

            if (TERMINAL_OP_NAMES.contains(name)) {
                addScope(root, annotation, lines, src, TERMINAL_OP_SUPPRESSED, suppressions);
                continue;
            }

            if (NULL_RETURN_NAMES.contains(name)) {
                addScope(root, annotation, lines, src, NULL_RETURN_SUPPRESSED, suppressions);
                continue;
            }

            if (!"SuppressWarnings".equals(name) && !"java.lang.SuppressWarnings".equals(name)) {
                continue;
            }

            var ruleIds = extractRuleIds(annotation);

            if (ruleIds.isEmpty()) {
                continue;
            }

            addScope(root, annotation, lines, src, ruleIds, suppressions);
        }

        return suppressions;
    }

    private static void addScope(Cursor root,
                                 Cursor annotation,
                                 LineIndex lines,
                                 String source,
                                 Set<String> ruleIds,
                                 ArrayList<Suppression> out) {
        findAnnotatedDeclaration(root, annotation).onPresent(scope -> out.add(Suppression.suppression(ruleIds,
                                                                                                      lines.lineAt(scope.spanStart()),
                                                                                                      lines.lineAt(lastContentOffset(source,
                                                                                                                                     scope.spanStart(),
                                                                                                                                     scope.spanEnd())))));
    }

    /// Position of the last non-whitespace character within [start, end). Used to
    /// compute a scope's effective end line, excluding trailing whitespace that
    /// would otherwise spill into the next sibling's line.
    private static int lastContentOffset(String source, int start, int end) {
        int i = Math.min(end, source.length()) - 1;

        while (i > start && Character.isWhitespace(source.charAt(i))) {
            i--;
        }

        return Math.max(i, start);
    }

    /// Check if a rule is suppressed at a specific line.
    public static boolean isSuppressed(List<Suppression> suppressions, String ruleId, int line) {
        for (var suppression : suppressions) {
            if (suppression.suppresses(ruleId, line)) {
                return true;
            }
        }

        return false;
    }

    private static Set<String> extractRuleIds(Cursor annotation) {
        var ruleIds = new HashSet<String>();
        var annotationText = text(annotation);

        if (annotationText.contains("\"all\"")) {
            ruleIds.add("all");

            return ruleIds;
        }

        var matcher = JBCT_RULE_PATTERN.matcher(annotationText);

        while (matcher.find()) {
            ruleIds.add(matcher.group());
        }

        return ruleIds;
    }

    private static Option<Cursor> findAnnotatedDeclaration(Cursor root, Cursor annotation) {
        return findAncestorPath(root, annotation).flatMap(SuppressionExtractor::findDeclarationInPath);
    }

    private static Option<Cursor> findDeclarationInPath(List<Cursor> path) {
        for (int i = path.size() - 1; i >= 0; i--) {
            var node = path.get(i);

            if (node.kindIs(RuleKind.TYPE_DECL) || node.kindIs(RuleKind.TYPE_KIND)) {
                return Option.some(node);
            }

            if (isMemberLevel(node)) {
                return Option.some(outermostClassMember(path, i));
            }

            if (node.kindIs(RuleKind.LOCAL_VAR) || node.kindIsAny(RuleKind.PLAIN_PARAM, RuleKind.LAST_PARAM, RuleKind.RECEIVER_PARAM)) {
                return Option.some(node);
            }
        }

        return Option.none();
    }

    private static Cursor outermostClassMember(List<Cursor> path, int startIndex) {
        var result = path.get(startIndex);

        for (int i = startIndex - 1; i >= 0; i--) {
            var parent = path.get(i);

            if (isMemberLevel(parent)) {
                result = parent;
            } else {
                break;
            }
        }

        return result;
    }
}
