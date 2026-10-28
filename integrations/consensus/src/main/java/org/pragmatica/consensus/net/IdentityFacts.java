/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.net;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// The facts the identity ledger holds (#2429): a GROW-ONLY set of retired NodeIds. A NodeId is retired when a different
/// process presented a boot token for it (terminal removal): from then on no process is admitted for it, for the life of
/// the cluster.
///
/// Only retirements are facts. Which boot token each peer presented is NOT: a token belongs to one process, a restarted
/// node carries a new one, and a core that loaded its peers' old tokens would refuse every restarted peer, which makes a
/// cold restart of the whole cluster impossible. A retirement is the one thing that must outlive a restart.
///
/// The merge of two sets is their union: commutative, associative, idempotent, with no removal path.
///
/// The serialised form is `aether-identity-ledger-v1`, one fact per line in a stable order, and a trailing digest line over
/// everything above it; a file whose digest or any line does not parse reads as lost, never as partial.
public record IdentityFacts(Map<NodeId, String> terminated) {
    private static final String HEADER = "aether-identity-ledger-v1";
    private static final String DIGEST_PREFIX = "D\t";
    private static final IdentityFacts EMPTY = new IdentityFacts(Map.of());
    private static final Cause MALFORMED = Causes.cause("Identity ledger content is malformed");

    public IdentityFacts {
        terminated = Map.copyOf(terminated);
    }

    public static IdentityFacts identityFacts() {
        return EMPTY;
    }

    public static IdentityFacts terminated(NodeId node, String cause) {
        return new IdentityFacts(Map.of(node, cause));
    }

    /// The set as it travels in a Hello: node id text to cause.
    public Map<String, String> toWire() {
        return terminated.entrySet()
                         .stream()
                         .collect(Collectors.toUnmodifiableMap(entry -> entry.getKey()
                                                                             .id(),
                                                               Map.Entry::getValue));
    }

    /// The set a Hello carried; the inverse of [#toWire].
    public static IdentityFacts fromWire(Map<String, String> wire) {
        return new IdentityFacts(wire.entrySet()
                                     .stream()
                                     .collect(Collectors.toUnmodifiableMap(entry -> new NodeId(entry.getKey()),
                                                                           Map.Entry::getValue)));
    }

    public boolean isEmpty() {
        return terminated.isEmpty();
    }

    /// Union. A NodeId terminated in both operands keeps the greater cause (natural string order), so the merge stays
    /// commutative; the cause is for the operator, the fact is the NodeId.
    public IdentityFacts merge(IdentityFacts other) {
        var all = new HashMap<>(terminated);

        other.terminated.forEach((node, cause) -> all.merge(node, cause, IdentityFacts::greater));

        return new IdentityFacts(all);
    }

    /// Whether every retirement of `other` is already in this set.
    public boolean covers(IdentityFacts other) {
        return terminated.keySet()
                         .containsAll(other.terminated.keySet());
    }

    public boolean isTerminated(NodeId node) {
        return terminated.containsKey(node);
    }

    /// The NodeIds no process is admitted for.
    public Set<NodeId> retired() {
        return terminated.keySet();
    }

    /// The serialised ledger content: header, facts in a stable order, trailing digest.
    public String render() {
        var body = Stream.concat(Stream.of(HEADER), terminatedLines()).reduce("", (text, line) -> text + line + "\n");

        return body + DIGEST_PREFIX + digestOf(body) + "\n";
    }

    /// Parse ledger content; any deviation (header, digest, line shape) is a failure, never a partial set.
    public static Result<IdentityFacts> parse(String content) {
        var lines = List.of(content.split("\n", -1));

        return verifyEnvelope(lines).flatMap(IdentityFacts::parseFacts);
    }

    private Stream<String> terminatedLines() {
        return terminated.entrySet()
                         .stream()
                         .sorted(Map.Entry.comparingByKey())
                         .map(entry -> "T\t" + encode(entry.getKey().id()) + "\t" + encode(entry.getValue()));
    }

    private static Result<List<String>> verifyEnvelope(List<String> lines) {
        var size = lines.size();

        if (size < 3 || !lines.get(size - 1).isEmpty() || !HEADER.equals(lines.get(0))) {
            return MALFORMED.result();
        }

        var factLines = lines.subList(1, size - 2);
        var body = HEADER + "\n" + String.join("",
                                               factLines.stream().map(line -> line + "\n").toList());

        return (DIGEST_PREFIX + digestOf(body)).equals(lines.get(size - 2))
               ? Result.success(factLines)
               : MALFORMED.result();
    }

    private static Result<IdentityFacts> parseFacts(List<String> factLines) {
        return factLines.stream()
                        .map(IdentityFacts::parseFact)
                        .reduce(Result.success(EMPTY),
                                IdentityFacts::mergeParsed);
    }

    private static Result<IdentityFacts> mergeParsed(Result<IdentityFacts> accumulated, Result<IdentityFacts> next) {
        return accumulated.flatMap(facts -> next.map(facts::merge));
    }

    private static Result<IdentityFacts> parseFact(String line) {
        var parts = line.split("\t", -1);

        return parts.length == 3 && "T".equals(parts[0])
               ? Result.success(terminated(new NodeId(decode(parts[1])), decode(parts[2])))
               : MALFORMED.result();
    }

    private static String greater(String first, String second) {
        return first.compareTo(second) >= 0
               ? first
               : second;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static String decode(String text) {
        return URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    private static String digestOf(String body) {
        return Result.lift(Causes::fromThrowable,
                           () -> MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)))
                     .map(IdentityFacts::hex)
                     .or("");
    }

    private static String hex(byte[] bytes) {
        var builder = new StringBuilder(bytes.length * 2);

        for (var value : bytes) {
            builder.append(String.format("%02x", value));
        }

        return builder.toString();
    }
}
