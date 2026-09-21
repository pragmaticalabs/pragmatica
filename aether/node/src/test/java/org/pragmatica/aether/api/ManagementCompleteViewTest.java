// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.management.route.ManagementRouteError;
import static org.assertj.core.api.Assertions.assertThat;

class ManagementCompleteViewTest {
    @Test
    void forwardedGlobalReadRequiresCompleteCoreView() {
        var rejected = ManagementServerImpl.checkCompleteView("GET", "/api/v1/nodes/live", false);
        assertThat(rejected.isFailure()).isTrue();
        boolean incompleteView = rejected.fold(cause -> cause instanceof ManagementRouteError.IncompleteClusterView, _ -> false);
        assertThat(incompleteView).isTrue();
        assertThat(ManagementServerImpl.checkCompleteView("GET", "/api/v1/nodes/live", true).isSuccess()).isTrue();
    }

    @Test
    void localDiagnosticRemainsLocalOnWorker() {
        assertThat(ManagementServerImpl.checkCompleteView("GET", "/health/live", false).isSuccess()).isTrue();
        assertThat(ManagementServerImpl.checkCompleteView("GET", "/api/v1/cluster/membership", false).isSuccess()).isTrue();
    }
}
