package org.pragmatica.aether.http.handler.security;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.ADMIN;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.OPERATOR;
import static org.pragmatica.aether.http.handler.security.AuthorizationRole.VIEWER;

class AuthorizationRoleTest {
    @Nested
    class AdminAccess {
        @Test
        void admin_hasAccessToAdminRoutes() {
            assertThat(ADMIN.hasAccess(ADMIN)).isTrue();
        }

        @Test
        void admin_hasAccessToOperatorRoutes() {
            assertThat(ADMIN.hasAccess(OPERATOR)).isTrue();
        }

        @Test
        void admin_hasAccessToViewerRoutes() {
            assertThat(ADMIN.hasAccess(VIEWER)).isTrue();
        }
    }

    @Nested
    class OperatorAccess {
        @Test
        void operator_noAccessToAdminRoutes() {
            assertThat(OPERATOR.hasAccess(ADMIN)).isFalse();
        }

        @Test
        void operator_hasAccessToOperatorRoutes() {
            assertThat(OPERATOR.hasAccess(OPERATOR)).isTrue();
        }

        @Test
        void operator_hasAccessToViewerRoutes() {
            assertThat(OPERATOR.hasAccess(VIEWER)).isTrue();
        }
    }

    @Nested
    class ViewerAccess {
        @Test
        void viewer_noAccessToAdminRoutes() {
            assertThat(VIEWER.hasAccess(ADMIN)).isFalse();
        }

        @Test
        void viewer_noAccessToOperatorRoutes() {
            assertThat(VIEWER.hasAccess(OPERATOR)).isFalse();
        }

        @Test
        void viewer_hasAccessToViewerRoutes() {
            assertThat(VIEWER.hasAccess(VIEWER)).isTrue();
        }
    }
}
