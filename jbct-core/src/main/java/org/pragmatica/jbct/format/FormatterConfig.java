package org.pragmatica.jbct.format;
/**
 * Configuration for the JBCT formatter.
 */
public record FormatterConfig(
 int maxLineLength,
 int indentSize,
 boolean useTabs,
 boolean alignChainedCalls,
 boolean alignArguments,
 boolean alignParameters,
 boolean organizeImports) {
    /**
     * Default JBCT formatting configuration.
     */
    public static final FormatterConfig DEFAULT = new FormatterConfig(
    120, 4, false, true, true, true, true);

    /**
     * Factory method for default config.
     */
    public static FormatterConfig defaultConfig() {
        return DEFAULT;
    }

    /**
     * Builder-style methods for customization.
     */
    public FormatterConfig withMaxLineLength(int maxLineLength) {
        return new FormatterConfig(maxLineLength,
                                   indentSize,
                                   useTabs,
                                   alignChainedCalls,
                                   alignArguments,
                                   alignParameters,
                                   organizeImports);
    }

    public FormatterConfig withIndentSize(int indentSize) {
        return new FormatterConfig(maxLineLength,
                                   indentSize,
                                   useTabs,
                                   alignChainedCalls,
                                   alignArguments,
                                   alignParameters,
                                   organizeImports);
    }

    public FormatterConfig withUseTabs(boolean useTabs) {
        return new FormatterConfig(maxLineLength,
                                   indentSize,
                                   useTabs,
                                   alignChainedCalls,
                                   alignArguments,
                                   alignParameters,
                                   organizeImports);
    }

    public FormatterConfig withAlignChainedCalls(boolean alignChainedCalls) {
        return new FormatterConfig(maxLineLength,
                                   indentSize,
                                   useTabs,
                                   alignChainedCalls,
                                   alignArguments,
                                   alignParameters,
                                   organizeImports);
    }

    public FormatterConfig withAlignArguments(boolean alignArguments) {
        return new FormatterConfig(maxLineLength,
                                   indentSize,
                                   useTabs,
                                   alignChainedCalls,
                                   alignArguments,
                                   alignParameters,
                                   organizeImports);
    }

    public FormatterConfig withAlignParameters(boolean alignParameters) {
        return new FormatterConfig(maxLineLength,
                                   indentSize,
                                   useTabs,
                                   alignChainedCalls,
                                   alignArguments,
                                   alignParameters,
                                   organizeImports);
    }

    public FormatterConfig withOrganizeImports(boolean organizeImports) {
        return new FormatterConfig(maxLineLength,
                                   indentSize,
                                   useTabs,
                                   alignChainedCalls,
                                   alignArguments,
                                   alignParameters,
                                   organizeImports);
    }

    /**
     * Get the indent string (spaces or tab).
     */
    public String indentString() {
        return useTabs
               ? "\t"
               : " ".repeat(indentSize);
    }
}
