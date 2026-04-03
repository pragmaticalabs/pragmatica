package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.List;

import static org.pragmatica.lang.Result.success;


/// Information about a managed load balancer, including its targets and status.
public record LoadBalancerInfo(String id, String name, String publicIp, String status, List<TargetInfo> targets) {
    public record TargetInfo(String ip, String healthStatus, int weight) {
        public static Result<TargetInfo> targetInfo(String ip, String healthStatus, int weight) {
            return success(new TargetInfo(ip, healthStatus, weight));
        }
    }

    public static Result<LoadBalancerInfo> loadBalancerInfo(String id,
                                                            String name,
                                                            String publicIp,
                                                            String status,
                                                            List<TargetInfo> targets) {
        return success(new LoadBalancerInfo(id, name, publicIp, status, List.copyOf(targets)));
    }
}
