package org.pragmatica.jbct.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.pragmatica.jbct.cli.SkeletonCommandTest.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;


/// `skeleton --json` through the real command line: the output is parsed, never matched as text.
class SkeletonCommandJsonTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String QUOTED = "import java.util.List;\n\nclass Q {\n    List<@Cfg(\"q\\\"x\\\\y\") String> ret() { return null; }\n}\n";

    @TempDir
    Path dir;

    @Test
    void twoFiles_areOneParsableArray_withEscapedStringsAndNullDocRows() throws IOException {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        var first = Files.writeString(dir.resolve("Back\\slash.java"), QUOTED);
        var second = Files.writeString(dir.resolve("Second.java"), "class S {\n    void m() {}\n}\n");
        var run = run("skeleton", "--json", first.toString(), second.toString());
        var root = JSON.readTree(run.out());

        assertThat(run.exit()).isZero();
        assertThat(root.size()).isEqualTo(2);
        assertThat(root.get(0).get("file").asString()).isEqualTo(first.toString()).contains("\\");
        assertThat(root.get(1).get("file").asString()).isEqualTo(second.toString());
        assertThat(method(root.get(0)).get("signature").asString()).isEqualTo("(): List<@Cfg(\"q\\\"x\\\\y\") String>");
        assertThat(method(root.get(1)).get("docRow").isNull()).isTrue();
    }

    @Test
    void noJavaFiles_printsAnEmptyArray_andExitsTwo() {
        var run = run("skeleton", "--json", dir.toString());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(JSON.readTree(run.out()).isArray()).isTrue();
        assertThat(JSON.readTree(run.out()).size()).isZero();
        assertThat(run.err()).contains("No Java files found");
    }

    @Test
    void aFileThatFailedToParse_isAnEntryWithAnErrorAndItsPosition() throws IOException {
        var broken = Files.writeString(dir.resolve("Broken.java"), "class B {\n  void m( {\n}\n");
        var good = Files.writeString(dir.resolve("Good.java"), "class G {}\n");
        var run = run("skeleton", "--json", broken.toString(), good.toString());
        var root = JSON.readTree(run.out());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(root.size()).isEqualTo(2);
        assertThat(root.get(0).get("file").asString()).isEqualTo(broken.toString());
        assertThat(root.get(0).has("declarations")).isFalse();
        assertThat(root.get(0).get("error").get("message").asString()).isNotBlank();
        assertThat(root.get(0).get("error").get("row").asInt()).isPositive();
        assertThat(root.get(0).get("error").get("column").asInt()).isPositive();
        assertThat(root.get(1).get("declarations").size()).isEqualTo(1);
    }

    @Test
    void aMissingFile_isAnEntryWithAnErrorAndNoPosition() {
        var missing = dir.resolve("Gone.java");
        var root = JSON.readTree(run("skeleton", "--json", missing.toString()).out());

        assertThat(root.get(0).get("error").get("message").asString()).isEqualTo("no such file");
        assertThat(root.get(0).get("error").get("row").isNull()).isTrue();
    }

    private static JsonNode method(JsonNode file) {
        return file.get("declarations")
                   .get(0)
                   .get("children")
                   .get(0);
    }
}
