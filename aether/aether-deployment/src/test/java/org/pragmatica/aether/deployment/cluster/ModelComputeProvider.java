// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// The G3 fake cloud provider, a model of what P-G1 measured at Hetzner (p-g1-report.md), on [VirtualTime]:
/// - a duplicate name is refused with 409, atomically by default ([#nonAtomicWindow] opens the race the probe never saw);
/// - names are case-sensitive; a name is reusable once its server is deleted;
/// - a created server is listed only [#visibilityLagMs] later (0.6-0.9 s in A6/A7), though its name is taken at once;
/// - a lost reply is either applied-and-dropped or dropped-and-not-applied (both seen in A7), and the caller sees a timeout;
/// - labels are not unique;
/// - the call log is append-only: time, op, name, labels, outcome.
/// The server NAME is the node id the reconciler minted (`aether.node-id`): today's code has no separate operation id.
final class ModelComputeProvider implements ComputeProvider {
    static final String NODE_ID_TAG = "aether.node-id";
    static final String CLUSTER_TAG = "aether-cluster";

    /// What the provider does with one create call.
    enum Fate {
        /// Applied, reply delivered.
        APPLIED,
        /// Applied, reply dropped: the caller times out and the server exists.
        APPLIED_REPLY_LOST,
        /// Dropped before it was applied: the caller times out and nothing exists.
        NOT_APPLIED_REPLY_LOST
    }

    record Server(String name, InstanceId id, Map<String, String> labels, long createdAtMs, long visibleAtMs) {}

    record Call(long seq, long atMs, String op, String name, Map<String, String> labels, String outcome) {}

    private final VirtualTime time;
    private final List<Call> log = new ArrayList<>();
    private final Map<InstanceId, Server> servers = new LinkedHashMap<>();
    private final Map<String, Integer> createAttempts = new LinkedHashMap<>();
    private int sequence;

    /// Name uniqueness (a planted violation switches it off: a provider that admits a duplicate name).
    boolean uniqueNames = true;
    /// False opens the race between the uniqueness check and the insert: two concurrent creates of one name both pass.
    boolean atomic = true;
    boolean outage;
    long visibilityLagMs = 750L;
    /// Overrides the visibility lag for the n-th create of a name when non-null (a create that stays unlisted for a long time).
    BiFunction<String, Integer, Long> lagOf = (name, attempt) -> null;
    /// Annotates a delete of `name` at the moment it is made (e.g. ":protected"), so the log can say what was deleted.
    java.util.function.Function<String, String> deleteAnnotation = name -> "";
    long createDelayMs = 400L;
    long clientTimeoutMs = 30_000L;
    /// Decides the fate of the n-th (1-based) create call carrying a name.
    BiFunction<String, Integer, Fate> fate = (name, attempt) -> Fate.APPLIED;
    /// Called with the name right after a create was applied and before its reply is delivered / right after the reply was delivered.
    Consumer<Fate> onFate = _ -> {};
    Consumer<String> beforeReply = _ -> {};
    Consumer<String> afterReply = _ -> {};
    /// Called with (name, instance id) when a server comes into existence.
    java.util.function.BiConsumer<String, String> afterApply = (name, instance) -> {};

    ModelComputeProvider(VirtualTime time) {
        this.time = time;
    }

    /// The provider as one process sees it: a process that is `alive` makes calls; a dead one makes none (its call chain stalls, nothing is logged).
    ComputeProvider viewFor(BooleanSupplier alive) {
        return new View(alive);
    }

    @Override
    public ProviderDefaults providerDefaults() {
        return ProviderDefaults.providerDefaults("cx23", "img", "img", "nbg1", Option.none(), true);
    }

