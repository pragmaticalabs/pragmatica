package org.pragmatica.jbct.format;

import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Result;

/**
 * Interface for JBCT code formatting operations.
 */
public interface Formatter {

    /**
     * Format a source file and return the formatted content.
     *
     * @param source the source file to format
     * @return Result containing the formatted source file, or an error
     */
    Result<SourceFile> format(SourceFile source);

    /**
     * Check if a source file is already properly formatted.
     *
     * @param source the source file to check
     * @return Result containing true if formatted, false otherwise, or an error
     */
    Result<Boolean> isFormatted(SourceFile source);

    /**
     * Get the configuration used by this formatter.
     */
    FormatterConfig config();
}
