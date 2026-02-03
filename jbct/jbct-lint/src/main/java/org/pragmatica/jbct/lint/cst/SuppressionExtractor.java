package org.pragmatica.jbct.lint.cst;

import org.pragmatica.jbct.parser.Java25Parser.CstNode;
import org.pragmatica.jbct.parser.Java25Parser.RuleId;
import org.pragmatica.lang.Option;

import java.util.*;
import java.util.regex.Pattern;

import static org.pragmatica.jbct.parser.CstNodes.*;

/**
 * Extracts @SuppressWarnings annotations and determines which rules are suppressed at which locations.
 *
 * Supports both standard suppressions and JBCT rule IDs:
 * - @SuppressWarnings("JBCT-RET-01") - single rule
 * - @SuppressWarnings({"JBCT-RET-01", "JBCT-RET-02"}) - multiple rules
 * - @SuppressWarnings("all") - suppresses all JBCT rules
 */
public final class SuppressionExtractor {
    private static final Pattern JBCT_RULE_PATTERN = Pattern.compile("JBCT-[A-Z]+-\\d+");

    private SuppressionExtractor() {}

    /**
     * A suppression scope with the suppressed rules and line range.
     */
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

    /**
     * Extract all suppressions from a CST.
     */
    public static List<Suppression> extractSuppressions(CstNode root, String source) {
        var suppressions = new ArrayList<Suppression>();
        // Find all annotations
        var annotations = findAll(root, RuleId.Annotation.class);
        for (var annotation : annotations) {
            // Check if this is @SuppressWarnings
            var name = findFirst(annotation, RuleId.QualifiedName.class).map(qn -> text(qn, source).trim())
                                .or("");
            if (!"SuppressWarnings".equals(name) && !"java.lang.SuppressWarnings".equals(name)) {
                continue;
            }
            // Extract suppressed rule IDs from annotation value
            var ruleIds = extractRuleIds(annotation, source);
            if (ruleIds.isEmpty()) {
                continue;
            }
            // Find the scope (declaration that this annotation applies to)
            findAnnotatedDeclaration(root, annotation)
                .onPresent(scopeNode -> {
                               var startLine = startLine(scopeNode);
                               var endLine = endLine(scopeNode);
                               suppressions.add(Suppression.suppression(ruleIds, startLine, endLine));
                           });
        }
        return suppressions;
    }

    /**
     * Check if a rule is suppressed at a specific line.
     */
    public static boolean isSuppressed(List<Suppression> suppressions, String ruleId, int line) {
        for (var suppression : suppressions) {
            if (suppression.suppresses(ruleId, line)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> extractRuleIds(CstNode annotation, String source) {
        var ruleIds = new HashSet<String>();
        // Get annotation value (could be single string or array)
        var annotationText = text(annotation, source);
        // Check for "all" suppression
        if (annotationText.contains("\"all\"")) {
            ruleIds.add("all");
            return ruleIds;
        }
        // Find JBCT rule IDs in the annotation text
        var matcher = JBCT_RULE_PATTERN.matcher(annotationText);
        while (matcher.find()) {
            ruleIds.add(matcher.group());
        }
        return ruleIds;
    }

    private static Option<CstNode> findAnnotatedDeclaration(CstNode root, CstNode annotation) {
        // Walk up the tree from the annotation to find what it annotates
        // Annotations can appear on: TypeDecl, ClassMember, Param, LocalVar, etc.
        return findAncestorPath(root, annotation).flatMap(SuppressionExtractor::findDeclarationInPath);
    }

    private static Option<CstNode> findDeclarationInPath(List<CstNode> path) {
        // Walk up the path looking for a declaration
        for (int i = path.size() - 1; i >= 0; i--) {
            var node = path.get(i);
            var rule = node.rule();
            // Type declarations
            if (rule instanceof RuleId.TypeDecl ||
            rule instanceof RuleId.ClassDecl ||
            rule instanceof RuleId.InterfaceDecl ||
            rule instanceof RuleId.EnumDecl ||
            rule instanceof RuleId.RecordDecl) {
                return Option.some(node);
            }
            // Class members
            if (rule instanceof RuleId.ClassMember ||
            rule instanceof RuleId.MethodDecl ||
            rule instanceof RuleId.FieldDecl ||
            rule instanceof RuleId.ConstructorDecl) {
                return Option.some(node);
            }
            // Local declarations
            if (rule instanceof RuleId.LocalVar ||
            rule instanceof RuleId.Param) {
                return Option.some(node);
            }
        }
        return Option.none();
    }

    private static int endLine(CstNode node) {
        return node.span()
                   .end()
                   .line();
    }
}
