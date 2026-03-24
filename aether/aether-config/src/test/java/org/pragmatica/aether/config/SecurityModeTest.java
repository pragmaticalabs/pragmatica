package org.pragmatica.aether.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityModeTest {

    @Nested
    class ParsingTests {
        @Test
        void securityMode_parsesNone() {
            assertThat(SecurityMode.securityMode("none").unwrap()).isEqualTo(SecurityMode.NONE);
        }

        @Test
        void securityMode_parsesApiKeyHyphenated() {
            assertThat(SecurityMode.securityMode("api-key").unwrap()).isEqualTo(SecurityMode.API_KEY);
        }

        @Test
        void securityMode_parsesApiKeyUnderscored() {
            assertThat(SecurityMode.securityMode("api_key").unwrap()).isEqualTo(SecurityMode.API_KEY);
        }

        @Test
        void securityMode_parsesApiKeyConcatenated() {
            assertThat(SecurityMode.securityMode("apikey").unwrap()).isEqualTo(SecurityMode.API_KEY);
        }

        @Test
        void securityMode_parsesJwt() {
            assertThat(SecurityMode.securityMode("jwt").unwrap()).isEqualTo(SecurityMode.JWT);
        }

        @Test
        void securityMode_caseInsensitive() {
            assertThat(SecurityMode.securityMode("API-KEY").unwrap()).isEqualTo(SecurityMode.API_KEY);
            assertThat(SecurityMode.securityMode("NONE").unwrap()).isEqualTo(SecurityMode.NONE);
            assertThat(SecurityMode.securityMode("JWT").unwrap()).isEqualTo(SecurityMode.JWT);
        }

        @Test
        void securityMode_trimsWhitespace() {
            assertThat(SecurityMode.securityMode("  api-key  ").unwrap()).isEqualTo(SecurityMode.API_KEY);
        }

        @Test
        void securityMode_returnsEmptyForUnknown() {
            assertThat(SecurityMode.securityMode("unknown").isEmpty()).isTrue();
        }

        @Test
        void securityMode_returnsEmptyForNull() {
            assertThat(SecurityMode.securityMode(null).isEmpty()).isTrue();
        }
    }
}
