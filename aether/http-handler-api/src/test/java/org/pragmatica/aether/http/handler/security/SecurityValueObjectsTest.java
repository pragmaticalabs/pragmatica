package org.pragmatica.aether.http.handler.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityValueObjectsTest {
    @Nested
    class PrincipalTests {
        @Test
        void principal_validValue_createsSuccessfully() {
            Principal.principal("test-user")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(principal -> assertThat(principal.value()).isEqualTo("test-user"));
        }

        @Test
        void principal_nullValue_returnsFailure() {
            var result = Principal.principal(null);
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void principal_blankValue_returnsFailure() {
            var result = Principal.principal("   ");
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void apiKeyPrincipal_validKey_prefixesValue() {
            Principal.apiKeyPrincipal("my-key")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(principal -> {
                         assertThat(principal.value()).isEqualTo("api-key:my-key");
                         assertThat(principal.isApiKey()).isTrue();
                     });
        }

        @Test
        void userPrincipal_validUser_prefixesValue() {
            Principal.userPrincipal("user-123")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(principal -> {
                         assertThat(principal.value()).isEqualTo("user:user-123");
                         assertThat(principal.isUser()).isTrue();
                     });
        }

        @Test
        void servicePrincipal_validService_prefixesValue() {
            Principal.servicePrincipal("order-service")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(principal -> {
                         assertThat(principal.value()).isEqualTo("service:order-service");
                         assertThat(principal.isService()).isTrue();
                     });
        }

        @Test
        void anonymous_constantPrincipal_isIdentifiable() {
            assertThat(Principal.ANONYMOUS.isAnonymous()).isTrue();
            Principal.principal("test")
                     .onFailureRun(Assertions::fail)
                     .onSuccess(principal -> assertThat(principal.isAnonymous()).isFalse());
        }
    }

    @Nested
    class RoleTests {
        @Test
        void role_validValue_createsSuccessfully() {
            Role.role("admin")
                .onFailureRun(Assertions::fail)
                .onSuccess(role -> assertThat(role.value()).isEqualTo("admin"));
        }

        @Test
        void role_nullValue_returnsFailure() {
            var result = Role.role(null);
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void role_blankValue_returnsFailure() {
            var result = Role.role("");
            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void role_constants_haveCorrectValues() {
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
        void anonymous_factory_returnsUnauthenticatedContext() {
            var context = SecurityContext.anonymous();
            assertThat(context.isAuthenticated()).isFalse();
            assertThat(context.principal()).isEqualTo(Principal.ANONYMOUS);
            assertThat(context.roles()).isEmpty();
            assertThat(context.claims()).isEmpty();
        }

        @Test
        void forApiKey_validKey_setsServiceRole() {
            SecurityContext.forApiKey("my-key")
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> {
                               assertThat(context.isAuthenticated()).isTrue();
                               assertThat(context.principal().isApiKey()).isTrue();
                               assertThat(context.hasRole(Role.SERVICE)).isTrue();
                           });
        }

        @Test
        void forApiKey_validKeyWithCustomRoles_appliesRoles() {
            SecurityContext.forApiKey("my-key", Set.of(Role.ADMIN, Role.USER))
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> {
                               assertThat(context.hasRole(Role.ADMIN)).isTrue();
                               assertThat(context.hasRole(Role.USER)).isTrue();
                               assertThat(context.hasRole(Role.SERVICE)).isFalse();
                           });
        }

        @Test
        void hasRole_validRoleName_checksCorrectly() {
            SecurityContext.forApiKey("key", Set.of(Role.ADMIN))
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> {
                               assertThat(context.hasRole("admin")).isTrue();
                               assertThat(context.hasRole("user")).isFalse();
                           });
        }

        @Test
        void hasAnyRole_multipleRoles_checksCorrectly() {
            SecurityContext.forApiKey("key", Set.of(Role.USER))
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> {
                               assertThat(context.hasAnyRole(Set.of(Role.ADMIN, Role.USER))).isTrue();
                               assertThat(context.hasAnyRole(Set.of(Role.ADMIN, Role.SERVICE))).isFalse();
                           });
        }

        @Test
        void forBearer_validSubject_includesClaims() {
            var claims = Map.of("tenant", "acme", "scope", "read:write");
            SecurityContext.forBearer("user-123", Set.of(Role.USER), claims)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> {
                               assertThat(context.principal().isUser()).isTrue();
                               assertThat(context.claim("tenant")).isEqualTo("acme");
                               assertThat(context.claim("scope")).isEqualTo("read:write");
                           });
        }
    }
}
