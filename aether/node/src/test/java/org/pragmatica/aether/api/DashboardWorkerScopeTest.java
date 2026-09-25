// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.api;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.http.websocket.WebSocketMessage;
import org.pragmatica.http.websocket.WebSocketSession;
import static org.assertj.core.api.Assertions.assertThat;

class DashboardWorkerScopeTest {
    @Test
    void workerRefusesDashboardBeforeRegisteringOrReadingClusterState() {
        var reads = new ArrayList<String>();
        var node = (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
            new Class<?>[]{ManageableNode.class}, (_, method, _) -> {
                reads.add(method.getName());
                return false;
            });
        var publisher = DashboardMetricsPublisher.dashboardMetricsPublisher(() -> node, null);
        var auth = WebSocketAuthenticator.webSocketAuthenticator(SecurityValidator.apiKeyValidator(Set.of()), false);
        var handler = new DashboardWebSocketHandler(publisher, auth);
        var session = new RecordingSession();
        var clients = DashboardWebSocketHandler.connectedClients();
        handler.handle(session, new WebSocketMessage.Open());
        publisher.publishMetrics();
        assertThat(session.open).isFalse();
        assertThat(session.messages).singleElement().asString().contains("INCOMPLETE_CLUSTER_VIEW", "\"completeClusterView\":false")
            .doesNotContain("INITIAL_STATE");
        assertThat(DashboardWebSocketHandler.connectedClients()).isEqualTo(clients);
        assertThat(reads).containsOnly("hasCompleteClusterView");
    }

    private static final class RecordingSession implements WebSocketSession {
        final List<String> messages = new ArrayList<>();
        boolean open = true;
        public String id() { return "worker-scope-test"; }
        public void send(String text) { messages.add(text); }
        public void send(byte[] binary) {}
        public void close() { open = false; }
        public boolean isOpen() { return open; }
    }
}
