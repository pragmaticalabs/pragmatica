package org.pragmatica.aether.http.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.ApiKeyEntry;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.AuthorizationRole;
import org.pragmatica.aether.http.handler.security.RoleEnforcer;
import org.pragmatica.aether.http.handler.security.RoutePermissionRegistry;
import org.pragmatica.aether.http.handler.security.RouteSecurityPolicy;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// Integration test for the full authentication + authorization pipeline.
///
/// Exercises: SecurityValidator → RoleEnforcer.enforce() with RoutePermissionRegistry
/// to verify end-to-end authorization behavior matching the ManagementServer security flow.
class AuthorizationPipelineTest {

    private static final String ADMIN_KEY = "admin-key-value-123";
    private static final String OPERATOR_KEY = "operator-key-val-1";
    private static final String VIEWER_KEY = "viewer-key-value-12";

    private static final Map<String, ApiKeyEntry> KEY_ENTRIES = Map.of(
        ADMIN_KEY, ApiKeyEntry.apiKeyEntry("admin-svc", Set.of("admin", "service"), "ADMIN"),
        OPERATOR_KEY, ApiKeyEntry.apiKeyEntry("operator-svc", Set.of("service"), "OPERATOR"),
        VIEWER_KEY, ApiKeyEntry.apiKeyEntry("viewer-svc", Set.of("service"), "VIEWER")
    );

    private final SecurityValidator validator = SecurityValidator.apiKeyValidator(KEY_ENTRIES);
    private final RouteSecurityPolicy policy = RouteSecurityPolicy.apiKeyRequired();

    /// Simulates the ManagementServer security pipeline:
    /// authenticate → resolve permission → enforce role.
    private Result<SecurityContext> runPipeline(String apiKey, String method, String path) {
        var request = createRequest(apiKey, method, path);
        var permission = RoutePermissionRegistry.resolve(method, path);
        return validator.validate(request, policy)
                        .flatMap(sc -> RoleEnforcer.enforce(sc, permission));
    }

    @Nested
    class ViewerRole {
        @Test
        void pipeline_success_viewerReadsStatus() {
            runPipeline(VIEWER_KEY, "GET", "/api/status")
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(ctx -> assertThat(ctx.isAuthenticated()).isTrue());
        }

        @Test
        void pipeline_success_viewerReadsMetrics() {
            runPipeline(VIEWER_KEY, "GET", "/api/metrics")
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(ctx -> assertThat(ctx.isAuthenticated()).isTrue());
        }

