package org.pragmatica.jbct.format.printer;

import org.pragmatica.jbct.format.FormatterConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Main source formatter that applies JBCT formatting rules.
 *
 * Key JBCT formatting patterns:
 * 1. Chain alignment: continuation `.` aligns to the `)` of first method call
 * 2. Arguments: align to opening `(`
 * 3. Ternary: always multi-line, `?` and `:` aligned under condition
 * 4. Boolean operators: operator at start of continuation, aligned
 * 5. Block lambdas: content indented, closing aligned with opening
 */
public class JbctSourceFormatter {

    private final FormatterConfig config;

    private JbctSourceFormatter(FormatterConfig config) {
        this.config = config;
    }

    public static JbctSourceFormatter jbctSourceFormatter(FormatterConfig config) {
        return new JbctSourceFormatter(config);
    }

    /**
     * Format source code according to JBCT style.
     */
    public String format(String source) {
        var lines = new ArrayList<>(source.lines().toList());

        // Apply formatting passes
        lines = normalizeWhitespace(lines);
        lines = formatChains(lines);
        lines = formatTernaries(lines);
        lines = formatBinaryOperators(lines);
        lines = formatArguments(lines);

        return joinLines(lines);
    }

    private ArrayList<String> normalizeWhitespace(List<String> lines) {
        var result = new ArrayList<String>();
        for (var line : lines) {
            result.add(line.stripTrailing());
        }
        return result;
    }

    /**
     * Format method chains: align `.` to the `)` of first method call.
     */
    private ArrayList<String> formatChains(List<String> lines) {
        var result = new ArrayList<String>();
        int chainAlignColumn = -1;
        boolean inChain = false;

        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var trimmed = line.stripLeading();
            var leadingSpaces = line.length() - trimmed.length();

            // Detect chain start: line with method call that continues
            if (!inChain && !trimmed.isEmpty()) {
                int alignCol = findChainAlignColumn(line);
                if (alignCol > 0 && i + 1 < lines.size()) {
                    var nextTrimmed = lines.get(i + 1).stripLeading();
                    if (nextTrimmed.startsWith(".")) {
                        chainAlignColumn = alignCol;
                        inChain = true;
                    }
                }
            }

            // Apply chain alignment
            if (inChain && trimmed.startsWith(".") && chainAlignColumn > 0) {
                result.add(" ".repeat(chainAlignColumn) + trimmed);

                // Check if chain continues
                if (i + 1 < lines.size()) {
                    var nextTrimmed = lines.get(i + 1).stripLeading();
                    if (!nextTrimmed.startsWith(".")) {
                        inChain = false;
                        chainAlignColumn = -1;
                    }
                } else {
                    inChain = false;
                    chainAlignColumn = -1;
                }
            } else {
                result.add(line);

                // Reset if line doesn't continue chain
                if (!trimmed.isEmpty() && !trimmed.startsWith(".") && !lineEndsInContinuation(trimmed)) {
                    inChain = false;
                    chainAlignColumn = -1;
                }
            }
        }

