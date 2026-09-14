// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AetherConfig;
import org.pragmatica.aether.config.ConfigLoader;
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

/// The published `ghcr.io/pragmaticalabs/aether-node` image must not carry a built-in
/// administrative credential.
///
/// WHERE THIS SITS, AND WHY IT READS A FILE. The claim is about an ARTIFACT — the config the
/// release workflow bakes into the image — so the subject under test is the file on disk, loaded
/// through the real [ConfigLoader], not a hand-built config object. A fixture that constructed an
/// empty key map and asserted refusal would assert only that an empty map is empty; it could not
/// fail if someone put a key back in the file, which is the single thing this test exists to catch.
///
/// The assertions then run the pipeline `ManagementServerImpl#validateManagementSecurity` runs —
/// `securityValidator.validate(...)` then `RoleEnforcer.enforce(...)` — against the validator
/// wiring `AetherNode` installs for the management plane, with an EMPTY KV store, which is a
/// freshly-started node before any bootstrap admin key is registered. That is the state a
/// `docker run` of the published image is in.
///
/// A FAIL-OPEN LOOKS EXACTLY LIKE A FAIL-CLOSED FROM A GREEN TEST, so every denial below is
/// accompanied by controls that run INSIDE this same class: the config was really parsed from the
/// real file (not defaulted empty because the path was wrong), the Dockerfile really copies THAT
/// file, the route really resolves to an ADMIN permission, and the harness can really reach a
/// success. Without those, "refused" is indistinguishable from "never arrived".
class PublishedImageConfigFailsClosedTest {
    /// The file `docker/aether-node/Dockerfile` COPYs to `/app/aether.toml`, via its
    /// `ARG CONFIG_PATH` default, in the image the release workflow pushes to ghcr.
    private static final String SHIPPED_CONFIG = "aether/docker/aether-node/aether.toml";
    private static final String NODE_DOCKERFILE = "aether/docker/aether-node/Dockerfile";

    /// The constant this file used to declare with `authorization_role = "ADMIN"`. It is a literal
    /// here on purpose: it is already public in this repository's history, and the whole point is
    /// that it must now authenticate NOTHING.
    private static final String FORMERLY_BAKED_KEY = "aether-integration-test-key";

    /// ADMIN-gated by `ManagementRoutePermissions.adminRoutes()`.
    private static final String PRIVILEGED_METHOD = "POST";
    private static final String PRIVILEGED_PATH = "/api/v1/cluster/keys";

    private static final String BOOTSTRAP_KEY = "bootstrap-admin-key-value";
    private static final String BOOTSTRAP_KEY_ID = "bootstrap-admin";
    /// `MissingCredentials` -> 401, `InvalidCredentials` -> 403, per
    /// [ManagementServerImpl#resolveSecurityErrorStatus]. The distinction is the point: a caller
    /// PRESENTING the formerly-baked key is refused as INVALID (403), which proves the key was
    /// looked up and not found. A caller presenting nothing is refused as MISSING (401). Asserting
    /// the wrong one of these would still be asserting a refusal, but not this refusal.
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    /// Exactly the wiring `AetherNode` installs for the management plane: the config validator over
    /// `[app-http.api-keys]`, wrapped by the KV-aware validator that consults cluster-held keys.
    private SecurityValidator managementValidator(AetherConfig config) {
        return SecurityValidator.kvStoreAwareValidator(SecurityValidator.apiKeyValidator(config.appHttp().apiKeys()),
                                                       () -> kvStore);
    }

    private Result<SecurityContext> pipeline(SecurityValidator validator, String apiKey, String method, String path) {
        return validator.validate(request(apiKey, method, path), SecurityPolicy.apiKeyRequired())
                        .flatMap(sc -> RoleEnforcer.enforce(sc, ManagementServerImpl.resolvePermission(method, path)));
    }

    @Nested
    class ShippedImageDefault {
        @Test
        void formerlyBakedKey_isRefused_onAnAdminRoute() {
            var validator = managementValidator(shippedConfig());

            pipeline(validator, FORMERLY_BAKED_KEY, PRIVILEGED_METHOD, PRIVILEGED_PATH)
                .onSuccess(sc -> fail("the published image still honours a built-in credential, as "
                                      + sc.authorizationRole()))
                .onFailure(cause -> {
                    assertThat(cause).isInstanceOf(SecurityError.InvalidCredentials.class);
                    assertThat(ManagementServerImpl.resolveSecurityErrorStatus(cause).code())
                        .isEqualTo(HTTP_FORBIDDEN);
                });
        }

