package org.pragmatica.jbct.lint;

import java.util.List;
import java.util.regex.Pattern;

/// Context for lint analysis providing configuration.
public record LintContext(List<Pattern> excludedPackagePatterns,
                          List<Pattern> slicePackagePatterns,
                          LintConfig config,
                          String fileName) {
    public LintContext {
        excludedPackagePatterns = List.copyOf(excludedPackagePatterns);
        slicePackagePatterns = List.copyOf(slicePackagePatterns);
    }

    /// Check if a package should be linted (not in excluded list).
    public boolean shouldLint(String packageName) {
        if (excludedPackagePatterns.isEmpty()) {
            return true;
        }
        return excludedPackagePatterns.stream()
                                      .noneMatch(pattern -> pattern.matcher(packageName)
                                                                   .matches());
    }

    /// Check if a package name matches any slice package pattern.
    public boolean isSlicePackage(String packageName) {
        return slicePackagePatterns.stream()
                                   .anyMatch(pattern -> pattern.matcher(packageName)
                                                               .matches());
    }

    /// Check if slice packages are configured.
    public boolean hasSlicePackages() {
        return ! slicePackagePatterns.isEmpty();
    }

    /// Get the configured severity for a rule.
    public DiagnosticSeverity severityFor(String ruleId) {
        return config.ruleSeverities()
                     .getOrDefault(ruleId, DiagnosticSeverity.WARNING);
    }

    /// Check if a rule is enabled.
    public boolean isRuleEnabled(String ruleId) {
        return ! config.disabledRules()
                      .contains(ruleId);
    }

    /// Factory method with default configuration.
    public static LintContext defaultContext() {
        return new LintContext(List.of(),
                               List.of(),
                               LintConfig.defaultConfig(),
                               "Unknown.java");
    }

    /// Factory method with custom excluded package patterns.
    public static LintContext lintContext(List<String> excludePackages) {
        var patterns = excludePackages.stream()
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

    /// Builder-style method to set config.
    public LintContext withConfig(LintConfig config) {
        return new LintContext(excludedPackagePatterns, slicePackagePatterns, config, fileName);
    }

    /// Builder-style method to set file name.
    public LintContext withFileName(String fileName) {
        return new LintContext(excludedPackagePatterns, slicePackagePatterns, config, fileName);
    }

    /// Builder-style method to set excluded package patterns from glob strings.
    public LintContext withExcludePackages(List<String> patterns) {
        var compiledPatterns = patterns.stream()
                                       .map(LintContext::globToRegex)
                                       .map(Pattern::compile)
                                       .toList();
        return new LintContext(compiledPatterns, slicePackagePatterns, config, fileName);
    }

    /// Builder-style method to set slice package patterns from glob strings.
    public LintContext withSlicePackages(List<String> patterns) {
        var compiledPatterns = patterns.stream()
                                       .map(LintContext::globToRegex)
                                       .map(Pattern::compile)
                                       .toList();
        return new LintContext(excludedPackagePatterns, compiledPatterns, config, fileName);
    }

    /// Factory method from JbctConfig.
    public static LintContext fromConfig(org.pragmatica.jbct.config.JbctConfig jbctConfig) {
        return lintContext(jbctConfig.excludePackages()).withSlicePackages(jbctConfig.slicePackages())
                          .withConfig(jbctConfig.lint());
    }
}
