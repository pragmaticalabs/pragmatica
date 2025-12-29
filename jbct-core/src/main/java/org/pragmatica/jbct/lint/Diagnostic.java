package org.pragmatica.jbct.lint;

import org.pragmatica.lang.Option;

/**
 * A lint diagnostic representing a JBCT rule violation or suggestion.
 */
public record Diagnostic(
 String ruleId,
 DiagnosticSeverity severity,
 String file,
 int line,
 int column,
 String message,
 String details,
 Option<String> example,
 Option<String> docLink) {
    /**
     * Factory method for creating a diagnostic.
     */
    public static Diagnostic diagnostic(String ruleId,
                                        DiagnosticSeverity severity,
                                        String file,
                                        int line,
                                        int column,
                                        String message,
                                        String details) {
        return new Diagnostic(ruleId, severity, file, line, column, message, details, Option.none(), Option.none());
    }

    /**
     * Factory method for creating a diagnostic with example and doc link.
     */
    public static Diagnostic diagnostic(String ruleId,
                                        DiagnosticSeverity severity,
                                        String file,
                                        int line,
                                        int column,
                                        String message,
                                        String details,
                                        String example,
                                        String docLink) {
        return new Diagnostic(ruleId,
                              severity,
                              file,
                              line,
                              column,
                              message,
                              details,
                              Option.option(example),
                              Option.option(docLink));
    }

    /**
     * Builder-style method to add an example.
     */
    public Diagnostic withExample(String example) {
        return new Diagnostic(ruleId, severity, file, line, column, message, details, Option.option(example), docLink);
    }

    /**
     * Builder-style method to add a documentation link.
     */
    public Diagnostic withDocLink(String docLink) {
        return new Diagnostic(ruleId, severity, file, line, column, message, details, example, Option.option(docLink));
    }

    /**
     * Format as human-readable string.
     */
    public String toHumanReadable() {
        var sb = new StringBuilder();
        sb.append("%s:%d:%d: %s [%s] %s%n".formatted(file, line, column, severity, ruleId, message));
        if (!details.isEmpty()) {
            sb.append("  ")
              .append(details)
              .append("\n");
        }
        example.onPresent(ex -> {
                              sb.append("  Example:\n");
                              ex.lines()
                                .forEach(l -> sb.append("    ")
                                                .append(l)
                                                .append("\n"));
                          });
        docLink.onPresent(link -> sb.append("  See: ")
                                    .append(link)
                                    .append("\n"));
        return sb.toString();
    }

    /**
     * Format location as file:line:column.
     */
    public String location() {
        return "%s:%d:%d".formatted(file, line, column);
    }
}
