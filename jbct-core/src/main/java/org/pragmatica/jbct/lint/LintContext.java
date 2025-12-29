package org.pragmatica.jbct.lint;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Context for lint analysis providing configuration.
 */
public record LintContext(
        List<Pattern> businessPackagePatterns,
        List<Pattern> slicePackagePatterns,
        LintConfig config,
        String fileName
) {

    /**
     * Check if a package name matches any business package pattern.
     */
    public boolean isBusinessPackage(String packageName) {
        return businessPackagePatterns.stream()
                .anyMatch(pattern -> pattern.matcher(packageName).matches());
    }

    /**
     * Check if a package name matches any slice package pattern.
     */
    public boolean isSlicePackage(String packageName) {
        return slicePackagePatterns.stream()
                .anyMatch(pattern -> pattern.matcher(packageName).matches());
    }

    /**
     * Check if slice packages are configured.
     */
    public boolean hasSlicePackages() {
        return !slicePackagePatterns.isEmpty();
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
                List.of(),  // No slice packages by default
                LintConfig.defaultConfig(),
                "Unknown.java"
        );
    }

    /**
     * Factory method with custom business package patterns.
     */
    public static LintContext lintContext(List<String> businessPackagePatterns) {
        var patterns = businessPackagePatterns.stream()
                .map(LintContext::globToRegex)
                .map(Pattern::compile)
                .toList();
        return new LintContext(patterns, List.of(), LintConfig.defaultConfig(), "Unknown.java");
    }

    private static String globToRegex(String glob) {
        // Use placeholder to avoid ** being affected by * replacement
        return glob.replace("**", "\0DOTSTAR\0")
                   .replace("*", "[^.]*")
                   .replace("\0DOTSTAR\0", ".*");
    }

    /**
     * Builder-style method to set config.
     */
    public LintContext withConfig(LintConfig config) {
        return new LintContext(businessPackagePatterns, slicePackagePatterns, config, fileName);
    }

    /**
     * Builder-style method to set file name.
     */
    public LintContext withFileName(String fileName) {
        return new LintContext(businessPackagePatterns, slicePackagePatterns, config, fileName);
    }

    /**
     * Builder-style method to set business package patterns from glob strings.
     */
    public LintContext withBusinessPackages(List<String> patterns) {
        var compiledPatterns = patterns.stream()
                .map(LintContext::globToRegex)
                .map(Pattern::compile)
                .toList();
        return new LintContext(compiledPatterns, slicePackagePatterns, config, fileName);
    }

    /**
     * Builder-style method to set slice package patterns from glob strings.
     */
    public LintContext withSlicePackages(List<String> patterns) {
        var compiledPatterns = patterns.stream()
                .map(LintContext::globToRegex)
                .map(Pattern::compile)
                .toList();
        return new LintContext(businessPackagePatterns, compiledPatterns, config, fileName);
    }

    /**
     * Factory method from JbctConfig.
     */
    public static LintContext fromConfig(org.pragmatica.jbct.config.JbctConfig jbctConfig) {
        return lintContext(jbctConfig.businessPackages())
               .withSlicePackages(jbctConfig.slicePackages())
               .withConfig(jbctConfig.lint());
    }
}