        @Test
        void anonymousCaller_isRefused_onAnAdminRoute() {
            var validator = managementValidator(shippedConfig());

            pipeline(validator, null, PRIVILEGED_METHOD, PRIVILEGED_PATH)
                .onSuccess(sc -> fail("anonymous caller was served as " + sc.authorizationRole()))
                .onFailure(PublishedImageConfigFailsClosedTest::assertMissingCredentialWith401);
        }

        @Test
        void formerlyBakedKey_isRefused_onAReadRouteToo() {
            var validator = managementValidator(shippedConfig());

            pipeline(validator, FORMERLY_BAKED_KEY, "GET", "/api/v1/nodes/status")
                .onSuccess(sc -> fail("read route served a built-in credential as " + sc.authorizationRole()));
        }

        @Test
        void shippedConfig_declaresNoApiKeyAtAll() {
            assertThat(shippedConfig().appHttp().apiKeys())
                .as("the config baked into the published image must declare no credential")
                .isEmpty();
        }

        /// Textual, and deliberately independent of the parser: a stanza could be added in a form
        /// [ConfigLoader] does not recognise today but a later version does.
        ///
        /// COMMENTS ARE STRIPPED FIRST, and that is not a convenience — the first version of this
        /// test failed against a correctly-fixed file. The shipped config now EXPLAINS the absent
        /// stanza, so its guidance prose contains the literal `[app-http.api-keys.<key>]`, and a
        /// raw substring search matched the very comment documenting the fix. An instrument whose
        /// pattern is drawn from the vocabulary of the text it inspects reports on itself.
        @Test
        void shippedConfigFile_declaresNoApiKeyOutsideComments() {
            var declarations = declarationLines(SHIPPED_CONFIG);

            assertThat(declarations)
                .as("no key declaration in the shipped config, comments excluded")
                .noneMatch(line -> line.contains("api-keys") || line.contains("api_keys"));
        }

        /// Positive control for the stripper above: a filter that returned nothing would satisfy
        /// `noneMatch` for every possible input. This proves it kept real settings, and that the
        /// raw file really does discuss api-keys in the prose the stripper removed.
        @Test
        void commentStripper_keepsSettingsAndRemovesOnlyComments() {
            var declarations = declarationLines(SHIPPED_CONFIG);

            assertThat(declarations).isNotEmpty();
            assertThat(declarations).anyMatch(line -> line.equals("[app-http]"));
            assertThat(declarations).anyMatch(line -> line.startsWith("enabled ="));
            assertThat(read(SHIPPED_CONFIG))
                .as("the raw file DOES mention api-keys in guidance prose, which is why stripping matters")
                .contains("[app-http.api-keys.");
        }
    }

    /// Controls. Each one runs in this same class against the same artifacts, and each excludes a
    /// specific way the refusals above could be true for a reason other than the one claimed.
    @Nested
    class Controls {
        /// THE NON-VACUITY CONTROL. If the path were wrong, [ConfigLoader] would hand back a config
        /// whose `apiKeys()` is empty for an entirely different reason and every assertion above
        /// would pass while reading nothing. These are non-credential settings that exist ONLY in
        /// the shipped file, so their presence proves the parse reached it.
        @Test
        void shippedConfig_wasActuallyParsedFromTheRealFile() {
            var config = shippedConfig();

            assertThat(config.appHttp().enabled())
                .as("[app-http] enabled = true is set in the shipped file")
                .isTrue();
            assertThat(read(SHIPPED_CONFIG))
                .as("marker settings unique to the shipped file")
                .contains("[messaging.click-events]")
                .contains("provider = \"docker\"");
        }

        /// Ties the file under test to the image. Without this, the class could be asserting about a
        /// file the published image never copies.
        @Test
        void dockerfile_copiesTheFileUnderTest_intoTheImage() {
            var dockerfile = read(NODE_DOCKERFILE);

            assertThat(dockerfile)
                .as("CONFIG_PATH default is the file this test asserts about")
                .contains("ARG CONFIG_PATH=docker/aether-node/aether.toml");
            assertThat(dockerfile).contains("COPY --chown=aether:aether ${CONFIG_PATH} /app/aether.toml");
            assertThat(dockerfile).contains("--config=/app/aether.toml");
        }

        /// The refusals are authentication, not a 404 in disguise and not a role check.
        @Test
        void privilegedRoute_resolvesToAnAdminPermission() {
            assertThat(ManagementServerImpl.resolvePermission(PRIVILEGED_METHOD, PRIVILEGED_PATH).minimumRole())
                .isEqualTo(AuthorizationRole.ADMIN);
        }

