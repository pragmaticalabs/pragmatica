package format.examples;

public class TextBlocks {
    private static final String SIMPLE = """
            Hello, World!
            """;

    private static final String MULTI_LINE = """
            First line
            Second line
            Third line
            """;

    private static final String INDENTED = """
            {
                "name": "John",
                "age": 30
            }
            """;

    public String getHtml() {
        return """
                <html>
                    <body>
                        <h1>Title</h1>
                    </body>
                </html>
                """;
    }

    private static final String ESCAPED = """
            Line with \t tab
            Line with \n newline literal
            Line with \\ backslash
            """;

    public String buildQuery(String table) {
        var query = """
                SELECT *
                FROM %s
                WHERE active = true
                ORDER BY created_at DESC
                """.formatted(table);
        return query;
    }

    public void useTextBlock() {
        process("""
                Content passed
                as argument
                """);
    }

    public String processTemplate() {
        return """
                Template content
                with multiple lines
                """.strip()
                   .indent(4);
    }

    private static final String EMPTY = """
            """;

    private static final String TRAILING = """
            Line with trailing\s
            Another line\s
            """;

    void process(String s) {}
}
