package org.pragmatica.aether.worker.group;

public record WorkerGroupId(String groupName, String zone) {
    public static final WorkerGroupId DEFAULT = workerGroupId("default", "local");

    @SuppressWarnings("JBCT-VO-02") public static WorkerGroupId workerGroupId(String groupName, String zone) {
        return new WorkerGroupId(groupName, zone);
    }

    public String communityId() {
        return groupName + ":" + zone;
    }

    @Override public String toString() {
        return communityId();
    }
}
