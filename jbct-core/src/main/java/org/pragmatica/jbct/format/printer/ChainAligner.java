package org.pragmatica.jbct.format.printer;

import org.pragmatica.jbct.format.FormatterConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Aligns method chain continuations according to JBCT style.
 *
 * JBCT style aligns chained method calls to the end of the receiver expression,
 * not with a fixed +4 indent:
 *
 * <pre>
 * return ValidRequest.validRequest(request)
 *                    .async()                          // aligned to 'r' in validRequest
 *                    .flatMap(checkCredentials::apply)
 * </pre>
 *
 * This differs from typical formatters that use:
 * <pre>
 * return ValidRequest.validRequest(request)
 *     .async()                                         // +4 indent
 *     .flatMap(checkCredentials::apply)
 * </pre>
 */
public class ChainAligner {

    private static final Pattern METHOD_CALL_START = Pattern.compile(
            "^(\\s*)(return\\s+)?([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\.([a-z][A-Za-z0-9_]*)\\(");

    private static final Pattern CHAIN_CONTINUATION = Pattern.compile(
            "^(\\s*)\\.([a-z][A-Za-z0-9_]*)\\(");

    private final FormatterConfig config;

    private ChainAligner(FormatterConfig config) {
        this.config = config;
    }

    /**
     * Factory method.
     */
    public static ChainAligner chainAligner(FormatterConfig config) {
        return new ChainAligner(config);
    }

    /**
     * Align method chains in the given source code.
     */
    public String align(String source) {
        if (!config.alignChainedCalls()) {
            return source;
        }

        var lines = source.lines().toList();
        var result = new ArrayList<String>();
        int chainAlignColumn = -1;
        boolean inChain = false;

        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var trimmed = line.stripLeading();

            // Check if this line starts a method chain
            var startMatch = METHOD_CALL_START.matcher(line);
            if (startMatch.find()) {
                // Calculate where the chain should align
                // Find the position after the receiver (before the first method call)
                var receiver = startMatch.group(3);
                var leadingWhitespace = startMatch.group(1);
                var returnKeyword = startMatch.group(2) != null ? startMatch.group(2) : "";

                // Chain alignment column is at the end of receiver
                chainAlignColumn = leadingWhitespace.length() + returnKeyword.length() + receiver.length();
                inChain = endsWithOpenParenOrDot(line);
                result.add(line);
                continue;
            }

            // Check if this is a chain continuation (starts with .)
            if (trimmed.startsWith(".") && chainAlignColumn > 0 && inChain) {
                // Realign to the chain column
                var aligned = " ".repeat(chainAlignColumn) + trimmed;
                result.add(aligned);
                inChain = endsWithOpenParenOrDot(line);
                continue;
            }

            // Not part of a chain - reset
            if (!trimmed.isEmpty() && !trimmed.startsWith(".")) {
                chainAlignColumn = -1;
                inChain = false;
            }

            result.add(line);
        }

        return String.join("\n", result);
    }

    /**
     * Check if a line ends in a way that suggests chain continuation.
     */
    private boolean endsWithOpenParenOrDot(String line) {
        var trimmed = line.stripTrailing();
        return trimmed.endsWith("(") ||
               trimmed.endsWith(".") ||
               trimmed.endsWith(",") ||
               trimmed.endsWith("->") ||
               trimmed.endsWith("{");
    }

    /**
     * Detect if this is a multi-line method chain and compute alignment info.
     */
    public ChainInfo analyzeChain(List<String> lines, int startLine) {
        if (startLine >= lines.size()) {
            return ChainInfo.none();
        }

        var line = lines.get(startLine);
        var startMatch = METHOD_CALL_START.matcher(line);

        if (!startMatch.find()) {
            return ChainInfo.none();
        }

        var receiver = startMatch.group(3);
        var leadingWhitespace = startMatch.group(1);
        var returnKeyword = startMatch.group(2) != null ? startMatch.group(2) : "";

        int alignColumn = leadingWhitespace.length() + returnKeyword.length() + receiver.length();

        // Count continuation lines
        int chainLength = 1;
        for (int i = startLine + 1; i < lines.size(); i++) {
            var nextLine = lines.get(i).stripLeading();
            if (nextLine.startsWith(".")) {
                chainLength++;
            } else if (!nextLine.isEmpty()) {
                break;
            }
        }

        return new ChainInfo(true, alignColumn, chainLength);
    }

    /**
     * Information about a detected method chain.
     */
    public record ChainInfo(boolean isChain, int alignColumn, int chainLength) {
        public static ChainInfo none() {
            return new ChainInfo(false, 0, 0);
        }
    }
}