        /// The harness CAN reach a success: the cluster's own bootstrap admin key — the credential
        /// that replaces the baked one — authenticates through the same validator and pipeline. So
        /// "refused" above is not "nothing works here".
        @Test
        void bootstrapAdminKey_isServedWithAdmin_provingTheHarnessReachesSuccess() {
            var validator = managementValidator(shippedConfig());

            registerKvKey(BOOTSTRAP_KEY_ID, BOOTSTRAP_KEY, "ADMIN");

            pipeline(validator, BOOTSTRAP_KEY, PRIVILEGED_METHOD, PRIVILEGED_PATH)
                .onFailure(cause -> fail("the cluster bootstrap admin key was refused: " + cause.message()))
                .onSuccess(sc -> assertThat(sc.authorizationRole()).isEqualTo(AuthorizationRole.ADMIN));
        }

        /// A key declared in config still authenticates — this fix removes a BAKED credential, it
        /// does not disable the configuration mechanism. Proves the empty result above comes from
        /// the file's content and not from a validator that stopped honouring config keys.
        @Test
        void configuredKeys_areStillHonoured_whenAFileDeclaresThem() {
            var configured = ConfigLoader.loadFromString("""
                                                         [app-http]
                                                         enabled = true

                                                         [app-http.api-keys.operator-declared-key]
                                                         authorization_role = "ADMIN"
                                                         """)
                                         .onFailure(cause -> fail("control config did not load: " + cause.message()))
                                         .unwrap();

            assertThat(configured.appHttp().apiKeys()).containsKey("operator-declared-key");
            pipeline(managementValidator(configured), "operator-declared-key", PRIVILEGED_METHOD, PRIVILEGED_PATH)
                .onFailure(cause -> fail("a file-declared key was refused: " + cause.message()))
                .onSuccess(sc -> assertThat(sc.authorizationRole()).isEqualTo(AuthorizationRole.ADMIN));
        }

        /// `ConfigLoader.resolveApiKeys` reads `AETHER_API_KEYS` ahead of any TOML, so an ambient
        /// value in the runner's environment would silently replace the subject of this whole class.
        /// Fail loudly rather than skip: a skipped security test is indistinguishable from a passing
        /// one in a summary.
        @Test
        void ambientEnvironment_doesNotSupplyKeys() {
            assertThat(System.getenv("AETHER_API_KEYS"))
                .as("AETHER_API_KEYS is set in this environment and overrides the file under test; "
                    + "unset it before running this suite")
                .isNull();
        }
    }

    private static void assertMissingCredentialWith401(Cause cause) {
        assertThat(cause).isInstanceOf(SecurityError.MissingCredentials.class);
        assertThat(cause).isNotInstanceOf(RoleEnforcer.AuthorizationError.AccessDenied.class);
        assertThat(ManagementServerImpl.resolveSecurityErrorStatus(cause).code()).isEqualTo(HTTP_UNAUTHORIZED);
    }

    private static AetherConfig shippedConfig() {
        return ConfigLoader.load(repositoryRoot().resolve(SHIPPED_CONFIG))
                           .onFailure(cause -> fail("the shipped node config did not load: " + cause.message()))
                           .unwrap();
    }

    /// Non-blank, non-comment lines of a TOML file — the lines that actually declare something.
    private static List<String> declarationLines(String relativePath) {
        return read(relativePath).lines()
                                 .map(String::trim)
                                 .filter(line -> !line.isEmpty())
                                 .filter(line -> !line.startsWith("#"))
                                 .toList();
    }

    private static String read(String relativePath) {
        try {
            return Files.readString(repositoryRoot().resolve(relativePath));
        } catch (Exception e) {
            throw new AssertionError("could not read " + relativePath, e);
        }
    }

    /// Walks up from the compiled test class rather than trusting `user.dir`, then VALIDATES the
    /// result against marker paths — an unvalidated walk that lands one directory short returns a
    /// path whose `resolve` silently yields a non-existent file.
    private static Path repositoryRoot() {
        var current = codeSourceLocation();

        while (current != null) {
            if (java.nio.file.Files.isRegularFile(current.resolve(SHIPPED_CONFIG))
                && java.nio.file.Files.isRegularFile(current.resolve(NODE_DOCKERFILE))) {
                return current;
            }

            current = current.getParent();
        }

        throw new AssertionError("repository root not found above " + codeSourceLocation()
                                 + " — no ancestor holds both " + SHIPPED_CONFIG + " and " + NODE_DOCKERFILE);
    }

    private static Path codeSourceLocation() {
        try {
            return Path.of(PublishedImageConfigFailsClosedTest.class.getProtectionDomain()
                                                                    .getCodeSource()
                                                                    .getLocation()
                                                                    .toURI());
        } catch (Exception e) {
            throw new AssertionError("cannot locate the test's own code source", e);
        }
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
