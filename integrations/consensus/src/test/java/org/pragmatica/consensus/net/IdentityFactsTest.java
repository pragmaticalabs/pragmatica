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

import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// The identity ledger's content (#2429): a grow-only set of retired NodeIds whose merge is a union, and whose serialised
/// form reads back whole or not at all.
class IdentityFactsTest {
    private static final int TRIALS = 200;
    private static final String GOLDEN_TWO_FACTS = "aether-identity-ledger-v1\n"
                                                   + "T\tnode+b\tx+y\n"
                                                   + "T\tnode-a\tTOKEN_CONFLICT\n"
                                                   + "D\te6e8012919992a3c3508e4ec6503b9c55de6299b4c82f72ca90266265d291ec1\n";

    private static NodeId node(String id) {
        return new NodeId(id);
    }

    @Test
    void render_parse_roundTripsEveryFact_includingAwkwardNodeIdsAndCauses() {
        var facts = IdentityFacts.terminated(node("ember-1"), "TOKEN_CONFLICT")
                                 .merge(IdentityFacts.terminated(node("tab\tand space\nnewline"), "a cause\twith tab"))
                                 .merge(IdentityFacts.terminated(node("\u00e9-3"), "TOKEN_CONFLICT"));

        IdentityFacts.parse(facts.render())
                     .onFailure(cause -> Assertions.fail(cause.message()))
                     .onSuccess(parsed -> assertThat(parsed).isEqualTo(facts));
    }

    @Test
    void parse_rejectsContentWithAChangedByte_asAWholeNotAPartialSet() {
        var rendered = IdentityFacts.terminated(node("ember-1"), "TOKEN_CONFLICT")
                                    .merge(IdentityFacts.terminated(node("ember-2"), "TOKEN_CONFLICT"))
                                    .render();

        assertThat(IdentityFacts.parse(rendered.replace("ember-1", "ember-9")).isFailure()).isTrue();
    }

    @Test
    void parse_rejectsTruncatedContent_atEveryCutPoint() {
        var rendered = IdentityFacts.terminated(node("ember-1"), "TOKEN_CONFLICT").render();

        for (var cut = 0; cut < rendered.length(); cut++) {
            assertThat(IdentityFacts.parse(rendered.substring(0, cut)).isFailure())
                .as("cut at " + cut)
                .isTrue();
        }
    }

    @Test
    void parse_rejectsAnUnknownHeader() {
        assertThat(IdentityFacts.parse("aether-identity-ledger-v9\nD\t00\n").isFailure()).isTrue();
    }

    @Test
    void parse_rejectsAFactOfAnUnknownKind_evenWithAValidDigest() {
        assertThat(IdentityFacts.parse(withValidDigest("B\tember-1\t42")).isFailure()).isTrue();
        assertThat(IdentityFacts.parse(withValidDigest("X\tember-1\tTOKEN_CONFLICT")).isFailure())
            .as("a future three-field kind is not read as a retirement")
            .isTrue();
    }

    @Test
    void parse_rejectsARetirementWithAnExtraField_evenWithAValidDigest() {
        assertThat(IdentityFacts.parse(withValidDigest("T\tember-1\tTOKEN_CONFLICT\textra")).isFailure()).isTrue();
        assertThat(IdentityFacts.parse(withValidDigest("T\tember-1\tTOKEN_CONFLICT")).isSuccess()).as("control").isTrue();
    }

    /// The `aether-identity-ledger-v1` format, byte for byte: a ledger written by one version must be read by the next, or
    /// every node would read its own file as lost after an upgrade.
    @Test
    void render_ofATwoFactLedger_isTheV1FormatByteForByte() {
        var facts = IdentityFacts.terminated(node("node-a"), "TOKEN_CONFLICT").merge(IdentityFacts.terminated(node("node b"), "x y"));

        assertThat(facts.render()).isEqualTo(GOLDEN_TWO_FACTS);
    }

