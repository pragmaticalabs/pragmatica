package org.pragmatica.jbct.lint;
/**
 * Severity level for lint diagnostics.
 */
public enum DiagnosticSeverity {
    /**
     * Error - must be fixed, will cause check to fail.
     */
    ERROR,
    /**
     * Warning - should be fixed, but won't cause check to fail by default.
     */
    WARNING,
    /**
     * Info - informational message, suggestion for improvement.
     */
    INFO
}
