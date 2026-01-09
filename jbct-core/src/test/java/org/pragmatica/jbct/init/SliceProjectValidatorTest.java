package org.pragmatica.jbct.init;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SliceProjectValidatorTest {
    @TempDir
    Path tempDir;

    @Test
    void should_report_error_when_pom_missing() {
        var validator = SliceProjectValidator.sliceProjectValidator(tempDir);
        var result = validator.validate();
        assertThat(result.hasErrors())
                  .isTrue();
        assertThat(result.errors())
                  .anyMatch(e -> e.contains("pom.xml not found"));
    }

    @Test
    void should_report_warning_when_pom_missing_slice_class() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"),
                          """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <groupId>com.test</groupId>
                <artifactId>test</artifactId>
            </project>
            """);
        var validator = SliceProjectValidator.sliceProjectValidator(tempDir);
        var result = validator.validate();
        assertThat(result.hasWarnings())
                  .isTrue();
        assertThat(result.warnings())
                  .anyMatch(w -> w.contains("slice.class"));
    }

    @Test
    void should_report_warning_when_target_not_compiled() throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"),
                          """
            <?xml version="1.0" encoding="UTF-8"?>
            <project>
                <properties>
                    <slice.class>com.test.TestSlice</slice.class>
                </properties>
            </project>
            """);
        var validator = SliceProjectValidator.sliceProjectValidator(tempDir);
        var result = validator.validate();
        assertThat(result.hasWarnings())
                  .isTrue();
        assertThat(result.warnings())
                  .anyMatch(w -> w.contains("target/classes"));
    }
}
