// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.awaitility.core.ConditionTimeoutException;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #345 increment I0 — a `DurableEntity` that actually RUNS inside a node, and a measured baseline
/// of what it does today.
///
/// ## Why this test exists
///
/// Before it, `resource/durable-entity` had no consumer anywhere: no `resources.toml` section, no
/// example, no fixture. `DurableEntityFactory`, `InMemoryDurableEntity`, `FencedDurableEntity` and
/// `PartitionFencedDurableEntity` were fully written and unit-tested, and not one line of them was
/// reachable from a running node. Every build was green and would have stayed green if the whole
/// module were deleted. I0 removes that: from here on, increments I1–I6 have something that breaks.
///
/// ## Non-vacuity
///
/// The fixture slice declares `@OrderEntity DurableEntity<String, OrderState, OrderCommand>` and does nothing
/// else — if resource provisioning fails, the slice does not load and every test here fails at
/// setup rather than passing quietly. Each response carries the slice instance's own id, so the
/// cross-node assertions ([#get_isServedByMoreThanOneNode_afterCreateOnOne],
/// [#scheduleTimer_succeedsOnEveryNode_includingInstancesThatCannotBeTheCommittedOwner]) prove they
/// contacted DIFFERENT instances rather
/// than being satisfied by a routing quirk that sent every request to one node.
///
/// ## What is proven, and what is NOT
///
/// Proven: the resource SPI resolves a `[entities.orders]` TOML section to a `DurableEntityConfig`,
/// provisions a `DurableEntity`, and the create / get / update / delete / scheduleTimer surface executes
/// inside a live node with correct per-key semantics.
///
/// NOT proven: that a PENDING timer survives an owner change or a full restart. That needs a disruption
/// this suite deliberately does not perform until its last test, and it is
/// [DurableEntityTimerDurabilityTest]'s question.
///
/// ## Timers
///
/// Every node answers a schedule with a token — the owner locally, every other node by forwarding to it
/// ([#scheduleTimer_succeedsOnEveryNode_includingInstancesThatCannotBeTheCommittedOwner]) — and five
/// presentations of ONE caller-minted token leave exactly one fire
/// ([#scheduleTimer_appliesTheEffectExactlyOnce_whenTheSameTokenIsResent]).
///
/// ## What I1 changed here
///
/// At I0 the provisioned entity was a per-process map with no ownership fence, and the headline
/// measurement was that all five nodes accepted a create for one key. I1 replaced it with the fenced
/// `PartitionFencedDurableEntity` and added owner admission, so exactly one node may now accept — see
/// [#create_succeedsOnExactlyOneNode_forTheSameKey]. Because that property is asserted by the shared
/// [#createOnOwner] helper, every test here that creates anything re-proves it as a side effect.
///
/// I1 also closed the deployability gap this test used to document: `resource-durable-entity` is now a
/// dependency of `aether/node`, so `DurableEntityFactory` is on a production node's ServiceLoader path
/// and `SliceClassLoader`'s parent chain resolves `DurableEntity`. setUp reaching its first test is what
/// demonstrates it — provisioning REFUSES if any fence collaborator is missing, so a slice that loads is
/// a slice that got a fully wired fenced entity.
///
/// ## State durability and replication
///
/// State is durable and replicated (I3): it lives on a fenced, fsync-durable, replicated log per
/// partition, and the in-memory view is a fold any node holding the partition rebuilds from it. So a key
/// is answered by more slice instances than the one that accepted it
/// ([#get_isServedByMoreThanOneNode_afterCreateOnOne]) and the value outlives the node that owned it
/// ([#state_survivesTheLossOfTheNodeThatOwnedIt] — the discriminating one, since forwarding cannot
/// explain an answer once the holder is gone).
/// `replication_factor` is honoured: the blueprint declares 3, `DurableEntityConfig.minSyncReplicas()`
/// derives 2, and the owner plus one peer hold a record before a write acks.
///
/// ## What the 02w hosting-set fix changed here
///
/// The suite now forms MORE nodes than slice instances (5/3, see [#INSTANCES]), so the leader's
/// ownership reconcile is exercised against the configuration that broke in the 02w cloud run:
/// owners must come from the keyspace's registered hosts, and failover re-placement
/// ([#state_survivesTheLossOfTheNodeThatOwnedIt]) must stay within that set.
/// [#ownership_isMintedOnlyOverNodesHostingTheEntitySlice] asserts the invariant directly.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DurableEntityForgeTest {
    private static final System.Logger LOG = System.getLogger(DurableEntityForgeTest.class.getName());

    private static final int BASE_PORT = 19000;
    private static final int BASE_MGMT_PORT = 19100;
    private static final int BASE_APP_HTTP_PORT = 19200;
    private static final int NODES = 5;
    /// Deliberately FEWER instances than nodes (02w hosting-set fix): the leader must mint entity arc
    /// ownership over the nodes hosting the declaring slice, never the whole member view. With 5/5 the
    /// two sets coincide and the defect is invisible; at 3/5 a wrongly-placed arc lands on a node with
    /// no entity registered, every write to it refuses, and setUp's ownership convergence times out —
    /// so the whole suite re-proves the hosting-set property as a precondition.
    private static final int INSTANCES = 3;
    /// Must match `partition_count` in the fixture's `resources.toml` — the ownership assertion counts
    /// committed arcs against it.
    private static final int ENTITY_PARTITIONS = 8;

    /// The SAME partition function production uses, so a key can be mapped to the arc whose committed owner
    /// [#entityArcOwners] reports. Re-deriving it here would only test this test's own arithmetic.
    private static final EntityPartitionArc ENTITY_ARC = EntityPartitionArc.entityPartitionArc("orders", ENTITY_PARTITIONS);

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    /// The delay [#scheduleTimer_appliesTheEffectExactlyOnce_whenTheSameTokenIsResent] schedules under. It
    /// only has to outlive the five presentations of the one token — the timer must still be pending when
    /// the last of them arrives, or that one plants a fresh timer and the gate measures the wrong thing.
    /// Five sequential localhost posts (four of them forwarded a hop) measure 5-15 ms, so 8s is nearly three
    /// orders of magnitude of margin, and the test asserts the measured figure against a quarter of it.
    private static final long RESEND_DELAY_MILLIS = 8_000L;

    /// Ticks to wait out before declaring a fire exactly-once. `ENTITY_TIMER_INTERVAL` is one second, so a
    /// duplicate timer planted alongside the first would come due within one tick of it; five gives four
    /// spare ticks for the second fire that must not happen to happen.
    private static final Duration EXACTLY_ONCE_SETTLE = Duration.ofSeconds(5);

    private static final String ENTITY_SLICE = TestArtifacts.ENTITY_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:durable-entity:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final int REASON_EXCERPT_LIMIT = 300;

    /// Distinct probe keys per convergence round. The fixture declares 8 partitions, and these keys hash
    /// across them, so readiness reflects the whole keyspace rather than whichever partition one key
    /// happened to land on.
    private static final int PARTITION_PROBE_KEYS = 12;

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();
    private final AtomicInteger ownershipProbe = new AtomicInteger();
    private final AtomicReference<List<String>> lastProbeAnswers = new AtomicReference<>(List.of());

    @BeforeAll
    void setUp() {
        // Resource provisioning is gated on a ConfigurationProvider being present; without it the
        // node installs a no-op facade and every resource-backed slice fails to load. Mirrors
        // ForgeServer.buildConfigurationProvider.
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "dur", Option.some(configProvider));
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);

        deployEntitySlice();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(this::failIfSliceFailed)
               .until(this::entityReadyOnEveryNode);
        // Placement converges LATER than entity readiness, and the gap is wide enough to be read as a
        // defect. Measured on 2026-08-27: the scheduler's first pass placed 2 of the 3 requested instances,
        // the reconciler logged "has 2 instances, desired 3 - adjusting" 52s after deploy, and the third
        // completed 8s after that. Nothing above waits for it — an entity answers, and ownership converges,
        // on 2 instances just as well as on 3 — so without this
        // [#ownership_isMintedOnlyOverNodesHostingTheEntitySlice] read a genuinely-mid-reconcile view and
        // failed its own non-vacuity precondition in 2 of 3 local runs. Polling here fails LOUDLY (a setUp
        // timeout naming placement) if the reconcile never gets there, instead of intermittently as a
        // wrong-looking hosting-set assertion.
        awaitFullSlicePlacement();
        // Ownership records are minted by a leader-only reconcile tick, so for a few seconds after deploy
        // NO arc has a committed owner and every write is refused as transient. Poll for convergence on a
        // throwaway key rather than sleeping: a sleep would either flake on a slow tick or hide a driver
        // that never ran at all.
        awaitOwnershipConvergence();
    }

    /// Wait until the blueprint's full instance count is ACTIVE cluster-wide, reporting WHICH hosts were
    /// seen on expiry — "expected 3, saw 2" is diagnosable; "condition not met in 240s" is not.
    private void awaitFullSlicePlacement() {
        try {
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(this::failIfSliceFailed)
                   .until(() -> activeSliceHosts().size() == INSTANCES);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Slice placement never reached " + INSTANCES + " ACTIVE instances; hosts seen: "
                                     + activeSliceHosts(), e);
        }
    }

    /// Wait for the ownership reconcile, and on expiry report WHAT the entity actually answered.
    ///
    /// A bare `ConditionTimeout` here would say only "not fulfilled in 4 minutes", which cannot
    /// distinguish "the driver never ran" from "it ran and the write was refused for some other reason" —
    /// and the entity reports every refusal as DATA in the response body, so the body is the only place
    /// the answer exists. Keeping the last probe's bodies turns a dead end into a diagnosis.
    private void awaitOwnershipConvergence() {
        try {
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(this::failIfSliceFailed)
                   .until(this::ownershipHasConverged);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError("Entity ownership never converged; last probe answers: " + lastProbeAnswers.get(), e);
        }
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());

            httpDelete(leaderPort, "/api/v1/blueprints/" + BLUEPRINT_ID);
            cluster.stop()
                   .await();
        }
    }

    // --- the API surface actually executes ------------------------------------

    /// The 02w hosting-set pin, asserted directly: every committed `entity:orders` arc owner is a node
    /// hosting an ACTIVE instance of the entity slice. Before the fix the leader minted owners over ALL
    /// members, so with 3 instances on 5 nodes roughly two-fifths of the arcs landed on nodes with no
    /// entity registered and refused every write (22/40 creates in the 02w cloud run). setUp already
    /// fails without the fix (convergence times out); this test names the invariant and reports the
    /// offending arcs when it regresses. Ordered before the writes so it reads the freshly-converged
    /// records with the cluster intact.
    @Test
    @Order(0)
    void ownership_isMintedOnlyOverNodesHostingTheEntitySlice() {
        var hosts = activeSliceHosts();
        var owners = entityArcOwners();

        assertThat(hosts).describedAs("the blueprint places fewer instances than nodes, else this pin is vacuous")
                         .hasSize(INSTANCES);
        assertThat(owners).describedAs("every entity partition must carry a committed ownership record")
                          .hasSize(ENTITY_PARTITIONS);
        assertThat(owners.entrySet()).describedAs("every arc owner must host the declaring slice; owners=%s hosts=%s",
                                                  owners,
                                                  hosts)
                                     .allSatisfy(entry -> assertThat(hosts).contains(entry.getValue()));
    }

    /// The headline I0 assertion: a durable entity provisioned from `resources.toml` accepts a
    /// create inside a running node and returns the state it stored. Against a runtime where the
    /// resource cannot be provisioned, setUp never reaches this test.
    @Test
    @Order(1)
    void create_returnsStoredState_forNewKey() {
        var accepted = createOnOwner("order-create", "placed", 100);

        assertThat(text(accepted.response(), "status")).isEqualTo("placed");
        assertThat(number(accepted.response(), "amount")).isEqualTo(100);
    }

    /// Read-your-writes on the owner. The value must come back with both components intact — a bare
    /// "found" would also be satisfied by a default-constructed state.
    @Test
    @Order(2)
    void get_returnsTheCreatedState_onTheSameNode() {
        var owner = createOnOwner("order-get", "placed", 250).port();

        var response = get(owner, "order-get");

        assertThat(outcome(response)).isEqualTo("found");
        assertThat(text(response, "status")).isEqualTo("placed");
        assertThat(number(response, "amount")).isEqualTo(250);
    }

    /// The pure `S -> S` mutator runs under the per-key serialization and the result is committed:
    /// the mutated component changes and the untouched one survives.
    @Test
    @Order(3)
    void update_commitsTheMutatedState_forExistingKey() {
        var owner = createOnOwner("order-update", "placed", 10).port();

        var updated = update(owner, "order-update", 999);

        assertThat(outcome(updated)).isEqualTo("updated");
        assertThat(number(updated, "amount")).isEqualTo(999);
        assertThat(text(updated, "status")).describedAs("the mutator changes amount only").isEqualTo("placed");
        assertThat(number(get(owner, "order-update"), "amount")).describedAs("the mutation is committed, not just returned")
                                                                 .isEqualTo(999);
    }

    /// Delete removes the instance, and the subsequent read reports absence rather than failing.
    @Test
    @Order(4)
    void delete_makesTheSubsequentGetAbsent() {
        var owner = createOnOwner("order-delete", "placed", 5).port();

        assertThat(outcome(delete(owner, "order-delete"))).isEqualTo("deleted");
        assertThat(outcome(get(owner, "order-delete"))).isEqualTo("absent");
    }

    // --- typed failures are real, not decorative -------------------------------

    /// `EntityAlreadyExists` is enforced, so the entity genuinely holds state rather than accepting
    /// every write. This is the sensor that stops the whole suite from passing against a no-op
    /// implementation.
    @Test
    @Order(5)
    void create_failsWithKeyAlreadyExists_forDuplicateKeyOnOneNode() {
        var owner = createOnOwner("order-duplicate", "placed", 1).port();

        var duplicate = create(owner, "order-duplicate", "placed", 2);

        assertThat(outcome(duplicate)).isEqualTo("failed");
        assertThat(text(duplicate, "failureType")).isEqualTo("EntityAlreadyExists");
    }

    /// Under owner-forwarding (#596) every node relays the update to the committed owner, so all five
    /// callers receive the OWNER's verdict: `EntityNotFound`, typed, reconstructed across the wire.
    /// Asserting the whole five-node shape proves the forward carries the owner's answer faithfully —
    /// a node that reported its own (non-authoritative) view instead would stand out here.
    @Test
    @Order(6)
    void update_failsWithKeyNotFound_forUnknownKey() {
        var failureTypes = appPorts().stream()
                                     .map(port -> text(update(port, "order-never-created", 1), "failureType"))
                                     .toList();

        assertThat(failureTypes).describedAs("every caller receives the owner's typed verdict through the forward (#596)")
                                .containsOnly("EntityNotFound");
    }

    @Test
    @Order(7)
    void delete_failsWithKeyNotFound_whenRepeated() {
        var owner = createOnOwner("order-double-delete", "placed", 1).port();

        delete(owner, "order-double-delete");

        var repeated = delete(owner, "order-double-delete");

        assertThat(outcome(repeated)).isEqualTo("failed");
        assertThat(text(repeated, "failureType")).isEqualTo("EntityNotFound");
    }

    /// EVERY node answers a schedule with a token. The fenced-log backing a node provisions schedules
    /// timers as ordinary fenced writes on the key's own log, and a node that is not the committed owner
    /// forwards to the one that is rather than refusing.
    ///
    /// What the five-node breadth pins, precisely. The key's partition carries exactly ONE committed owner
    /// (read from the ownership records here, so it is a checked premise and not an assumed one), a node
    /// hosts at most one instance of the entity slice, and the five schedules come back served by MORE THAN
    /// ONE instance — so at least one instance that cannot be the owner's answered `scheduled`. That is
    /// what the forward buys: without it, only the owner could answer at all.
    ///
    /// What it does NOT pin is where the timer LANDED. The response's `instance` field names the slice
    /// instance that served the HTTP call — a UUID minted at slice construction, correlatable to no node id
    /// — and the forward happens inside the entity, below that seam. A node that quietly scheduled locally
    /// rather than forwarding would answer identically here. That half is
    /// [#scheduleTimer_appliesTheEffectExactlyOnce_whenTheSameTokenIsResent]'s, where five presentations of
    /// ONE token must yield exactly ONE fire, and [DurableEntityTimerDurabilityTest]'s.
    ///
    /// Each call mints its own token (none is supplied), so this leaves FIVE independent pending timers on
    /// one key — deliberately, because it is also the proof that the token is what distinguishes schedules.
    /// The delay is the fixture's five-minute default, far longer than this suite runs, so none of them
    /// fires here.
    @Test
    @Order(8)
    void scheduleTimer_succeedsOnEveryNode_includingInstancesThatCannotBeTheCommittedOwner() {
        createOnOwner("order-timer", "placed", 1);

        var partition = ENTITY_ARC.partitionOf("order-timer");

        assertThat(entityArcOwners()).describedAs("the key's partition must carry a committed owner, else 'an instance "
                                                  + "that cannot be the owner' names nothing")
                                     .containsKey(partition);

        var responses = appPorts().stream()
                                  .map(port -> scheduleTimer(port, "order-timer"))
                                  .toList();

        assertThat(responses).describedAs("every node must answer a schedule, the owner locally and the rest by forwarding")
                             .hasSize(NODES);
        assertThat(responses).allSatisfy(response -> {
            assertThat(outcome(response)).describedAs("timers are real after I4 — a refusal here is the regression")
                                         .isEqualTo("scheduled");
            assertThat(text(response, "token")).describedAs("a scheduled timer must come back with the handle that cancels it")
                                               .isNotEmpty();
        });

        var tokens = responses.stream()
                              .map(response -> text(response, "token"))
                              .distinct()
                              .toList();

        assertThat(tokens).describedAs("five schedules with no caller token are five timers, each with its own handle")
                          .hasSize(NODES);

        var instances = responses.stream()
                                 .map(response -> text(response, "instance"))
                                 .distinct()
                                 .toList();

        LOG.log(System.Logger.Level.INFO,
                "I4 forwarding gate: nodes={0} committedOwner={1} distinctServingInstances={2}",
                NODES,
                entityArcOwners().get(partition),
                instances.size());

        assertThat(instances).describedAs("more than one slice instance must have answered: a node hosts at most one, and "
                                          + "the partition asserted above has a single committed owner, so a second "
                                          + "instance answering 'scheduled' is a non-owner that relayed instead of "
                                          + "refusing. All five served by one instance would leave that untested; "
                                          + "instances=%s",
                                          instances)
                             .hasSizeGreaterThan(1);
    }

    /// The retry-after-lost-ack gate, at cluster level: a schedule RE-SENT under the same token is the same
    /// schedule, and its effect lands exactly once.
    ///
    /// The scenario it stands for is the one a caller cannot distinguish from success: the schedule reached
    /// the owner and was appended, and the acknowledgement was lost on the way back. The caller holds the
    /// token — it minted it — and re-sends. If the owner treated that as a fresh request it would plant a
    /// second timer under a handle the caller does not know it has, and `Expire` would be applied twice.
    ///
    /// Offering the SAME token to all five nodes is the strongest available form of that: four of the five
    /// presentations arrive at the owner through the forward, one locally, and the owner's already-pending
    /// check is the only thing standing between five presentations and five timers. Every one of them must
    /// answer with the token it was given — a token of the owner's own minting coming back would mean the
    /// caller's handle was silently replaced.
    ///
    /// ## The delay, chosen deliberately
    ///
    /// {@value #RESEND_DELAY_MILLIS} ms. It has to outlive the five presentations — a timer that fires
    /// between presentation one and presentation five would leave the pending set, and the later ones would
    /// then legitimately plant NEW timers, turning an exactly-once gate into an at-least-once one that
    /// passes for the wrong reason. The five sequential posts are measured and asserted to consume less than
    /// a quarter of the delay, so the margin is a number this test checks rather than one it assumes.
    /// Measured over three 2026-08-27 local runs: 5-15 ms against the 8,000 ms delay, i.e. better than a
    /// 500x margin against the 2,000 ms threshold.
    ///
    /// After the fire, the state is re-read a second time a few ticks later: a duplicate timer would be a
    /// second `Expire` arriving on a later tick, so a single reading at the moment of the fire could not
    /// tell exactly-once from not-yet-twice.
    @Test
    @Order(9)
    void scheduleTimer_appliesTheEffectExactlyOnce_whenTheSameTokenIsResent() {
        createOnOwner("order-timer-resend", "placed", 500);

        var token = "resent-" + UUID.randomUUID();
        var startNanos = System.nanoTime();
        var responses = appPorts().stream()
                                  .map(port -> scheduleTimer(port, "order-timer-resend", RESEND_DELAY_MILLIS, token))
                                  .toList();
        var presentationMillis = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

        assertThat(presentationMillis).describedAs("all five presentations must land while the timer is still pending, "
                                                   + "else the later ones plant fresh timers and prove nothing")
                                      .isLessThan(RESEND_DELAY_MILLIS / 4);

        LOG.log(System.Logger.Level.INFO,
                "I4 resend gate: delay={0}ms fivePresentations={1}ms",
                RESEND_DELAY_MILLIS,
                presentationMillis);
        assertThat(responses).allSatisfy(response -> {
            assertThat(outcome(response)).describedAs("a re-sent schedule is the same schedule, and answers success")
                                         .isEqualTo("scheduled");
            assertThat(text(response, "token")).describedAs("the owner must echo the CALLER's token, not one of its own")
                                               .isEqualTo(token);
        });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> expiriesAcrossCluster("order-timer-resend").contains(1));

        // A duplicate arrives on a LATER tick, so a reading taken at the moment of the fire cannot tell
        // exactly-once from not-yet-twice. There is no condition to poll for the absence of a second fire —
        // only elapsed ticks — so this waits out a quiet period and then re-reads.
        awaitQuietPeriod(EXACTLY_ONCE_SETTLE);
        var counts = expiriesAcrossCluster("order-timer-resend");

        assertThat(counts).describedAs("at least one node must serve the key after the fire").isNotEmpty();
        assertThat(counts).describedAs("five presentations of ONE token are one timer, so Expire lands once — "
                                       + "a second timer would show as 2 on whichever node folded both fires")
                          .allSatisfy(count -> assertThat(count).isLessThanOrEqualTo(1));
        assertThat(counts).describedAs("and the fire did land, on some node's committed view").contains(1);

        var settled = servedView("order-timer-resend");

        assertThat(text(settled, "status")).describedAs("the fire is a real mutation, applied through the ordinary update path")
                                           .isEqualTo("expired");
        assertThat(number(settled, "amount")).describedAs("Expire touches the counter and the status only")
                                             .isEqualTo(500);
    }

    // --- replication and durability, across nodes --------------------------------

    /// More than ONE slice instance answers a key with the value that was written, after a create on a
    /// single node.
    ///
    /// The instance ids make this non-vacuous: at least one "found" answer is proven to come from a
    /// different slice instance than the one that accepted the create. Without that check, a
    /// load-balancer sending every request to one node would produce the same assertion outcome for
    /// the opposite reason.
    ///
    /// What it does NOT pin is WHERE each answer was served from. A node holding the partition answers
    /// from its own fold; a node that does not hold it forwards to the committed owner, and the response's
    /// `instance` field names the slice that served the HTTP call, not the node that read the state — the
    /// forward happens inside the entity, below that seam. Replication itself is pinned by
    /// [#state_survivesTheLossOfTheNodeThatOwnedIt], where the node that held the state is gone.
    ///
    /// Polled rather than sampled, and the VALUE is asserted rather than the outcome field: a replica
    /// serves only once it has folded the partition, and a node answering with anything other than
    /// what was written would be worse than one that refused.
    @Test
    @Order(10)
    void get_isServedByMoreThanOneNode_afterCreateOnOne() {
        var accepted = createOnOwner("order-isolated", "placed", 42);
        var ownerInstance = text(accepted.response(), "instance");

        // Polled, not sampled: a replica serves only once it has folded the partition, and taking one
        // reading immediately after the create would measure the fold's latency rather than whether the
        // key is answerable cluster-wide at all.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> serversOf("order-isolated").size() > 1);

        var servers = serversOf("order-isolated");

        assertThat(servers).describedAs("more than the accepting node must answer the key — from its own fold if it "
                                        + "holds the partition, by forwarding to the owner if it does not")
                           .hasSizeGreaterThan(1);

        var instances = servers.stream()
                               .map(response -> text(response, "instance"))
                               .distinct()
                               .toList();

        assertThat(instances).describedAs("the answers must come from DIFFERENT slice instances, else the test is vacuous")
                             .hasSizeGreaterThan(1);
        assertThat(instances).describedAs("and one of them is not the node that accepted the write")
                             .anySatisfy(instance -> assertThat(instance).isNotEqualTo(ownerInstance));

        // The value is the assertion. A replica that answered with anything other than what was written
        // would be worse than one that refused.
        assertThat(servers).allSatisfy(response -> {
            assertThat(text(response, "status")).isEqualTo("placed");
            assertThat(number(response, "amount")).isEqualTo(42);
        });
    }

    /// Every node currently serving `key`, by response body. Filters on a POSITIVE answer rather than
    /// counting negatives: a node whose fold is still replaying answers neither "found" nor "absent" but
    /// a transient refusal, and "absent" and "I cannot say" are different claims that must not be summed.
    /// A node that does not hold the key's partition forwards to the committed owner, so a "found" here
    /// names a node that answered, not necessarily one that holds the state.
    private List<String> serversOf(String key) {
        return appPorts().stream()
                         .map(port -> get(port, key))
                         .filter(response -> response.contains("\"outcome\":\"found\""))
                         .toList();
    }

    /// The I1 gate, flipped — then flipped once more by #596. At I0 all five nodes accepted a create for
    /// the SAME key and each believed it held the only copy — five single-writer entities for one key,
    /// not one. With admission, exactly one accepted and four refused `NotCurrentOwner`. With
    /// owner-forwarding, every node ACCEPTS the request but relays it to the committed owner — so the
    /// single-writer invariant now shows as: exactly one attempt CREATES, and every later attempt
    /// surfaces the owner's `EntityAlreadyExists`. The owner's admission still decides; the forward is
    /// a route to it, not a way around it.
    ///
    /// Deliberately a thin assertion over [#createOnOwner], the same helper every other create in this
    /// suite goes through — so the property is checked continuously as a side effect of ordinary setup,
    /// not only here.
    @Test
    @Order(11)
    void create_succeedsOnExactlyOneNode_forTheSameKey() {
        var accepted = createOnOwner("order-fenced", "placed", 7);

        assertThat(text(accepted.response(), "status")).isEqualTo("placed");
        assertThat(number(accepted.response(), "amount")).isEqualTo(7);
    }

    /// State outlives the node that owned it. The blueprint declares `replication_factor = 3`, so
    /// `minSyncReplicas` derives 2 and the owner plus one peer hold each record before the write acks —
    /// which is what makes the value recoverable from a survivor rather than only from the owner's own
    /// restart.
    ///
    /// Forge cannot hard-kill in-JVM (`stop()` always closes cleanly), so the loss here is a GRACEFUL
    /// shutdown. That makes this the weaker half of the durability claim: SIGKILL crash durability is
    /// gated separately by the `02w-entity-crash` cloud suite. What this pins is that the surviving
    /// nodes serve the exact value that was written, after ownership moves and the new owner rebuilds
    /// the partition from the log. Ordered last because it shrinks the cluster.
    @Test
    @Order(12)
    void state_survivesTheLossOfTheNodeThatOwnedIt() {
        var ownerPort = createOnOwner("order-durability", "placed", 77).port();

        assertThat(outcome(get(ownerPort, "order-durability"))).isEqualTo("found");

        var ownerNodeId = nodeIdForAppPort(ownerPort);

        cluster.killNode(ownerNodeId)
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Failed to stop node " + ownerNodeId + ": " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> !appPorts().contains(ownerPort));

        // Ownership must move and the new owner must rebuild the partition from the log before it can
        // answer, so the read is POLLED rather than taken once. A fold in progress reports itself as a
        // transient refusal — polling is what distinguishes "still replaying" from "gone", and taking a
        // single reading immediately after the kill would conflate them.
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> survivorsAnswering("order-durability").contains("found"));

        // Assert on the DATA, not on a status field. #508 passed throughout a run in which a status-gated
        // test failed on the same cluster at the same moment; the value is the thing that either survived
        // or did not.
        var recovered = appPorts().stream()
                                  .map(port -> get(port, "order-durability"))
                                  .filter(response -> "found".equals(outcome(response)))
                                  .toList();

        assertThat(recovered).describedAs("at least one surviving node must serve the entity after failover")
                             .isNotEmpty();
        assertThat(recovered).allSatisfy(response -> {
            assertThat(text(response, "status")).describedAs("the recovered state must be the state that was written")
                                                .isEqualTo("placed");
            assertThat(number(response, "amount")).describedAs("the recovered amount must be the amount that was written")
                                                  .isEqualTo(77);
        });
    }

    private List<String> survivorsAnswering(String key) {
        return appPorts().stream()
                         .map(port -> get(port, key))
                         .map(DurableEntityForgeTest::outcomeOrPending)
                         .toList();
    }

    /// `outcome` throws on a transport-level error body, and this runs while ownership is still moving, so
    /// a node mid-handover would abort the poll instead of simply reporting "not yet".
    private static String outcomeOrPending(String response) {
        return response.contains("\"outcome\":\"found\"") ? "found" : "pending";
    }

    // --- the fence invariant, asserted on every create ---------------------------

    /// The port that accepted a create, plus its response body.
    private record Accepted(int port, String response) {}

    /// Create `key` by offering it to EVERY node and requiring that exactly one creates it.
    ///
    /// This is the shared create helper rather than a special case in the fence test, and that is
    /// deliberate: tests 1–5, 7–9 and 11 all need a successful create as a precondition anyway, so routing
    /// them through here makes every one of them assert the single-writer invariant continuously, for free.
    ///
    /// It is also independent of the production owner resolution. Asking the cluster who owns the arc and
    /// then writing there would make the test partly self-confirming — it would pass even if that
    /// resolution were wrong, as long as it were wrong the same way on both sides. Offering the write to
    /// all five and counting acceptances cannot be fooled that way.
    ///
    /// Under owner-forwarding (#596) every node ACCEPTS the request — a non-owner forwards it to the
    /// committed owner — so the single-writer invariant now shows as: exactly one attempt CREATES, and
    /// every later attempt surfaces `EntityAlreadyExists` (the owner's typed duplicate refusal,
    /// reconstructed across the wire). `OwnershipNotYetCommitted` would still mean the arc has no
    /// committed owner — a real defect once setUp has waited for convergence — and `containsOnly`
    /// fails it explicitly rather than lumping it in with "not created".
    private Accepted createOnOwner(String key, String status, int amount) {
        var responses = appPorts().stream()
                                  .map(port -> new Accepted(port, create(port, key, status, amount)))
                                  .toList();
        var accepted = responses.stream()
                                .filter(entry -> "created".equals(outcome(entry.response())))
                                .toList();

        assertThat(responses).describedAs("every node must have answered").hasSize(NODES);
        assertThat(accepted).describedAs("exactly one attempt may create '%s'; got %s",
                                          key,
                                          outcomesOf(responses))
                            .hasSize(1);
        assertThat(rejectionTypesOf(responses)).describedAs("every later attempt must surface the duplicate as EntityAlreadyExists — its forward reached the owner and the single-writer invariant held (#596)")
                                               .containsOnly("EntityAlreadyExists");

        return accepted.getFirst();
    }

    private static List<String> outcomesOf(List<Accepted> responses) {
        return responses.stream()
                        .map(entry -> outcome(entry.response()))
                        .toList();
    }

    private static List<String> rejectionTypesOf(List<Accepted> responses) {
        return responses.stream()
                        .filter(entry -> !"created".equals(outcome(entry.response())))
                        .map(entry -> text(entry.response(), "failureType"))
                        .toList();
    }

    /// True once the leader's ownership reconcile has minted records for the entity arcs — detected by a
    /// throwaway create being ACCEPTED somewhere rather than refused everywhere as
    /// `OwnershipNotYetCommitted`. Uses a fresh key per attempt so a successful probe never collides with
    /// a previous one and reports `EntityAlreadyExists`.
    /// Convergence means every PARTITION can accept a write, not merely one.
    ///
    /// Ownership records are minted per `(entity:orders, partition)` arc, and the write barrier
    /// additionally needs that partition's replica set populated before `minSyncReplicas` can be met. A
    /// single probe key exercises exactly ONE partition and says nothing about the other seven — which is
    /// how a run reached the tests with some partitions still unable to accept a write, and failed there
    /// instead of here. Probing a spread of keys turns that into a readiness condition rather than a
    /// mid-suite surprise.
    private boolean ownershipHasConverged() {
        var round = ownershipProbe.incrementAndGet();

        return java.util.stream.IntStream.range(0, PARTITION_PROBE_KEYS)
                                         .allMatch(index -> probeAccepted("__ownership_probe_" + round + "_" + index + "__"));
    }

    private boolean probeAccepted(String key) {

        // Deliberately NOT via outcome(): that throws on a transport-level error body, and this predicate
        // runs while the cluster is still settling, so one slow node would abort setUp instead of simply
        // reporting "not converged yet". A failed request is not-yet-converged; only a literal accepted
        // create counts.
        var answers = appPorts().stream()
                                .map(port -> create(port, key, "probe", 0))
                                .toList();

        lastProbeAnswers.set(answers);

        return answers.stream().anyMatch(response -> response.contains("\"outcome\":\"created\""));
    }

    // --- entity operations -----------------------------------------------------

    private String create(int port, String key, String status, int amount) {
        return httpPost(port,
                        "/api/v1/entity/create",
                        "{\"orderId\":\"" + key + "\",\"status\":\"" + status + "\",\"amount\":" + amount + "}");
    }

    private String get(int port, String key) {
        return httpPost(port, "/api/v1/entity/get", "{\"orderId\":\"" + key + "\"}");
    }

    private String update(int port, String key, int amount) {
        return httpPost(port, "/api/v1/entity/update", "{\"orderId\":\"" + key + "\",\"amount\":" + amount + "}");
    }

    private String delete(int port, String key) {
        return httpPost(port, "/api/v1/entity/delete", "{\"orderId\":\"" + key + "\"}");
    }

    /// Schedules a timer with the fixture's default delay and no caller token — the entity mints one per
    /// call, so N calls are N timers.
    private String scheduleTimer(int port, String key) {
        return httpPost(port, "/api/v1/entity/schedule-timer", "{\"orderId\":\"" + key + "\"}");
    }

    /// Schedules a timer under a CALLER-minted token and an explicit delay. Re-sending the same token is
    /// what makes a lost acknowledgement recoverable: the owner recognises the token as already pending and
    /// appends nothing, so the schedule — and its effect — happen once.
    private String scheduleTimer(int port, String key, long delayMillis, String token) {
        return httpPost(port,
                        "/api/v1/entity/schedule-timer",
                        "{\"orderId\":\"" + key + "\",\"delayMillis\":" + delayMillis + ",\"token\":\"" + token + "\"}");
    }

    /// Every currently-serving node's `expiries` count for `key` — the multiplicity of applied `Expire`
    /// commands as each node's own committed view reports it.
    ///
    /// Read cluster-wide rather than from a resolved owner, for the same reason [#createOnOwner] offers its
    /// write to everyone: asking production who owns the arc and then believing that node's answer would
    /// make the gate partly self-confirming. A duplicate fire shows up as a 2 on whichever node folded both,
    /// and this sees all of them.
    private List<Integer> expiriesAcrossCluster(String key) {
        return serversOf(key).stream()
                             .map(response -> number(response, "expiries"))
                             .toList();
    }

    /// One serving node's view of `key`, for reading components other than the count.
    private String servedView(String key) {
        return serversOf(key).stream()
                             .findFirst()
                             .orElseThrow(() -> new AssertionError("No node serves '" + key + "'"));
    }

    /// Wait out a fixed span. Deliberately a sleep and not a poll: what is being waited for is the ABSENCE
    /// of a second fire, and absence has no condition to poll on — only elapsed ticks.
    ///
    /// The loop is load-bearing, not defensive. [org.pragmatica.lang.Promise#await()] registers an unpark
    /// callback and then parks in a `while (result == null)` loop; when the promise resolves between the
    /// registration and the check, the loop exits WITHOUT consuming the permit that callback's `unpark`
    /// leaves behind. A bare `parkNanos` on the same thread then returns in ~0ns by consuming that residual
    /// permit — and every caller here runs dozens of `await()` calls immediately beforehand. Parking to a
    /// DEADLINE re-parks after such a wakeup, so the span elapses whatever the permit state.
    ///
    /// The elapsed span is measured and asserted rather than assumed. A quiet period that does not elapse
    /// degrades its caller's exactly-once gate into a re-read of the value it just polled for — the gate
    /// stays green and stops testing anything, which is the failure mode a bare park already produced here.
    private static void awaitQuietPeriod(Duration span) {
        var startedAt = System.nanoTime();
        var deadline = startedAt + span.toNanos();

        while (System.nanoTime() < deadline) {
            LockSupport.parkNanos(deadline - System.nanoTime());
        }

        var elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        LOG.log(System.Logger.Level.INFO,
                "I4 quiet period: requested={0}ms elapsed={1}ms",
                span.toMillis(),
                elapsedMillis);

        assertThat(elapsedMillis).describedAs("the quiet period must actually elapse — a residual unpark permit left by "
                                              + "Promise.await() makes a bare park return at once, which would reduce the "
                                              + "exactly-once assertion below to a re-read of the value just polled for")
                                 .isGreaterThanOrEqualTo(span.toMillis());
    }

    // --- response reading -------------------------------------------------------

    /// Fails loudly on a missing field rather than returning "": a renamed component would otherwise
    /// turn every `isEqualTo` into a silent comparison against the empty string.
    private static String text(String body, String field) {
        var matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);

        if (!matcher.find()) {
            throw new AssertionError("No string field '" + field + "' in entity response: " + body);
        }

        return matcher.group(1);
    }

    private static int number(String body, String field) {
        var matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?\\d+)").matcher(body);

        if (!matcher.find()) {
            throw new AssertionError("No numeric field '" + field + "' in entity response: " + body);
        }

        return Integer.parseInt(matcher.group(1));
    }

    /// The outcome, with the raw body attached on the error path — an HTTP-level failure would
    /// otherwise surface as an opaque "expected created but was ''".
    private static String outcome(String body) {
        if (body.contains("\"error\"")) {
            throw new AssertionError("Entity request failed at the HTTP layer: " + body);
        }

        return text(body, "outcome");
    }

    // --- cluster addressing ------------------------------------------------------

    /// Node ids hosting an ACTIVE instance of the entity slice, from the cluster-wide slice view. The
    /// suite deploys exactly one slice, so every `nodeId` in the filtered response belongs to it.
    private Set<String> activeSliceHosts() {
        var body = httpGet(anyMgmtPort(), "/api/v1/slices?state=ACTIVE");
        var matcher = Pattern.compile("\"nodeId\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        var hosts = new HashSet<String>();

        while (matcher.find()) {
            hosts.add(matcher.group(1));
        }

        return hosts;
    }

    /// Committed `entity:orders` arc owners by partition, from the stream ownership domain (entity
    /// arcs ride the stream record family under the `entity:` namespace).
    private Map<Integer, String> entityArcOwners() {
        var body = httpGet(anyMgmtPort(), "/api/v1/ownership/stream");
        var matcher = Pattern.compile("\"identity\"\\s*:\\s*\"entity:orders:(\\d+)\"[^}]*\"owner\"\\s*:\\s*\"([^\"]+)\"")
                             .matcher(body);
        var owners = new HashMap<Integer, String>();

        while (matcher.find()) {
            owners.put(Integer.parseInt(matcher.group(1)), matcher.group(2));
        }

        return owners;
    }

    private List<Integer> appPorts() {
        return cluster.getAvailableAppHttpPorts();
    }

    private int firstPort() {
        return appPorts().stream()
                         .findFirst()
                         .orElseThrow(() -> new AssertionError("No app-http route is ready"));
    }

    /// The node id behind an app-http port. Both ports are assigned from the same per-node slot
    /// (`base + slot`), so the slot recovered from the management port identifies the node.
    private String nodeIdForAppPort(int appPort) {
        var slot = appPort - BASE_APP_HTTP_PORT;

        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(node -> node.mgmtPort() - BASE_MGMT_PORT == slot)
                      .map(EmberCluster.NodeStatus::id)
                      .findFirst()
                      .orElseThrow(() -> new AssertionError("No node owns app-http port " + appPort));
    }

    private List<Integer> mgmtPorts() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .map(EmberCluster.NodeStatus::mgmtPort)
                      .toList();
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    // --- deployment + readiness ----------------------------------------------------

    private void deployEntitySlice() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, ENTITY_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        var response = httpPostToml(leaderPort, "/api/v1/blueprints", blueprint);

        assertThat(response).describedAs("durable-entity slice deployment")
                            .doesNotContain("\"error\"")
                            .contains("\"status\":\"applied\"");
    }

    /// Every node must serve the entity before any test runs: the cross-node assertions compare
    /// answers from all five, so a node still provisioning would read as an absent key.
    private boolean entityReadyOnEveryNode() {
        var ports = appPorts();

        return ports.size() == NODES && ports.stream().allMatch(this::entityReady);
    }

    /// A node is ready once its entity resource ANSWERS — either with a state verdict, or by saying
    /// plainly that it does not hold the key's partition.
    ///
    /// Before I3 every node kept a private map and answered `absent` for any key, so readiness could
    /// require `absent` everywhere. Entity state is replicated now and a partition lives on a subset of
    /// nodes, so a node outside that subset genuinely cannot answer — and it says so, stably, rather than
    /// pretending the key does not exist. Requiring `absent` from all five would wait forever for a lie.
    ///
    /// What this still catches is the thing it was written for: a node whose entity resource failed to
    /// provision returns a transport-level error and never becomes ready.
    private boolean entityReady(int port) {
        var body = get(port, "__readiness_probe__");

        return !body.contains("\"error\"")
               && (body.contains("\"outcome\":\"absent\"")
                   || body.contains("\"outcome\":\"found\"")
                   || body.contains("PartitionNotHeld"));
    }

    /// `slicesStatus()` cannot detect this failure: under `ALL_OR_NOTHING` a deterministic slice
    /// failure rolls back the blueprint and the deployment map entry is removed along with it, so a
    /// FAILED status here never appears and this predicate would never fire — the suite would run to
    /// the full 240s `WAIT_TIMEOUT` and fail with "condition not met" instead of the real cause
    /// (#345 I1a follow-up). The cluster-event stream is append-only and is NOT retracted by the
    /// rollback, so it is the only surface that still carries the failure.
    private void failIfSliceFailed() {
        for (int port : mgmtPorts()) {
            var reason = deploymentFailedReason(httpGet(port, "/api/v1/events"));

            if (reason != null) {
                throw new AssertionError("Deployment of " + ENTITY_SLICE + " FAILED — event surface reason: " + excerpt(reason));
            }
        }
    }

    /// Extracts `details.reason` from a `DEPLOYMENT_FAILED` cluster event for {@link #ENTITY_SLICE}
    /// in an `/api/v1/events` response body, or null if no such event is present (yet).
    private static String deploymentFailedReason(String eventsBody) {
        var matcher = Pattern.compile("\"type\"\\s*:\\s*\"DEPLOYMENT_FAILED\".*?\"artifact\"\\s*:\\s*\""
                                      + Pattern.quote(ENTITY_SLICE)
                                      + "\".*?\"reason\"\\s*:\\s*\"([^\"]*)\"", Pattern.DOTALL)
                                .matcher(eventsBody);

        return matcher.find() ? matcher.group(1) : null;
    }

    /// Caps a raw event reason to {@link #REASON_EXCERPT_LIMIT} characters so a large nested
    /// exception chain in `details.reason` cannot blow up the assertion message.
    private static String excerpt(String reason) {
        return reason.length() <= REASON_EXCERPT_LIMIT ? reason : reason.substring(0, REASON_EXCERPT_LIMIT) + "...";
    }

    private boolean allNodesHealthy() {
        return mgmtPorts().stream().allMatch(this::checkNodeHealth);
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(response -> response.statusCode() == 200 && response.body().contains("\"quorum\":true"))
                   .or(false);
    }

    // --- HTTP ------------------------------------------------------------------------

    private String httpPostToml(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(15))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpGet(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String httpDelete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
