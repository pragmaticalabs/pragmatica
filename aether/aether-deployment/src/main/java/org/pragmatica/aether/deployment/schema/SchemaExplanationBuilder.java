package org.pragmatica.aether.deployment.schema;

import java.util.List;

/// Builds natural language explanations for schema migration events.
/// Explanations are suitable for both human operators and LLM agents.
@SuppressWarnings("JBCT-UTIL-02")
public interface SchemaExplanationBuilder {
    static String buildFailedExplanation(String datasource,
                                         String artifactCoords,
                                         FailureClassification classification,
                                         String causeMessage,
                                         List<String> blockedSlices,
                                         int attemptNumber,
                                         int maxRetries,
                                         long nextRetryMs) {
        var sb = new StringBuilder();
        sb.append("Schema migration FAILED for datasource '")
          .append(datasource)
          .append("' (artifact '")
          .append(artifactCoords)
          .append("').\n\n");
        appendCauseLine(sb, causeMessage, attemptNumber, maxRetries);
        appendClassificationLine(sb, classification, nextRetryMs);
        appendImpactSection(sb, blockedSlices);
        appendOptionsSection(sb, classification, datasource, attemptNumber, maxRetries, nextRetryMs);
        return sb.toString();
    }

    static String buildRetryingExplanation(String datasource,
                                           String artifactCoords,
                                           int attemptNumber,
                                           long nextRetryMs) {
        return "Schema migration for datasource '" + datasource + "' (artifact '" + artifactCoords
               + "') — scheduling retry attempt " + (attemptNumber + 1) + " in " + (nextRetryMs / 1000) + "s.";
    }

    private static void appendCauseLine(StringBuilder sb,
                                        String causeMessage,
                                        int attemptNumber,
                                        int maxRetries) {
        sb.append("Cause: ")
          .append(causeMessage)
          .append(" (attempt ")
          .append(attemptNumber)
          .append("/")
          .append(maxRetries)
          .append(").\n");
    }

    private static void appendClassificationLine(StringBuilder sb,
                                                 FailureClassification classification,
                                                 long nextRetryMs) {
        sb.append("Classification: ")
          .append(classification.name());
        if (classification == FailureClassification.TRANSIENT && nextRetryMs > 0) {
            sb.append(" — automatic retry scheduled in ")
              .append(nextRetryMs / 1000)
              .append("s");
        } else if (classification == FailureClassification.PERMANENT) {
            sb.append(" — automatic retry will not help");
        }
        sb.append(".\n\n");
    }

    private static void appendImpactSection(StringBuilder sb, List<String> blockedSlices) {
        sb.append("Impact: ")
          .append(blockedSlices.size())
          .append(" slices blocked in LOADED state");
        if (blockedSlices.isEmpty()) {
            sb.append(".\n\n");
            return;
        }
        sb.append(":\n");
        blockedSlices.forEach(slice -> sb.append("  - ")
                                         .append(slice)
                                         .append("\n"));
        sb.append("\n");
    }

    private static void appendOptionsSection(StringBuilder sb,
                                             FailureClassification classification,
                                             String datasource,
                                             int attemptNumber,
                                             int maxRetries,
                                             long nextRetryMs) {
        sb.append("Options:\n");
        var optionIndex = 1;
        if (classification == FailureClassification.TRANSIENT && attemptNumber < maxRetries) {
            sb.append("  ")
              .append(optionIndex++)
              .append(". Wait for automatic retry (attempt ")
              .append(attemptNumber + 1)
              .append("/")
              .append(maxRetries)
              .append(" in ")
              .append(nextRetryMs / 1000)
              .append("s)\n");
        }
        sb.append("  ")
          .append(optionIndex++)
          .append(". Fix the underlying issue and retry: POST /api/schema/")
          .append(datasource)
          .append("/retry\n");
        sb.append("  ")
          .append(optionIndex)
          .append(". Skip schema gate: redeploy with schema_required=false\n");
    }
}
