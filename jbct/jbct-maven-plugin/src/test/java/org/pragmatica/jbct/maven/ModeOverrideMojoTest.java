package org.pragmatica.jbct.maven;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.cst.RootResolver.ResolvedRoot;
import org.pragmatica.lang.Option;

import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #2525, C7: only a session user property (`-Djbct.mode`, or a `-D` line of `.mvn/maven.config`) overrides `jbct.toml`.
/// A pom `<jbct.mode>` and a `jbct.mode` system property (`MAVEN_OPTS`) override nothing and each warns, naming
/// `jbct.toml`. All four modes are accepted; an unknown value is refused naming it.
class ModeOverrideMojoTest {
    private static final String USER_HOME = "user.home";
    private static final String MODE = "jbct.mode";

    @TempDir
    Path tempDir;

    private String savedUserHome;
    private Path module;
    private final List<String> warnings = new ArrayList<>();

    @BeforeEach
    void fixture() throws Exception {
        savedUserHome = System.getProperty(USER_HOME);
        System.setProperty(USER_HOME, tempDir.resolve("home").toString());
        System.clearProperty(MODE);

        module = Files.createDirectories(tempDir.resolve("repo/module"));
        Files.createDirectories(tempDir.resolve("repo/.git"));
        Files.writeString(module.resolve("pom.xml"), "<project/>\n");
        Files.writeString(module.resolve("jbct.toml"), "[lint]\nmode = \"infrastructure\"\n");
    }

    @AfterEach
    void restore() {
        System.setProperty(USER_HOME, savedUserHome);
        System.clearProperty(MODE);
    }

    private static final class Probe extends AbstractJbctMojo {
        @Override
        public void execute() {}
    }

    private Probe probe(Properties userProperties, Properties pomProperties) {
        var probe = new Probe();
        var mavenProject = new MavenProject();
        var request = new DefaultMavenExecutionRequest();

        mavenProject.setFile(new File(module.toFile(), "pom.xml"));
        mavenProject.getModel().setProperties(pomProperties);
        request.setUserProperties(userProperties);
        probe.project = mavenProject;
        probe.session = new MavenSession(null, null, request, new DefaultMavenExecutionResult());
        probe.setLog(new SystemStreamLog() {
            @Override
            public void warn(CharSequence content) {
                warnings.add(content.toString());
            }
        });

        return probe;
    }

    /// The root the goal's shared resolver decides for the module's main directory: the live path every goal takes.
    private ResolvedRoot rootOf(Probe probe) throws Exception {
        probe.sourceDirectory = Files.createDirectories(module.resolve("src/main/java")).toFile();

        return probe.resolvePlan(false).roots().getFirst();
    }

    private static Properties with(String value) {
        var properties = new Properties();

        properties.setProperty(MODE, value);

        return properties;
    }

    @Test
    void resolvePlan_userPropertyOverridesJbctToml_withoutWarning() throws Exception {
        var config = rootOf(probe(with("business"), new Properties())).config();

        assertThat(config.mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(warnings).isEmpty();
    }

    @Test
    void resolvePlan_keepsTheTomlMode_withoutWarning_whenNothingIsGiven() throws Exception {
        var config = rootOf(probe(new Properties(), new Properties())).config();

        assertThat(config.mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(warnings).isEmpty();
    }

    @Test
    void resolvePlan_ignoresPomPropertyAndWarnsNamingJbctToml() throws Exception {
        var config = rootOf(probe(new Properties(), with("business"))).config();

        assertThat(config.mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("<jbct.mode> in the pom's <properties>", "jbct.toml");
    }

    @Test
    void resolvePlan_ignoresSystemPropertyAndWarnsNamingJbctToml() throws Exception {
        System.setProperty(MODE, "business");

        var config = rootOf(probe(new Properties(), new Properties())).config();

        assertThat(config.mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("The jbct.mode system property", "jbct.toml");
    }

    @Test
    void resolvePlan_userPropertyWinsOverPomProperty_andOnlyThePomPropertyWarns() throws Exception {
        var config = rootOf(probe(with("business"), with("infrastructure"))).config();

        assertThat(config.mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst()).contains("<jbct.mode> in the pom's <properties>");
    }

    @Test
    void resolvePlan_doesNotWarnAboutTheSystemPropertyMavenMirrorsFromAUserProperty() throws Exception {
        System.setProperty(MODE, "business");

        var config = rootOf(probe(with("business"), new Properties())).config();

        assertThat(config.mode()).isEqualTo(JbctMode.BUSINESS);
        assertThat(warnings).isEmpty();
    }

    @Test
    void resolvePlan_refusesUnknownUserPropertyValue_namingIt() {
        assertThatThrownBy(() -> rootOf(probe(with("strict"), new Properties()))).isInstanceOf(MojoExecutionException.class)
                                                                                      .hasMessageContaining("strict");
    }

    @Test
    void resolvedRoot_acceptsFormatOnlyAndDisabledUserPropertyValues() throws Exception {
        var formatOnly = probe(with("format-only"), new Properties());
        var disabled = probe(with("disabled"), new Properties());

        assertThat(rootOf(formatOnly).mode()).isEqualTo(JbctMode.FORMAT_ONLY);
        assertThat(rootOf(disabled).mode()).isEqualTo(JbctMode.DISABLED);
    }

    @Test
    void resolvePlan_refusalOfAnUnknownValue_listsAllFourModes() {
        assertThatThrownBy(() -> rootOf(probe(with("strict"), new Properties())))
            .hasMessageContaining("business", "infrastructure", "format-only", "disabled");
    }

    @Test
    void resolvedRoot_carriesTheTomlModeAndBasePackage() throws Exception {
        Files.writeString(module.resolve("jbct.toml"),
                          "[lint]\nmode = \"infrastructure\"\n[project]\nbasePackage = \"org.acme.billing\"\n");

        var probe = probe(new Properties(), new Properties());
        var context = rootOf(probe).context();

        assertThat(context.mode()).isEqualTo(JbctMode.INFRASTRUCTURE);
        assertThat(context.basePackage()).isEqualTo(Option.some("org.acme.billing"));
    }

    @Test
    void resolvedRoot_carriesTheUserPropertyModeOverride() throws Exception {
        var probe = probe(with("business"), new Properties());

        assertThat(rootOf(probe).mode()).isEqualTo(JbctMode.BUSINESS);
    }
}
