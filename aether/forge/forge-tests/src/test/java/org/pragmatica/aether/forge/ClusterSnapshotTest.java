// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;

/// #727 review B1/B2 — the state dump must report what it read, and name what it could not read.
///
/// The stream's own probes ran against a cluster that WAS healthy, where `EmberCluster`'s hardcoded
/// `"healthy"` literal happened to equal the truth; they could not have caught the fabrication. These
/// cases are the ones a healthy cluster never produces: a node reporting a state other than active, a
/// health endpoint that cannot be answered, a cluster whose registry a failed start already cleared,
/// and a cluster with nothing to report at all. No cluster is started here — every input is supplied
/// directly, so each assertion is about the renderer and nothing else.
class ClusterSnapshotTest {
    private static final int MGMT_PORT = 6100;

    @Test
    void unreachableHealthEndpoint_isNamedUnavailable_neverHealthy() {
        var node = new EmberCluster.NodeStatus("si-1", 6000, MGMT_PORT, EmberCluster.STATE_INACTIVE, false);

        var line = ClusterSnapshot.liveNodeLine(node, failingHttp("Connection refused"));

        assertThat(line).describedAs("an unanswerable health probe must be reported as such, by name")
                        .contains("health=unavailable (Connection refused)");
        assertThat(line).describedAs("nothing in the line may claim health that was never observed")
                        .doesNotContain("healthy");
        assertThat(line).describedAs("the node's own state is read from the node, not defaulted")
                        .contains("si-1 state=inactive leader=false");
    }

    @Test
    void reachableHealthEndpoint_reportsTheBodyItRead() {
        var node = new EmberCluster.NodeStatus("si-2", 6001, MGMT_PORT, EmberCluster.STATE_ACTIVE, true);

        var line = ClusterSnapshot.liveNodeLine(node, respondingHttp(200, "{\"quorum\":true}"));

        assertThat(line).isEqualTo("  si-2 state=active leader=true health=200 {\"quorum\":true}");
    }

    /// The positive control for the assertion above: the SAME renderer, given a node that is not
    /// active and a health endpoint answering 503, reproduces both — so "it printed active" is
    /// evidence about the node, not about the renderer having one output.
    @Test
    void aNodeThatIsNotActive_isRenderedAsSuch() {
        var node = new EmberCluster.NodeStatus("si-3", 6002, MGMT_PORT, EmberCluster.STATE_INACTIVE, false);

        var line = ClusterSnapshot.liveNodeLine(node, respondingHttp(503, "{\"quorum\":false}"));

        assertThat(line).isEqualTo("  si-3 state=inactive leader=false health=503 {\"quorum\":false}");
    }

    @Test
    void aClusterWithNothingToReport_saysSo_ratherThanPrintingAnEmptyBlock() {
        var rendered = ClusterSnapshot.render(emptyStatus(), Option.none(), failingHttp("must not be probed"));

        assertThat(rendered).describedAs("no nodes and no failed start is a fact to state, not an empty block")
                            .isEqualTo(ClusterSnapshot.NO_STATE);
    }

    /// Review B2's rendering half: when the live registry is empty because an abort cleared it, the
    /// dump reports the retained snapshot and says that is what it is. Before this, the dump for a
    /// start failure was `leader=none` with zero node lines — empty on exactly the failure it exists
    /// for. That a real failed start populates the retained snapshot at all is pinned separately, by
    /// `EmberClusterPartialStartFailureTest` in `aether/ember`.
    @Test
    void aClearedRegistry_rendersTheRetainedStartFailure_labelledAsCaptured() {
        var captured = new EmberCluster.StartFailure(
            new EmberCluster.ClusterStatus(
                List.of(new EmberCluster.NodeStatus("si-1", 6000, 6100, EmberCluster.STATE_INACTIVE, false),
                        new EmberCluster.NodeStatus("si-2", 6001, 6101, EmberCluster.STATE_INACTIVE, false)),
                "none"),
            Map.of("si-1", "Address already in use"));

        var rendered = ClusterSnapshot.render(emptyStatus(), Option.some(captured), failingHttp("must not be probed"));

        assertThat(rendered).contains("captured when the start failed");
        assertThat(rendered).contains("si-1 state=inactive leader=false");
        assertThat(rendered).contains("si-2 state=inactive leader=false");
        assertThat(rendered).contains("start failures: {si-1=Address already in use}");
        assertThat(rendered).describedAs("a captured snapshot must not carry a live health probe")
                            .doesNotContain("health=");
    }

    /// The live registry wins over a retained failure: a cluster that failed one start and then
    /// started must report what it is doing now, not what it did then.
    @Test
    void aLiveRegistry_isPreferredOverARetainedStartFailure() {
        var live = new EmberCluster.ClusterStatus(
            List.of(new EmberCluster.NodeStatus("si-1", 6000, 6100, EmberCluster.STATE_ACTIVE, true)),
            "si-1");
        var stale = new EmberCluster.StartFailure(new EmberCluster.ClusterStatus(List.of(), "none"),
                                                  Map.of("si-9", "Address already in use"));

        var rendered = ClusterSnapshot.render(live, Option.some(stale), respondingHttp(200, "{\"quorum\":true}"));

        assertThat(rendered).isEqualTo("  leader=si-1\n  si-1 state=active leader=true health=200 {\"quorum\":true}");
    }

    private static EmberCluster.ClusterStatus emptyStatus() {
        return new EmberCluster.ClusterStatus(List.of(), "none");
    }

    private record ProbeFailure(String message) implements Cause {}

    private static HttpOperations failingHttp(String message) {
        return new HttpOperations() {
            @Override
            public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
                return Promise.failure(new ProbeFailure(message));
            }
        };
    }

    private static HttpOperations respondingHttp(int statusCode, String body) {
        return new HttpOperations() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
                return Promise.success((HttpResult<T>) new HttpResult<>(statusCode, noHeaders(), body));
            }
        };
    }

    private static HttpHeaders noHeaders() {
        return HttpHeaders.of(Map.of(), (_, _) -> true);
    }
}
