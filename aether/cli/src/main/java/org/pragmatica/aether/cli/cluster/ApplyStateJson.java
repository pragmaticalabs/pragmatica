// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import tools.jackson.databind.JsonNode;


/// Reads and writes the schema-2 apply ledger. Written by hand like `bootstrap-state.json`
/// ([BootstrapStateJson]). Reading is strict: a ledger records billed resources, so an unknown phase or state,
/// a missing field or another schema is a refusal naming the offender, never a default.
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-EX-01"})
final class ApplyStateJson {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    private ApplyStateJson() {}

    static String toJson(ApplyState state) {
        return "{\n" + String.join(",\n",
                                   numberField("schemaVersion", ApplyState.SCHEMA_VERSION),
                                   stringField("applyId", state.applyId()),
                                   stringField("clusterName",
                                               state.clusterName().value()),
                                   stringField("configHash", state.configHash()),
                                   numberField("storedVersion", state.storedVersion()),
                                   stringField("startedAt", state.startedAt()),
                                   stringField("phase",
                                               state.phase().name()),
                                   arrayField("actions",
                                              state.actions().stream().map(ApplyStateJson::actionJson).toList()),
                                   arrayField("resources",
                                              state.resources().stream().map(ApplyStateJson::resourceJson).toList()),
                                   arrayField("reservations",
                                              state.reservations()
                                                   .stream()
                                                   .map(ApplyStateJson::reservationJson)
                                                   .toList()),
                                   arrayField("createdResources",
                                              state.createdResources()
                                                   .stream()
                                                   .map(ApplyStateJson::createdResourceJson)
                                                   .toList()),
                                   arrayField("destroyedNodeIds",
                                              state.destroyedNodeIds().stream().map(ApplyStateJson::quoted).toList()))
             + "\n}";
    }

    static Result<ApplyState> fromJson(String json) {
        return MAPPER.readTree(json).flatMap(ApplyStateJson::parseTree);
    }

    /// The cluster name is parsed OUT of the lifted block: a state file naming a cluster outside the
    /// grammar is a corrupt file, and reporting it as such beats letting an unusable name through to
    /// the path resolver and the label selectors that would then match nothing.
    private static Result<ApplyState> parseTree(JsonNode root) {
        return ClusterName.clusterName(root.path("clusterName").asText()).flatMap(clusterName -> liftedParse(root,
                                                                                                             clusterName));
    }

    private static Result<ApplyState> liftedParse(JsonNode root, ClusterName clusterName) {
        return Result.lift(ApplyState.ParseError::new, () -> doParse(root, clusterName));
    }

    private static ApplyState doParse(JsonNode root, ClusterName clusterName) {
        requireSchema(root);

        return ApplyState.applyState(requiredApplyId(root),
                                     clusterName,
                                     requiredText(root, "configHash"),
                                     requiredNumber(root, "storedVersion").asLong(),
                                     requiredText(root, "startedAt"),
                                     parseEnum(ApplyPhase::valueOf, requiredText(root, "phase"), "phase"),
                                     parseArray(root, "actions", ApplyStateJson::parseAction),
                                     parseArray(root, "resources", ApplyStateJson::parseResource),
                                     parseArray(root, "reservations", ApplyStateJson::parseReservation),
                                     parseArray(root,
                                                "createdResources",
                                                element -> BootstrapStateJson.parseSingleResourceNode(element,
                                                                                                      clusterName)),
                                     parseArray(root, "destroyedNodeIds", JsonNode::asText));
    }

    private static void requireSchema(JsonNode root) {
        var found = root.path("schemaVersion");

        if (!found.isNumber() || found.asInt() != ApplyState.SCHEMA_VERSION) {
            throw new IllegalArgumentException("apply state schema " + (found.isMissingNode()
                                                                        ? "(none, a pre-2 file)"
                                                                        : found.asText())
                                              + " is not supported, this CLI reads and writes schema " + ApplyState.SCHEMA_VERSION
                                              + "; the file was left untouched");
        }
    }

    private static String requiredApplyId(JsonNode root) {
        var applyId = requiredText(root, "applyId");

        if (!ApplyState.isApplyId(applyId)) {
            throw new IllegalArgumentException("applyId '" + applyId + "' is not a lowercase ULID");
        }

        return applyId;
    }

