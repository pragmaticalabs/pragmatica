package org.pragmatica.aether.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpProtocolTest {
    @Nested
    class ParsingTests {
        @Test
        void httpProtocol_returnsH1_forH1String() {
            assertThat(HttpProtocol.httpProtocol("h1").isPresent()).isTrue();
            assertThat(HttpProtocol.httpProtocol("h1").unwrap()).isEqualTo(HttpProtocol.H1);
        }

        @Test
        void httpProtocol_returnsH1_forHttp1String() {
            assertThat(HttpProtocol.httpProtocol("http1").unwrap()).isEqualTo(HttpProtocol.H1);
        }

        @Test
        void httpProtocol_returnsH3_forH3String() {
            assertThat(HttpProtocol.httpProtocol("h3").unwrap()).isEqualTo(HttpProtocol.H3);
        }

        @Test
        void httpProtocol_returnsH3_forHttp3String() {
            assertThat(HttpProtocol.httpProtocol("http3").unwrap()).isEqualTo(HttpProtocol.H3);
        }

        @Test
        void httpProtocol_returnsBoth_forBothString() {
            assertThat(HttpProtocol.httpProtocol("both").unwrap()).isEqualTo(HttpProtocol.BOTH);
        }

        @Test
        void httpProtocol_returnsBoth_forDualString() {
            assertThat(HttpProtocol.httpProtocol("dual").unwrap()).isEqualTo(HttpProtocol.BOTH);
        }

        @Test
        void httpProtocol_returnsEmpty_forUnknownString() {
            assertThat(HttpProtocol.httpProtocol("invalid").isEmpty()).isTrue();
        }

        @Test
        void httpProtocol_returnsEmpty_forNull() {
            assertThat(HttpProtocol.httpProtocol(null).isEmpty()).isTrue();
        }

        @Test
        void httpProtocol_isCaseInsensitive() {
            assertThat(HttpProtocol.httpProtocol("H3").unwrap()).isEqualTo(HttpProtocol.H3);
            assertThat(HttpProtocol.httpProtocol("BOTH").unwrap()).isEqualTo(HttpProtocol.BOTH);
        }

        @Test
        void httpProtocol_trimWhitespace() {
            assertThat(HttpProtocol.httpProtocol("  h3  ").unwrap()).isEqualTo(HttpProtocol.H3);
        }
    }

    @Nested
    class ProtocolQueryTests {
        @Test
        void includesH1_trueForH1() {
            assertThat(HttpProtocol.H1.includesH1()).isTrue();
        }

        @Test
        void includesH1_falseForH3() {
            assertThat(HttpProtocol.H3.includesH1()).isFalse();
        }

        @Test
        void includesH1_trueForBoth() {
            assertThat(HttpProtocol.BOTH.includesH1()).isTrue();
        }

        @Test
        void includesH3_falseForH1() {
            assertThat(HttpProtocol.H1.includesH3()).isFalse();
        }

        @Test
        void includesH3_trueForH3() {
            assertThat(HttpProtocol.H3.includesH3()).isTrue();
        }

        @Test
        void includesH3_trueForBoth() {
            assertThat(HttpProtocol.BOTH.includesH3()).isTrue();
        }

        @Test
        void requiresTls_falseForH1() {
            assertThat(HttpProtocol.H1.requiresTls()).isFalse();
        }

        @Test
        void requiresTls_trueForH3() {
            assertThat(HttpProtocol.H3.requiresTls()).isTrue();
        }

        @Test
        void requiresTls_trueForBoth() {
            assertThat(HttpProtocol.BOTH.requiresTls()).isTrue();
        }
    }

    @Nested
    class AppHttpConfigIntegrationTests {
        @Test
        void defaultConfig_usesH1Protocol() {
            var config = AppHttpConfig.appHttpConfig();
            assertThat(config.httpProtocol()).isEqualTo(HttpProtocol.H1);
        }

        @Test
        void withHttpProtocol_changesProtocol() {
            var config = AppHttpConfig.appHttpConfig().withHttpProtocol(HttpProtocol.H3);
            assertThat(config.httpProtocol()).isEqualTo(HttpProtocol.H3);
        }

        @Test
        void withHttpProtocol_preservesOtherFields() {
            var config = AppHttpConfig.appHttpConfig(8080).withHttpProtocol(HttpProtocol.BOTH);
            assertThat(config.port()).isEqualTo(8080);
            assertThat(config.enabled()).isTrue();
            assertThat(config.httpProtocol()).isEqualTo(HttpProtocol.BOTH);
        }
    }
}
