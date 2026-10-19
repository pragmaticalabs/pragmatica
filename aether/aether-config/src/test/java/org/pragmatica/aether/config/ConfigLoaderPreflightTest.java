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
}
