package org.pragmatica.jbct.derive;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.jbct.derive.model.Axis;
import org.pragmatica.jbct.derive.pipeline.Derive;
import org.pragmatica.jbct.derive.result.DeriveResult;
import org.pragmatica.jbct.derive.result.JudgmentPoint;
import org.pragmatica.jbct.derive.result.RecoveryAssignment.RecoveryClass;
import org.pragmatica.jbct.derive.result.VectorPosition;
import org.pragmatica.jbct.derive.result.VectorPosition.Resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// The four published runs, transcribed into schema form, run through `derive` (issue #443 Phase B
/// acceptance; SPEC.md §6). The engine reproduces each run's *mechanical* moves — strikes, the
/// shape-driven pressure matrix, discrete forced moves (event-based, audit-log, scope-exclusion),
/// recovery from domain shape — and STOPS at the book's judgment points (exit 3), which it emits
/// rather than resolves.
///
/// Where the engine and a run diverge, the divergence is the engine correctly refusing the book's
/// JUDGMENT (SPEC.md §1: the engine is the entry gate and the bookkeeping, not the oracle). Each is
/// noted at its assertion. None is tuned away: the engine reproduces what a sheet mechanically
/// determines and defers the rest, by design.
class DeriveGoldenTest {
    @ParameterizedTest
    @ValueSource(strings = {"companies-house.toml", "stack-overflow.toml", "shopify.toml", "discord.toml"})
    void derive_clearsGate_andStopsAtJudgment_forEveryRun(String fileName) {
        derive(fileName, result -> {
            assertThat(result.gatePassed()).as("gate clean").isTrue();
            assertThat(result.halts()).as("no halts").isEmpty();
            assertThat(result.judgmentPoints()).as("judgment points emitted").isNotEmpty();
            assertThat(result.exitCode()).as("exit 3 — judgment points pending").isEqualTo(3);
        });
    }

    @Nested
    class CompaniesHouse {
        @Test
        void reproduces_strike_recovery_and_discreteMoves() {
            derive("companies-house.toml", result -> {
                // prune: the Part 35 publication mandate strikes substrate:private-only (recorded verbatim).
                assertThat(hasStrike(result, "substrate", "private-only")).isTrue();

                // recovery from domain shape: accept-filing is append-only -> design-out (matches the run).
                assertThat(recovery(result, "accept-filing")).isEqualTo(RecoveryClass.DESIGN_OUT);
                // SCHEMA GAP (v0.1): schema v0.1 has no status-transition reshapeable category, so
                // incorporate(reshapeable=["none"]) falls back to BER, while the book's systematic rule is
                // design-out for append/status-transition ops. The asymmetry is the tell: accept-filing=
                // ["append-only"] vs incorporate=["none"], both record-appends. NOT engine judgment — a v0.1
                // transcription gap; schema v0.2 adds a "status-transition" reshape value (see isSafeReshape).
                assertThat(recovery(result, "incorporate")).isEqualTo(RecoveryClass.BER);

                // press+resolve: the December deadline (Q5) forces event-based ingestion (matches the run).
                assertThat(position(result, Axis.SUBSTRATE).value()).contains("event-based");
                // the FOI audit demand (Q6) forces current-state + audit-log, NOT event-sourced (F3, matches).
                assertThat(position(result, Axis.STATE).value()).contains("audit-log-as-data");
                // the DOB mandate (Q6, no strike) is contained by scope-exclusion — and ONLY that (the
                // company-search path is the primary latency path, so it is not split). Matches the run.
                assertThat(topologySplits(result)).containsExactly("data-class:date-of-birth");
            });
        }

        @Test
        void defersTheCeilingAxes_andEmitsTheirJudgments() {
            derive("companies-house.toml", result -> {
                // volume (Q5) + residency (Q6) converge on persistence -> a first-class combination.
                assertThat(result.combinations().stream().anyMatch(combination -> combination.axis() == Axis.PERSISTENCE)).isTrue();
                // persistence stays deferred: the F12 ceiling (does one store contain 12bn reads/yr?) is judgment.
                // DIVERGENCE: the run keeps single-shared (hardware-rung, judgment); the engine records the
                // pressure and emits the rung-depth + partition-key judgments instead of moving.
                assertThat(position(result, Axis.PERSISTENCE).resolution()).isEqualTo(Resolution.DEFERRED);
                assertThat(hasJudgment(result, JudgmentPoint.Kind.RUNG_DEPTH)).isTrue();
                assertThat(hasJudgment(result, JudgmentPoint.Kind.PARTITION_KEY)).isTrue();
                // the UNKNOWN incorporation criticality tier (Q2) is a target-setting judgment, never guessed.
                assertThat(hasJudgment(result, JudgmentPoint.Kind.TARGET_SETTING)).isTrue();
            });
        }
    }

    @Nested
    class StackOverflow {
        @Test
        void reproduces_noStrikes_designOut_and_directSubstrate() {
            derive("stack-overflow.toml", result -> {
                assertThat(result.strikes()).as("Q6 is UNKNOWN — nothing prunes").isEmpty();
                assertThat(recovery(result, "post-content")).isEqualTo(RecoveryClass.DESIGN_OUT);
                // no burst/deadline anywhere -> substrate stays direct (matches the run's direct + scoped pub/sub).
                assertThat(position(result, Axis.SUBSTRATE).value()).isEqualTo("direct");
                assertThat(position(result, Axis.PERSISTENCE).resolution()).isEqualTo(Resolution.DEFERRED);
            });
        }

