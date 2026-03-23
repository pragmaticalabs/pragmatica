package org.pragmatica.aether.http.handler.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.ADMIN;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.OPERATOR;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.VIEWER;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ADMIN_ONLY;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ALL_AUTHENTICATED;
import static org.pragmatica.aether.http.handler.security.RoutePermission.OPERATOR_AND_ABOVE;

class RoleEnforcerTest {

    @Nested
    class AdminPrincipal {
        private final SecurityContext adminContext = contextWithRole(ADMIN);

        @Test
        void enforce_allowed_adminAccessesAdminRoute() {
            RoleEnforcer.enforce(adminContext, ADMIN_ONLY)
                        .onFailureRun(() -> fail("Expected success"))
                        .onSuccess(ctx -> assertThat(ctx).isSameAs(adminContext));
        }

        @Test
        void enforce_allowed_adminAccessesOperatorRoute() {
            RoleEnforcer.enforce(adminContext, OPERATOR_AND_ABOVE)
                        .onFailureRun(() -> fail("Expected success"))
                        .onSuccess(ctx -> assertThat(ctx).isSameAs(adminContext));
        }

        @Test
        void enforce_allowed_adminAccessesViewerRoute() {
            RoleEnforcer.enforce(adminContext, ALL_AUTHENTICATED)
                        .onFailureRun(() -> fail("Expected success"))
                        .onSuccess(ctx -> assertThat(ctx).isSameAs(adminContext));
        }
    }

    @Nested
    class OperatorPrincipal {
        private final SecurityContext operatorContext = contextWithRole(OPERATOR);

        @Test
        void enforce_denied_operatorAccessesAdminRoute() {
            RoleEnforcer.enforce(operatorContext, ADMIN_ONLY)
                        .onSuccessRun(() -> fail("Expected failure"))
                        .onFailure(cause -> {
                            assertThat(cause).isInstanceOf(RoleEnforcer.AuthorizationError.AccessDenied.class);
                            assertThat(cause.message()).contains("OPERATOR");
                            assertThat(cause.message()).contains("ADMIN");
                        });
        }

        @Test
        void enforce_allowed_operatorAccessesOperatorRoute() {
            RoleEnforcer.enforce(operatorContext, OPERATOR_AND_ABOVE)
                        .onFailureRun(() -> fail("Expected success"))
                        .onSuccess(ctx -> assertThat(ctx).isSameAs(operatorContext));
        }

        @Test
        void enforce_allowed_operatorAccessesViewerRoute() {
            RoleEnforcer.enforce(operatorContext, ALL_AUTHENTICATED)
                        .onFailureRun(() -> fail("Expected success"))
                        .onSuccess(ctx -> assertThat(ctx).isSameAs(operatorContext));
        }
    }

    @Nested
    class ViewerPrincipal {
        private final SecurityContext viewerContext = contextWithRole(VIEWER);

        @Test
        void enforce_denied_viewerAccessesAdminRoute() {
            RoleEnforcer.enforce(viewerContext, ADMIN_ONLY)
                        .onSuccessRun(() -> fail("Expected failure"))
                        .onFailure(cause -> {
                            assertThat(cause).isInstanceOf(RoleEnforcer.AuthorizationError.AccessDenied.class);
                            assertThat(cause.message()).contains("VIEWER");
                            assertThat(cause.message()).contains("ADMIN");
                        });
        }

        @Test
        void enforce_denied_viewerAccessesOperatorRoute() {
            RoleEnforcer.enforce(viewerContext, OPERATOR_AND_ABOVE)
                        .onSuccessRun(() -> fail("Expected failure"))
                        .onFailure(cause -> {
                            assertThat(cause).isInstanceOf(RoleEnforcer.AuthorizationError.AccessDenied.class);
                            assertThat(cause.message()).contains("VIEWER");
                            assertThat(cause.message()).contains("OPERATOR");
                        });
        }

        @Test
        void enforce_allowed_viewerAccessesViewerRoute() {
            RoleEnforcer.enforce(viewerContext, ALL_AUTHENTICATED)
                        .onFailureRun(() -> fail("Expected success"))
                        .onSuccess(ctx -> assertThat(ctx).isSameAs(viewerContext));
        }
    }

    @Nested
    class BackwardCompatibility {
        @Test
        void enforce_defaultRoleIsAdmin_forContextWithoutExplicitRole() {
            var context = SecurityContext.securityContext(
                Principal.principal("my-key", Principal.PrincipalType.API_KEY).unwrap(),
                Set.of(Role.SERVICE),
                Map.of()
            );

            assertThat(context.authorizationRole()).isEqualTo(ADMIN);

            RoleEnforcer.enforce(context, ADMIN_ONLY)
                        .onFailureRun(() -> fail("Expected success for default ADMIN role"))
                        .onSuccess(ctx -> assertThat(ctx.authorizationRole()).isEqualTo(ADMIN));
        }
    }

    private static SecurityContext contextWithRole(AuthorizationRole role) {
        return SecurityContext.securityContext(
            Principal.principal("test-principal", Principal.PrincipalType.API_KEY).unwrap(),
            Set.of(Role.SERVICE),
            Map.of(),
            role
        );
    }
}
