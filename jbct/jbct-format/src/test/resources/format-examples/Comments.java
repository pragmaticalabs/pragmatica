package format.examples;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


public class Comments<T> {
    /// Example of Markdown JavaDoc
    /// @param value
    /// @return
    public static Result<String> markdownDoc(String value) {
        return Result.success(value);
    }

    // Short documentation
    public String shortDoc() {
        return "";
    }

    /**
     * JavaDoc comment with links inside
     * {@link org.pragmatica.jbct.format.flow.BlankLineRules}
     * https://dev.to
     */
    public void withLinks() {}

    /**
     * Classic JavaDoc comment
     *
     * @param first
     *        Some text inside
     * @param second
     *        Some more text
     */
    @Deprecated
    public String traditionalJavaDoc(String first, String second) {
        return first + second;
    }

    // Comment for field
    private String field;       // one more comment

    // Method with comment
    public void methodWithComment() {
        var x = 1;
        if (x > 0) {process(x);}
    }

    /*
    block comment
     */
    public void blockComment() {}

    /* single line block */
    public void singleLineBlock() {}

    // TODO: comment
    public void todoComment() {}

    // FIXME: comment
    public void fixmeComment() {}

    // NOTE: comment
    public void noteComment() {}

    // Constant comment
    private static final int MAX_SIZE = 100;

    // Initialized field comment
    private String name = "default";

    // Method comment
    public static <T> Comments<T> comments() {
        return new Comments(); // return value comment
    }

    /**
     * Method comment
     *
     * @return
     */
    public T getValue() {
        return null;
    }

    public void inlineComments() {
        var a = 1;  // inline comment 1
        var b = 2;  // inline comment 2
        var c = a + b; // inline comment 3
    }

    /** Here we have {@code void code()} in comment */
    /// More `void code() {}` in comment
    public Result<String> codeInComment() {
        return Result.success("");
    }

    /// | Element | Syntax | Description | Example |
    /// |--------|--------|------------|---------|
    /// | Class comment | `/** ... */` | Describes purpose of class | `/** Service for user management */` |
    /// | Method comment | `/** ... */` | Describes behavior, params, return | `/** Creates user */` |
    /// | `@param` | `@param name desc` | Documents method parameter | `@param id user identifier` |
    /// | `@return` | `@return desc` | Documents return value | `@return created user` |
    /// | `@throws` | `@throws Exception desc` | Documents thrown exception | `@throws IllegalStateException if exists` |
    /// | `@see` | `@see Class#method` | Reference to related API | `@see UserRepository#findById` |
    /// | `@since` | `@since version` | Version introduced | `@since 1.2` |
    /// | `@author` | `@author name` | Author info | `@author Sergiy` |
    /// | Inline link | `{@link ...}` | Inline reference | `{@link User}` |
    /// | Inline code | `{@code ...}` | Code snippet | `{@code null}` |
    /// | Deprecated | `@deprecated desc` | Marks deprecated API | `@deprecated use createNew()` |
    public Result<String> tableDoc(String value, Option<String> option) {
        return Result.success(value);
    }

    /**
     * <table>
     *   <tr><th>Element</th><th>Syntax</th><th>Description</th><th>Example</th></tr>
     *   <tr><td>@param</td><td>@param name desc</td><td>Parameter</td><td>@param id user identifier</td></tr>
     * </table>
     */
    enum Status {
        PENDING,
        ACTIVE,
        COMPLETED
    }

    record Entity(String id, String name) {}

    void process(int x) {}
}