        @Test
        void reproduces_exactlyTwoScopeSplits_tagAndSearch() {
            derive("stack-overflow.toml", result -> {
                // F20/F24 mechanical split: the two secondary paths whose shape diverges from the
                // page-render baseline — full-text-search (volume) and tag-match (contention) — split
                // out, and ONLY those two. realtime-updates is a contained thin-tier (F18), so it does
                // not split. Reproduces the run's "✓ both, and only these".
                assertThat(topologySplits(result)).containsExactlyInAnyOrder("path:full-text-search", "path:tag-match");
                assertThat(position(result, Axis.TOPOLOGY).resolution()).isEqualTo(Resolution.FORCED);
                assertThat(hasJudgment(result, JudgmentPoint.Kind.TOPOLOGY_SHAPE)).isTrue();
            });
        }
    }

    @Nested
    class Shopify {
        @Test
        void reproduces_cardPathExclusion_eventBased_and_shardKeyJudgment() {
            derive("shopify.toml", result -> {
                // PCI mandate carries no strike -> scope-exclusion splits the card path out, and ONLY that:
                // checkout is operation-scoped (contention + burst), so the path-only guard keeps it from
                // splitting. Exact set catches an over-split regression. Matches the run.
                assertThat(result.strikes()).isEmpty();
                assertThat(topologySplits(result)).containsExactly("data-class:card-data");
                // the flash-sale burst (Q5) forces event-based at the checkout edge (matches async jobs at edges).
                assertThat(position(result, Axis.SUBSTRATE).value()).contains("event-based");
                // checkout is idempotent -> design-out (checked first). DIVERGENCE: the run also assigns BER to the
                // money sub-operation; the schema carries one domain-shape row for checkout, so the engine sees
                // one class. The finer split is judgment.
                assertThat(recovery(result, "checkout")).isEqualTo(RecoveryClass.DESIGN_OUT);
                // storefront volume -> persistence deferred; the shop partition key (Q9 gift) is emitted, not guessed.
                assertThat(position(result, Axis.PERSISTENCE).resolution()).isEqualTo(Resolution.DEFERRED);
                assertThat(hasJudgment(result, JudgmentPoint.Kind.PARTITION_KEY)).isTrue();
            });
        }
    }

    @Nested
    class Discord {
        @Test
        void reproduces_designOut_eventBasedRealtime_and_shardKeyJudgment() {
            derive("discord.toml", result -> {
                assertThat(result.strikes()).isEmpty();
                assertThat(recovery(result, "send-message")).isEqualTo(RecoveryClass.DESIGN_OUT);
                // the presence fan-out burst (Q5) forces event-based on the real-time path (matches the run).
                assertThat(position(result, Axis.SUBSTRATE).value()).contains("event-based");
                // the real-time fan-out path (presence-fanout) splits out as its own component — the gateway
                // (F24 own-shape-diverges at a secondary path scope). Matches the run's "gateway split".
                assertThat(topologySplits(result)).containsExactly("path:presence-fanout");
                assertThat(position(result, Axis.TOPOLOGY).resolution()).isEqualTo(Resolution.FORCED);
                // read volume -> persistence deferred; the (channel, time-bucket) key is a judgment, emitted.
                assertThat(position(result, Axis.PERSISTENCE).resolution()).isEqualTo(Resolution.DEFERRED);
                assertThat(hasJudgment(result, JudgmentPoint.Kind.PARTITION_KEY)).isTrue();
            });
        }
    }

    // ---- helpers ----

    private static void derive(String fileName, Consumer<DeriveResult> assertions) {
        Derive.derive(Sheets.load(fileName), fileName)
              .onFailure(cause -> fail("golden did not derive: " + cause.message()))
              .onSuccess(assertions::accept);
    }

    private static VectorPosition position(DeriveResult result, Axis axis) {
        return result.vector().stream().filter(vectorPosition -> vectorPosition.axis() == axis).findFirst().orElseThrow();
    }

    private static RecoveryClass recovery(DeriveResult result, String operation) {
        return result.recovery()
                     .stream()
                     .filter(assignment -> assignment.operation().equals(operation))
                     .findFirst()
                     .orElseThrow()
                     .recoveryClass();
    }

    private static boolean hasStrike(DeriveResult result, String axisLabel, String value) {
        return result.strikes()
                     .stream()
                     .anyMatch(strike -> strike.axisLabel().equals(axisLabel) && strike.value().equals(value));
    }

    private static boolean hasJudgment(DeriveResult result, JudgmentPoint.Kind kind) {
        return result.judgmentPoints().stream().anyMatch(judgment -> judgment.kind() == kind);
    }

    private static List<String> topologySplits(DeriveResult result) {
        return result.judgmentPoints()
                     .stream()
                     .filter(judgment -> judgment.kind() == JudgmentPoint.Kind.TOPOLOGY_SHAPE)
                     .map(JudgmentPoint::subject)
                     .toList();
    }
}
