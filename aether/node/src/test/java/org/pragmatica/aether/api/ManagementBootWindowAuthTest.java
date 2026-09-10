// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.RoleEnforcer;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.http.security.SecurityError;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyValue;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// #908: the management plane must REFUSE an anonymous caller during the boot window between node
/// start and the leader registering the bootstrap admin key in KV -- it used to answer such a
/// caller with `SecurityContext.securityContext()`, whose `authorizationRole` is `VIEWER`, and
/// every management read route admits VIEWER.
///
/// WHAT THIS ASSERTS, AND WHERE IT SITS. The claim is about what an unauthenticated caller
/// RECEIVES, so the assertions run the pipeline `ManagementServerImpl#validateManagementSecurity`
/// runs -- `securityValidator.validate(ctx, apiKeyRequired())` then
/// `RoleEnforcer.enforce(sc, resolvePermission(method, path))` -- and end on
/// [ManagementServerImpl#resolveSecurityErrorStatus], the switch that decides the status handed to
/// the response writer. Not covered below that line: the Netty write itself
/// (`ProblemResponses.writeProblem`), which needs a live listener. `resolvePermission` and
/// `resolveSecurityErrorStatus` are called directly rather than re-implemented, so a change to
/// either reaches this test.
///
/// THE ESCAPE HATCH IS NOT HERE. `security_mode = "none"` is honored one level up, at
/// `ManagementServerImpl#handleRequestInScope`'s `if (securityEnabled)`, which never consults a
/// validator at all; this fix does not touch it. Every request reaching these assertions has
/// already passed a node that asked for a credential.
///
/// A FAIL-OPEN DEFECT RETURNS THE SAME THING ON THE HAPPY PATH AS THE FIX DOES, so each denial
/// here carries three discriminators run INSIDE the same test: the route resolves to a real
/// permission (it was not a 404 in disguise), that permission WOULD have admitted the anonymous
/// context (so the refusal is authentication, not authorization), and the cause is the specific
/// missing-credential error rather than a generic denial.
class ManagementBootWindowAuthTest {
    private static final String STATUS_PATH = "/api/v1/nodes/status";
    private static final String BOOTSTRAP_KEY = "bootstrap-admin-key-value";
    private static final String BOOTSTRAP_KEY_ID = "bootstrap-admin";
    private static final int HTTP_UNAUTHORIZED = 401;

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    /// The wiring `AetherNode` installs for the management plane when `security_mode` is not
    /// `none`: the config validator over `[app-http.api-keys]`, wrapped by the KV-aware validator
    /// that consults the cluster's bootstrap admin keys.
    private SecurityValidator managementValidator(Map<String, ApiKeyEntry> configuredKeys) {
        return SecurityValidator.kvStoreAwareValidator(SecurityValidator.apiKeyValidator(configuredKeys),
                                                       () -> kvStore);
    }

    /// Mirrors `ManagementServerImpl#validateManagementSecurity`, calling the same two production
    /// decisions it calls.
    private Result<SecurityContext> pipeline(SecurityValidator validator, String apiKey, String method, String path) {
        return validator.validate(request(apiKey, method, path), SecurityPolicy.apiKeyRequired())
                        .flatMap(sc -> RoleEnforcer.enforce(sc, ManagementServerImpl.resolvePermission(method, path)));
    }

    @Nested
    class BootWindow {
        @Test
        void anonymousGet_isRefusedWith401_whenNoCredentialExistsAnywhereYet() {
            var validator = managementValidator(Map.of());

            pipeline(validator, null, "GET", STATUS_PATH)
                .onSuccess(sc -> fail("#908: anonymous GET was served as " + sc.authorizationRole()))
                .onFailure(ManagementBootWindowAuthTest::assertMissingApiKeyWith401);
        }

        /// Control #1, run inside the failing scenario: the request aimed at a route the management
        /// plane actually resolves. Without this, the denial above is indistinguishable from a
        /// request that matched nothing.
        @Test
        void statusRoute_resolvesToARealPermission_provingTheRequestReachedARoute() {
            var permission = ManagementServerImpl.resolvePermission("GET", STATUS_PATH);

            assertThat(permission.minimumRole()).isEqualTo(AuthorizationRole.VIEWER);
        }