    @Override
    public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
        return new View(() -> true).createFrom(request);
    }

    @Override
    public Promise<Unit> terminate(InstanceId instanceId) {
        return new View(() -> true).terminate(instanceId);
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances() {
        return new View(() -> true).listInstances();
    }

    @Override
    public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        return new View(() -> true).instanceStatus(instanceId);
    }

    /// A delete made outside Aether (an operator, the provider, a bug): logged as `external-delete`, never as a `delete` by a caller.
    boolean externalDelete(String name) {
        var victim = servers.values().stream().filter(server -> server.name().equals(name)).findFirst();

        victim.ifPresent(server -> {
            servers.remove(server.id());
            record("external-delete", name, server.labels(), "deleted");
        });

        return victim.isPresent();
    }

    List<Server> servers() {
        return List.copyOf(servers.values());
    }

    List<Server> serversNamed(String name) {
        return servers.values().stream().filter(server -> server.name().equals(name)).toList();
    }

    List<Call> callLog() {
        return Collections.unmodifiableList(log);
    }

    List<Call> calls(String op) {
        return log.stream().filter(call -> call.op().equals(op)).toList();
    }

    private void record(String op, String name, Map<String, String> labels, String outcome) {
        log.add(new Call(time.seq(), time.nowMs(), op, name, labels, outcome));
    }

    private static Cause refused(String why) {
        return EnvironmentError.provisionFailed(new RuntimeException(why));
    }

    private final class View implements ComputeProvider {
        private final BooleanSupplier alive;

        private View(BooleanSupplier alive) {
            this.alive = alive;
        }

        @Override
        public ProviderDefaults providerDefaults() {
            return ModelComputeProvider.this.providerDefaults();
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            if (!alive.getAsBoolean()) {
                return Promise.promise();
            }

            var name = request.context().nodeId().or(() -> "unnamed-" + sequence++);
            var labels = labelsOf(request, name);

            if (outage) {
                record("create", name, labels, "outage");

                return refused("provider outage").promise();
            }

            var attempt = createAttempts.merge(name, 1, Integer::sum);
            var decided = fate.apply(name, attempt);

            onFate.accept(decided);
            var lag = Option.option(lagOf.apply(name, attempt)).or(visibilityLagMs);
            var checkedAtCall = !atomic && nameTaken(name);
            var reply = Promise.<InstanceInfo> promise();

            record("create", name, labels, "sent:" + decided + ":lag=" + lag);
            time.after(createDelayMs, () -> apply(name, labels, decided, checkedAtCall, lag, reply));

            return reply;
        }

        private void apply(String name, Map<String, String> labels, Fate decided, boolean checkedAtCall, long lag, Promise<InstanceInfo> reply) {
            if (decided == Fate.NOT_APPLIED_REPLY_LOST) {
                record("create-apply", name, labels, "dropped");
                timeoutLater(reply);

                return;
            }

            var taken = uniqueNames && (atomic ? nameTaken(name) : checkedAtCall);

            if (taken) {
                record("create-apply", name, labels, "409");
                deliver(decided, reply, refused("409 uniqueness_error: server name is already used: " + name).<InstanceInfo> result());

                return;
            }

            var server = new Server(name,
                                    new InstanceId("srv-" + name + "-" + sequence++),
                                    labels,
                                    time.nowMs(),
                                    time.nowMs() + lag);

            servers.put(server.id(), server);
            afterApply.accept(name, server.id().value());
            record("create-apply", name, labels, "created");
            beforeReply.accept(name);
            deliver(decided, reply, org.pragmatica.lang.Result.success(describe(server)));
            afterReply.accept(name);
        }

        private void deliver(Fate decided, Promise<InstanceInfo> reply, org.pragmatica.lang.Result<InstanceInfo> result) {
            if (decided == Fate.APPLIED) {
                reply.resolve(result);
            } else {
                timeoutLater(reply);
            }
        }

        private void timeoutLater(Promise<InstanceInfo> reply) {
            time.after(clientTimeoutMs, () -> reply.fail(refused("client timeout waiting for the create reply")));
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            if (!alive.getAsBoolean()) {
                return Promise.promise();
            }

            var victim = Option.option(servers.get(instanceId));
            var name = victim.map(Server::name).or("?");

            if (outage) {
                record("delete", name, Map.of(), "outage");

                return refused("provider outage").promise();
            }

            record("delete", name, victim.map(Server::labels).or(Map.of()), (victim.isPresent() ? "deleted" : "absent") + deleteAnnotation.apply(name));
            servers.remove(instanceId);

            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            if (!alive.getAsBoolean()) {
                return Promise.promise();
            }

            if (outage) {
                record("list", "", Map.of(), "outage");

                return refused("provider outage").promise();
            }

            var visible = servers.values().stream().filter(server -> server.visibleAtMs() <= time.nowMs()).map(ModelComputeProvider::describe).toList();

            record("list", "", Map.of(), "visible=" + visible.size());

            return Promise.success(visible);
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            if (!alive.getAsBoolean()) {
                return Promise.promise();
            }

            return Option.option(servers.get(instanceId))
                         .map(ModelComputeProvider::describe)
                         .async(EnvironmentError.instanceNotFound(instanceId));
        }
    }

    private boolean nameTaken(String name) {
        return servers.values().stream().anyMatch(server -> server.name().equals(name));
    }

    private static Map<String, String> labelsOf(ProvisionRequest request, String name) {
        var context = request.context();
        var labels = new LinkedHashMap<String, String>();

        labels.put(CLUSTER_TAG, context.clusterName().map(org.pragmatica.aether.environment.ClusterName::value).or(""));
        labels.put("aether-role", context.role().isEmpty() ? "core" : context.role());
        labels.put("aether-source", context.sourceName().value());
        labels.put(NODE_ID_TAG, name);

        return Map.copyOf(labels);
    }

    private static InstanceInfo describe(Server server) {
        return new InstanceInfo(server.id(),
                                InstanceStatus.RUNNING,
                                List.of("model:" + server.name()),
                                InstanceType.ON_DEMAND,
                                server.labels(),
                                Option.some(server.name()),
                                Option.none());
    }
}