        return result;
    }

    /**
     * Find the column where chain continuation should align.
     * This is the column of the `)` of the first method call + 1 (for the `.`).
     */
    private int findChainAlignColumn(String line) {
        // Pattern: anything.methodName(...) or return anything.methodName(...)
        int firstDot = -1;
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            // Handle strings
            if ((c == '"' || c == '\'') && (i == 0 || line.charAt(i - 1) != '\\')) {
                if (!inString) {
                    inString = true;
                    stringChar = c;
                } else if (c == stringChar) {
                    inString = false;
                }
                continue;
            }
            if (inString) continue;

            if (c == '.' && depth == 0 && firstDot < 0) {
                // Check if this is a method call (followed by identifier and paren)
                if (isMethodCallDot(line, i)) {
                    firstDot = i;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                // If we're back to depth 0 after the first method call, this is our align point
                if (depth == 0 && firstDot >= 0) {
                    return i; // Align at the `)` position
                }
            }
        }

        return -1;
    }

    private boolean isMethodCallDot(String line, int dotPos) {
        // Check if character after dot is start of identifier
        if (dotPos + 1 >= line.length()) return false;
        char next = line.charAt(dotPos + 1);
        if (!Character.isJavaIdentifierStart(next)) return false;

        // Find the opening paren
        int i = dotPos + 2;
        while (i < line.length() && Character.isJavaIdentifierPart(line.charAt(i))) {
            i++;
        }
        // Skip whitespace
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return i < line.length() && line.charAt(i) == '(';
    }

    /**
     * Format ternary operators: always multi-line with `?` and `:` aligned.
     */
    private ArrayList<String> formatTernaries(List<String> lines) {
        // Ternaries are complex - for now, trust the input formatting
        // The golden examples show the expected format
        return new ArrayList<>(lines);
    }

    /**
     * Format binary operators (&&, ||, +, etc.): operator at start of continuation.
     */
    private ArrayList<String> formatBinaryOperators(List<String> lines) {
        // Binary operators at line start - for now, trust input
        return new ArrayList<>(lines);
    }

    /**
     * Format multi-line arguments: align to opening `(`.
     */
    private ArrayList<String> formatArguments(List<String> lines) {
        var result = new ArrayList<String>();
        int alignColumn = -1;
        int parenDepth = 0;
        boolean inMultiLineArgs = false;

        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            var trimmed = line.stripLeading();

            if (!inMultiLineArgs) {
                // Check for start of multi-line args
                int openParen = findUnclosedParen(line);
                if (openParen >= 0) {
                    alignColumn = openParen + 1;
                    parenDepth = countParenBalance(line);
                    inMultiLineArgs = parenDepth > 0;
                }
                result.add(line);
            } else {
                // In multi-line args context
                if (shouldAlignAsArgument(trimmed)) {
                    result.add(" ".repeat(alignColumn) + trimmed);
                } else {
                    result.add(line);
                }

                parenDepth += countParenBalance(line);
                if (parenDepth <= 0) {
                    inMultiLineArgs = false;
                    alignColumn = -1;
                }
            }
        }

        return result;
    }

    private int findUnclosedParen(String line) {
        int depth = 0;
        int lastUnclosedOpen = -1;
        boolean inString = false;
        char stringChar = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if ((c == '"' || c == '\'') && (i == 0 || line.charAt(i - 1) != '\\')) {
                if (!inString) {
                    inString = true;
                    stringChar = c;
                } else if (c == stringChar) {
                    inString = false;
                }
                continue;
            }
            if (inString) continue;

            if (c == '(') {
                if (depth == 0) {
                    lastUnclosedOpen = i;
                }
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    lastUnclosedOpen = -1;
                }
            }
        }

        return depth > 0 ? lastUnclosedOpen : -1;
    }

    private int countParenBalance(String line) {
        int balance = 0;
        boolean inString = false;
        char stringChar = 0;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if ((c == '"' || c == '\'') && (i == 0 || line.charAt(i - 1) != '\\')) {
                if (!inString) {
                    inString = true;
                    stringChar = c;
                } else if (c == stringChar) {
                    inString = false;
                }
                continue;
            }
            if (inString) continue;

            if (c == '(') balance++;
            else if (c == ')') balance--;
        }

        return balance;
    }

    private boolean shouldAlignAsArgument(String trimmed) {
        if (trimmed.isEmpty()) return false;
        char first = trimmed.charAt(0);
        return Character.isJavaIdentifierStart(first)
               || first == '"'
               || first == '\''
               || first == ')'
               || first == '('
               || first == '@'
               || Character.isDigit(first)
               || trimmed.startsWith("new ")
               || trimmed.startsWith("null")
               || trimmed.startsWith("true")
               || trimmed.startsWith("false");
    }

    private boolean lineEndsInContinuation(String trimmed) {
        return trimmed.endsWith("(")
               || trimmed.endsWith(",")
               || trimmed.endsWith(".")
               || trimmed.endsWith("->")
               || trimmed.endsWith("{")
               || trimmed.endsWith("&&")
               || trimmed.endsWith("||")
               || trimmed.endsWith("+")
               || trimmed.endsWith("-")
               || trimmed.endsWith("*")
               || trimmed.endsWith("/")
               || trimmed.endsWith("?")
               || trimmed.endsWith(":");
    }

    private String joinLines(List<String> lines) {
        if (lines.isEmpty()) return "";

        var sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) {
                sb.append("\n");
            }
        }

        // Ensure trailing newline
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '\n') {
            sb.append("\n");
        }

        return sb.toString();
    }
}