        /// Control #2, and the honesty pin on the whole fix: the anonymous context the validator
        /// USED to hand back clears the role gate this route imposes. So the denial cannot be
        /// coming from `RoleEnforcer` -- if authentication ever succeeds anonymously again, the
        /// request is served, exactly as it was before #908. This test must stay GREEN; it is
        /// asserting that the fail-open, if reintroduced, is still fully open.
        @Test
        void anonymousContext_clearsTheRoleGate_soOnlyAuthenticationCanRefuseIt() {
            var permission = ManagementServerImpl.resolvePermission("GET", STATUS_PATH);
            var anonymous = SecurityContext.securityContext();

            assertThat(anonymous.isAuthenticated()).isFalse();
            assertThat(anonymous.authorizationRole()).isEqualTo(AuthorizationRole.VIEWER);
            RoleEnforcer.enforce(anonymous, permission)
                        .onFailure(cause -> fail("role gate refused the anonymous context: " + cause.message()));
        }

        /// Control #3: the harness CAN reach a success. A registered bootstrap admin key -- the
        /// event that ends the boot window -- authenticates and is served, through the same
        /// validator instance and the same pipeline. "Refused" is therefore not "never arrived".
        @Test
        void registeredBootstrapKey_isServedWithAdmin_provingTheHarnessReachesSuccess() {
            var validator = managementValidator(Map.of());

            registerKvKey(BOOTSTRAP_KEY_ID, BOOTSTRAP_KEY, "ADMIN");

            pipeline(validator, BOOTSTRAP_KEY, "GET", STATUS_PATH)
                .onFailure(cause -> fail("registered KV key was refused: " + cause.message()))
                .onSuccess(sc -> {
                    assertThat(sc.isAuthenticated()).isTrue();
                    assertThat(sc.authorizationRole()).isEqualTo(AuthorizationRole.ADMIN);
                });
        }

        @Test
        void anonymousGet_isStillRefused_afterTheBootstrapKeyLands() {
            var validator = managementValidator(Map.of());

            registerKvKey(BOOTSTRAP_KEY_ID, BOOTSTRAP_KEY, "ADMIN");

            pipeline(validator, null, "GET", STATUS_PATH)
                .onSuccess(sc -> fail("anonymous GET was served as " + sc.authorizationRole()))
                .onFailure(ManagementBootWindowAuthTest::assertMissingApiKeyWith401);
        }

        @Test
        void anonymousGet_isRefused_whenTheKvStoreIsNotEvenAvailableYet() {
            SecurityValidator validator =
                SecurityValidator.kvStoreAwareValidator(SecurityValidator.apiKeyValidator(Map.of()), () -> null);

            pipeline(validator, null, "GET", STATUS_PATH)
                .onSuccess(sc -> fail("anonymous GET was served as " + sc.authorizationRole()))
                .onFailure(ManagementBootWindowAuthTest::assertMissingApiKeyWith401);
        }

        @Test
        void anonymousWrite_isRefusedAsMissingCredential_notAsInsufficientRole() {
            var validator = managementValidator(Map.of());

            pipeline(validator, null, "POST", "/api/v1/blueprints")
                .onSuccess(_ -> fail("anonymous POST was served"))
                .onFailure(ManagementBootWindowAuthTest::assertMissingApiKeyWith401);
        }

        @Test
        void configuredKey_stillAuthenticates_whenTheKvStoreIsEmpty() {
            var validator = managementValidator(Map.of("configured-key-value1",
                                                       ApiKeyEntry.apiKeyEntry("ops", Set.of("service"), "OPERATOR")));

            pipeline(validator, "configured-key-value1", "GET", STATUS_PATH)
                .onFailure(cause -> fail("configured key was refused: " + cause.message()))
                .onSuccess(sc -> assertThat(sc.authorizationRole()).isEqualTo(AuthorizationRole.OPERATOR));
        }