    private static ApplyAction parseAction(JsonNode node) {
        return ApplyAction.applyAction(requiredText(node, "key"),
                                       requiredNumber(node, "planned").asInt(),
                                       parseEnum(ApplyAction.Status::valueOf,
                                                 requiredText(node, "status"),
                                                 "action status"));
    }

    private static ApplyResource parseResource(JsonNode node) {
        return ApplyResource.applyResource(requiredText(node, "actionKey"),
                                           requiredText(node, "nodeId"),
                                           requiredText(node, "provider"),
                                           optionalText(node, "serverId"),
                                           parseEnum(ApplyResource.State::valueOf,
                                                     requiredText(node, "state"),
                                                     "resource state"));
    }

    private static ApplyReservation parseReservation(JsonNode node) {
        return ApplyReservation.applyReservation(requiredText(node, "source"),
                                                 requiredText(node, "role"),
                                                 requiredNumber(node, "fromN").asLong(),
                                                 requiredNumber(node, "toN").asLong());
    }

    private static <T> List<T> parseArray(JsonNode parent, String field, Function<JsonNode, T> parser) {
        var node = parent.path(field);

        if (!node.isArray()) {
            throw new IllegalArgumentException("field '" + field + "' is missing or not an array");
        }

        var parsed = new ArrayList<T>();

        for (var element : node) {
            parsed.add(parser.apply(element));
        }

        return List.copyOf(parsed);
    }

    private static <E> E parseEnum(Function<String, E> valueOf, String name, String what) {
        try {
            return valueOf.apply(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown " + what + " '" + name + "'", e);
        }
    }

    private static String requiredText(JsonNode parent, String field) {
        var node = parent.path(field);

        if (!node.isString() || node.asText().isBlank()) {
            throw new IllegalArgumentException("field '" + field + "' is missing or blank");
        }

        return node.asText();
    }

    private static JsonNode requiredNumber(JsonNode parent, String field) {
        var node = parent.path(field);

        if (!node.isNumber()) {
            throw new IllegalArgumentException("field '" + field + "' is missing or not a number");
        }

        return node;
    }

    private static Option<String> optionalText(JsonNode parent, String field) {
        var node = parent.path(field);

        return node.isString()
               ? Option.some(node.asText())
               : Option.none();
    }

    private static String actionJson(ApplyAction action) {
        return "    {" + String.join(", ",
                                     inlineString("key", action.key()),
                                     "\"planned\": " + action.planned(),
                                     inlineString("status",
                                                  action.status().name()))
             + "}";
    }

    private static String resourceJson(ApplyResource row) {
        return "    {" + String.join(", ",
                                     inlineString("actionKey", row.actionKey()),
                                     inlineString("nodeId", row.nodeId()),
                                     inlineString("provider", row.provider()),
                                     row.serverId()
                                        .map(id -> inlineString("serverId", id) + ", ")
                                        .or("") + inlineString("state",
                                                               row.state().name()))
             + "}";
    }

    private static String reservationJson(ApplyReservation reservation) {
        return "    {" + String.join(", ",
                                     inlineString("source", reservation.source()),
                                     inlineString("role", reservation.role()),
                                     "\"fromN\": " + reservation.fromN(),
                                     "\"toN\": " + reservation.toN())
             + "}";
    }

    private static String createdResourceJson(CreatedResource resource) {
        var sb = new StringBuilder("    ");

        BootstrapStateJson.appendResourceJson(sb, resource);

        return sb.toString();
    }

    private static String numberField(String key, long value) {
        return "  \"" + key + "\": " + value;
    }

    private static String stringField(String key, String value) {
        return "  \"" + key + "\": " + quoted(value);
    }

    private static String inlineString(String key, String value) {
        return "\"" + key + "\": " + quoted(value);
    }

    private static String arrayField(String key, List<String> elements) {
        return elements.isEmpty()
               ? "  \"" + key + "\": []"
               : "  \"" + key + "\": [\n" + String.join(",\n", elements) + "\n  ]";
    }

    private static String quoted(String value) {
        return "\"" + value.replace("\\", "\\\\")
                           .replace("\"", "\\\"")
                           .replace("\n", "\\n")
                           .replace("\r", "\\r")
                           .replace("\t", "\\t")
             + "\"";
    }
}
