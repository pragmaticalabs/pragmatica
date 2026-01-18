package org.pragmatica.jbct.format;

import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.jbct.format.JbctFormatter.jbctFormatter;

class JbctFormatterTest {
    private final JbctFormatter formatter = jbctFormatter();

    @Test
    void format_producesValidOutput_forSimpleClass() {
        var source = new SourceFile(Path.of("Test.java"),
                                    """
                package com.example;

                public class Test {
                    public String hello() {
                        return "world";
                    }
                }
                """);
        var result = formatter.format(source);
        assertThat(result.isSuccess())
                  .as("Format should succeed")
                  .isTrue();
        var formatted = result.unwrap();
        assertThat(formatted.content())
                  .contains("package com.example;");
        assertThat(formatted.content())
                  .contains("public class Test");
        assertThat(formatted.content())
                  .contains("return \"world\"");
    }

    @Test
    void format_handlesMethodChains() {
        var source = new SourceFile(Path.of("ChainTest.java"),
                                    """
                package com.example;

                import org.pragmatica.lang.Result;

                public class ChainTest {
                    public Result<String> process() {
                        return Result.success("hello").map(String::toUpperCase).flatMap(s -> Result.success(s + "!"));
                    }
                }
                """);
        var result = formatter.format(source);
        assertThat(result.isSuccess())
                  .as("Format should succeed")
                  .isTrue();
        var formatted = result.unwrap();
        // Verify chain structure is preserved
        assertThat(formatted.content())
                  .contains("Result.success(\"hello\")");
        // Verify method chain operations are preserved
        assertThat(formatted.content())
                  .contains(".map(String::toUpperCase)");
        assertThat(formatted.content())
                  .contains(".flatMap(s -> Result.success(s + \"!\"))");
    }

    @Test
    void isFormatted_returnsTrue_forAlreadyFormattedCode() {
        var source = new SourceFile(Path.of("Formatted.java"),
                                    """
                package com.example;

                public class Formatted {
                }
                """);
        var result = formatter.format(source)
                              .flatMap(formatter::isFormatted);
        assertThat(result.isSuccess())
                  .as("Format check should succeed")
                  .isTrue();
        assertThat(result.unwrap())
                  .isTrue();
    }

    @Test
    void format_returnsParseError_forInvalidSyntax() {
        var source = new SourceFile(Path.of("Invalid.java"),
                                    """
                package com.example;

                public class Invalid {
                    // Missing closing brace
                """);
        var result = formatter.format(source);
        assertThat(result.isFailure())
                  .as("Format should fail for invalid syntax")
                  .isTrue();
        result.onFailure(cause -> assertThat(cause)
                                            .isInstanceOf(FormattingError.ParseError.class));
    }
}
