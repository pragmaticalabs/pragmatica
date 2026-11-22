package org.pragmatica.jbct.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.pragmatica.jbct.config.JbctMode;

import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2525, Q11: the goal's configuration is the one `jbct check <module>/src/main/java` resolves. The fixture is the
/// one `CliConfigSourceRootTest` resolves on the CLI side, with the same expected values.
class SourceRootConfigMojoTest {
    private static final String USER_HOME = "user.home";

    @TempDir
    Path tempDir;

    private String savedUserHome;

    @BeforeEach
    void redirectUserHome() {
        savedUserHome = System.getProperty(USER_HOME);
        System.setProperty(USER_HOME, tempDir.resolve("home").toString());
    }

    @AfterEach
    void restoreUserHome() {
        System.setProperty(USER_HOME, savedUserHome);
    }

    private static final class Probe extends AbstractJbctMojo {
        @Override
        public void execute() {}
    }

    @Test
    void resolvePlan_readsTheModulesChain_andSkipsTomlsBetweenModuleAndSourceRoot() throws Exception {
        var repo = Files.createDirectories(tempDir.resolve("repo"));
        var module = Files.createDirectories(repo.resolve("module"));

        Files.createDirectories(repo.resolve(".git"));
        Files.writeString(repo.resolve("jbct.toml"), "[lint]\nfailOnWarning = true\n");
        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"), "[lint]\nmode = \"infrastructure\"\n");
        Files.createDirectories(module.resolve("src"));
        Files.writeString(module.resolve("src/jbct.toml"), "[lint]\nmode = \"business\"\nfailOnWarning = false\n");

        var probe = new Probe();
        var mavenProject = new MavenProject();

        mavenProject.setFile(new File(module.toFile(), "pom.xml"));
        probe.project = mavenProject;
        probe.sourceDirectory = Files.createDirectories(module.resolve("src/main/java")).toFile();

        var config = probe.resolvePlan(false).roots().getFirst().config();

        assertThat(config.mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(config.lint().failOnWarning()).isTrue();
    }
}
