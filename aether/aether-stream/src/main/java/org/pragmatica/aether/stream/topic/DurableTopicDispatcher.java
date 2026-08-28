// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.topic;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ConsumerConfig;
import org.pragmatica.aether.slice.ConsumerConfig.ErrorStrategy;
import org.pragmatica.aether.slice.ConsumerConfig.ProcessingMode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.resource.DurableTopicSpec;
import org.pragmatica.aether.stream.StreamConsumerRuntime;
import org.pragmatica.aether.stream.StreamConsumerRuntime.IdlePolicy;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;

import static org.pragmatica.lang.Result.unitResult;


/// Durable-topic dispatch (durable-pubsub-spec §6): one strictly serial loop per
/// (group × partition) over the stream consumer runtime — event N+1 is not dispatched until N is
/// acked or dead-lettered, which is what earns the §4 per-partition ordering guarantee. Each loop
/// reads from the group's cursor, decodes the [TopicEventEnvelope], and hands the PAYLOAD bytes to
/// the wired [DurableSubscriberInvoker]; the handler promise is the ack. Failure → the runtime's
/// bounded redelivery (5 attempts, exponential backoff), then the group-attributed dead-letter
/// path whose cursor-hold semantics live in the consumer runtime.
///
/// **Placement contract — the caller owns single-dispatcher placement (spec §13 item 4):** attach
/// a group's loops on exactly ONE node (the wiring drives this from the same placement machinery
/// declarative consumers use). Attaching the same group on two nodes concurrently produces
/// concurrent duplicate dispatch — still within at-least-once semantics, never loss, but exactly
/// the storm the single-dispatcher rule exists to avoid.
///
/// Attach and detach are idempotent per (group × partition): `CONSUMER_ALREADY_SUBSCRIBED` and
/// `CONSUMER_NOT_FOUND` respectively count as success, so re-activation after redeploys and
/// repeated detach on teardown both converge instead of failing the deployment step.
///
/// Cadence deltas from the spec's normative defaults, stated rather than hidden: redelivery
/// backoff rides the runtime's 100ms-base/×2/10s-cap (spec §6 names 1s base, 60s cap — a faster
/// cadence, same attempt bound); cursor commits ride the runtime's 500ms-interval/1000-event
/// checkpoint (spec §7 names 16 acks/500ms — the time bound dominates at durable-topic
/// throughputs, so the practical crash-redelivery window matches). Tightening both to the letter
/// requires per-topic knobs on `ConsumerConfig` — deferred until that surface is coordinated.
public interface DurableTopicDispatcher {
    Result<Unit> attachGroup(String topicAddress, DurableTopicSpec spec, Artifact subscriber, MethodName method);
    Result<Unit> detachGroup(String topicAddress, DurableTopicSpec spec, Artifact subscriber, MethodName method);

    static DurableTopicDispatcher durableTopicDispatcher(StreamConsumerRuntime runtime,
                                                         Deserializer deserializer,
                                                         DurableSubscriberInvoker invoker) {
        return new DurableTopicDispatcherState(runtime, deserializer, invoker);
    }

    /// 5 attempts (§6 default), serial ORDERED dispatch, RETRY-then-dead-letter strategy, 500ms
    /// checkpoint interval (§7's time bound). Batch size 1 keeps the ack-by-ack contract visible
    /// in the config itself.
    static ConsumerConfig groupConsumerConfig(String groupId) {
        return ConsumerConfig.consumerConfig(groupId, 1, ProcessingMode.ORDERED, ErrorStrategy.RETRY, 500L, 5, "");
    }

    record DurableTopicDispatcherState(StreamConsumerRuntime runtime,
                                       Deserializer deserializer,
                                       DurableSubscriberInvoker invoker) implements DurableTopicDispatcher {
        @Override
        public Result<Unit> attachGroup(String topicAddress,
                                        DurableTopicSpec spec,
                                        Artifact subscriber,
                                        MethodName method) {
            var streamName = DurableTopicNames.topicStream(topicAddress);
            var config = groupConsumerConfig(DurableGroupIdentity.groupId(subscriber, method));
            var attached = unitResult();

            for (var partition = 0; partition < spec.partitions() && attached.isSuccess(); partition++) {
                attached = tolerate(runtime.subscribe(streamName,
                                                      partition,
                                                      config,
                                                      callback(subscriber, method),
                                                      IdlePolicy.KEEP_UNTIL_UNSUBSCRIBED),
                                    StreamError.General.CONSUMER_ALREADY_SUBSCRIBED);
            }

            return attached;
        }

        @Override
        public Result<Unit> detachGroup(String topicAddress,
                                        DurableTopicSpec spec,
                                        Artifact subscriber,
                                        MethodName method) {
            var streamName = DurableTopicNames.topicStream(topicAddress);
            var groupId = DurableGroupIdentity.groupId(subscriber, method);
            var detached = unitResult();

            for (var partition = 0; partition < spec.partitions() && detached.isSuccess(); partition++) {
                detached = tolerate(runtime.unsubscribe(streamName, partition, groupId),
                                    StreamError.General.CONSUMER_NOT_FOUND);
            }

            return detached;
        }

        private StreamConsumerRuntime.ConsumerCallback callback(Artifact subscriber, MethodName method) {
            return (offset, payload, timestamp) -> deliverEnvelope(subscriber, method, payload);
        }

        private Promise<Unit> deliverEnvelope(Artifact subscriber, MethodName method, byte[] rawEvent) {
            TopicEventEnvelope envelope = deserializer.decode(rawEvent);

            return invoker.deliver(subscriber, method, envelope.payload());
        }

        private static Result<Unit> tolerate(Result<Unit> outcome, Cause benign) {
            return outcome.fold(cause -> recoverBenign(cause, benign), Result::success);
        }

        private static Result<Unit> recoverBenign(Cause cause, Cause benign) {
            return cause == benign
                   ? unitResult()
                   : cause.result();
        }
    }
}
