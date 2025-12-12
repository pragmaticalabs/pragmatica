package org.pragmatica.jbct.lint;

import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Context for lint analysis providing configuration and type resolution.
 */
public record LintContext(
        List<Pattern> businessPackagePatterns,
        Option<JavaSymbolSolver> symbolSolver,
        LintConfig config
) {

    /**
     * Check if a package name matches any business package pattern.
     */
    public boolean isBusinessPackage(String packageName) {
        return businessPackagePatterns.stream()
                .anyMatch(pattern -> pattern.matcher(packageName).matches());
    }

    /**
     * Get the configured severity for a rule.
     */
    public DiagnosticSeverity severityFor(String ruleId) {
        return config.ruleSeverities().getOrDefault(ruleId, DiagnosticSeverity.WARNING);
    }

    /**
     * Check if a rule is enabled.
     */
    public boolean isRuleEnabled(String ruleId) {
        return !config.disabledRules().contains(ruleId);
    }

    /**
     * Factory method with default configuration.
     */
    public static LintContext defaultContext() {
        return new LintContext(
                List.of(
                        Pattern.compile(".*\\.usecase\\..*"),
                        Pattern.compile(".*\\.domain\\..*")
                ),
                Option.none(),
                LintConfig.defaultConfig()
        );
    }

    /**
     * Factory method with custom business package patterns.
     */
    public static LintContext lintContext(List<String> businessPackagePatterns) {
        var patterns = businessPackagePatterns.stream()
                .map(p -> p.replace("**", ".*").replace("*", "[^.]*"))
                .map(Pattern::compile)
                .toList();
        return new LintContext(patterns, Option.none(), LintConfig.defaultConfig());
    }

    /**
     * Builder-style method to set symbol solver.
     */
    public LintContext withSymbolSolver(JavaSymbolSolver solver) {
        return new LintContext(businessPackagePatterns, Option.option(solver), config);
    }

    /**
     * Builder-style method to set config.
     */
    public LintContext withConfig(LintConfig config) {
        return new LintContext(businessPackagePatterns, symbolSolver, config);
    }
}
