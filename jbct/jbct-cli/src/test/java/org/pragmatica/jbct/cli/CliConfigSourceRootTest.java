package org.pragmatica.jbct.cli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/// #2525, Q11: a checked path reads its own module's `jbct.toml` chain, not the working directory's. The fixture is
/// the one the Maven side resolves in `SourceRootConfigMojoTest`, with the same expected values.
class CliConfigSourceRootTest {
    private static final String USER_HOME_PROPERTY = "user.home";

    @TempDir
    Path tempDir;

    private String savedUserHome;

    @BeforeEach
    void redirectUserHome() {
        savedUserHome = System.getProperty(USER_HOME_PROPERTY);
        System.setProperty(USER_HOME_PROPERTY, tempDir.resolve("home").toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty(USER_HOME_PROPERTY, savedUserHome);
    }

    @Test
    void load_readsTheModulesChain_whateverTheWorkingDirectory() throws Exception {
        var repo = Files.createDirectories(tempDir.resolve("repo"));
        var module = Files.createDirectories(repo.resolve("module"));
        var sourceRoot = Files.createDirectories(module.resolve("src/main/java"));

        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve("jbct.toml"), "[lint]\nfailOnWarning = true\n");
        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"), "[lint]\nmode = \"infrastructure\"\n");
        Files.writeString(module.resolve("src/jbct.toml"), "[lint]\nmode = \"business\"\nfailOnWarning = false\n");

        var err = new ByteArrayOutputStream();
        var loaded = CliConfig.load(Option.none(), sourceRoot, new PrintStream(err, true, UTF_8));

        assertThat(Path.of(System.getProperty("user.dir"))).isNotEqualTo(module);
        assertThat(loaded.unwrap().mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(loaded.unwrap().lint().failOnWarning()).isTrue();
        assertThat(err.toString(UTF_8)).isEmpty();
    }
}
