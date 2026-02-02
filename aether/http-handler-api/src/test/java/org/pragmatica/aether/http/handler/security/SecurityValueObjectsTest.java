package org.pragmatica.aether.http.handler.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityValueObjectsTest {
    @Nested
    class PrincipalTests {
        @Test
        void principal_createsWithValue() {
            var principal = Principal.principal("test-user");
            assertThat(principal.value()).isEqualTo("test-user");
        }

        @Test
        void principal_rejectsNull() {
            assertThatThrownBy(() -> Principal.principal(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        void principal_rejectsBlank() {
            assertThatThrownBy(() -> Principal.principal("   "))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void apiKeyPrincipal_prefixesValue() {
            var principal = Principal.apiKeyPrincipal("my-key");
            assertThat(principal.value()).isEqualTo("api-key:my-key");
            assertThat(principal.isApiKey()).isTrue();
        }

        @Test
        void userPrincipal_prefixesValue() {
            var principal = Principal.userPrincipal("user-123");
            assertThat(principal.value()).isEqualTo("user:user-123");
            assertThat(principal.isUser()).isTrue();
        }

        @Test
        void servicePrincipal_prefixesValue() {
            var principal = Principal.servicePrincipal("order-service");
            assertThat(principal.value()).isEqualTo("service:order-service");
            assertThat(principal.isService()).isTrue();
        }

        @Test
        void anonymous_isIdentifiable() {
            assertThat(Principal.ANONYMOUS.isAnonymous()).isTrue();
            assertThat(Principal.principal("test").isAnonymous()).isFalse();
        }
    }

    @Nested
    class RoleTests {
        @Test
        void role_createsWithValue() {
            var role = Role.role("admin");
            assertThat(role.value()).isEqualTo("admin");
        }

        @Test
        void role_rejectsNull() {
            assertThatThrownBy(() -> Role.role(null))
                .isInstanceOf(NullPointerException.class);
        }

        @Test
        void role_rejectsBlank() {
            assertThatThrownBy(() -> Role.role(""))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void role_commonConstantsExist() {
            assertThat(Role.ADMIN.value()).isEqualTo("admin");
            assertThat(Role.USER.value()).isEqualTo("user");
            assertThat(Role.SERVICE.value()).isEqualTo("service");
        }
    }

    @Nested
    class ApiKeyTests {
        @Test
        void apiKey_acceptsValidFormat() {
            ApiKey.apiKey("valid-key-12345")
                  .onFailureRun(Assertions::fail)
                  .onSuccess(key -> assertThat(key.value()).isEqualTo("valid-key-12345"));
        }

        @Test
        void apiKey_acceptsMinLength() {
            ApiKey.apiKey("12345678")
                  .onFailureRun(Assertions::fail);
        }

        @Test
        void apiKey_acceptsMaxLength() {
            var longKey = "a".repeat(64);
            ApiKey.apiKey(longKey)
                  .onFailureRun(Assertions::fail);
        }

        @Test
        void apiKey_rejectsTooShort() {
            ApiKey.apiKey("short")
                  .onSuccessRun(Assertions::fail);
        }

        @Test
        void apiKey_rejectsTooLong() {
            var tooLong = "a".repeat(65);
            ApiKey.apiKey(tooLong)
                  .onSuccessRun(Assertions::fail);
        }

        @Test
        void apiKey_rejectsInvalidChars() {
            ApiKey.apiKey("invalid!key@#$")
                  .onSuccessRun(Assertions::fail);
        }

        @Test
        void isValidFormat_checksWithoutException() {
            assertThat(ApiKey.isValidFormat("valid-key-12345")).isTrue();
            assertThat(ApiKey.isValidFormat("short")).isFalse();
            assertThat(ApiKey.isValidFormat(null)).isFalse();
        }
    }

    @Nested
    class RouteSecurityPolicyTests {
        @Test
        void publicRoute_factory() {
            var policy = RouteSecurityPolicy.publicRoute();
            assertThat(policy).isInstanceOf(RouteSecurityPolicy.Public.class);
        }

        @Test
        void apiKeyRequired_factory() {
            var policy = RouteSecurityPolicy.apiKeyRequired();
            assertThat(policy).isInstanceOf(RouteSecurityPolicy.ApiKeyRequired.class);
        }

        @Test
        void asString_serialization() {
            assertThat(RouteSecurityPolicy.publicRoute().asString()).isEqualTo("PUBLIC");
            assertThat(RouteSecurityPolicy.apiKeyRequired().asString()).isEqualTo("API_KEY");
        }

        @Test
        void fromString_deserialization() {
            assertThat(RouteSecurityPolicy.fromString("PUBLIC"))
                .isInstanceOf(RouteSecurityPolicy.Public.class);
            assertThat(RouteSecurityPolicy.fromString("API_KEY"))
                .isInstanceOf(RouteSecurityPolicy.ApiKeyRequired.class);
        }

        @Test
        void fromString_unknownDefaultsToPublic() {
            assertThat(RouteSecurityPolicy.fromString("UNKNOWN"))
                .isInstanceOf(RouteSecurityPolicy.Public.class);
        }
    }

    @Nested
    class SecurityContextTests {
        @Test
        void anonymous_factory() {
            var context = SecurityContext.anonymous();
            assertThat(context.isAuthenticated()).isFalse();
            assertThat(context.principal()).isEqualTo(Principal.ANONYMOUS);
            assertThat(context.roles()).isEmpty();
            assertThat(context.claims()).isEmpty();
        }

        @Test
        void forApiKey_setsServiceRole() {
            var context = SecurityContext.forApiKey("my-key");
            assertThat(context.isAuthenticated()).isTrue();
            assertThat(context.principal().isApiKey()).isTrue();
            assertThat(context.hasRole(Role.SERVICE)).isTrue();
        }

        @Test
        void forApiKey_withCustomRoles() {
            var context = SecurityContext.forApiKey("my-key", Set.of(Role.ADMIN, Role.USER));
            assertThat(context.hasRole(Role.ADMIN)).isTrue();
            assertThat(context.hasRole(Role.USER)).isTrue();
            assertThat(context.hasRole(Role.SERVICE)).isFalse();
        }

        @Test
        void hasRole_byStringName() {
            var context = SecurityContext.forApiKey("key", Set.of(Role.ADMIN));
            assertThat(context.hasRole("admin")).isTrue();
            assertThat(context.hasRole("user")).isFalse();
        }

        @Test
        void hasAnyRole_checksMultiple() {
            var context = SecurityContext.forApiKey("key", Set.of(Role.USER));
            assertThat(context.hasAnyRole(Set.of(Role.ADMIN, Role.USER))).isTrue();
            assertThat(context.hasAnyRole(Set.of(Role.ADMIN, Role.SERVICE))).isFalse();
        }

        @Test
        void forBearer_withClaims() {
            var claims = Map.of("tenant", "acme", "scope", "read:write");
            var context = SecurityContext.forBearer("user-123", Set.of(Role.USER), claims);
            assertThat(context.principal().isUser()).isTrue();
            assertThat(context.claim("tenant")).isEqualTo("acme");
            assertThat(context.claim("scope")).isEqualTo("read:write");
        }
    }
}
