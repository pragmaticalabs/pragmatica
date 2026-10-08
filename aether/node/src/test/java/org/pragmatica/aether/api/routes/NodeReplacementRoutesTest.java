package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.api.routes.NodeReplacementRoutes.ReplaceNodeRequest;
import org.pragmatica.aether.api.routes.NodeReplacementRoutes.SettleRequest;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService.Refusal;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService.Settlement;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #1543 E2: the replacement routes start CTM or EXTERNAL replacements by whether the caller named an id, settle with a
/// validated outcome, and answer a refusal as a request/state problem (404/400/409), never as a server fault.
class NodeReplacementRoutesTest {
    private static final NodeId OLD = NodeId.nodeId("old-1").unwrap();
    private static final NodeId CHOSEN = NodeId.nodeId("fresh-9").unwrap();

    private final RecordingService service = new RecordingService();
    private final NodeReplacementRoutes routes = routesOver(service);

    @Test
    void replace_withoutId_startsCtmReplacement() {
        var entry = routes.replace("old-1", new ReplaceNodeRequest(null, "2.0.0")).await().unwrap();

        assertThat(service.calls).containsExactly("begin old-1 2.0.0");
        assertThat(entry.mode()).isEqualTo(NodeReplacementValue.MODE_CTM);
    }

    @Test
    void replace_withChosenId_startsExternalReplacementOnThatId() {
        var entry = routes.replace("old-1", new ReplaceNodeRequest("fresh-9", null)).await().unwrap();

        assertThat(service.calls).containsExactly("beginExternal old-1 fresh-9 ");
        assertThat(entry.replacement()).isEqualTo("fresh-9");
        assertThat(entry.mode()).isEqualTo(NodeReplacementValue.MODE_EXTERNAL);
    }

    @Test
    void refusals_answerAsRequestOrStateProblems() {
        assertThat(statusOf(new Refusal.UnknownNode(OLD))).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(statusOf(new Refusal.RoleNotSupported(OLD, "dht"))).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(statusOf(new Refusal.ReplacementIdInUse(CHOSEN))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(new Refusal.AlreadyReplacing(OLD))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(new Refusal.NothingToSettle(OLD))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(new Refusal.Conflict(OLD))).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(new Refusal.NotLeader())).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void refusal_reachesTheCallerThroughReplace() {
        service.refuse = new Refusal.ReplacementIdInUse(CHOSEN);

        var result = routes.replace("old-1", new ReplaceNodeRequest("fresh-9", null)).await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void settle_mapsTheOutcomeAndRejectsAnythingElse() {
        assertThat(routes.settle("old-1", new SettleRequest("keep-new")).await().isSuccess()).isTrue();
        assertThat(routes.settle("old-1", new SettleRequest("roll-back")).await().isSuccess()).isTrue();
        assertThat(service.calls).containsExactly("settle old-1 KEEP_NEW", "settle old-1 ROLL_BACK");

        var bad = routes.settle("old-1", new SettleRequest("whatever")).await();

        assertThat(bad.isFailure()).isTrue();
        assertThat(service.calls).hasSize(2);
        bad.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementServerError.InvalidRequest.class));
    }

    @Test
    void list_isSortedByOriginal_notInTheOrderTheServiceHoldsThem() {
        service.records.put(NodeId.nodeId("b-2").unwrap(), record(CHOSEN, NodeReplacementValue.MODE_CTM));
        service.records.put(NodeId.nodeId("a-1").unwrap(), record(CHOSEN, NodeReplacementValue.MODE_CTM));

        assertThat(routes.list()).extracting(NodeReplacementRoutes.ReplacementEntry::original)
                                 .containsExactly("a-1", "b-2");
    }

    /// A blank id is "no id": the leader provisions the node (CTM), it is not an EXTERNAL replacement of a node called "".
    @Test
    void replace_withABlankId_isTheSameAsNoId_theLeaderProvisions() {
        routes.replace("old-1", new ReplaceNodeRequest("   ", null)).await();
        routes.replace("old-1", new ReplaceNodeRequest("", null)).await();

        assertThat(service.calls).containsExactly("begin old-1 ", "begin old-1 ");
    }

    /// All three routes are answered by the leader (it drives the phases and commits the records) and are what the route table
    /// says they are.
    @Test
    void theRoutesAreLeaderBound_withTheDocumentedMethodAndPath() {
        assertThat(org.pragmatica.aether.management.route.ManagementRoute.NODE_REPLACE.target()).isEqualTo(org.pragmatica.aether.management.route.RouteTarget.LEADER);
        assertThat(org.pragmatica.aether.management.route.ManagementRoute.NODE_REPLACEMENTS.target()).isEqualTo(org.pragmatica.aether.management.route.RouteTarget.LEADER);
        assertThat(org.pragmatica.aether.management.route.ManagementRoute.NODE_REPLACEMENT_SETTLE.target()).isEqualTo(org.pragmatica.aether.management.route.RouteTarget.LEADER);
    }

    /// B1 (v-2042): a node that has no replacement service answers with a refusal; it never throws.
    @Test
    void aNodeWithoutAService_refusesInsteadOfThrowing() {
        var node = mock(ManageableNode.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        var unavailable = NodeReplacementRoutes.nodeReplacementRoutes(() -> node);
        var result = unavailable.replace("old-1", new ReplaceNodeRequest(null, null)).await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(ManagementServerError.Conflict.class));
        assertThat(unavailable.list()).isEmpty();
    }

    private static HttpStatus statusOf(Cause refusal) {
        return ((HttpStatusAware) NodeReplacementRoutes.asManagementError(refusal)).httpStatus();
    }

    private static NodeReplacementValue record(NodeId replacement, String mode) {
        return new NodeReplacementValue(replacement,
                                        "core",
                                        NodeReplacementPhase.PROVISIONING,
                                        0L,
                                        "",
                                        "",
                                        mode,
                                        0,
                                        "",
                                        0L);
    }

    private static NodeReplacementRoutes routesOver(NodeReplacementService service) {
        var node = mock(ManageableNode.class);

        when(node.nodeReplacementService()).thenReturn(service);

        return NodeReplacementRoutes.nodeReplacementRoutes(() -> node);
    }

    private static final class RecordingService implements NodeReplacementService {
        final List<String> calls = new ArrayList<>();
        final Map<NodeId, NodeReplacementValue> records = new java.util.LinkedHashMap<>();
        Cause refuse;

        @Override
        public Promise<NodeReplacementValue> begin(NodeId original, String targetVersion) {
            calls.add("begin " + original.id() + " " + targetVersion);

            return refuse == null
                   ? Promise.success(record(NodeId.nodeId("generated-1").unwrap(), NodeReplacementValue.MODE_CTM))
                   : refuse.promise();
        }

        @Override
        public Promise<NodeReplacementValue> beginExternal(NodeId original, NodeId replacement, String targetVersion) {
            calls.add("beginExternal " + original.id() + " " + replacement.id() + " " + targetVersion);

            return refuse == null
                   ? Promise.success(record(replacement, NodeReplacementValue.MODE_EXTERNAL))
                   : refuse.promise();
        }

        @Override
        public Option<NodeReplacementValue> status(NodeId original) {
            return Option.option(records.get(original));
        }

        @Override
        public Map<NodeId, NodeReplacementValue> all() {
            return records;
        }

        @Override
        public Promise<Unit> settle(NodeId original, Settlement settlement) {
            calls.add("settle " + original.id() + " " + settlement);

            return Promise.unitPromise();
        }
    }
}
