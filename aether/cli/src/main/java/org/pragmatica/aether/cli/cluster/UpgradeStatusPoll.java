// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import tools.jackson.databind.JsonNode;


/// #1543 F2 -- the poll behind `cluster upgrade --wait`, which survives the replacement of the node it polls.
///
/// The run replaces EVERY node, the one the CLI was pointed at included, so a poll bound to one endpoint never sees the run end: the
/// endpoint goes away mid-run and every later poll fails until the bound. After each answered poll this reads the live membership
/// (`GET /api/v1/cluster/topology`, nodes with a live link) and keeps the members' hosts, each under the scheme and management port of
/// the endpoint in force. A poll that fails moves to the next known live member and carries on from there.
///
/// Limits: the members' hosts are the cluster's own addresses, so they must be reachable from where the CLI runs; with none known yet
/// (the endpoint failed before its first answer) there is nothing to fail over to and the failure is returned as before.
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03", "JBCT-RET-04", "JBCT-UTIL-01", "JBCT-UTIL-02", "JBCT-SEQ-01"})
final class UpgradeStatusPoll implements Supplier<Result<String>> {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final String CONNECTED = "CONNECTED";

    private final Supplier<Result<String>> status;
    private final Supplier<Result<String>> topology;
    private final Consumer<String> switchTo;
    private final Consumer<String> note;
    private String current;
    private List<String> known = List.of();

    private UpgradeStatusPoll(String primary,
                              Supplier<Result<String>> status,
                              Supplier<Result<String>> topology,
                              Consumer<String> switchTo,
                              Consumer<String> note) {
        this.current = primary;
        this.status = status;
        this.topology = topology;
        this.switchTo = switchTo;
        this.note = note;
    }

    /// `status` and `topology` read from whatever endpoint `switchTo` last installed; `primary` is the one in force now.
    static UpgradeStatusPoll upgradeStatusPoll(String primary,
                                               Supplier<Result<String>> status,
                                               Supplier<Result<String>> topology,
                                               Consumer<String> switchTo,
                                               Consumer<String> note) {
        return new UpgradeStatusPoll(primary, status, topology, switchTo, note);
    }

    @Override
    public Result<String> get() {
        var answered = status.get();

        if (answered.isSuccess()) {
            refreshMembers();

            return answered;
        }

        return failOver(answered);
    }

    @Contract
    private void refreshMembers() {
        topology.get().map(json -> liveMemberEndpoints(current, json)).onSuccess(members -> known = members);
    }

    private Result<String> failOver(Result<String> failure) {
        for (var candidate : known) {
            if (candidate.equals(current)) {
                continue;
            }

            switchTo.accept(candidate);
            var answered = status.get();

            if (answered.isSuccess()) {
                note.accept("  The endpoint " + current
                           + " no longer answers; following the run through " + candidate
                           + ".");
                current = candidate;
                refreshMembers();

                return answered;
            }
        }

        switchTo.accept(current);

        return failure;
    }

    /// The endpoints of the members with a live link, under `endpoint`'s scheme and port; the endpoint itself is left out. Package-private
    /// so a test drives the parse on a real topology body.
    static List<String> liveMemberEndpoints(String endpoint, String topologyJson) {
        return MAPPER.readTree(topologyJson)
                     .map(root -> membersOf(endpoint, root))
                     .or(List.of());
    }

    private static List<String> membersOf(String endpoint, JsonNode root) {
        var endpoints = new ArrayList<String>();

        for (var node : root.path("nodeDetails")) {
            if (CONNECTED.equals(node.path("health").asText(""))) {
                hostOf(node.path("address").asText("")).flatMap(host -> endpointFor(endpoint, host))
                      .filter(candidate -> !candidate.equals(endpoint))
                      .filter(candidate -> !endpoints.contains(candidate))
                      .onPresent(endpoints::add);
            }
        }

        return List.copyOf(endpoints);
    }

    private static Option<String> hostOf(String address) {
        var colon = address.lastIndexOf(':');
        var host = colon > 0
                   ? address.substring(0, colon)
                   : address;

        return host.isBlank()
               ? Option.none()
               : Option.some(host);
    }

    private static Option<String> endpointFor(String endpoint, String host) {
        return Result.lift(Causes::fromThrowable,
                           () -> URI.create(endpoint))
                     .option()
                     .filter(uri -> uri.getScheme() != null)
                     .map(uri -> uri.getPort() < 0
                                 ? uri.getScheme() + "://" + host
                                 : uri.getScheme() + "://" + host + ":" + uri.getPort());
    }
}
