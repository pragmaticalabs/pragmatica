package org.pragmatica.aether.http.handler.security;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.routing.security.Access;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.http.handler.security.Principal.PrincipalType;

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
        void principal_apiKeyType_prefixesValue() {
            Principal.principal("my-key", PrincipalType.API_KEY)
                     .onFailureRun(Assertions::fail)
                     .onSuccess(principal -> assertThat(principal.value()).isEqualTo("api-key:my-key"));
        }

        @Test
        void principal_apiKeyType_isApiKey() {
            Principal.principal("my-key", PrincipalType.API_KEY)
                     .onFailureRun(Assertions::fail)
                     .onSuccess(principal -> assertThat(principal.isApiKey()).isTrue());
        }

        @Test
        void principal_userType_prefixesValue() {
            Principal.principal("user-123", PrincipalType.USER)
                     .onFailureRun(Assertions::fail)
                     .onSuccess(principal -> assertThat(principal.value()).isEqualTo("user:user-123"));
        }

        @Test
        void principal_userType_isUser() {
            Principal.principal("user-123", PrincipalType.USER)
                     .onFailureRun(Assertions::fail)
                     .onSuccess(principal -> assertThat(principal.isUser()).isTrue());
        }

        @Test
        void principal_serviceType_prefixesValue() {
            Principal.principal("order-service", PrincipalType.SERVICE)
                     .onFailureRun(Assertions::fail)
                     .onSuccess(principal -> assertThat(principal.value()).isEqualTo("service:order-service"));
        }

        @Test
        void principal_serviceType_isService() {
            Principal.principal("order-service", PrincipalType.SERVICE)
                     .onFailureRun(Assertions::fail)
                     .onSuccess(principal -> assertThat(principal.isService()).isTrue());
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
    class SecurityPolicyTests {
        @Test
        void publicRoute_factory() {
            var policy = SecurityPolicy.publicRoute();
            assertThat(policy).isInstanceOf(SecurityPolicy.Public.class);
        }

        @Test
        void apiKeyRequired_factory() {
            var policy = SecurityPolicy.apiKeyRequired();
            assertThat(policy).isInstanceOf(SecurityPolicy.ApiKeyRequired.class);
        }

        @Test
        void asString_serialization() {
            assertThat(SecurityPolicy.publicRoute().asString()).isEqualTo("PUBLIC");
            assertThat(SecurityPolicy.apiKeyRequired().asString()).isEqualTo("API_KEY");
        }

        @Test
        void fromString_deserialization() {
            assertThat(SecurityPolicy.fromString("PUBLIC"))
                .isInstanceOf(SecurityPolicy.Public.class);
            assertThat(SecurityPolicy.fromString("API_KEY"))
                .isInstanceOf(SecurityPolicy.ApiKeyRequired.class);
        }

        @Test
        void fromString_unknownDefaultsToPublic() {
            assertThat(SecurityPolicy.fromString("UNKNOWN"))
                .isInstanceOf(SecurityPolicy.Public.class);
        }

        @Test
        void authenticated_factory() {
            var policy = SecurityPolicy.authenticated();
            assertThat(policy).isInstanceOf(SecurityPolicy.Authenticated.class);
        }

        @Test
        void bearerTokenRequired_factory() {
            var policy = SecurityPolicy.bearerTokenRequired();
            assertThat(policy).isInstanceOf(SecurityPolicy.BearerTokenRequired.class);
        }

        @Test
        void roleRequired_factory() {
            var policy = SecurityPolicy.roleRequired("admin");
            assertThat(policy).isInstanceOf(SecurityPolicy.RoleRequired.class);
        }

        @Test
        void asString_authenticatedSerialization() {
            assertThat(SecurityPolicy.authenticated().asString()).isEqualTo("AUTHENTICATED");
            assertThat(SecurityPolicy.bearerTokenRequired().asString()).isEqualTo("BEARER_TOKEN");
        }

        @Test
        void asString_roleRequired() {
            assertThat(SecurityPolicy.roleRequired("admin").asString()).isEqualTo("ROLE:admin");
        }

        @Test
        void fromString_roleDeserialization() {
            var policy = SecurityPolicy.fromString("ROLE:admin");
            assertThat(policy).isInstanceOf(SecurityPolicy.RoleRequired.class);
            assertThat(policy.asString()).isEqualTo("ROLE:admin");
        }

        @Test
        void fromString_authenticatedDeserialization() {
            assertThat(SecurityPolicy.fromString("AUTHENTICATED"))
                .isInstanceOf(SecurityPolicy.Authenticated.class);
        }

        @Test
        void fromString_bearerTokenDeserialization() {
            assertThat(SecurityPolicy.fromString("BEARER_TOKEN"))
                .isInstanceOf(SecurityPolicy.BearerTokenRequired.class);
        }

        @Test
        void canAccess_publicRoute_allowsAnonymous() {
            var policy = SecurityPolicy.publicRoute();
            var anonymous = SecurityContext.securityContext();
            assertThat(policy.canAccess(anonymous)).isEqualTo(Access.ALLOW);
        }

        @Test
        void canAccess_authenticated_deniesAnonymous() {
            var policy = SecurityPolicy.authenticated();
            var anonymous = SecurityContext.securityContext();
            assertThat(policy.canAccess(anonymous)).isEqualTo(Access.DENY);
        }

        @Test
        void canAccess_authenticated_allowsAuthenticatedUser() {
            var policy = SecurityPolicy.authenticated();
            var context = SecurityContext.securityContext("test-key").unwrap();
            assertThat(policy.canAccess(context)).isEqualTo(Access.ALLOW);
        }

        @Test
        void canAccess_apiKeyRequired_allowsApiKey() {
            var policy = SecurityPolicy.apiKeyRequired();
            var context = SecurityContext.securityContext("test-key").unwrap();
            assertThat(policy.canAccess(context)).isEqualTo(Access.ALLOW);
        }

        @Test
        void canAccess_apiKeyRequired_deniesAnonymous() {
            var policy = SecurityPolicy.apiKeyRequired();
            var anonymous = SecurityContext.securityContext();
            assertThat(policy.canAccess(anonymous)).isEqualTo(Access.DENY);
        }

        @Test
        void canAccess_roleRequired_allowsMatchingRole() {
            var policy = SecurityPolicy.roleRequired("admin");
            var context = SecurityContext.securityContext("key", Set.of(Role.ADMIN)).unwrap();
            assertThat(policy.canAccess(context)).isEqualTo(Access.ALLOW);
        }

        @Test
        void canAccess_roleRequired_deniesNonMatchingRole() {
            var policy = SecurityPolicy.roleRequired("admin");
            var context = SecurityContext.securityContext("key", Set.of(Role.USER)).unwrap();
            assertThat(policy.canAccess(context)).isEqualTo(Access.DENY);
        }
    }

    @Nested
    class SecurityContextTests {
        @Test
        void securityContext_noArgs_returnsUnauthenticatedContext() {
            var context = SecurityContext.securityContext();
            assertThat(context.isAuthenticated()).isFalse();
            assertThat(context.principal()).isEqualTo(Principal.ANONYMOUS);
            assertThat(context.roles()).isEmpty();
            assertThat(context.claims()).isEmpty();
        }

        @Test
        void securityContext_apiKey_setsServiceRole() {
            SecurityContext.securityContext("my-key")
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> assertThat(context.isAuthenticated()).isTrue());
        }

        @Test
        void securityContext_apiKey_hasApiKeyPrincipal() {
            SecurityContext.securityContext("my-key")
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> assertThat(context.principal().isApiKey()).isTrue());
        }

        @Test
        void securityContext_apiKey_hasServiceRole() {
            SecurityContext.securityContext("my-key")
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> assertThat(context.hasRole(Role.SERVICE)).isTrue());
        }

        @Test
        void securityContext_apiKeyWithCustomRoles_appliesRoles() {
            SecurityContext.securityContext("my-key", Set.of(Role.ADMIN, Role.USER))
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> assertThat(context.hasRole(Role.ADMIN)).isTrue());
        }

        @Test
        void securityContext_apiKeyWithCustomRoles_noDefaultServiceRole() {
            SecurityContext.securityContext("my-key", Set.of(Role.ADMIN, Role.USER))
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> assertThat(context.hasRole(Role.SERVICE)).isFalse());
        }

        @Test
        void hasRole_validRoleName_checksCorrectly() {
            SecurityContext.securityContext("key", Set.of(Role.ADMIN))
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> assertThat(context.hasRole("admin")).isTrue());
        }

        @Test
        void hasRole_invalidRoleName_returnsFalse() {
            SecurityContext.securityContext("key", Set.of(Role.ADMIN))
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> assertThat(context.hasRole("user")).isFalse());
        }

        @Test
        void hasAnyRole_matchingRole_returnsTrue() {
            SecurityContext.securityContext("key", Set.of(Role.USER))
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> assertThat(context.hasAnyRole(Set.of(Role.ADMIN, Role.USER))).isTrue());
        }

        @Test
        void hasAnyRole_noMatchingRole_returnsFalse() {
            SecurityContext.securityContext("key", Set.of(Role.USER))
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> assertThat(context.hasAnyRole(Set.of(Role.ADMIN, Role.SERVICE))).isFalse());
        }

        @Test
        void securityContext_bearer_includesClaims() {
            var claims = Map.of("tenant", "acme", "scope", "read:write");
            SecurityContext.securityContext("user-123", Set.of(Role.USER), claims)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> assertThat(context.principal().isUser()).isTrue());
        }

        @Test
        void securityContext_bearer_claimAccess() {
            var claims = Map.of("tenant", "acme", "scope", "read:write");
            SecurityContext.securityContext("user-123", Set.of(Role.USER), claims)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(context -> assertThat(context.claim("tenant")).isEqualTo("acme"));
        }
    }

    @Nested
    class StrengthTests {
        @Test
        void strength_publicIsLowest() {
            assertThat(SecurityPolicy.publicRoute().strength()).isEqualTo(0);
        }

        @Test
        void strength_authenticatedIsMiddle() {
            assertThat(SecurityPolicy.authenticated().strength()).isEqualTo(10);
        }

        @Test
        void strength_apiKeyAndBearerAreEqual() {
            assertThat(SecurityPolicy.apiKeyRequired().strength())
                .isEqualTo(SecurityPolicy.bearerTokenRequired().strength());
        }

        @Test
        void strength_roleRequiredIsHighest() {
            assertThat(SecurityPolicy.roleRequired("admin").strength()).isGreaterThan(
                SecurityPolicy.bearerTokenRequired().strength());
        }

        @Test
        void strength_ordering_publicLessThanAuthenticated() {
            assertThat(SecurityPolicy.publicRoute().strength())
                .isLessThan(SecurityPolicy.authenticated().strength());
        }
    }

    @Nested
    class BlueprintStringTests {
        @Test
        void fromBlueprintString_parsesPublic() {
            assertThat(SecurityPolicy.fromBlueprintString("public"))
                .isInstanceOf(SecurityPolicy.Public.class);
        }

        @Test
        void fromBlueprintString_parsesAuthenticated() {
            assertThat(SecurityPolicy.fromBlueprintString("authenticated"))
                .isInstanceOf(SecurityPolicy.Authenticated.class);
        }

        @Test
        void fromBlueprintString_parsesApiKey() {
            assertThat(SecurityPolicy.fromBlueprintString("api_key"))
                .isInstanceOf(SecurityPolicy.ApiKeyRequired.class);
        }

        @Test
        void fromBlueprintString_parsesBearerToken() {
            assertThat(SecurityPolicy.fromBlueprintString("bearer_token"))
                .isInstanceOf(SecurityPolicy.BearerTokenRequired.class);
        }

        @Test
        void fromBlueprintString_parsesRole() {
            var policy = SecurityPolicy.fromBlueprintString("role:admin");
            assertThat(policy).isInstanceOf(SecurityPolicy.RoleRequired.class);
            assertThat(policy.asString()).isEqualTo("ROLE:admin");
        }

        @Test
        void fromBlueprintString_caseInsensitive() {
            assertThat(SecurityPolicy.fromBlueprintString("AUTHENTICATED"))
                .isInstanceOf(SecurityPolicy.Authenticated.class);
        }

        @Test
        void fromBlueprintString_unknownDefaultsToPublic() {
            assertThat(SecurityPolicy.fromBlueprintString("unknown_value"))
                .isInstanceOf(SecurityPolicy.Public.class);
        }
    }
}
