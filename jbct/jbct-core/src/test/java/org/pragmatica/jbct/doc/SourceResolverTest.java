package org.pragmatica.jbct.doc;

import org.pragmatica.lang.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SourceResolverTest {
    @Test
    void resolve_readsEntryFromExplicitJar(@TempDir Path tempDir) throws Exception {
        var jar = writeSourcesJar(tempDir,
                                  "org/pragmatica/lang/Verify.java",
                                  """
                package org.pragmatica.lang;

                /// Validation entry point.
                public sealed interface Verify {
                }
                """);
        SourceResolver.resolve("org.pragmatica.lang.Verify", Option.option(jar), Option.none())
                      .onFailure(cause -> Assertions.fail(cause.message()))
                      .onSuccess(content -> assertThat(content).contains("/// Validation entry point."));
    }

    @Test
    void resolve_failsWithAttemptChain_whenEntryAbsent(@TempDir Path tempDir) throws Exception {
        var jar = writeSourcesJar(tempDir, "org/pragmatica/lang/Other.java", "package org.pragmatica.lang;\n");
        SourceResolver.resolve("org.pragmatica.lang.Missing", Option.option(jar), Option.none())
                      .onSuccess(content -> Assertions.fail("Expected failure but got content"))
                      .onFailure(cause -> assertThat(cause.message()).contains("org/pragmatica/lang/Missing.java"));
    }

    private static Path writeSourcesJar(Path dir, String entryName, String content) throws Exception {
        var jar = dir.resolve("core-test-sources.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(entryName));
            out.write(content.getBytes());
            out.closeEntry();
        }
        return jar;
    }
}
