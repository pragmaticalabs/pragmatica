package org.pragmatica.jbct.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import static org.pragmatica.jbct.cli.SkeletonCommandTest.run;
import static org.assertj.core.api.Assertions.assertThat;


/// A file that cannot be read: one line on stderr, no stack trace, exit 2, the other files still print.
class SkeletonCommandFailureTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path dir;

    @Test
    void aMissingFile_isOneLineOnStderr_exitTwo_andTheOtherFilesStillPrint() throws IOException {
        var missing = dir.resolve("Gone.java");
        var good = Files.writeString(dir.resolve("Good.java"), "class G {}\n");
        var run = run("skeleton", missing.toString(), good.toString());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err().strip()).isEqualTo(missing + ": no such file");
        assertThat(run.err()).doesNotContain("Exception").doesNotContain("\tat ");
        assertThat(run.out()).contains("class G ");
    }

    @Test
    void aFileThatIsNotUtf8_isOneLineOnStderr() throws IOException {
        var latin = Files.write(dir.resolve("Latin.java"), new byte[]{'/', '/', (byte) 0xE9, (byte) 0xFF, '\n'});
        var run = run("skeleton", latin.toString());

        assertThat(run.exit()).isEqualTo(2);
        assertThat(run.err().strip()).isEqualTo(latin + ": not valid UTF-8");
    }

    @Test
    void aParseFailureAtRowSevenColumnThree_carriesRowThenColumn_onStderrAndInJson() throws IOException {
        var broken = Files.writeString(dir.resolve("Broken.java"),
                                       "class A {}\nclass B {}\nclass C {}\nclass D {}\nclass E {}\nclass F {}\n  )\n");
        var text = run("skeleton", broken.toString());
        var json = JSON.readTree(run("skeleton", "--json", broken.toString()).out()).get(0).get("error");

        assertThat(text.err()).startsWith(broken + ":7:3: ");
        assertThat(json.get("row").asInt()).isEqualTo(7);
        assertThat(json.get("column").asInt()).isEqualTo(3);
    }
}
