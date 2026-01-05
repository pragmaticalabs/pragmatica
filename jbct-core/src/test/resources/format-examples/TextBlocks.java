package format.examples;
public class TextBlocks {
    // Simple text block
    private static final String SIMPLE = """
            Hello, World!
            """;

    // Multi-line text block
    private static final String MULTI_LINE = """
            First line
            Second line
            Third line
            """;

    // Text block with indentation
    private static final String INDENTED = """
            {
                "name": "John",
                "age": 30
            }
            """;

    // Text block in method
    public String getHtml() {
        return """
                <html>
                    <body>
                        <h1>Title</h1>
                    </body>
                </html>
                """;
    }

    // Text block with escape sequences
    private static final String ESCAPED = """
            Line with \t tab
            Line with \n newline literal
            Line with \\ backslash
            """;

    // Text block assigned in method
    public String buildQuery(String table) {
        var query = """
                SELECT *
                FROM %s
                WHERE active = true
                ORDER BY created_at DESC
                """.formatted(table);
        return query;
    }

    // Text block as method argument
    public void useTextBlock() {
        process("""
                Content passed
                as argument
                """);
    }

    // Text block in chain
    public String processTemplate() {
        return """
                Template content
                with multiple lines
                """.strip()
                   .indent(4);
    }

    // Empty text block
    private static final String EMPTY = """
            """;

    // Text block with trailing spaces (marked with \s)
    private static final String TRAILING = """
            Line with trailing\s
            Another line\s
            """;

    void process(String s) {}
}