        @Test
        void unknownKey_isRefused_ratherThanFallingBackToAnonymous() {
            var validator = managementValidator(Map.of());

            validator.validate(request("not-a-registered-key", "GET", STATUS_PATH), SecurityPolicy.apiKeyRequired())
                     .onSuccess(sc -> fail("unknown key was accepted as " + sc.authorizationRole()))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(SecurityError.InvalidCredentials.class));
        }
    }

    /// The policy arms that used to fall through `default -> validateApiKey(request)`, or -- for
    /// `BearerTokenRequired` -- to return an anonymous SUCCESS with no credential inspected at all.
    @Nested
    class UnservedPolicies {
        @Test
        void bearerTokenRequired_isRefusedAsUnenforceable_notServedAnonymously() {
            assertRefused(SecurityPolicy.bearerTokenRequired(), SecurityError.UNENFORCEABLE_POLICY.message());
        }

        @Test
        void bearerTokenRequired_isRefused_evenWithARegisteredAdminKey() {
            registerKvKey(BOOTSTRAP_KEY_ID, BOOTSTRAP_KEY, "ADMIN");

            managementValidator(Map.of()).validate(request(BOOTSTRAP_KEY, "GET", STATUS_PATH),
                                                   SecurityPolicy.bearerTokenRequired())
                                         .onSuccess(sc -> fail("bearer-token route served an api-key caller as "
                                                              + sc.authorizationRole()));
        }

        @Test
        void unspecified_isRefusedAsUnresolved_notServed() {
            assertRefused(SecurityPolicy.unspecified(), SecurityError.UNRESOLVED_POLICY.message());
        }

        @Test
        void unusedPolicy_isRefusedAsUnresolved_notServed() {
            assertRefused(new SecurityPolicy.unused(), SecurityError.UNRESOLVED_POLICY.message());
        }

        @Test
        void publicRoute_isStillServedAnonymously() {
            managementValidator(Map.of()).validate(request(null, "GET", STATUS_PATH), SecurityPolicy.publicRoute())
                                         .onFailure(cause -> fail("public route was refused: " + cause.message()))
                                         .onSuccess(sc -> assertThat(sc.isAuthenticated()).isFalse());
        }

        private void assertRefused(SecurityPolicy policy, String expectedMessage) {
            registerKvKey(BOOTSTRAP_KEY_ID, BOOTSTRAP_KEY, "ADMIN");

            managementValidator(Map.of()).validate(request(BOOTSTRAP_KEY, "GET", STATUS_PATH), policy)
                                         .onSuccess(sc -> fail("policy " + policy.asString() + " served "
                                                              + sc.authorizationRole()))
                                         .onFailure(cause -> {
                                             assertThat(cause.message()).isEqualTo(expectedMessage);
                                             assertThat(ManagementServerImpl.resolveSecurityErrorStatus(cause).code())
                                                 .isEqualTo(HTTP_UNAUTHORIZED);
                                         });
        }
    }

    /// The refusal must be the SPECIFIC missing-credential one carrying a 401, never a generic
    /// denial and never `AccessDenied` -- the latter would mean the caller authenticated and then
    /// failed a role check, i.e. the fail-open is still there and something else refused.
    private static void assertMissingApiKeyWith401(Cause cause) {
        assertThat(cause).isInstanceOf(SecurityError.MissingCredentials.class);
        assertThat(cause).isNotInstanceOf(RoleEnforcer.AuthorizationError.AccessDenied.class);
        assertThat(cause.message()).isEqualTo(SecurityError.MISSING_API_KEY.message());
        assertThat(ManagementServerImpl.resolveSecurityErrorStatus(cause).code()).isEqualTo(HTTP_UNAUTHORIZED);
    }

    private void registerKvKey(String keyId, String rawKey, String role) {
        AetherKey key = ApiKeyKey.apiKeyKey(keyId);
        AetherValue value = ApiKeyValue.apiKeyValue(keyId, sha256Hex(rawKey), 0L, role);

        kvStore.process(kvStore.createBatch(List.of(new Put<>(key, value))));
    }

    private static HttpRequestContext request(String apiKey, String method, String path) {
        var headers = apiKey == null
                      ? Map.<String, List<String>>of()
                      : Map.of("X-API-Key", List.of(apiKey));

        return HttpRequestContext.httpRequestContext(path, method, Map.of(), headers, new byte[0], "mgmt");
    }

    @SuppressWarnings("JBCT-EX-01")
    private static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256")
                                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
