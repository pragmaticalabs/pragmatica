package org.pragmatica.jbct.format.printer;

import com.github.javaparser.ast.CompilationUnit;
import org.pragmatica.jbct.format.FormatterConfig;

/**
 * Custom printer for JBCT formatting style.
 *
 * Key style features:
 * - Method chain continuation aligned to receiver end
 * - Multi-line arguments aligned to opening paren
 * - 120 character max line length
 * - 4 space indentation
 */
public class JbctPrinter {

    private final FormatterConfig config;

    private JbctPrinter(FormatterConfig config) {
        this.config = config;
    }

    /**
     * Factory method to create a JBCT printer.
     */
    public static JbctPrinter jbctPrinter(FormatterConfig config) {
        return new JbctPrinter(config);
    }

    /**
     * Print the AST to a formatted string.
     */
    public String print(CompilationUnit cu) {
        var visitor = new JbctPrettyPrinterVisitor(config);
        cu.accept(visitor, null);
        return ensureTrailingNewline(visitor.getOutput());
    }

    /**
     * Ensure the file ends with exactly one newline.
     */
    private String ensureTrailingNewline(String output) {
        if (output.isEmpty()) {
            return output;
        }

        // Remove trailing newlines
        while (output.endsWith("\n")) {
            output = output.substring(0, output.length() - 1);
        }

        // Add exactly one
        return output + "\n";
    }
}
