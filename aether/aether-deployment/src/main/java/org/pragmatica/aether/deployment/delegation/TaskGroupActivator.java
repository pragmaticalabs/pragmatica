package org.pragmatica.aether.deployment.delegation;

import org.pragmatica.aether.slice.delegation.DelegatedComponent;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.TaskAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TaskAssignmentValue.AssignmentStatus;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Node-side component that watches TaskAssignmentKey KV notifications and
/// activates/deactivates DelegatedComponent instances. Runs on EVERY core node.
@SuppressWarnings("JBCT-RET-01")
// MessageReceiver callbacks --- void required by messaging framework
public sealed interface TaskGroupActivator {
    void register(DelegatedComponent component);
    @MessageReceiver void onTaskAssignmentPut(ValuePut<TaskAssignmentKey, TaskAssignmentValue> valuePut);
    @MessageReceiver void onTaskAssignmentRemove(ValueRemove<TaskAssignmentKey, TaskAssignmentValue> valueRemove);

    static TaskGroupActivator taskGroupActivator(NodeId self, ClusterNode<KVCommand<AetherKey>> clusterNode) {
        return new taskGroupActivator(self, clusterNode, new ConcurrentHashMap<>());
    }

    @SuppressWarnings("JBCT-RET-01") record taskGroupActivator(NodeId self,
                                                               ClusterNode<KVCommand<AetherKey>> clusterNode,
                                                               Map<TaskGroup, List<DelegatedComponent>> components) implements TaskGroupActivator {
        private static final Logger log = LoggerFactory.getLogger(taskGroupActivator.class);

        @Override public void register(DelegatedComponent component) {
            components.computeIfAbsent(component.taskGroup(), _ -> new CopyOnWriteArrayList<>()).add(component);
            log.info("Registered component for task group {}", component.taskGroup());
        }

        @Override public void onTaskAssignmentPut(ValuePut<TaskAssignmentKey, TaskAssignmentValue> valuePut) {
            var taskGroup = valuePut.cause().key()
                                          .taskGroup();
            var assignment = valuePut.cause().value();
            var groupComponents = components.get(taskGroup);
            if (groupComponents == null || groupComponents.isEmpty()) {
                log.debug("No components registered for task group {}, ignoring assignment", taskGroup);
                return;
            }
            if (isAssignedToSelf(assignment)) {handleLocalAssignment(taskGroup, groupComponents);} else {handleRemoteAssignment(taskGroup,
                                                                                                                                groupComponents);}
        }

        @Override public void onTaskAssignmentRemove(ValueRemove<TaskAssignmentKey, TaskAssignmentValue> valueRemove) {
            var taskGroup = valueRemove.cause().key()
                                             .taskGroup();
            var groupComponents = components.get(taskGroup);
            if (groupComponents == null) {return;}
            groupComponents.stream().filter(DelegatedComponent::isActive)
                                  .forEach(component -> deactivateComponent(taskGroup, component));
        }

        private boolean isAssignedToSelf(TaskAssignmentValue assignment) {
            return self.equals(assignment.assignedTo()) && assignment.status() == AssignmentStatus.ASSIGNED;
        }

        private void handleLocalAssignment(TaskGroup taskGroup, List<DelegatedComponent> groupComponents) {
            log.info("Task group {} assigned to this node {}, activating {} components",
                     taskGroup,
                     self,
                     groupComponents.size());
            var activations = groupComponents.stream().map(component -> activateComponent(taskGroup, component))
                                                    .toList();
            Promise.allOf(activations).onSuccess(_ -> reportActivationSuccess(taskGroup))
                         .onFailure(cause -> reportActivationFailure(taskGroup,
                                                                     cause.message()));
        }

        private void handleRemoteAssignment(TaskGroup taskGroup, List<DelegatedComponent> groupComponents) {
            var activeComponents = groupComponents.stream().filter(DelegatedComponent::isActive)
                                                         .toList();
            if (!activeComponents.isEmpty()) {log.info("Task group {} reassigned away from node {}, deactivating {} components",
                                                       taskGroup,
                                                       self,
                                                       activeComponents.size());}
            activeComponents.forEach(component -> deactivateComponent(taskGroup, component));
        }

        private Promise<Unit> activateComponent(TaskGroup taskGroup, DelegatedComponent component) {
            return component.activate()
                                     .onSuccess(_ -> log.info("Task group {} component activated on node {}",
                                                              taskGroup,
                                                              self));
        }

        private void deactivateComponent(TaskGroup taskGroup, DelegatedComponent component) {
            component.deactivate().onSuccess(_ -> log.info("Task group {} component deactivated on node {}",
                                                           taskGroup,
                                                           self))
                                .onFailure(cause -> log.error("Task group {} deactivation failed on node {}: {}",
                                                              taskGroup,
                                                              self,
                                                              cause.message()));
        }

        private void reportActivationSuccess(TaskGroup taskGroup) {
            log.info("Task group {} all components activated on node {}", taskGroup, self);
            var key = TaskAssignmentKey.taskAssignmentKey(taskGroup);
            var value = TaskAssignmentValue.taskAssignmentValue(self).withStatus(AssignmentStatus.ACTIVE);
            var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
            clusterNode.apply(List.of(command))
                             .onFailure(cause -> log.error("Failed to report ACTIVE status for task group {}: {}",
                                                           taskGroup,
                                                           cause.message()));
        }

        private void reportActivationFailure(TaskGroup taskGroup, String reason) {
            log.error("Task group {} activation failed on node {}: {}", taskGroup, self, reason);
            var key = TaskAssignmentKey.taskAssignmentKey(taskGroup);
            var value = TaskAssignmentValue.taskAssignmentValue(self).withFailure(reason);
            var command = new KVCommand.Put<AetherKey, AetherValue>(key, value);
            clusterNode.apply(List.of(command))
                             .onFailure(cause -> log.error("Failed to report FAILED status for task group {}: {}",
                                                           taskGroup,
                                                           cause.message()));
        }
    }
}
