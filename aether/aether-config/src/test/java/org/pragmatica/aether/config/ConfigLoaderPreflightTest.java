package org.pragmatica.aether.config;

import java.util.Map;

import org.pragmatica.config.toml.TomlParser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #1455: cluster bootstrap judges a node config against an EMPTY environment. The operator machine's environment
/// (`AETHER_BACKUP_*`, `AETHER_API_KEYS`, `${env:...}` targets) describes the operator, not the node that will boot.
class ConfigLoaderPreflightTest {
    private static final String BACKUP_ENABLED = """
        [cluster]
        environment = "docker"
        nodes = 3

        [backup]
        enabled = true
        path = "/data/backups"
        """;

    @Test
    void preflightEnvironment_resolvesNothing_evenForVariablesTheProcessHas() {
        assertThat(System.getenv("PATH")).describedAs("control: the process environment has PATH").isNotNull();
        assertThat(ConfigLoader.PREFLIGHT_ENVIRONMENT.apply("PATH")).isNull();
        assertThat(ConfigLoader.PREFLIGHT_ENVIRONMENT.apply("AETHER_BACKUP_ENABLED")).isNull();
    }

    @Test
    void checkNodeDocument_verdictDoesNotDependOnTheOperatorEnvironment() {
        var doc = TomlParser.parse(BACKUP_ENABLED).unwrap();

        assertThat(ConfigLoader.checkNodeDocument(doc).isSuccess()).describedAs("no environment: accepted").isTrue();
        assertThat(ConfigLoader.checkNodeDocument(TomlParser.parse(BACKUP_ENABLED).unwrap(), Map.of("AETHER_BACKUP_ENABLED", "yes")::get).isFailure())
            .describedAs("control: an environment that supplies a bad value DOES change a load judged against it")
            .isTrue();
    }

    @Test
    void checkNodeDocument_runsTheValidationStep_notOnlyTheParse() {
        var doc = TomlParser.parse("""
            [cluster]
            environment = "docker"
            nodes = 3
            tls = true

            [tls]
            auto_generate = false
            """).unwrap();

        assertThat(ConfigLoader.checkNodeDocument(doc).isFailure()).isTrue();
    }

    @Test
    void checkNodeDocument_runsTheStorageEncryptionValidation_too() {
        var doc = TomlParser.parse("""
            [cluster]
            environment = "docker"
            nodes = 3

            [storage.encryption]
            active_key_id = "k1"

            [storage.encryption.keys]
            k1 = "inline-secret"
            """).unwrap();

        ConfigLoader.checkNodeDocument(doc)
                    .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("an inline keyring entry must be refused"))
                    .onFailure(cause -> assertThat(cause.message()).contains("storage.encryption.keys.k1"));
    }

    /// Child JVM entry point: exit 0 when the pre-flight check accepts the document, 1 when it refuses; with the argument
    /// `node`, the node's own load (which reads the process environment) is judged instead.
    public static final class Probe {
        public static void main(String[] args) {
            var verdict = args.length > 0 && args[0].equals("node")
                          ? ConfigLoader.loadFromString(BACKUP_ENABLED).isSuccess()
                          : ConfigLoader.checkNodeDocument(TomlParser.parse(BACKUP_ENABLED).unwrap()).isSuccess();

            System.exit(verdict
                        ? 0
                        : 1);
        }
    }

    /// The operator's PROCESS environment (here `AETHER_BACKUP_ENABLED=yes`) must not change the pre-flight verdict. The
    /// JVM environment cannot be changed in-process, so the check runs in a child JVM that has the variable; the node's own
    /// load in the same child is the control that proves the variable reaches the loader.
    @Test
    void checkNodeDocument_ignoresTheOperatorProcessEnvironment() throws Exception {
        assertThat(childExit("preflight")).describedAs("pre-flight with AETHER_BACKUP_ENABLED=yes in the process environment").isZero();
        assertThat(childExit("node")).describedAs("control: the node's own load sees that variable and refuses").isEqualTo(1);
    }

    private static int childExit(String mode) throws Exception {
        var java = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Probe.class.getName(), mode);

        builder.environment().put("AETHER_BACKUP_ENABLED", "yes");
        builder.redirectErrorStream(true);

        var process = builder.start();

        process.getInputStream().readAllBytes();

        return process.waitFor();
    }
}