    @Test
    void parse_ofTheV1GoldenBytes_yieldsTheTwoFacts() {
        IdentityFacts.parse(GOLDEN_TWO_FACTS)
                     .onFailure(cause -> Assertions.fail(cause.message()))
                     .onSuccess(parsed -> assertThat(parsed.terminated()).containsOnly(Map.entry(node("node-a"), "TOKEN_CONFLICT"),
                                                                                       Map.entry(node("node b"), "x y")));
    }

    @Test
    void render_parse_keepsAnEmptyCause() {
        IdentityFacts.parse(IdentityFacts.terminated(node("a"), "").render())
                     .onFailure(cause -> Assertions.fail(cause.message()))
                     .onSuccess(parsed -> assertThat(parsed.terminated()).containsOnly(Map.entry(node("a"), "")));
    }

    @Test
    void facts_doNotChange_whenTheMapTheyWereBuiltFromChanges() {
        var source = new java.util.HashMap<NodeId, String>();

        source.put(node("a"), "TOKEN_CONFLICT");
        var facts = new IdentityFacts(source);

        source.put(node("b"), "TOKEN_CONFLICT");

        assertThat(facts.retired()).containsExactly(node("a"));
    }

    @Test
    void parse_acceptsTheEmptyLedger() {
        IdentityFacts.parse(IdentityFacts.identityFacts().render())
                     .onFailure(cause -> Assertions.fail(cause.message()))
                     .onSuccess(parsed -> assertThat(parsed.isEmpty()).isTrue());
    }

    @Test
    void merge_isCommutativeAssociativeAndIdempotent_overRandomSets() {
        var random = new Random(2429L);

        for (var trial = 0; trial < TRIALS; trial++) {
            var a = randomFacts(random);
            var b = randomFacts(random);
            var c = randomFacts(random);

            assertThat(a.merge(b)).as("commutative").isEqualTo(b.merge(a));
            assertThat(a.merge(b).merge(c)).as("associative").isEqualTo(a.merge(b.merge(c)));
            assertThat(a.merge(a)).as("idempotent").isEqualTo(a);
            assertThat(a.merge(b).covers(a)).as("grow-only").isTrue();
        }
    }

    @Test
    void merge_keepsARetirementForever_whateverIsMergedAfterIt() {
        var merged = IdentityFacts.terminated(node("ember-1"), "TOKEN_CONFLICT")
                                  .merge(IdentityFacts.terminated(node("ember-2"), "TOKEN_CONFLICT"))
                                  .merge(IdentityFacts.identityFacts());

        assertThat(merged.isTerminated(node("ember-1"))).isTrue();
        assertThat(merged.retired()).containsExactlyInAnyOrder(node("ember-1"), node("ember-2"));
    }

    @Test
    void covers_isFalse_forAFactTheSetLacks() {
        assertThat(IdentityFacts.identityFacts().covers(IdentityFacts.terminated(node("x"), "TOKEN_CONFLICT"))).isFalse();
    }

    private static IdentityFacts randomFacts(Random random) {
        var facts = IdentityFacts.identityFacts();
        var size = random.nextInt(6);

        for (var index = 0; index < size; index++) {
            facts = facts.merge(IdentityFacts.terminated(node("n" + random.nextInt(4)), "C" + random.nextInt(3)));
        }

        return facts;
    }

    /// A ledger body with the given fact lines and a correct digest, so only the line under test can make it malformed.
    private static String withValidDigest(String... factLines) {
        var body = "aether-identity-ledger-v1\n" + String.join("", java.util.Arrays.stream(factLines).map(line -> line + "\n").toList());

        return body + "D\t" + sha256(body) + "\n";
    }

    private static String sha256(String body) {
        return Result.lift(Causes::fromThrowable, () -> java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                     .map(java.util.HexFormat.of()::formatHex)
                     .or("");
    }
}
