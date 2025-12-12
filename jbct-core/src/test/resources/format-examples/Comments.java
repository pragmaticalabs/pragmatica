package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

/**
 * Traditional JavaDoc comment for the class.
 * Multiple lines are supported.
 *
 * @param <T> type parameter description
 */
public class Comments<T> {
    /// New-style Markdown documentation comment.
    /// Supports **bold**, *italic*, and `code`.
    ///
    /// Code blocks:
    /// ```java
    /// var result = Comments.create("example");
    /// ```
    ///
    /// Lists:
    /// - First item
    /// - Second item
    /// - Third item
    ///
    /// @param value the input value
    ///
    /// @return the result
    public static Result<String> markdownDoc(String value) {
        return Result.success(value);
    }

    /// Short single-line Markdown doc.
    public String shortDoc() {
        return "";
    }

    /// Documentation with links.
    /// See [Result] for more information.
    /// Also see [Option#map(Function)] for transformation.
    ///
    /// @see Result
    /// @see Option
    public void withLinks() {}

    /**
     * Traditional JavaDoc with tags.
     *
     * @param first  first parameter description
     * @param second second parameter description
     *
     * @return the combined result
     *
     * @throws IllegalArgumentException if parameters are invalid
     *
     * @since 1.0
     * @deprecated Use {@link #markdownDoc(String)} instead
     */
    @Deprecated
    public String traditionalJavaDoc(String first, String second) {
        return first + second;
    }

    // Single-line comment
    private String field;

    // Comment before method
    public void methodWithComment() {
        var x = 1;
        if (x > 0) {
            process(x);
        }
    }

    /*
     * Block comment spanning
     * multiple lines for longer
     * explanations.
     */
    public void blockComment() {}

    /* Single-line block comment */
    public void singleLineBlock() {}

    // TODO: implement this feature
    public void todoComment() {}

    // FIXME: this has a bug
    public void fixmeComment() {}

    // NOTE: important consideration
    public void noteComment() {}

    /// Documentation for constant.
    /// Value represents the maximum allowed size.
    private static final int MAX_SIZE = 100;

    // inline comment
    private String name = "default";

    /// Factory method following JBCT naming convention.
    public static <T>Comments<T> comments() {
        return new Comments();
    }

    /// Instance method documentation.
    public T getValue() {
        return null;
    }

    // Inline comment at end of line
    public void inlineComments() {
        var a = 1;
        var b = 2;
        var c = a + b;
    }

    // Uses Result<T> for error handling
    public Result<String> codeInComment() {
        return Result.success("");
    }

    /// Markdown doc with parameter table:
    ///
    /// | Parameter | Type | Description |
    /// |-----------|------|-------------|
    /// | value | String | The input value |
    /// | option | Option | Optional config |
    ///
    /// @param value input value
    /// @param option optional configuration
    ///
    /// @return processed result
    public Result<String> tableDoc(String value, Option<String> option) {
        return Result.success(value);
    }

    /// Documented enum.
    enum Status {
        /// Pending state - waiting for processing.
        PENDING,
        /// Active state - currently processing.
        ACTIVE,
        /// Completed state - processing finished.
        COMPLETED
    }

    /// Documented record.
    ///
    /// @param id unique identifier
    /// @param name display name
    record Entity(String id, String name) {}

    // Stub methods
    void process(int x) {}
}
