package org.pragmatica.jbct.format.printer;

import org.pragmatica.jbct.format.FormatterConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Aligns multi-line method arguments and parameters according to JBCT style.
 *
 * JBCT style aligns continuation arguments to the opening parenthesis:
 *
 * <pre>
 * return Result.all(Email.email(raw.email()),
 *                   Password.password(raw.password()),
 *                   ReferralCode.referralCode(raw.referral()))
 * </pre>
 *
 * And for method parameters:
 * <pre>
 * public static Result&lt;ValidRequest&gt; validRequest(Email email,
 *                                                  Password password,
 *                                                  Option&lt;ReferralCode&gt; referral)
 * </pre>
 */
public class ArgumentAligner {

    private static final Pattern METHOD_WITH_ARGS = Pattern.compile(
            "^(.*?)([a-zA-Z_][a-zA-Z0-9_]*)\\((.*)$");

    private static final Pattern PARAMETER_DECL = Pattern.compile(
            "^(\\s*)(public|private|protected|static|final|abstract|synchronized|native|strictfp|default|\\s)+.*\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\((.*)$");

    private final FormatterConfig config;

    private ArgumentAligner(FormatterConfig config) {
        this.config = config;
    }

    /**
     * Factory method.
     */
    public static ArgumentAligner argumentAligner(FormatterConfig config) {
        return new ArgumentAligner(config);
    }

    /**
     * Align multi-line arguments in the given source code.
     */
    public String align(String source) {
        if (!config.alignArguments()) {
            return source;
        }

        var lines = source.lines().toList();
        var result = new ArrayList<String>();
        int alignColumn = -1;
        int parenDepth = 0;
        boolean inMultiLineArgs = false;

        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);

            // Check if we're starting a multi-line argument list
            if (!inMultiLineArgs) {
                int openParenPos = findOpeningParen(line);
                if (openParenPos >= 0 && !isClosedOnSameLine(line, openParenPos)) {
                    // This line opens a paren that isn't closed on the same line
                    alignColumn = openParenPos + 1;
                    parenDepth = countOpenParens(line) - countCloseParens(line);
                    inMultiLineArgs = parenDepth > 0;
                    result.add(line);
                    continue;
                }
            }

            // We're in a multi-line argument context
            if (inMultiLineArgs && alignColumn > 0) {
                var trimmed = line.stripLeading();

                // Check if this is a continuation line (doesn't start with special chars)
                if (!trimmed.isEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("/*")) {
                    // Check if it looks like a continuation (starts with arg or closing paren)
                    if (looksLikeContinuation(trimmed)) {
                        var aligned = " ".repeat(alignColumn) + trimmed;
                        result.add(aligned);
                    } else {
                        result.add(line);
                    }
                } else {
                    result.add(line);
                }

                // Update paren depth
                parenDepth += countOpenParens(line) - countCloseParens(line);
                if (parenDepth <= 0) {
                    inMultiLineArgs = false;
                    alignColumn = -1;
                }
                continue;
            }

            result.add(line);
        }

        return String.join("\n", result);
    }

    /**
     * Find the position of the opening paren that starts a multi-line argument list.
     */
    private int findOpeningParen(String line) {
        int depth = 0;
        int lastOpenParen = -1;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(') {
                if (depth == 0) {
                    lastOpenParen = i;
                }
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }

        // Return the last unclosed open paren position
        return depth > 0 ? lastOpenParen : -1;
    }

    /**
     * Check if the parenthesis opened at the given position is closed on the same line.
     */
    private boolean isClosedOnSameLine(String line, int openPos) {
        int depth = 0;
        for (int i = openPos; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Count open parentheses in a line.
     */
    private int countOpenParens(String line) {
        return (int) line.chars().filter(c -> c == '(').count();
    }

    /**
     * Count close parentheses in a line.
     */
    private int countCloseParens(String line) {
        return (int) line.chars().filter(c -> c == ')').count();
    }

    /**
     * Check if a line looks like a continuation of arguments.
     */
    private boolean looksLikeContinuation(String trimmed) {
        // Starts with an identifier, closing paren, or common continuation patterns
        return Character.isJavaIdentifierStart(trimmed.charAt(0)) ||
               trimmed.startsWith(")") ||
               trimmed.startsWith("\"") ||
               trimmed.startsWith("'") ||
               trimmed.startsWith("new ") ||
               trimmed.startsWith("null") ||
               trimmed.startsWith("true") ||
               trimmed.startsWith("false") ||
               Character.isDigit(trimmed.charAt(0));
    }

    /**
     * Align method/constructor parameters.
     */
    public String alignParameters(String source) {
        if (!config.alignParameters()) {
            return source;
        }

        // Similar logic to arguments but for parameter declarations
        // For now, delegate to the same logic as it handles both cases
        return align(source);
    }
}
