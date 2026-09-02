// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Test;

import org.pragmatica.aether.api.ManagementApiResponses.ScaleRequest;
import org.pragmatica.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the wire contract of `POST /api/cluster/scale` against what `aether cluster scale` sends.
///
/// This boundary was broken and nothing could see it. The CLI posted
/// `{"count":…,"role":…,"source":…}` while [ScaleRequest] read a lone `coreCount`, so every scale
/// request arrived without a usable count. The DTOs live in `aether/node` and the CLI cannot depend
/// on that module, so the contract was spelled twice — a Java record on the server, a hand-built
/// string on the client — with nothing tying the two spellings together. The only existing
/// `CLUSTER_SCALE` tests assert which node the route dispatches to, which stays green either way.
///
/// The matching CLI-side assertion is `ClusterScaleCommandTest`. Together they catch a unilateral
/// rename on either side; only moving the DTOs into a shared module would make it a compile error.
class ScaleRequestContractTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    /// The exact body `ClusterScaleCommand.buildScaleJson` produces.
    private static final String CLI_BODY =
            "{\"source\":\"eu-central\",\"role\":\"worker\",\"count\":8,\"expectedVersion\":42}";

    @Test
    void scaleRequest_deserializesEveryFieldTheCliSends() {
        var parsed = MAPPER.readString(CLI_BODY, ScaleRequest.class);

        assertThat(parsed.isSuccess())
                .as("CLI scale body must deserialize into ScaleRequest")
                .isTrue();

        parsed.onSuccess(request -> {
            assertThat(request.source()).isEqualTo("eu-central");
            assertThat(request.role()).isEqualTo("worker");
            assertThat(request.count()).isEqualTo(8);
            assertThat(request.expectedVersion()).isEqualTo(42);
        });
    }

    /// A blank source is the "infer it" signal, not a missing field — it must survive the wire so the
    /// server can resolve it against the topology.
    @Test
    void scaleRequest_carriesBlankSource_asTheInferenceSignal() {
        var parsed = MAPPER.readString("{\"source\":\"\",\"role\":\"core\",\"count\":5,\"expectedVersion\":1}",
                                       ScaleRequest.class);

        parsed.onSuccess(request -> {
            assertThat(request.source()).isEmpty();
            assertThat(request.role()).isEqualTo("core");
            assertThat(request.count()).isEqualTo(5);
        });
        assertThat(parsed.isSuccess()).isTrue();
    }

    /// The regression itself: the pre-fix body carried no `count` the record could read.
    ///
    /// Observed behaviour (executed, not inferred): the mapper REJECTS this body with
    /// `Type mismatch: expected int, got unknown … ["count"]` — it trips on the absent required
    /// `count` rather than complaining about the unknown `coreCount`. So the pre-fix command failed
    /// at deserialization and never reached the quorum check.
    ///
    /// Asserted as a disjunction anyway, because which of the two safe outcomes occurs is a mapper
    /// configuration detail this contract does not depend on — only the unsafe one matters, and that
    /// is `coreCount` reaching `count` and silently scaling the cluster to a number the operator
    /// never sent through this field. Written as a disjunction rather than an `onSuccess` block
    /// because that block does not execute on a parse failure, so the test would have passed
    /// vacuously in exactly the case it exists to cover.
    @Test
    void scaleRequest_preFixBodyWithCoreCount_doesNotProduceAUsableCount() {
        var parsed = MAPPER.readString("{\"coreCount\":5,\"expectedVersion\":42}", ScaleRequest.class);

        var refusedOrZero = parsed.fold(_ -> true, request -> request.count() == 0);

        assertThat(refusedOrZero)
                .as("a body without 'count' must be refused or yield count=0, never count=5")
                .isTrue();
    }
}