        @Test
        void pipeline_success_viewerReadsNodes() {
            runPipeline(VIEWER_KEY, "GET", "/api/nodes")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_viewerReadsSlices() {
            runPipeline(VIEWER_KEY, "GET", "/api/slices")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_viewerReadsTraces() {
            runPipeline(VIEWER_KEY, "GET", "/api/traces")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_denied_viewerDeploysBlueprint() {
            runPipeline(VIEWER_KEY, "POST", "/api/blueprint")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_viewerScalesSlice() {
            runPipeline(VIEWER_KEY, "POST", "/api/scale")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_viewerDrainsNode() {
            runPipeline(VIEWER_KEY, "POST", "/api/node/drain/node-1")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_viewerStartsCanary() {
            runPipeline(VIEWER_KEY, "POST", "/api/canary/start")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_viewerShutsDownNode() {
            runPipeline(VIEWER_KEY, "POST", "/api/node/shutdown/node-1")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_success_viewerValidatesBlueprint() {
            runPipeline(VIEWER_KEY, "POST", "/api/blueprint/validate")
                .onFailureRun(() -> fail("Expected success for blueprint validation"));
        }

        @Test
        void pipeline_denied_viewerDeletesLogLevel() {
            runPipeline(VIEWER_KEY, "DELETE", "/api/logging/levels/com.example")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_viewerUploadsMavenArtifact() {
            runPipeline(VIEWER_KEY, "PUT", "/repository/org/example/artifact/1.0/artifact-1.0.jar")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_viewerTriggersSchemaUndo() {
            runPipeline(VIEWER_KEY, "POST", "/api/schema/undo/orders_db")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_viewerTriggersSchemaBaseline() {
            runPipeline(VIEWER_KEY, "POST", "/api/schema/baseline/orders_db")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }
    }

    @Nested
    class OperatorRole {
        @Test
        void pipeline_success_operatorReadsStatus() {
            runPipeline(OPERATOR_KEY, "GET", "/api/status")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_operatorDrainsNode() {
            runPipeline(OPERATOR_KEY, "POST", "/api/node/drain/node-1")
                .onFailureRun(() -> fail("Expected success"))
                .onSuccess(ctx -> assertThat(ctx.isAuthenticated()).isTrue());
        }

        @Test
        void pipeline_success_operatorScalesSlice() {
            runPipeline(OPERATOR_KEY, "POST", "/api/scale")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_operatorStartsCanary() {
            runPipeline(OPERATOR_KEY, "POST", "/api/canary/start")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_operatorStartsBlueGreen() {
            runPipeline(OPERATOR_KEY, "POST", "/api/blue-green/deploy")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_operatorTriggersSchemaRetry() {
            runPipeline(OPERATOR_KEY, "POST", "/api/schema/retry/mydb")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_operatorCreatesBackup() {
            runPipeline(OPERATOR_KEY, "POST", "/api/backup")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_operatorDeploysBlueprintFromArtifact() {
            runPipeline(OPERATOR_KEY, "POST", "/api/blueprint/deploy")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_denied_operatorPublishesRawBlueprint() {
            runPipeline(OPERATOR_KEY, "POST", "/api/blueprint")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_operatorDeletesBlueprint() {
            runPipeline(OPERATOR_KEY, "DELETE", "/api/blueprint/some-id")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_operatorShutsDownNode() {
            runPipeline(OPERATOR_KEY, "POST", "/api/node/shutdown/node-1")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_operatorRestoresBackup() {
            runPipeline(OPERATOR_KEY, "POST", "/api/backup/restore")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_operatorChangesLogLevel() {
            runPipeline(OPERATOR_KEY, "POST", "/api/logging/levels")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }

        @Test
        void pipeline_denied_operatorDeletesLogLevel() {
            runPipeline(OPERATOR_KEY, "DELETE", "/api/logging/levels/com.example")
                .onSuccessRun(() -> fail("Expected failure"))
                .onFailure(AuthorizationPipelineTest::assertAccessDenied);
        }
    }

    @Nested
    class AdminRole {
        @Test
        void pipeline_success_adminReadsStatus() {
            runPipeline(ADMIN_KEY, "GET", "/api/status")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_adminDeploysBlueprint() {
            runPipeline(ADMIN_KEY, "POST", "/api/blueprint")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_adminDeletesBlueprint() {
            runPipeline(ADMIN_KEY, "DELETE", "/api/blueprint/some-id")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_adminShutsDownNode() {
            runPipeline(ADMIN_KEY, "POST", "/api/node/shutdown/node-1")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_adminRestoresBackup() {
            runPipeline(ADMIN_KEY, "POST", "/api/backup/restore")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_adminChangesLogLevel() {
            runPipeline(ADMIN_KEY, "POST", "/api/logging/levels")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_adminDrainsNode() {
            runPipeline(ADMIN_KEY, "POST", "/api/node/drain/node-1")
                .onFailureRun(() -> fail("Expected success"));
        }

        @Test
        void pipeline_success_adminScalesSlice() {
            runPipeline(ADMIN_KEY, "POST", "/api/scale")
                .onFailureRun(() -> fail("Expected success"));
        }
    }

    @Nested
    class AuthenticationFailures {
        @Test
        void pipeline_missingKey_returnsUnauthorized() {
            var request = createRequest(null, "GET", "/api/status");
            var permission = RoutePermissionRegistry.resolve("GET", "/api/status");
            validator.validate(request, policy)
                     .flatMap(sc -> RoleEnforcer.enforce(sc, permission))
                     .onSuccessRun(() -> fail("Expected failure"))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(SecurityError.MissingCredentials.class));
        }

        @Test
        void pipeline_invalidKey_returnsForbidden() {
            var request = createRequest("invalid-key-value-1", "GET", "/api/status");
            var permission = RoutePermissionRegistry.resolve("GET", "/api/status");
            validator.validate(request, policy)
                     .flatMap(sc -> RoleEnforcer.enforce(sc, permission))
                     .onSuccessRun(() -> fail("Expected failure"))
                     .onFailure(cause -> assertThat(cause).isInstanceOf(SecurityError.InvalidCredentials.class));
        }
    }

    @Nested
    class DefaultRoleHandling {
        @Test
        void pipeline_defaultsToAdmin_whenAuthorizationRoleOmitted() {
            var keyWithNoRole = "no-role-key-value-1";
            var entries = Map.of(
                keyWithNoRole, ApiKeyEntry.apiKeyEntry("no-role-svc", Set.of("service"))
            );
            var validatorNoRole = SecurityValidator.apiKeyValidator(entries);
            var request = createRequest(keyWithNoRole, "POST", "/api/blueprint");
            var permission = RoutePermissionRegistry.resolve("POST", "/api/blueprint");
            validatorNoRole.validate(request, policy)
                           .flatMap(sc -> RoleEnforcer.enforce(sc, permission))
                           .onFailureRun(() -> fail("Expected success — default ADMIN role"))
                           .onSuccess(sc -> assertThat(sc.authorizationRole()).isEqualTo(AuthorizationRole.ADMIN));
        }

        @Test
        void pipeline_defaultsToViewer_whenAuthorizationRoleInvalid() {
            var keyWithBadRole = "bad-role-key-value1";
            var entries = Map.of(
                keyWithBadRole, ApiKeyEntry.apiKeyEntry("bad-role-svc", Set.of("service"), "SUPERUSER")
            );
            var validatorBadRole = SecurityValidator.apiKeyValidator(entries);
            var request = createRequest(keyWithBadRole, "GET", "/api/status");
            var permission = RoutePermissionRegistry.resolve("GET", "/api/status");
            validatorBadRole.validate(request, policy)
                            .flatMap(sc -> RoleEnforcer.enforce(sc, permission))
                            .onFailureRun(() -> fail("Expected success — invalid role defaults to VIEWER"))
                            .onSuccess(sc -> assertThat(sc.authorizationRole()).isEqualTo(AuthorizationRole.VIEWER));
        }
    }

    @Nested
    class AnonymousContext {
        @Test
        void anonymousContext_hasViewerRole() {
            var ctx = SecurityContext.securityContext();
            assertThat(ctx.authorizationRole()).isEqualTo(AuthorizationRole.VIEWER);
            assertThat(ctx.isAuthenticated()).isFalse();
        }
    }

    private static void assertAccessDenied(Cause cause) {
        assertThat(cause).isInstanceOf(RoleEnforcer.AuthorizationError.AccessDenied.class);
        assertThat(cause.message()).contains("Access denied");
    }

    private static HttpRequestContext createRequest(String apiKey, String method, String path) {
        var headers = apiKey != null
                      ? Map.of("X-API-Key", List.of(apiKey))
                      : Map.<String, List<String>>of();
        return HttpRequestContext.httpRequestContext(path,
                                                     method,
                                                     Map.of(),
                                                     headers,
                                                     new byte[0],
                                                     "test");
    }
}
