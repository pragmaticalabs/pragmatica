package org.pragmatica.aether.config.cluster;

public record PortMapping(int cluster, int management, int appHttp, int swim) {
    public static PortMapping portMapping(int cluster, int management, int appHttp, int swim) {
        return new PortMapping(cluster, management, appHttp, swim);
    }

    public static PortMapping defaultPortMapping() {
        return new PortMapping(8090, 8080, 8070, 8190);
    }
}
