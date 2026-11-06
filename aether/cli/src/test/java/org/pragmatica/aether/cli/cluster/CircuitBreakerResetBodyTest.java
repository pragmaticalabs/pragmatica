package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.json.JsonMapper;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

/// v2711 finding 6: the reset body is written by the JSON writer, so a source name round-trips whatever characters it holds, and no
/// `--source` sends an empty object (every source).
class CircuitBreakerResetBodyTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Test
    void requestBody_sourceWithJsonMetacharacters_roundTripsExactly() {
        var source = "eu\"1\\x";
        var body = bodyFor("--source", source);

        assertThat(MAPPER.readTree(body)
                         .map(node -> node.path("source")
                                          .asText())
                         .unwrap()).isEqualTo(source);
    }

    @Test
    void requestBody_noSource_isAnEmptyObject() {
        assertThat(bodyFor()).isEqualTo("{}");
    }

    @Test
    void requestBody_blankSource_isOmitted() {
        assertThat(bodyFor("--source", "  ")).isEqualTo("{}");
        assertThat(bodyFor("--source", "")).isEqualTo("{}");
    }

    @Test
    void requestBody_plainSource_isTheSourceKeyWithThatValue() {
        assertThat(bodyFor("--source", "eu-1")).isEqualTo("{\"source\":\"eu-1\"}");
    }

    private static String bodyFor(String... args) {
        var command = new ClusterTopologyCommand.CircuitBreakerCommand.ResetCommand();
        new CommandLine(command).parseArgs(args);

        return command.requestBody()
                      .unwrap();
    }
}
